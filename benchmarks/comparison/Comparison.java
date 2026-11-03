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
        for (String stage :
            new String[] {"compile", "evaluate", "image-ast", "interpret", "image-interpret"})
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
        || !Arrays.asList("compile", "evaluate", "image-ast", "interpret", "image-interpret")
            .contains(stage)) throw new IllegalArgumentException("Unknown benchmark");
    boolean ours = engine.equals("trufflecel");
    boolean compile = stage.equals("compile");
    boolean interpreter = stage.equals("interpret") || stage.equals("image-interpret");
    boolean imageAst = stage.equals("image-ast") || stage.equals("image-interpret");
    boolean nativeImage = ImageInfo.inImageRuntimeCode();
    if (nativeImage && !ImageExpressions.BUILT_IN_IMAGE)
      throw new AssertionError("Checked ASTs were not created at image build time");
    try (Context context =
        ours
            ? Context.newBuilder("cel")
                .allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false")
                .option("engine.Compilation", interpreter ? "false" : "true")
                .option("engine.CompilationFailureAction", "Throw")
                .option("compiler.TreatPerformanceWarningsAsErrors", "all")
                .build()
            : null) {
      if (context != null) {
        context.initialize("cel");
        context.enter();
      }
      try {
        var inputs = Fixtures.inputs(k);
        Program truffle = null;
        Operation evaluate;
        Operation operation;
        if (ours) {
          truffle =
              imageAst
                  ? ImageExpressions.Truffle.AST[k].instantiate()
                  : Fixtures.Truffle.COMPILER.compile(Fixtures.SOURCES[k]);
          Program program = truffle;
          evaluate = i -> program.evaluate(inputs.get(i & 3));
          operation =
              compile ? i -> Fixtures.Truffle.COMPILER.compile(Fixtures.SOURCES[k]) : evaluate;
        } else {
          CelRuntime.Program program =
              Fixtures.Java.RUNTIME.createProgram(
                  imageAst
                      ? ImageExpressions.Java.AST[k]
                      : Fixtures.Java.COMPILER.compile(Fixtures.SOURCES[k]).getAst());
          evaluate = i -> program.eval(inputs.get(i & 3));
          operation =
              compile
                  ? i ->
                      Fixtures.Java.RUNTIME.createProgram(
                          Fixtures.Java.COMPILER.compile(Fixtures.SOURCES[k]).getAst())
                  : evaluate;
        }
        for (int i = 0; i < 4; i++) {
          if (!Fixtures.EXPECTED[k][i].equals(evaluate.run(i)))
            throw new AssertionError("Incorrect " + fixture + " activation " + i);
        }
        OptimizedCallTarget target = null;
        if (ours && !compile && !interpreter) {
          if (!(truffle.callTarget() instanceof OptimizedCallTarget optimized))
            throw new AssertionError("Optimizing runtime required");
          target = optimized;
          for (int i = 0; i < 2000; i++) sink = operation.run(i);
          target.compile(true);
          target.waitForCompilation();
          if (!target.isValidLastTier()) throw new AssertionError("Guest compilation failed");
        }
        if (interpreter
            && truffle != null
            && truffle.callTarget() instanceof OptimizedCallTarget optimized
            && optimized.isValid())
          throw new AssertionError("Interpreter case acquired guest machine code");
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
        if (interpreter
            && truffle != null
            && truffle.callTarget() instanceof OptimizedCallTarget optimized
            && optimized.isValid())
          throw new AssertionError("Interpreter case acquired guest machine code");
        long buildNs =
            nativeImage ? (ours ? ImageExpressions.Truffle.NS[k] : ImageExpressions.Java.NS[k]) : 0;
        System.out.printf(
            Locale.ROOT,
            "{\"engine\":\"%s\",\"stage\":\"%s\",\"workload\":\"%s\",\"runtime\":\"%s\","
                + "\"image_build_ast\":%s,\"image_prepare_ns\":%d,\"guest_compiled\":%s,\"interpreter\":%s,\"ns_per_op\":%s}%n",
            engine,
            stage,
            fixture,
            nativeImage ? "svm" : "jvm",
            nativeImage && ImageExpressions.BUILT_IN_IMAGE,
            buildNs,
            target != null,
            interpreter,
            Arrays.toString(samples));
      } finally {
        if (context != null) context.leave();
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
