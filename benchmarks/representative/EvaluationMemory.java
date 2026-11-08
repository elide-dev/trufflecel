package dev.trufflecel;

import com.google.gson.Gson;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedRuntimeOptions;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.graalvm.nativeimage.ImageInfo;

/** Fixed-work allocation diagnostic; the parent samples Linux RSS after the result checkpoint. */
public final class EvaluationMemory {
  private static volatile Object sink;
  private static volatile Engines liveEngine;
  private static volatile Engines.Evaluation liveEvaluation;

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !Set.of("measure", "smoke").contains(args[2]))
      throw new IllegalArgumentException("engine workload measure|smoke");
    String engine = args[0], workload = args[1];
    boolean smoke = args[2].equals("smoke");
    int batch = smoke ? 16 : 65_536;
    int rounds = smoke ? 1 : 5;
    int samples = smoke ? 2 : 7;
    // Initialize counters before workload preparation; calibration escapes independently.
    if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
        || !bean.isThreadAllocatedMemorySupported())
      throw new AssertionError("Thread allocation accounting is required");
    bean.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    sink = new byte[1024 * 1024];
    long calibration = bean.getThreadAllocatedBytes(thread) - before;
    sink = null;
    if (before < 0 || calibration < 1024 * 1024)
      throw new AssertionError("Allocation counter failed escaped-array calibration");
    try (var e = new Engines(engine)) {
      var evaluation = e.evaluation(workload, e.prepare(Workloads.source(workload)));
      verify(e, evaluation, workload);
      e.peak(evaluation);
      for (int i = 0; i < rounds; i++) run(evaluation, batch);
      var allocated = new ArrayList<Double>();
      for (int sample = 0; sample < samples; sample++) {
        before = bean.getThreadAllocatedBytes(thread);
        run(evaluation, batch);
        long delta = bean.getThreadAllocatedBytes(thread) - before;
        if (delta < 0) throw new AssertionError("Allocation counter went backwards");
        if (!Workloads.expected(workload, (batch - 1) & 3).equals(sink))
          throw new AssertionError("Final measured result changed");
        allocated.add((double) delta / batch);
      }
      verify(e, evaluation, workload);
      e.verifyTier(true);
      var out = new LinkedHashMap<String, Object>();
      out.put("runtime", ImageInfo.inImageRuntimeCode() ? "svm" : "jvm");
      out.put("engine", engine);
      out.put("workload", workload);
      out.put("source", Workloads.source(workload));
      out.put("input_variants", 4);
      out.put("bindings_per_input", workload.equals("scalar") ? 1 : 69);
      out.put("warmup_operations", rounds * batch);
      out.put("operations_per_sample", batch);
      out.put("allocation_calibration_bytes", calibration);
      out.put("allocated_bytes_per_op", allocated);
      if (e.truffle != null) {
        var target = (OptimizedCallTarget) e.truffle.callTarget();
        out.put("guest_last_tier", target.isValidLastTier());
        out.put("compilation_enabled", target.getOptionValue(OptimizedRuntimeOptions.Compilation));
        out.put(
            "background_compilation",
            target.getOptionValue(OptimizedRuntimeOptions.BackgroundCompilation));
        out.put(
            "compilation_failure_action",
            target.getOptionValue(OptimizedRuntimeOptions.CompilationFailureAction).toString());
        out.put("compiler_options", target.getCompilerOptions());
      }
      System.out.println(new Gson().toJson(out));
      System.out.flush();
      // Keep target, engine and prepared inputs alive while the parent reads /proc.
      liveEngine = e;
      liveEvaluation = evaluation;
      try {
        if (!smoke && System.in.read() != '\n')
          throw new AssertionError("Missing memory checkpoint acknowledgement");
      } finally {
        liveEngine = null;
        liveEvaluation = null;
      }
    }
  }

  private static void run(Engines.Evaluation evaluation, int count) throws Exception {
    for (int i = 0; i < count; i++) sink = evaluation.run(i);
  }

  private static void verify(Engines e, Engines.Evaluation evaluation, String workload)
      throws Exception {
    for (int i = 0; i < 4; i++) {
      if (Workloads.input(workload, e.ours, i).size() != (workload.equals("scalar") ? 1 : 69))
        throw new AssertionError("Fixture bindings were trimmed");
      if (!Workloads.expected(workload, i).equals(evaluation.run(i)))
        throw new AssertionError("Wrong full result: " + workload + "/" + i);
    }
  }
}
