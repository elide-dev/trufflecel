package dev.trufflecel;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Diagnostic stages; do not add these independent timings into an end-to-end estimate. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Thread)
public class ActivationPreparation {
  @Param({"scalar", "scalar-wide"})
  public String workload;

  private List<Map<String, Object>> inputs;
  private int cursor;

  @Setup(Level.Trial)
  public void setup() {
    inputs = new ArrayList<>();
    for (int i = 0; i < 4; i++) inputs.add(Workloads.input(workload, true, i));
  }

  @Benchmark
  public Object copyBindings() {
    var copy = new HashMap<String, Object>();
    inputs.get(cursor++ & 3).forEach(copy::put);
    return copy;
  }

  @Benchmark
  public void normalizeValues(Blackhole consumer) {
    inputs.get(cursor++ & 3).forEach((name, value) -> consumer.consume(Values.in(value)));
  }

  @Benchmark
  public Activation snapshot() {
    return Activation.snapshot(inputs.get(cursor++ & 3));
  }
}
