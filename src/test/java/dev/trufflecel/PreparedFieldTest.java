package dev.trufflecel;

import static org.junit.Assert.*;

import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import com.oracle.truffle.api.source.Source;
import java.util.*;
import java.util.concurrent.*;
import dev.elide.truffledata.DataArray;
import dev.elide.truffledata.protobuf.GeneratedMessage;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.Breakpoint;
import org.graalvm.polyglot.Context;
import org.junit.Test;

public final class PreparedFieldTest {
  @Test
  public void runtimeCopiesReleasePlanningDataWithoutMutatingSharedTemplates() throws Exception {
    var environment =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .commonSubexpressions(true)
            .macro(
                Macro.global(
                    "twice",
                    1,
                    (context, target, arguments) ->
                        Ast.Node.call("+", arguments.getFirst(), arguments.getFirst())))
            .build();
    for (String source :
        List.of(
            "[1, 2, 3].map(n, n * 2).size()",
            "twice(x + 1)",
            "((x + 1) * (x + 1)) + ((x + 1) * (x + 1))",
            "x * 2 + 1",
            "x + (1 / 0)")) {
      var prepared = environment.prepare(source);
      var syntax = Ast.snapshot(prepared.expression());
      var templateNodes = new ArrayList<ExprNode>();
      prepared
          .expression()
          .accept(
              node -> {
                if (node instanceof ExprNode expression) templateNodes.add(expression);
                return true;
              });
      var types = templateNodes.stream().map(node -> node.checkedType).toList();
      var folds = templateNodes.stream().map(node -> node.fold).toList();
      var bindings = Map.of("x", 42L);
      var reference = environment.compile(source);
      var options = EvaluationOptions.newBuilder().budget(10_000).build();
      var expected = reference.evaluateWithDetails(bindings, options);
      var expectedEvents = new ArrayList<TraceEvent>();
      reference.trace(expectedEvents::add).evaluateWithDetails(bindings, options);
      try (var executor = Executors.newFixedThreadPool(4)) {
        var jobs = new ArrayList<Future<?>>();
        for (int i = 0; i < 16; i++)
          jobs.add(
              executor.submit(
                  () -> {
                    var program = prepared.instantiate();
                    var result = program.evaluateWithDetails(bindings, options);
                    assertEquals(source, expected.actualCost(), result.actualCost());
                    if (expected.error() == null) {
                      assertNull(result.error());
                      assertEquals(source, expected.value(), result.value());
                    } else {
                      assertEquals(expected.error().code(), result.error().code());
                      assertEquals(expected.error().offset(), result.error().offset());
                      assertEquals(expected.error().expressionId(), result.error().expressionId());
                    }
                    var events = new ArrayList<TraceEvent>();
                    program.trace(events::add).evaluateWithDetails(bindings, options);
                    assertEquals(
                        expectedEvents.stream().map(TraceEvent::expressionId).toList(),
                        events.stream().map(TraceEvent::expressionId).toList());
                    assertEquals(
                        expectedEvents.stream().map(TraceEvent::offset).toList(),
                        events.stream().map(TraceEvent::offset).toList());
                    var activation = PartialActivation.of(bindings, AttributePattern.of("x"));
                    assertEquals(
                        reference.partialEvaluate(activation).unknownAttributes(),
                        program.partialEvaluate(activation).unknownAttributes());
                  }));
        for (var job : jobs) job.get();
      }
      assertEquals(syntax, Ast.snapshot(prepared.expression()));
      for (int i = 0; i < templateNodes.size(); i++) {
        assertSame(types.get(i), templateNodes.get(i).checkedType);
        assertSame(folds.get(i), templateNodes.get(i).fold);
      }
    }
  }

  @Test
  public void nativeScalarOwnershipAndBuilderTransferStayBounded() {
    var source = new Object[] {1L, 2L};
    var data = new DataArray(source);
    var view = Values.scalarRange(data);
    source[0] = 9L;
    assertEquals(List.of(1L, 2L), view);
    var exported = view.toArray();
    exported[0] = 8L;
    assertEquals(1L, view.get(0));
    assertEquals(0, Values.scalarRange(new DataArray(new Object[0])).toArray().length);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    Values.scalarRange(new DataArray(Collections.nCopies(1_000_000, 1L).toArray())))
            .code());
    var builder = new Values.ListBuilder();
    for (long i = 0; i < 17; i++) builder.add(i);
    var first = builder.finish();
    builder.add(99L);
    assertEquals(17, first.size());
    assertEquals(16L, first.get(16));
    assertEquals(List.of(99L), builder.finish());
    assertTrue(builder.finish().isEmpty());
    assertThrows(IndexOutOfBoundsException.class, () -> first.get(17));
    assertThrows(IndexOutOfBoundsException.class, () -> view.get(-1));
  }

  @Test
  public void customAdapterPrecedenceDisablesPreparation() throws Exception {
    var type = new ProtobufType(descriptor());
    var visits = new ArrayList<String>();
    ObjectType overlap =
        new ObjectType() {
          public String name() {
            return "other.Record";
          }

          public Map<String, CelType> fields() {
            return Map.of("count", CelType.INT);
          }

          public Object construct(Map<String, Object> values) {
            return values;
          }

          public Object get(Object value, String field) {
            visits.add("get");
            return 42L;
          }

          public boolean has(Object value, String field) {
            visits.add("has");
            return true;
          }

          public boolean accepts(Object value) {
            visits.add("accepts");
            return value instanceof ProtobufType.Value;
          }
        };
    var env =
        type.register(Cel.newBuilder())
            .objectType(overlap)
            .variable("r", CelType.of(type.name()))
            .build();
    var reference =
        Cel.newBuilder()
            .objectType(generic(type))
            .objectType(overlap)
            .variable("r", CelType.of(type.name()))
            .build();
    ExprNode tree = new Parser("r.count", env).parse();
    env.prepare("r.count", tree, true);
    assertNull(tree.fieldPlan);
    assertNull(type.prepareField("missing", new ObjectType[] {type}));
    var bindings = Map.of("r", type.construct(Map.of("count", 7L)));
    Object expected = reference.compile("r.count").evaluate(bindings);
    var trace = List.copyOf(visits);
    visits.clear();
    assertEquals(expected, env.compile("r.count").evaluate(bindings));
    assertEquals(trace, visits);
  }

  @Test
  public void preparedInstancesAndLateInstrumentationRemainIndependent() throws Exception {
    var type = new ProtobufType(descriptor());
    var env = environment(type, 1000);
    var source = "r.items.map(v,\n v + r.count)";
    var template = env.prepare(source);
    var first = template.instantiate();
    var second = template.instantiate();
    var input =
        Activation.snapshot(
            Map.of("r", type.construct(Map.of("count", 10L, "items", List.of(1L, 2L)))));
    try (var executor = Executors.newFixedThreadPool(4)) {
      var work = new ArrayList<Callable<Object>>();
      for (int i = 0; i < 100; i++) {
        Program program = i % 2 == 0 ? first : second;
        work.add(() -> program.evaluate(input));
      }
      for (var result : executor.invokeAll(work)) assertEquals(List.of(11L, 12L), result.get());
    }
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        var text = Source.newBuilder("cel", source, "prepared-field-debug.cel").build();
        var program = template.instantiate(text);
        assertEquals(List.of(11L, 12L), program.evaluate(input));
        var locals = new ArrayList<String>();
        try (var session =
            Debugger.find(context.getEngine())
                .startSession(
                    event ->
                        locals.add(
                            event
                                .getTopStackFrame()
                                .getScope()
                                .getDeclaredValue("v")
                                .toDisplayString()))) {
          session.install(Breakpoint.newBuilder(text).lineIs(2).build());
          assertEquals(List.of(11L, 12L), program.evaluate(input));
        }
        assertEquals(List.of("1", "2"), locals);
      } finally {
        context.leave();
      }
    }
  }

  private static Descriptor descriptor() throws Exception {
    var message = DescriptorProto.newBuilder().setName("Record");
    String[] names = {"count", "items", "ratio", "active", "text", "blob"};
    var types =
        new FieldDescriptorProto.Type[] {
          FieldDescriptorProto.Type.TYPE_INT64, FieldDescriptorProto.Type.TYPE_INT64,
          FieldDescriptorProto.Type.TYPE_DOUBLE, FieldDescriptorProto.Type.TYPE_BOOL,
          FieldDescriptorProto.Type.TYPE_STRING, FieldDescriptorProto.Type.TYPE_BYTES
        };
    for (int i = 0; i < names.length; i++)
      message.addField(
          FieldDescriptorProto.newBuilder()
              .setName(names[i])
              .setNumber(i + 1)
              .setType(types[i])
              .setLabel(
                  i == 1
                      ? FieldDescriptorProto.Label.LABEL_REPEATED
                      : FieldDescriptorProto.Label.LABEL_OPTIONAL));
    return FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("prepared.proto")
                .setPackage("prepared")
                .setSyntax("proto3")
                .addMessageType(message)
                .build(),
            new FileDescriptor[0])
        .findMessageTypeByName("Record");
  }

  private static Environment environment(ProtobufType type, long budget) {
    return type.register(Cel.newBuilder())
        .variable("r", CelType.of(type.name()))
        .evaluationBudget(budget)
        .build();
  }

  private static ObjectType generic(ProtobufType type) {
    return new ObjectType() {
      public String name() {
        return type.name();
      }

      public Map<String, CelType> fields() {
        return type.fields();
      }

      public Object construct(Map<String, Object> values) {
        return type.construct(values);
      }

      public Object get(Object value, String field) {
        return type.get(value, field);
      }

      public boolean has(Object value, String field) {
        return type.has(value, field);
      }

      public boolean accepts(Object value) {
        return type.accepts(value);
      }
    };
  }

  @Test
  public void preparedReadsMatchGenericAcrossEntryPathsAndGuardMisses() throws Exception {
    var type = new ProtobufType(descriptor());
    var env = environment(type, 1000);
    var reference =
        Cel.newBuilder().objectType(generic(type)).variable("r", CelType.of(type.name())).build();
    var input =
        type.construct(
            Map.of(
                "count",
                7L,
                "items",
                List.of(1L, 60L, 80L),
                "ratio",
                2.5,
                "active",
                true,
                "text",
                "yes"));
    var other = new ProtobufType(descriptor());
    // A distinct adapter for the same descriptor must use its own plan, not a cached ordinal.
    Descriptor descriptor = descriptor();
    var first = new ProtobufType(descriptor);
    var second = new ProtobufType(descriptor);
    assertEquals(
        9L,
        environment(first, 100)
            .compile("r.count")
            .evaluate(Map.of("r", second.construct(Map.of("count", 9L)))));
    for (String source :
        List.of(
            "r.count",
            "r.ratio",
            "r.active",
            "r.text",
            "r.items",
            "r.items[1]",
            "r.items.filter(v, v > 50).map(v, v * 2)",
            "has(r.count)",
            "has(r.items)",
            "r.?count",
            "r.blob")) {
      var template = env.prepare(source);
      for (Object value :
          List.of(input, type.construct(Map.of()), Map.of("count", 5L), Unknown.of("r"))) {
        var bindings = Map.of("r", value);
        Object expected = outcome(() -> reference.compile(source).evaluate(bindings));
        for (Program program :
            List.of(
                template.instantiate(),
                template.instantiate(),
                CanonicalAst.program(
                    env, CanonicalAst.check(env, CanonicalAst.parse(env, source))))) {
          assertEquals(source, expected, outcome(() -> program.evaluate(bindings)));
          assertEquals(
              source, expected, outcome(() -> program.evaluate(Activation.snapshot(bindings))));
        }
      }
    }
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        outcome(
            () ->
                env.compile("r.count")
                    .evaluate(Map.of("r", other.construct(Map.of("count", 1L))))));
  }

  private interface Evaluation {
    Object run();
  }

  private static Object outcome(Evaluation evaluation) {
    try {
      Object result = evaluation.run();
      return result instanceof byte[] bytes ? new Bytes(bytes) : result;
    } catch (CelException error) {
      return error.code();
    }
  }

  @Test
  public void rangeOwnershipBudgetsAndCallbackOrderAreUnchanged() throws Exception {
    var type = new ProtobufType(descriptor());
    var original = new ArrayList<>(List.of(1L, 2L, 3L));
    var value = type.construct(Map.of("items", original));
    original.clear();
    var activation = Activation.snapshot(Map.of("r", value));
    for (long budget = 1; budget < 60; budget++) {
      var planned = environment(type, budget);
      var generic =
          Cel.newBuilder()
              .objectType(generic(type))
              .variable("r", CelType.of(type.name()))
              .evaluationBudget(budget)
              .build();
      for (String source :
          List.of(
              "r.items.filter(v, v > 1).map(v, v * 2)",
              "r.items.map(v, v / (v - 2))",
              "r.items.exists(v, v > 2)")) {
        assertEquals(
            source + "/" + budget,
            outcome(() -> generic.compile(source).evaluate(activation)),
            outcome(() -> planned.compile(source).evaluate(activation)));
      }
    }
    var visits = new ArrayList<Long>();
    var env =
        type.register(Cel.newBuilder())
            .variable("r", CelType.of(type.name()))
            .function(
                "visit",
                List.of(CelType.INT),
                CelType.INT,
                args -> {
                  long n = (Long) args.get(0);
                  visits.add(n);
                  return n;
                })
            .build();
    var program = env.compile("r.items.filter(v, visit(v) > 1).map(v, visit(v * 10))");
    assertEquals(List.of(20L, 30L), program.evaluate(activation));
    assertEquals(List.of(1L, 2L, 3L, 20L, 30L), visits);
    var read = env.compile("r.items");
    assertThrows(
        UnsupportedOperationException.class, () -> ((List<?>) read.evaluate(activation)).clear());
    assertEquals(List.of(1L, 2L, 3L), read.evaluate(activation));
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        var instrumented =
            env.compile(Source.newBuilder("cel", "r.items.map(v, v + 1)", "fields.cel").build());
        assertEquals(List.of(2L, 3L, 4L), instrumented.evaluate(activation));
      } finally {
        context.leave();
      }
    }
  }

  private static FieldDescriptorProto.Builder nestedField(
      String name, int number, FieldDescriptorProto.Type type, String typeName, boolean repeated) {
    var field =
        FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setType(type)
            .setLabel(
                repeated
                    ? FieldDescriptorProto.Label.LABEL_REPEATED
                    : FieldDescriptorProto.Label.LABEL_OPTIONAL);
    if (!typeName.isEmpty()) field.setTypeName(typeName);
    return field;
  }

  private static Descriptor nestedDescriptor(boolean required) throws Exception {
    var leaf =
        DescriptorProto.newBuilder()
            .setName("Leaf")
            .addField(
                nestedField("count", 1, FieldDescriptorProto.Type.TYPE_INT64, "", false)
                    .setLabel(
                        required
                            ? FieldDescriptorProto.Label.LABEL_REQUIRED
                            : FieldDescriptorProto.Label.LABEL_OPTIONAL));
    var labels =
        DescriptorProto.newBuilder()
            .setName("LabelsEntry")
            .setOptions(MessageOptions.newBuilder().setMapEntry(true))
            .addField(nestedField("key", 1, FieldDescriptorProto.Type.TYPE_STRING, "", false))
            .addField(nestedField("value", 2, FieldDescriptorProto.Type.TYPE_STRING, "", false));
    var byId =
        DescriptorProto.newBuilder()
            .setName("ByIdEntry")
            .setOptions(MessageOptions.newBuilder().setMapEntry(true))
            .addField(nestedField("key", 1, FieldDescriptorProto.Type.TYPE_INT64, "", false))
            .addField(
                nestedField(
                    "value", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, ".nested.Leaf", false));
    var root =
        DescriptorProto.newBuilder()
            .setName("Root")
            .addNestedType(labels)
            .addNestedType(byId)
            .addField(
                nestedField(
                    "child", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".nested.Leaf", false))
            .addField(
                nestedField(
                    "children", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, ".nested.Leaf", true))
            .addField(
                nestedField(
                    "labels",
                    3,
                    FieldDescriptorProto.Type.TYPE_MESSAGE,
                    ".nested.Root.LabelsEntry",
                    true))
            .addField(
                nestedField(
                    "by_id",
                    4,
                    FieldDescriptorProto.Type.TYPE_MESSAGE,
                    ".nested.Root.ByIdEntry",
                    true));
    return FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("nested.proto")
                .setPackage("nested")
                .setSyntax(required ? "proto2" : "proto3")
                .addMessageType(leaf)
                .addMessageType(root)
                .build(),
            new FileDescriptor[0])
        .findMessageTypeByName("Root");
  }

  @Test
  public void nestedMessagesAndMapsPreserveGenericSemantics() throws Exception {
    Descriptor descriptor = nestedDescriptor(false);
    var type = new ProtobufType(descriptor);
    var leaf = new ProtobufType(descriptor.findFieldByName("child").getMessageType());
    Object child = leaf.construct(Map.of("count", 7L));
    var fields =
        Map.<String, Object>of(
            "child",
            child,
            "children",
            List.of(child, child),
            "labels",
            Map.of("env", "prod"),
            "by_id",
            Map.of(1L, child));
    var input = type.construct(fields);
    var other = new ProtobufType(descriptor).construct(fields);
    for (long budget : new long[] {1, 5, 12, 30, 1000}) {
      var planned = environment(type, budget);
      var reference =
          type.register(Cel.newBuilder())
              .objectType(generic(type))
              .variable("r", CelType.of(type.name()))
              .evaluationBudget(budget)
              .build();
      for (String source :
          List.of(
              "r.child",
              "r.child.count",
              "has(r.child)",
              "r.?child",
              "r.children",
              "r.children.map(v, v.count * 2)",
              "r.children.map(v, v.count / (v.count - 7))",
              "r.labels",
              "r.labels['env']",
              "r.labels.map(k, r.labels[k])",
              "has(r.labels.env)",
              "r.by_id",
              "dyn(r.by_id)[1u].count",
              "dyn(r.by_id)[1.0].count",
              "r.by_id[2].count",
              "r.labels.size()")) {
        var template = planned.prepare(source);
        var ordinary = template.instantiate();
        var imported =
            CanonicalAst.program(
                planned, CanonicalAst.check(planned, CanonicalAst.parse(planned, source)));
        var generic = reference.compile(source);
        var genericImported =
            CanonicalAst.program(
                reference, CanonicalAst.check(reference, CanonicalAst.parse(reference, source)));
        for (Object value :
            List.of(input, other, type.construct(Map.of()), Unknown.of("r"), Map.of())) {
          var bindings = Map.of("r", value);
          Object expected = outcome(() -> generic.evaluate(bindings));
          assertEquals(source + "/" + budget, expected, outcome(() -> ordinary.evaluate(bindings)));
          assertEquals(
              source,
              outcome(() -> genericImported.evaluate(Activation.snapshot(bindings))),
              outcome(() -> imported.evaluate(Activation.snapshot(bindings))));
        }
      }
    }
    var env = environment(type, 1000);
    var snapshot = Activation.snapshot(Map.of("r", input));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((Map<?, ?>) env.compile("r.labels").evaluate(snapshot)).clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((List<?>) env.compile("r.children").evaluate(snapshot)).clear());
    assertEquals(
        List.of(14L, 14L), env.compile("r.children.map(v, v.count * 2)").evaluate(snapshot));
    // Duplicate wire entries retain protobuf's last-value-wins behavior.
    Descriptor entry = descriptor.findFieldByName("labels").getMessageType();
    var first =
        com.google.protobuf.DynamicMessage.newBuilder(entry)
            .setField(entry.findFieldByName("key"), "env")
            .setField(entry.findFieldByName("value"), "old")
            .build();
    var last = first.toBuilder().setField(entry.findFieldByName("value"), "new").build();
    var message =
        com.google.protobuf.DynamicMessage.newBuilder(descriptor)
            .addRepeatedField(descriptor.findFieldByName("labels"), first)
            .addRepeatedField(descriptor.findFieldByName("labels"), last)
            .build();
    assertEquals(
        Map.of("env", "new"), env.compile("r.labels").evaluate(Map.of("r", type.input(message))));
  }

  @Test
  public void absentRequiredMessagesFailOnlyWhenReadAndOwnedStorageStaysBounded() throws Exception {
    var type = new ProtobufType(nestedDescriptor(true));
    var env = environment(type, 1000);
    var input = Map.of("r", type.construct(Map.of()));
    assertEquals(false, env.compile("false && r.child.count > 0").evaluate(input));
    var program = env.prepare("r.child").instantiate();
    assertThrows(dev.elide.truffledata.ContractViolation.class, () -> program.evaluate(input));
    assertEquals(List.of(1L), Values.ownedList(new Object[] {1L}));
    assertThrows(CelException.class, () -> Values.ownedList(new Object[1_000_000]));
    var large = new HashMap<Object, Object>();
    for (long i = 0; i < 500_000; i++) large.put(i, i);
    assertThrows(CelException.class, () -> Values.ownedMap(large));
  }

  @Test
  public void mappedFieldsShareValidationAndPreserveAbsentValues() throws Exception {
    Descriptor[] mapped = {
      com.google.protobuf.BoolValue.getDescriptor(),
      com.google.protobuf.Int32Value.getDescriptor(),
      com.google.protobuf.Int64Value.getDescriptor(),
      com.google.protobuf.FloatValue.getDescriptor(),
      com.google.protobuf.DoubleValue.getDescriptor(),
      com.google.protobuf.StringValue.getDescriptor(),
      com.google.protobuf.Timestamp.getDescriptor(),
      com.google.protobuf.Duration.getDescriptor()
    };
    String[] names = {"flag", "small", "count", "single", "real", "text", "created", "elapsed"};
    var root = DescriptorProto.newBuilder().setName("Mapped");
    for (int i = 0; i < names.length; i++)
      root.addField(
          nestedField(
              names[i],
              i + 1,
              FieldDescriptorProto.Type.TYPE_MESSAGE,
              "." + mapped[i].getFullName(),
              false));
    FileDescriptor[] dependencies = {mapped[0].getFile(), mapped[6].getFile(), mapped[7].getFile()};
    var file =
        FileDescriptorProto.newBuilder()
            .setName("mapped.proto")
            .setSyntax("proto3")
            .addMessageType(root);
    for (FileDescriptor dependency : dependencies) file.addDependency(dependency.getName());
    Descriptor descriptor =
        FileDescriptor.buildFrom(file.build(), dependencies).findMessageTypeByName("Mapped");
    var type = new ProtobufType(descriptor);
    var env = environment(type, 1000);
    var reference =
        type.register(Cel.newBuilder())
            .objectType(generic(type))
            .variable("r", CelType.of(type.name()))
            .build();
    var fields =
        Map.<String, Object>of(
            "flag",
            true,
            "small",
            12L,
            "count",
            17L,
            "single",
            1.25,
            "real",
            2.5,
            "text",
            "yes",
            "created",
            java.time.Instant.ofEpochSecond(100, 5),
            "elapsed",
            java.time.Duration.ofSeconds(-2, 5));
    for (String name : names) {
      assertNotNull(type.prepareField(name, new ObjectType[] {type}));
      for (String expression : List.of("r." + name, "has(r." + name + ")", "r.?" + name)) {
        var planned = env.compile(expression);
        var generic = reference.compile(expression);
        for (Object value :
            List.of(
                type.construct(fields),
                type.construct(Map.of()),
                new ProtobufType(descriptor).construct(fields),
                Unknown.of("r"))) {
          var input = Activation.snapshot(Map.of("r", value));
          assertEquals(
              expression,
              outcome(() -> generic.evaluate(input)),
              outcome(() -> planned.evaluate(input)));
        }
      }
    }
    // Generated-message rows are outside this adapter's existing nested-row contract.
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var generated =
        new GeneratedMessage(type.plan().nested(2), 1, new byte[0]) {
          public Object get(int ordinal) {
            reads.incrementAndGet();
            return 7L;
          }

          public byte[] encode() {
            return new byte[] {8, 7};
          }
        };
    Object generatedInput = type.input(type.plan().newBuilder().set(2, generated).build());
    reads.set(0);
    assertThrows(
        ClassCastException.class,
        () -> reference.compile("r.count").evaluate(Map.of("r", generatedInput)));
    assertThrows(
        ClassCastException.class,
        () -> env.compile("r.count").evaluate(Map.of("r", generatedInput)));
    assertEquals(0, reads.get());
    var descriptorFields = descriptor.getFields();
    // Direct native builders can represent invalid well-known values; both paths must validate
    // them.
    for (int index : new int[] {6, 7}) {
      var nested = type.plan().nested(index);
      var builder = nested.newBuilder();
      builder.set(1, 1_000_000_000L);
      Object value = type.input(type.plan().newBuilder().set(index, builder.build()).build());
      String source = "r." + descriptorFields.get(index).getName();
      assertEquals(
          CelException.Code.OVERFLOW,
          outcome(() -> env.compile(source).evaluate(Map.of("r", value))));
      assertEquals(
          outcome(() -> reference.compile(source).evaluate(Map.of("r", value))),
          outcome(() -> env.compile(source).evaluate(Map.of("r", value))));
    }
  }
}
