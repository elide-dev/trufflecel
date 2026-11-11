package dev.trufflecel;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Compare unchanged map entry, explicit reuse, and one-shot preparation plus evaluation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Thread)
public class ActivationEvaluation {
  @Param({"truffle-interpreter", "truffle-jit"})
  public String engine;

  @Param({
    "scalar",
    "scalar-wide",
    "proto-policy",
    "proto-repeated",
    "map-regex",
    "optimizer-policy"
  })
  public String workload;

  private Engines engines;
  private Program program;
  private Activation[] snapshots;
  private int cursor;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    engines = new Engines(engine);
    engines.evaluation(workload, engines.prepare(Workloads.source(workload)));
    program = engines.truffle;
    snapshots = new Activation[4];
    for (int i = 0; i < 4; i++) {
      snapshots[i] = Activation.snapshot(Workloads.input(workload, true, i));
      if (!Workloads.expected(workload, i).equals(program.evaluate(snapshots[i])))
        throw new AssertionError(workload + "/" + i);
      if (!Workloads.expected(workload, i)
          .equals(program.evaluate(Workloads.input(workload, true, i))))
        throw new AssertionError("map/" + workload + "/" + i);
    }
    // Both entry paths reach the same target and see all four activations before compilation.
    engines.peak(i ->
        i % 2 == 0
            ? program.evaluate(snapshots[(i / 2) & 3])
            : program.evaluate(Workloads.input(workload, true, (i / 2) & 3)));
  }

  @Benchmark
  public Object evaluateMap() {
    return program.evaluate(Workloads.input(workload, true, cursor++));
  }

  @Benchmark
  public Object evaluateSnapshot() {
    return program.evaluate(snapshots[cursor++ & 3]);
  }

  @Benchmark
  public Object snapshotAndEvaluate() {
    return program.evaluate(Activation.snapshot(Workloads.input(workload, true, cursor++)));
  }

  @TearDown(Level.Trial)
  public void teardown() {
    engines.verifyTier(true);
    engines.close();
  }
}
