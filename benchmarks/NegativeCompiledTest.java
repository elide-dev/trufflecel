package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.runtime.OptimizedCallTarget;
import dev.trufflecel.NegativeCases.Case;
import dev.trufflecel.NegativeCases.Outcome;
import dev.trufflecel.NegativeCases.Phase;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Every evaluation-phase negative case must produce the same phase and category from last-tier
 * guest-compiled code as from the interpreter, and the compiled code must survive the error path.
 */
@RunWith(Parameterized.class)
public final class NegativeCompiledTest {
  private static Context context;

  @BeforeClass
  public static void enter() {
    var builder =
        Context.newBuilder("cel")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false");
    // NEGATIVE_TRACE=1 NEGATIVE_CASE=<id> narrows the run and logs compiler decisions.
    if (System.getenv("NEGATIVE_TRACE") != null)
      builder
          .option("engine.TraceCompilation", "true")
          .option("engine.TraceDeoptimizeFrame", "true");
    context =
        builder
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.TreatPerformanceWarningsAsErrors", "all")
            .option("compiler.MaximumGraalGraphSize", "10000")
            .build();
    context.initialize("cel");
    context.enter();
  }

  @AfterClass
  public static void leave() {
    context.leave();
    context.close();
  }

  @Parameterized.Parameters(name = "{0}/{1}")
  public static Collection<Object[]> cases() {
    var result = new ArrayList<Object[]>();
    String only = System.getenv("NEGATIVE_CASE");
    for (Case c : NegativeCases.all()) {
      Outcome planned = c.checked().phase() == Phase.CHECK ? c.unchecked() : c.checked();
      if (planned.isError() && planned.phase() != Phase.EVALUATE) continue;
      if (only != null && !only.equals(c.id())) continue;
      result.add(new Object[] {c, "fixture"});
      if (c.config().protobuf()) result.add(new Object[] {c, "production"});
    }
    return result;
  }

  private final Case testCase;
  private final boolean production;

  public NegativeCompiledTest(Case testCase, String adapter) {
    this.testCase = testCase;
    this.production = adapter.equals("production");
  }

  @Test
  public void compiledOutcomeMatchesInterpreter() {
    Environment env = testCase.config().environment(production);
    boolean unchecked = testCase.checked().phase() == Phase.CHECK;
    Outcome expected = unchecked ? testCase.unchecked() : testCase.checked();
    Program program = unchecked ? env.parse(testCase.source()) : env.compile(testCase.source());
    Map<String, Object> bindings = bindings();
    assertTrue(
        "Optimizing Truffle runtime required", program.callTarget() instanceof OptimizedCallTarget);
    var target = (OptimizedCallTarget) program.callTarget();
    Outcome interpreted = Outcome.observe(() -> program.evaluate(bindings));
    expected.verify("interpreter", interpreted);
    // Every compilation must reproduce the interpreter's phase and category. Error paths raised
    // inside @TruffleBoundary methods settle after one profiling deoptimization; errors thrown from
    // inlined helpers and exact-arithmetic intrinsics currently deoptimize on each throw, so the
    // number of compilations is reported rather than asserted (see docs/COVERAGE.md).
    int compilations = 0;
    boolean stable = false;
    while (!stable && compilations < 3) {
      for (int i = 0; i < 100; i++) Outcome.observe(() -> program.evaluate(bindings));
      target.compile(true);
      target.waitForCompilation();
      compilations++;
      assertTrue(
          "Guest last-tier compilation required: " + testCase.source(), target.isValidLastTier());
      Outcome compiled = Outcome.observe(() -> program.evaluate(bindings));
      expected.verify("compiled#" + compilations, compiled);
      assertEquals("category differs between tiers", interpreted.code(), compiled.code());
      stable = target.isValidLastTier();
    }
    System.out.printf(
        "COMPILED %s: %d compilation(s), %s%n",
        testCase.id(), compilations, stable ? "stable" : "deoptimizes on each error");
  }

  private Map<String, Object> bindings() {
    if (!production) return testCase.bindings();
    var converted = new LinkedHashMap<String, Object>();
    testCase.bindings().forEach((k, v) -> converted.put(k, ProductionCorpus.input(v)));
    return converted;
  }
}
