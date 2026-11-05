package dev.trufflecel;

import static org.junit.Assert.*;

import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import com.oracle.truffle.api.source.Source;
import java.util.*;
import java.util.concurrent.*;
import dev.elide.truffledata.DataArray;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.Breakpoint;
import org.graalvm.polyglot.Context;
import org.junit.Test;

public final class PreparedFieldTest {
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
}
