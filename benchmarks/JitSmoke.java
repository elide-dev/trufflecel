package dev.trufflecel;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.graalvm.polyglot.Context;

/** Native Image must include a working guest compiler, not just build successfully. */
public final class JitSmoke {
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
