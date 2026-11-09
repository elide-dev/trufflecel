package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import dev.trufflecel.NegativeCases.Case;
import dev.trufflecel.NegativeCases.Outcome;
import dev.trufflecel.NegativeCases.Phase;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Every evaluation-phase negative case must produce the same phase and category from last-tier
 * guest-compiled code as from the interpreter, and the error path must stay compiled: after a
 * bounded number of compilations, repeated errors neither deoptimize nor invalidate the target.
 */
@RunWith(Parameterized.class)
public final class NegativeCompiledTest {
  /**
   * Compilations an error path may need before it settles: the initial compilation plus one
   * recompilation per distinct {@code @TruffleBoundary} method that raises the error, because each
   * boundary's no-exception speculation fails at most once per call target. Errors thrown from
   * inlined code are compiled control flow from the first compilation. No evaluation-phase case
   * raises errors from more than two boundaries.
   */
  private static final int MAX_COMPILATIONS = 3;

  private static final int SUSTAINED_ERRORS = 1_000;

  private static final Map<OptimizedCallTarget, AtomicInteger> invalidations =
      new ConcurrentHashMap<>();
  private static final Map<OptimizedCallTarget, AtomicInteger> deoptimizations =
      new ConcurrentHashMap<>();
  private static final OptimizedTruffleRuntimeListener listener =
      new OptimizedTruffleRuntimeListener() {
        @Override
        public void onCompilationInvalidated(
            OptimizedCallTarget target, Object source, CharSequence reason) {
          invalidations.computeIfAbsent(target, t -> new AtomicInteger()).incrementAndGet();
        }

        @Override
        @SuppressWarnings("deprecation") // Observe the callback exposed by the pinned GraalVM.
        public void onCompilationDeoptimized(OptimizedCallTarget target, Frame frame) {
          deoptimizations.computeIfAbsent(target, t -> new AtomicInteger()).incrementAndGet();
        }
      };
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
    OptimizedTruffleRuntime.getRuntime().addListener(listener);
  }

  @AfterClass
  public static void leave() {
    OptimizedTruffleRuntime.getRuntime().removeListener(listener);
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
  public void compiledOutcomeMatchesInterpreterAndStaysCompiled() {
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
    // Every compilation must reproduce the interpreter's phase and category, and the error path
    // must settle within MAX_COMPILATIONS.
    int compilations = 0;
    while (true) {
      for (int i = 0; i < 100; i++) Outcome.observe(() -> program.evaluate(bindings));
      target.compile(true);
      target.waitForCompilation();
      compilations++;
      assertTrue(
          "Guest last-tier compilation required: " + testCase.source(), target.isValidLastTier());
      Outcome compiled = Outcome.observe(() -> program.evaluate(bindings));
      expected.verify("compiled#" + compilations, compiled);
      assertEquals("category differs between tiers", interpreted.code(), compiled.code());
      if (target.isValidLastTier()) break;
      assertTrue(
          "error path still invalidates after "
              + MAX_COMPILATIONS
              + " compilations: "
              + testCase.source(),
          compilations < MAX_COMPILATIONS);
    }
    // Once settled, errors are handled by the compiled code without any further deoptimization.
    int invalidated = count(invalidations, target), deoptimized = count(deoptimizations, target);
    for (int i = 0; i < SUSTAINED_ERRORS; i++) {
      Outcome compiled = Outcome.observe(() -> program.evaluate(bindings));
      expected.verify("sustained#" + i, compiled);
      assertEquals("category differs between tiers", interpreted.code(), compiled.code());
    }
    assertEquals(
        "error path invalidated compiled code: " + testCase.source(),
        0,
        count(invalidations, target) - invalidated);
    assertEquals(
        "error path deoptimized compiled code: " + testCase.source(),
        0,
        count(deoptimizations, target) - deoptimized);
    assertTrue("error path must stay compiled: " + testCase.source(), target.isValidLastTier());
    System.out.printf("COMPILED %s: %d compilation(s), stable%n", testCase.id(), compilations);
  }

  private static int count(
      Map<OptimizedCallTarget, AtomicInteger> counters, OptimizedCallTarget t) {
    AtomicInteger counter = counters.get(t);
    return counter == null ? 0 : counter.get();
  }

  private Map<String, Object> bindings() {
    if (!production) return testCase.bindings();
    var converted = new LinkedHashMap<String, Object>();
    testCase.bindings().forEach((k, v) -> converted.put(k, ProductionCorpus.input(v)));
    return converted;
  }
}
