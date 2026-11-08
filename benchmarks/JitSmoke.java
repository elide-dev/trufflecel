package dev.trufflecel;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;

/** Native Image must include a working guest compiler, not just build successfully. */
public final class JitSmoke {
  private static final PreparedProgram IMAGE_POLICY =
      PolicyCompiler.prepare(
          EnvironmentYaml.read("variables: [{name: x, type: int}]"),
          "name: image-policy\nrule: {match: [{condition: 'x > 0', output: 'x + 1'}, {output: '0'}]}");

  public static void main(String[] args) throws Exception {
    dev.trufflecel.bench.SimulationBenchmark.main(new String[] {"--smoke"});
    try (Context context =
        Context.newBuilder("cel")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.TreatPerformanceWarningsAsErrors", "all")
            .option("compiler.MaximumGraalGraphSize", "10000")
            .build()) {
      context.initialize("cel");
      var aggregate = context.eval("cel", "{'items': [1, 2], 'large': 18446744073709551615u}");
      if (aggregate.getMember("items").getArrayElement(1).asLong() != 2L
          || !aggregate.getMember("large").asBigInteger().toString().equals("18446744073709551615"))
        throw new AssertionError("Native Polyglot aggregate interop");
      var debugSource =
          org.graalvm.polyglot.Source.newBuilder(
                  "cel", "[1,2].map(x,\n x + 1).size()", "native-debug.cel")
              .build();
      context.eval(debugSource);
      var locals = new ArrayList<String>();
      try (var session =
          Debugger.find(context.getEngine())
              .startSession(
                  event ->
                      locals.add(
                          event
                              .getTopStackFrame()
                              .getScope()
                              .getDeclaredValue("x")
                              .toDisplayString()))) {
        session.install(Breakpoint.newBuilder(debugSource.getURI()).lineIs(2).build());
        if (context.eval(debugSource).asLong() != 2 || !locals.equals(List.of("1", "2")))
          throw new AssertionError("Native debugger source scopes: " + locals);
      }
      System.out.println("Native debugger qualification passed");
      context.enter();
      try {
        var sorting = Cel.compile("xs.sortBy(x, x)");
        var sortTarget = (OptimizedCallTarget) sorting.callTarget();
        var mapReceiver = Map.of("xs", Map.of("a", 1L));
        var listReceiver = Map.of("xs", List.of(2L, 1L));
        for (int phase = 0; phase < 2; phase++) {
          if (phase == 1) {
            sortTarget.compile(true);
            sortTarget.waitForCompilation();
          }
          for (int i = 0; i < 1000; i++) {
            if (!sorting.evaluate(listReceiver).equals(List.of(1L, 2L)))
              throw new AssertionError("Native sortBy list result");
            try {
              sorting.evaluate(mapReceiver);
              throw new AssertionError("Native sortBy accepted a map");
            } catch (CelException error) {
              if (error.code() != CelException.Code.NO_OVERLOAD) throw error;
            }
          }
        }
        if (!sortTarget.isValidLastTier())
          throw new AssertionError("Native sortBy receiver errors must stay compiled");
        var regexProgram = Cel.compile("s.matches('^[a-z]+@[a-z]+[.]com$')");
        check(
            regexProgram,
            Map.of("s", "alice@example.com"),
            true,
            Map.of("s", "not-an-email"),
            false);
        if (NodeUtil.findAllNodeInstances(
                ((OptimizedCallTarget) regexProgram.callTarget()).getRootNode(),
                Regex.MatchNode.class)
            .stream()
            .noneMatch(Regex.MatchNode::isAccelerated))
          throw new AssertionError("Native constant matches did not use TruffleRegex");
        check(IMAGE_POLICY.instantiate(), Map.of("x", 2L), 3L, Map.of("x", -1L), 0L);
        var policyEnvironment = EnvironmentYaml.read("variables: [{name: x, type: int}]");
        check(
            PolicyCompiler.compile(
                policyEnvironment,
                "name: runtime-policy\nrule: {match: [{condition: 'x > 0', output: 'x + 2'}]}"),
            Map.of("x", 2L),
            4L,
            Map.of("x", -1L),
            OptionalValue.none());
        if (CostEstimator.estimate(policyEnvironment, "x + 1").maximum().isEmpty())
          throw new AssertionError("Native static cost estimation");
        System.out.println("Native policy and environment tooling qualification passed");
        var macros =
            Cel.newBuilder()
                .variable("x", CelType.INT)
                .macro(
                    Macro.global(
                        "twice", 1, (c, t, a) -> Ast.Node.call("*", a.get(0), Ast.Node.literal(2))))
                .build();
        Ast syntax = Ast.parse(macros, "twice(x) + 1");
        if (!syntax.unparse().contains("twice"))
          throw new AssertionError("Native macro source reconstruction");
        Ast edited =
            syntax
                .editor()
                .transform(
                    n ->
                        n.kind() == Ast.Kind.CONSTANT && Long.valueOf(1).equals(n.value())
                            ? Ast.Node.literal(3)
                            : n)
                .freeze();
        var legacy = CanonicalAst.toV1Alpha1(CanonicalAst.parsed(edited));
        var checked = CanonicalAst.check(macros, legacy);
        if (!checked
            .getDescriptorForType()
            .getFullName()
            .equals("google.api.expr.v1alpha1.CheckedExpr"))
          throw new AssertionError("Native legacy AST schema");
        check(CanonicalAst.program(macros, checked), Map.of("x", 2L), 7L, Map.of("x", 4L), 11L);
        var original = CanonicalAst.toV1Alpha1(CanonicalAst.parse(macros, "twice(x)"));
        if (!CanonicalAst.unparse(macros, original).equals("twice(x)"))
          throw new AssertionError("Native canonical macro source reconstruction");
        System.out.println("Native editable AST, macro and v1alpha1 qualification passed");
        var file =
            FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                    .setName("native_zero.proto")
                    .setSyntax("proto3")
                    .addMessageType(
                        DescriptorProto.newBuilder()
                            .setName("Record")
                            .addField(
                                FieldDescriptorProto.newBuilder()
                                    .setName("number")
                                    .setNumber(1)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT64)))
                    .build(),
                new FileDescriptor[] {});
        var protobuf = new ProtobufType(file.getMessageTypes().get(0));
        var zeroProgram =
            protobuf
                .register(Cel.newBuilder())
                .variable("p", CelType.of(protobuf.name()))
                .build()
                .compile("optional.ofNonZeroValue(p).hasValue()");
        check(
            zeroProgram,
            Map.of("p", protobuf.construct(Map.of())),
            false,
            Map.of("p", protobuf.construct(Map.of("number", 1L))),
            true);
        check(
            zeroProgram,
            Map.of("p", protobuf.construct(Map.of())),
            false,
            Map.of("p", protobuf.decode(new Bytes(new byte[] {16, 0}))),
            true);
        check(
            "regex.replace(s, '(a)', r'\\1x')", Map.of("s", "aba"), "axbax", Map.of("s", "z"), "z");
        check(
            "regex.extractAll(s, '[0-9]+')",
            Map.of("s", "a1b2"),
            java.util.List.of("1", "2"),
            Map.of("s", "z"),
            java.util.List.of());
        check(
            "optional.unwrap([optional.of(x), optional.none()]).last().value() == math.sqrt(9.0)",
            Map.of("x", 3.0),
            true,
            Map.of("x", 2.0),
            false);
        var masked =
            protobuf.mask(com.google.protobuf.FieldMask.newBuilder().addPaths("number").build());
        var maskedProgram =
            masked
                .register(Cel.newBuilder())
                .variable("p", CelType.of(masked.name()))
                .build()
                .compile("p.number + 1");
        check(
            maskedProgram,
            Map.of("p", masked.construct(Map.of("number", 1L))),
            2L,
            Map.of("p", masked.construct(Map.of("number", 3L))),
            4L);
        var partialProgram = Cel.compile("cel.bind(x, data, x.known + x.secret)");
        var partialInput =
            PartialActivation.of(
                Map.of("data", Map.of("known", 2L)), AttributePattern.of("data").qualify("secret"));
        for (int i = 0; i < 2000; i++) {
          var pending = partialProgram.partialEvaluate(partialInput);
          if (!pending.unknownAttributes().equals(java.util.Set.of("data.secret")))
            throw new AssertionError("partial unknown");
          if (!pending
              .advanceEvaluation(Map.of(Attribute.of("data").qualify("secret"), 3L))
              .value()
              .equals(5L)) throw new AssertionError("partial advance");
        }
        var partialTarget = (OptimizedCallTarget) partialProgram.traceTarget();
        partialTarget.compile(true);
        partialTarget.waitForCompilation();
        if (!partialProgram
                .partialEvaluate(partialInput)
                .advanceEvaluation(Map.of(Attribute.of("data").qualify("secret"), 4L))
                .value()
                .equals(6L)
            || !partialTarget.isValidLastTier())
          throw new AssertionError("compiled partial target");
        var traceCount = new java.util.concurrent.atomic.AtomicInteger();
        if (!Cel.compile("1 + 2").trace(event -> traceCount.incrementAndGet()).evaluate().equals(3L)
            || traceCount.get() != 3) throw new AssertionError("native source trace");
        var late =
            Cel.newBuilder()
                .lateFunction("inc", "inc_int", false, List.of(CelType.INT), CelType.INT)
                .build()
                .compile("inc(1)");
        var functions =
            EvaluationOptions.newBuilder()
                .function("inc_int", a -> (Long) a.getFirst() + 1)
                .build();
        if (!late.evalAsync(Map.of(), functions, Runnable::run).join().equals(2L))
          throw new AssertionError("native async late function");
        var controlled = Cel.newBuilder().variable("x", CelType.INT).build().compile("x + 1");
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var controls = EvaluationOptions.newBuilder().budget(10).cancelled(cancelled::get).build();
        var input = Activation.snapshot(Map.of("x", 2L));
        for (int i = 0; i < 2000; i++) {
          cancelled.set(i % 2 == 0);
          var result = controlled.evaluateWithDetails(input, controls);
          if ((result.error() != null) != cancelled.get())
            throw new AssertionError("cancellation outcome");
        }
        var controlledTarget = (OptimizedCallTarget) controlled.controlledTarget();
        controlledTarget.compile(true);
        controlledTarget.waitForCompilation();
        for (int i = 0; i < 1000; i++) {
          cancelled.set(i % 2 == 0);
          var result = controlled.evaluateWithDetails(input, controls);
          if (cancelled.get()) {
            if (result.error().code() != CelException.Code.RESOURCE_EXHAUSTED)
              throw new AssertionError("cancel code");
          } else if (!result.value().equals(3L) || result.actualCost() != 3)
            throw new AssertionError("controlled result/cost");
        }
        if (!controlledTarget.isValidLastTier())
          throw new AssertionError("controlled target must remain compiled");
        var overloaded =
            Cel.newBuilder()
                .variable("x", CelType.DYN)
                .function(
                    "label",
                    "label_int",
                    false,
                    List.of(CelType.INT),
                    CelType.STRING,
                    a -> "integer")
                .function(
                    "label",
                    "label_string",
                    false,
                    List.of(CelType.STRING),
                    CelType.STRING,
                    a -> "text")
                .build()
                .compile("label(x)");
        check(overloaded, Map.of("x", 1L), "integer", Map.of("x", "one"), "text");
        check(
            Cel.newBuilder()
                .variable("x", CelType.INT)
                .commonSubexpressions(true)
                .build()
                .compile("(x + 1) * (x + 1)"),
            Map.of("x", 2L),
            9L,
            Map.of("x", 3L),
            16L);
        var pojo = PojoFixtures_AddressCel.INSTANCE;
        check(
            pojo.register(Cel.newBuilder())
                .variable("p", CelType.of(pojo.name()))
                .build()
                .compile("p.zip > 100"),
            Map.of("p", pojo.snapshot(new PojoFixtures.Address(123, "Main"))),
            true,
            Map.of("p", pojo.snapshot(new PojoFixtures.Address(1, "Other"))),
            false);
        check("x * 2 + 1 > 100", Map.of("x", 60L), true, Map.of("x", 2L), false);
        check(
            "enabled && 10 / x > 1",
            Map.of("enabled", true, "x", 2L),
            true,
            Map.of("enabled", false, "x", 0L),
            false);
        check(
            "xs.all(x, x > 0)",
            Map.of("xs", List.of(1L, 2L, 3L)),
            true,
            Map.of("xs", List.of(1L, -2L)),
            false);
        check("{'v': x}.v > 5", Map.of("x", 10L), true, Map.of("x", 0L), false);
        check("optional.of(x).orValue(0) > 5", Map.of("x", 10L), true, Map.of("x", 0L), false);
        check("cel.bind(v, x + 1, v > 5)", Map.of("x", 10L), true, Map.of("x", 0L), false);
        check(
            "cel.block([x + 1], cel.index(0) > 5)", Map.of("x", 10L), true, Map.of("x", 0L), false);
        check(
            "json.parse(s).value().a == 1",
            Map.of("s", "{\"a\":1}"),
            true,
            Map.of("s", "{\"a\":2}"),
            false);
        check(
            "yaml.parse(s).value().a == 1", Map.of("s", "a: 1"), true, Map.of("s", "a: 2"), false);
        check("sets.contains([1,2,3], [x])", Map.of("x", 2L), true, Map.of("x", 4L), false);
      } finally {
        context.leave();
      }
    }
    System.out.println("Native guest-JIT qualification passed");
  }

  private static void check(
      String source,
      Map<String, ?> input,
      Object expected,
      Map<String, ?> changed,
      Object changedExpected) {
    check(Cel.compile(source), input, expected, changed, changedExpected);
  }

  private static void check(
      Program program,
      Map<String, ?> input,
      Object expected,
      Map<String, ?> changed,
      Object changedExpected) {
    String source = program.source();
    Activation prepared = Activation.snapshot(input);
    Activation preparedChanged = Activation.snapshot(changed);
    if (!(program.callTarget() instanceof OptimizedCallTarget target))
      throw new AssertionError("Optimizing runtime required");
    for (int i = 0; i < 2000; i++) {
      if (!expected.equals(i % 2 == 0 ? program.evaluate(input) : program.evaluate(prepared)))
        throw new AssertionError(source);
    }
    target.compile(true);
    target.waitForCompilation();
    if (!target.isValidLastTier())
      throw new AssertionError("Guest compilation required: " + source);
    if (!expected.equals(program.evaluate(input))
        || !changedExpected.equals(program.evaluate(changed))
        || !expected.equals(program.evaluate(prepared))
        || !changedExpected.equals(program.evaluate(preparedChanged)))
      throw new AssertionError("Compiled semantics changed: " + source);
    System.out.println("NATIVE COMPILED " + source);
  }
}
