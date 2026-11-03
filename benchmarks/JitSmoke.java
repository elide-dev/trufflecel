package dev.trufflecel;

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
    Program program = Cel.compile(source);
    if (!(program.callTarget() instanceof OptimizedCallTarget target))
      throw new AssertionError("Optimizing runtime required");
    for (int i = 0; i < 2000; i++) {
      if (!expected.equals(program.evaluate(input))) throw new AssertionError(source);
    }
    target.compile(true);
    target.waitForCompilation();
    if (!target.isValidLastTier())
      throw new AssertionError("Guest compilation required: " + source);
    if (!expected.equals(program.evaluate(input))
        || !changedExpected.equals(program.evaluate(changed)))
      throw new AssertionError("Compiled semantics changed: " + source);
    System.out.println("NATIVE COMPILED " + source);
  }
}
