package dev.trufflecel;

import com.google.gson.Gson;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.util.*;
import org.graalvm.nativeimage.ImageInfo;

/** Fresh-process cold curves and native steady-state timing; JVM peak results use JMH. */
public final class Lifecycle {
  private static volatile Object sink;

  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException(
          "engine workload cold|steady|image-cold|image-steady|ingest");
    String engine = args[0], workload = args[1], mode = args[2];
    if (!Set.of("cold", "steady", "image-cold", "image-steady", "ingest", "smoke", "image-smoke")
        .contains(mode)) throw new IllegalArgumentException(mode);
    boolean image = mode.startsWith("image-");
    if (image && !ImageInfo.inImageRuntimeCode())
      throw new IllegalArgumentException("Image preparation requires Native Image");
    if (image && !ImagePrepared.BUILT_IN_IMAGE)
      throw new AssertionError("AST preparation did not run during image construction");
    // Dataset creation/ingestion is excluded from these eval curves and measured separately.
    for (int i = 0; i < 4; i++) Workloads.input(workload, engine.startsWith("truffle-"), i);
    long start = System.nanoTime();
    try (var e = new Engines(engine, true, image)) {
      long configured = System.nanoTime();
      Object ast =
          image
              ? ImagePrepared.AST.get(engine)[Arrays.asList(Workloads.NAMES).indexOf(workload)]
              : e.prepare(Workloads.source(workload));
      long prepared = System.nanoTime();
      if (image && mode.endsWith("smoke") && e.ours) ImagePrepared.verifySnapshot(e);
      var evaluation = e.evaluation(workload, ast);
      long planned = System.nanoTime();
      var out = new LinkedHashMap<String, Object>();
      out.put("engine", engine);
      out.put("workload", workload);
      out.put("mode", mode);
      out.put("runtime", ImageInfo.inImageRuntimeCode() ? "svm" : "jvm");
      out.put("image_prepared", image);
      out.put("configure_ns", configured - start);
      out.put("prepare_ns", prepared - configured);
      out.put("plan_ns", planned - prepared);
      if (mode.endsWith("smoke")) {
        e.peak(evaluation);
        e.verifyTier(true);
      } else if (mode.endsWith("cold")) {
        int previous = 0;
        var checkpoints = new LinkedHashMap<String, Long>();
        var compiled = new LinkedHashMap<String, Boolean>();
        for (int n : new int[] {1, 10, 100, 1000, 10000}) {
          for (int i = previous; i < n; i++) sink = evaluation.run(i);
          checkpoints.put(Integer.toString(n), System.nanoTime() - start);
          compiled.put(
              Integer.toString(n),
              e.truffle != null
                  && e.truffle.callTarget() instanceof OptimizedCallTarget target
                  && target.isValidLastTier());
          previous = n;
        }
        out.put("total_ns_through_evaluations", checkpoints);
        out.put("guest_compiled_at_checkpoint", compiled);
        e.verifyTier(false);
      } else {
        Engines.Evaluation operation = evaluation;
        if (mode.equals("ingest")) {
          byte[][] wire = new byte[4][];
          for (int i = 0; i < 4; i++)
            wire[i] =
                ((com.google.protobuf.Message) Workloads.Raw.INPUTS.get(i).get("request"))
                    .toByteArray();
          operation =
              e.ours
                  ? i -> Workloads.Native.ADAPTER.decode(new Bytes(wire[i & 3]))
                  : i -> dev.trufflecel.bench.proto.PolicySchema.Request.parseFrom(wire[i & 3]);
        } else {
          long peakStart = System.nanoTime();
          e.peak(evaluation);
          out.put("forced_guest_preparation_ns", System.nanoTime() - peakStart);
        }
        var warmup = new ArrayList<Double>();
        for (int i = 0; i < 5; i++) warmup.add(sample(operation, 1_000_000_000L));
        var samples = new ArrayList<Double>();
        for (int i = 0; i < 7; i++) samples.add(sample(operation, 500_000_000L));
        out.put("warmup_ns_per_op", warmup);
        out.put("ns_per_op", samples);
        e.verifyTier(!mode.equals("ingest"));
        if (mode.equals("ingest"))
          for (int i = 0; i < 4; i++) {
            Object decoded = operation.run(i);
            if (decoded instanceof ProtobufType.Value value) decoded = value.message();
            if (!Workloads.Raw.INPUTS.get(i).get("request").equals(decoded))
              throw new AssertionError("Ingestion changed protobuf content");
          }
      }
      // Validate after cold timing so correctness checks cannot warm the measured expression.
      for (int i = 0; i < 4; i++)
        if (!Workloads.expected(workload, i).equals(evaluation.run(i)))
          throw new AssertionError("Wrong result: " + workload);
      System.out.println(new Gson().toJson(out));
    }
  }

  private static double sample(Engines.Evaluation e, long duration) throws Exception {
    long start = System.nanoTime();
    int count = 0;
    do {
      for (int i = 0; i < 64; i++) sink = e.run(count++);
    } while (System.nanoTime() - start < duration);
    return (double) (System.nanoTime() - start) / count;
  }
}
