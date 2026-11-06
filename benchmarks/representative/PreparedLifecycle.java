package dev.trufflecel;

import com.google.gson.Gson;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.*;
import org.graalvm.nativeimage.ImageInfo;

/** Full-input entry paths, natural cold compilation, and separately forced native steady state. */
public final class PreparedLifecycle {
  private static volatile Object sink;

  public static void main(String[] args) throws Exception {
    if (args.length < 4 || args.length > 5)
      throw new IllegalArgumentException(
          "engine workload map|snapshot|fresh cold|steady|image-cold|image-steady [maximum]");
    String engine = args[0], workload = args[1], entry = args[2], mode = args[3];
    if (!List.of("truffle-interpreter", "truffle-jit").contains(engine)
        || !List.of("map", "snapshot", "fresh").contains(entry)
        || !List.of("cold", "steady", "image-cold", "image-steady", "smoke", "image-smoke")
            .contains(mode)) throw new IllegalArgumentException(Arrays.toString(args));
    int maximum = args.length == 5 ? Integer.parseInt(args[4]) : 1_000_000;
    if (!Set.of(10_000, 100_000, 1_000_000, 10_000_000).contains(maximum))
      throw new IllegalArgumentException("Unsupported cold checkpoint");
    boolean image = mode.startsWith("image-");
    if (image && (!ImageInfo.inImageRuntimeCode() || !ImagePrepared.BUILT_IN_IMAGE))
      throw new AssertionError("Expected image-build AST preparation");
    // Input generation and ingestion precede the timer. All supplied bindings remain present.
    for (int i = 0; i < 4; i++) Workloads.input(workload, true, i);
    long start = System.nanoTime();
    try (var e = new Engines(engine, true, image)) {
      long configured = System.nanoTime();
      Object ast =
          image
              ? ImagePrepared.AST.get(engine)[Arrays.asList(Workloads.NAMES).indexOf(workload)]
              : e.prepare(Workloads.source(workload));
      long prepared = System.nanoTime();
      e.truffle = ((PreparedProgram) ast).instantiate();
      long instantiated = System.nanoTime();
      Activation[] snapshots = new Activation[4];
      if (entry.equals("snapshot"))
        for (int i = 0; i < 4; i++)
          snapshots[i] = Activation.snapshot(Workloads.input(workload, true, i));
      long inputsPrepared = System.nanoTime();
      Engines.Evaluation evaluation =
          switch (entry) {
            case "map" -> i -> e.truffle.evaluate(Workloads.input(workload, true, i));
            case "snapshot" -> i -> e.truffle.evaluate(snapshots[i & 3]);
            default ->
                i -> e.truffle.evaluate(Activation.snapshot(Workloads.input(workload, true, i)));
          };
      var out = new LinkedHashMap<String, Object>();
      out.put("engine", engine);
      out.put("workload", workload);
      out.put("entry", entry);
      out.put("mode", mode);
      out.put("runtime", ImageInfo.inImageRuntimeCode() ? "svm" : "jvm");
      out.put("image_prepared", image);
      out.put("configure_ns", configured - start);
      out.put("prepare_ns", prepared - configured);
      out.put("instantiate_ns", instantiated - prepared);
      out.put("snapshot_ns", inputsPrepared - instantiated);
      if (mode.endsWith("cold")) {
        var elapsed = new LinkedHashMap<String, Long>();
        var compiled = new LinkedHashMap<String, Boolean>();
        int previous = 0;
        for (int n = 1; n <= maximum; n *= 10) {
          for (int i = previous; i < n; i++) sink = evaluation.run(i);
          elapsed.put(Integer.toString(n), System.nanoTime() - start);
          compiled.put(Integer.toString(n), compiled(e));
          previous = n;
        }
        out.put("total_ns_through_evaluations", elapsed);
        out.put("guest_compiled_at_checkpoint", compiled);
        e.verifyTier(false);
      } else {
        long peakStart = System.nanoTime();
        e.peak(evaluation);
        out.put("forced_guest_preparation_ns", System.nanoTime() - peakStart);
        if (!mode.endsWith("smoke")) {
          var warmup = new ArrayList<Double>();
          for (int i = 0; i < 5; i++) warmup.add(sample(evaluation, 1_000_000_000L));
          var samples = new ArrayList<Double>();
          for (int i = 0; i < 7; i++) samples.add(sample(evaluation, 500_000_000L));
          out.put("warmup_ns_per_op", warmup);
          out.put("ns_per_op", samples);
        }
        e.verifyTier(true);
      }
      // Validate only after cold timing; no evaluation occurs before its first checkpoint.
      for (int i = 0; i < 4; i++)
        if (!Workloads.expected(workload, i).equals(evaluation.run(i)))
          throw new AssertionError("Wrong result: " + workload + "/" + entry + "/" + i);
      out.put("last_tier_at_end", compiled(e));
      System.out.println(new Gson().toJson(out));
    }
  }

  private static boolean compiled(Engines engine) {
    return engine.truffle.callTarget() instanceof OptimizedCallTarget target
        && target.isValidLastTier();
  }

  private static double sample(Engines.Evaluation operation, long duration) throws Exception {
    long start = System.nanoTime();
    int count = 0;
    do {
      for (int i = 0; i < 64; i++) sink = operation.run(count++);
    } while (System.nanoTime() - start < duration);
    return (double) (System.nanoTime() - start) / count;
  }
}
