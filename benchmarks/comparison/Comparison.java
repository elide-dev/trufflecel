package dev.trufflecel;

import com.oracle.truffle.runtime.OptimizedCallTarget;
import dev.cel.runtime.CelRuntime;
import java.util.Locale;
import java.util.Arrays;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Context;

/** Small batched timing harness. Fork isolation and result rendering are handled by compare.py. */
public final class Comparison {
  private static volatile Object sink;

  private interface Operation {
    Object run(int index) throws Exception;
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--smoke")) {
      for (String engine : new String[] {"trufflecel", "cel-java"})
        for (String stage : new String[] {"compile", "evaluate", "image-ast"})
          for (String fixture : Fixtures.NAMES) run(engine, stage, fixture, true);
    } else {
      if (args.length != 3) throw new IllegalArgumentException("engine stage workload, or --smoke");
      run(args[0], args[1], args[2], false);
    }
  }

  private static void run(String engine, String stage, String fixture, boolean smoke)
      throws Exception {
    int k = Arrays.asList(Fixtures.NAMES).indexOf(fixture);
    if (k < 0
        || !(engine.equals("trufflecel") || engine.equals("cel-java"))
        || !(stage.equals("compile") || stage.equals("evaluate") || stage.equals("image-ast")))
      throw new IllegalArgumentException("Unknown benchmark");
    boolean nativeImage = ImageInfo.inImageRuntimeCode();
    if (nativeImage && !ImageExpressions.BUILT_IN_IMAGE)
      throw new AssertionError("Checked ASTs were not created at image build time");
    try (Context context =
        Context.newBuilder("cel")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.TreatPerformanceWarningsAsErrors", "all")
            .build()) {
      context.initialize("cel");
      context.enter();
      try {
        Program truffle =
            stage.equals("image-ast")
                ? ImageExpressions.TRUFFLE[k].instantiate()
                : Fixtures.TRUFFLE.compile(Fixtures.SOURCES[k]);
        CelRuntime.Program java =
            Fixtures.RUNTIME.createProgram(
                stage.equals("image-ast")
                    ? ImageExpressions.JAVA[k]
                    : Fixtures.JAVA.compile(Fixtures.SOURCES[k]).getAst());
        var inputs = Fixtures.inputs(k);
        for (int i = 0; i < 4; i++) {
          if (!Fixtures.EXPECTED[k][i].equals(truffle.evaluate(inputs.get(i)))
              || !Fixtures.EXPECTED[k][i].equals(java.eval(inputs.get(i))))
            throw new AssertionError("Incorrect " + fixture + " activation " + i);
        }
        boolean compile = stage.equals("compile");
        boolean ours = engine.equals("trufflecel");
        Operation operation =
            compile
                ? (ours
                    ? i -> Fixtures.TRUFFLE.compile(Fixtures.SOURCES[k])
                    : i ->
                        Fixtures.RUNTIME.createProgram(
                            Fixtures.JAVA.compile(Fixtures.SOURCES[k]).getAst()))
                : (ours
                    ? i -> truffle.evaluate(inputs.get(i & 3))
                    : i -> java.eval(inputs.get(i & 3)));
        OptimizedCallTarget target = null;
        if (ours && !compile) {
          if (!(truffle.callTarget() instanceof OptimizedCallTarget optimized))
            throw new AssertionError("Optimizing runtime required");
          target = optimized;
          for (int i = 0; i < 2000; i++) sink = operation.run(i);
          target.compile(true);
          target.waitForCompilation();
          if (!target.isValidLastTier()) throw new AssertionError("Guest compilation failed");
        }
        if (smoke) {
          sink = operation.run(0);
          return;
        }
        // Timed work includes the public API and input normalization. Inputs are prepared outside.
        long warmUntil = System.nanoTime() + 500_000_000L;
        int batch = compile ? 16 : 256;
        while (System.nanoTime() < warmUntil) measure(operation, batch, k, compile);
        double[] samples = new double[7];
        for (int sample = 0; sample < samples.length; sample++) {
          long count = 0, start = System.nanoTime(), elapsed;
          do {
            measure(operation, batch, k, compile);
            count += batch;
            elapsed = System.nanoTime() - start;
          } while (elapsed < 150_000_000L);
          samples[sample] = (double) elapsed / count;
        }
        if (target != null && !target.isValidLastTier())
          throw new AssertionError("Guest code invalidated");
        long buildNs = ours ? ImageExpressions.TRUFFLE_NS[k] : ImageExpressions.JAVA_NS[k];
        System.out.printf(
            Locale.ROOT,
            "{\"engine\":\"%s\",\"stage\":\"%s\",\"workload\":\"%s\",\"runtime\":\"%s\","
                + "\"image_build_ast\":%s,\"image_prepare_ns\":%d,\"guest_compiled\":%s,\"ns_per_op\":%s}%n",
            engine,
            stage,
            fixture,
            nativeImage ? "svm" : "jvm",
            ImageExpressions.BUILT_IN_IMAGE,
            buildNs,
            target != null,
            Arrays.toString(samples));
      } finally {
        context.leave();
      }
    }
  }

  private static void measure(Operation operation, int batch, int fixture, boolean compile)
      throws Exception {
    long checksum = 0;
    for (int i = 0; i < batch; i++) {
      Object result = operation.run(i);
      if (compile) sink = result;
      else checksum += result instanceof Boolean b ? (b ? 1 : 0) : (Long) result;
    }
    if (!compile) {
      long expected = 0;
      for (Object value : Fixtures.EXPECTED[fixture])
        expected += value instanceof Boolean b ? (b ? 1 : 0) : (Long) value;
      if (checksum != batch / 4 * expected) throw new AssertionError("Timed checksum mismatch");
      sink = checksum;
    }
  }
}
