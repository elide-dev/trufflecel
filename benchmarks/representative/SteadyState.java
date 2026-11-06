package dev.trufflecel;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** JMH owns forks, warmup, measurement, and result consumption. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Thread)
public class SteadyState {
  @Param({
    "truffle-interpreter",
    "truffle-jit",
    "cel-java",
    "cel-java-optimized",
    "cel-java-pratt-optimized"
  })
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
  private Engines.Evaluation evaluation;
  private int cursor;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    engines = new Engines(engine);
    evaluation = engines.evaluation(workload, engines.prepare(Workloads.source(workload)));
    for (int i = 0; i < 4; i++)
      if (!Workloads.expected(workload, i).equals(evaluation.run(i)))
        throw new AssertionError(workload + "/" + i);
    engines.peak(evaluation);
  }

  @Benchmark
  public Object evaluate() throws Exception {
    return evaluation.run(cursor++);
  }

  @Benchmark
  public Object compile() throws Exception {
    return engines.compile(Workloads.source(workload));
  }

  @TearDown(Level.Trial)
  public void teardown() {
    engines.verifyTier(true);
    engines.close();
  }
}
