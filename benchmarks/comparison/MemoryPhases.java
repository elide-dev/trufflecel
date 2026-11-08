package dev.trufflecel;

import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedRuntimeOptions;
import dev.cel.runtime.CelRuntime;
import java.util.Arrays;
import java.util.Locale;
import org.graalvm.polyglot.Context;

/**
 * Diagnostic phase checkpoints; the parent samples Linux process mappings while execution pauses.
 */
public final class MemoryPhases {
  private static volatile Object sink;
  private static long phaseStart = System.nanoTime();

  private interface Operation {
    Object run(int index) throws Exception;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4)
      throw new IllegalArgumentException("engine workload checkpoints idleMillis");
    boolean standalone = args[0].equals("truffle-standalone");
    boolean ours = args[0].equals("trufflecel") || standalone;
    boolean interpreter = args[0].equals("truffle-interpreter");
    if (!ours && !interpreter && !args[0].equals("cel-java"))
      throw new IllegalArgumentException("unknown engine");
    int k = Arrays.asList(Fixtures.NAMES).indexOf(args[1]);
    if (k < 0) throw new IllegalArgumentException("unknown workload");
    boolean checkpoints = Boolean.parseBoolean(args[2]);
    long idleMillis = Long.parseLong(args[3]);
    // Standalone Java API roots use the fallback engine, so set and verify its defaults too.
    if (ours || interpreter) {
      System.setProperty("polyglot.engine.Compilation", Boolean.toString(ours));
      System.setProperty("polyglot.engine.BackgroundCompilation", "false");
      System.setProperty("polyglot.engine.CompilationFailureAction", "Throw");
      System.setProperty("polyglot.compiler.TreatPerformanceWarningsAsErrors", "all");
    }
    // Initialize diagnostic bookkeeping before taking the baseline.
    Runtime.getRuntime().totalMemory();
    System.out.flush();
    System.in.available();
    checkpoint("baseline", null, checkpoints);
    Context context = null;
    if ((ours || interpreter) && !standalone) {
      context =
          Context.newBuilder("cel")
              .allowExperimentalOptions(true)
              .option("engine.BackgroundCompilation", "false")
              .option("engine.Compilation", Boolean.toString(ours))
              .option("engine.CompilationFailureAction", "Throw")
              .option("compiler.TreatPerformanceWarningsAsErrors", "all")
              .build();
      context.initialize("cel");
      context.enter();
    }
    checkpoint("context", null, checkpoints);
    try {
      var inputs = Fixtures.inputs(k);
      Program truffle = null;
      Operation operation;
      if (ours || interpreter) {
        truffle = Fixtures.Truffle.COMPILER.compile(Fixtures.SOURCES[k]);
        Program program = truffle;
        operation = i -> program.evaluate(inputs.get(i & 3));
      } else {
        CelRuntime.Program program =
            Fixtures.Java.RUNTIME.createProgram(
                Fixtures.Java.COMPILER.compile(Fixtures.SOURCES[k]).getAst());
        operation = i -> program.eval(inputs.get(i & 3));
      }
      checkpoint("prepared", null, checkpoints);
      for (int i = 0; i < 4; i++)
        if (!Fixtures.EXPECTED[k][i].equals(operation.run(i)))
          throw new AssertionError("incorrect fixture " + i);
      OptimizedCallTarget target =
          truffle == null ? null : (OptimizedCallTarget) truffle.callTarget();
      if (target != null) {
        if (target.getOptionValue(OptimizedRuntimeOptions.Compilation) != ours
            || target.getOptionValue(OptimizedRuntimeOptions.BackgroundCompilation)
            || target.getOptionValue(OptimizedRuntimeOptions.CompilationFailureAction)
                != OptimizedRuntimeOptions.ExceptionAction.Throw)
          throw new AssertionError("target uses incorrect engine options");
        if (!"all"
            .equals(target.getCompilerOptions().get("compiler.TreatPerformanceWarningsAsErrors")))
          throw new AssertionError("compiler warning gate missing: " + target.getCompilerOptions());
        if ("false".equals(System.getProperty("polyglot.compiler.EncodedGraphCache"))
            && !"false".equals(target.getCompilerOptions().get("compiler.EncodedGraphCache")))
          throw new AssertionError("compiler cache option missing: " + target.getCompilerOptions());
      }
      checkpoint("first_results", target, checkpoints);
      if (ours) {
        for (int i = 0; i < 2000; i++) sink = operation.run(i);
        checkpoint("warmup", target, checkpoints);
        target.compile(true);
        target.waitForCompilation();
        if (!target.isValidLastTier()) throw new AssertionError("last-tier compilation required");
      } else {
        checkpoint("warmup", target, checkpoints);
      }
      checkpoint("compiled", target, checkpoints);
      // Keep the existing comparison's 8 x 65,536 evaluation work volume.
      for (int sample = 0; sample < 8; sample++) measure(operation, 65536, k);
      checkpoint("evaluated", target, checkpoints);
      if (checkpoints) {
        System.gc();
        checkpoint("application_gc", target, true);
        if (idleMillis > 0) Thread.sleep(idleMillis);
        checkpoint("idle", target, true);
        System.gc();
        checkpoint("idle_application_gc", target, true);
      }
      if (ours && !target.isValidLastTier()) throw new AssertionError("guest code invalidated");
      if (interpreter && target.isValid()) throw new AssertionError("interpreter compiled");
      if (!checkpoints) {
        double[] samples = new double[7];
        for (int sample = 0; sample < samples.length; sample++) {
          long start = System.nanoTime(), elapsed, count = 0;
          do {
            measure(operation, 256, k);
            count += 256;
            elapsed = System.nanoTime() - start;
          } while (elapsed < 150_000_000L);
          samples[sample] = (double) elapsed / count;
        }
        if (ours && !target.isValidLastTier()) throw new AssertionError("guest code invalidated");
        System.out.println("{\"samples_ns\":" + Arrays.toString(samples) + "}");
      }
    } finally {
      if (context != null) {
        context.leave();
        context.close();
      }
    }
    if (checkpoints) {
      System.gc();
      checkpoint("context_closed_gc", null, true);
    }
  }

  private static void checkpoint(String phase, OptimizedCallTarget target, boolean enabled)
      throws Exception {
    if (!enabled) return;
    long now = System.nanoTime();
    Runtime runtime = Runtime.getRuntime();
    System.out.printf(
        Locale.ROOT,
        "{\"phase\":\"%s\",\"elapsed_ns\":%d,\"heap_used\":%d,\"heap_committed\":%d,\"last_tier\":%s,\"effective_options\":%s}%n",
        phase,
        now - phaseStart,
        runtime.totalMemory() - runtime.freeMemory(),
        runtime.totalMemory(),
        target != null && target.isValidLastTier(),
        effectiveOptions(target));
    System.out.flush();
    if (System.in.read() != '\n')
      throw new AssertionError("parent checkpoint acknowledgement missing");
    phaseStart = System.nanoTime();
  }

  private static String effectiveOptions(OptimizedCallTarget target) {
    if (target == null) return "null";
    return "{\"compilation\":"
        + target.getOptionValue(OptimizedRuntimeOptions.Compilation)
        + ",\"background_compilation\":"
        + target.getOptionValue(OptimizedRuntimeOptions.BackgroundCompilation)
        + ",\"compiler_idle_delay_ms\":"
        + target.getOptionValue(OptimizedRuntimeOptions.CompilerIdleDelay)
        + ",\"graph_cache_purge_delay_ms\":"
        + target.getOptionValue(OptimizedRuntimeOptions.EncodedGraphCachePurgeDelay)
        + ",\"compiler_options\":\""
        + target.getCompilerOptions().toString().replace("\\", "\\\\").replace("\"", "\\\"")
        + "\"}";
  }

  private static void measure(Operation operation, int count, int fixture) throws Exception {
    long checksum = 0;
    for (int i = 0; i < count; i++) {
      Object value = operation.run(i);
      checksum += value instanceof Boolean b ? b ? 1 : 0 : (Long) value;
    }
    long expected = 0;
    for (Object value : Fixtures.EXPECTED[fixture])
      expected += value instanceof Boolean b ? b ? 1 : 0 : (Long) value;
    if (checksum != count / 4 * expected) throw new AssertionError("checksum mismatch");
    sink = checksum;
  }
}
