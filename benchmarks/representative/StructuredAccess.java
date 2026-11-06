package dev.trufflecel;

import dev.trufflecel.bench.proto.PolicySchema.Request;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Diagnostic size/selectivity matrix plus the cost paid before evaluation; not ingestion timing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Thread)
public class StructuredAccess {
  @Param({"truffle-interpreter", "truffle-jit"})
  public String engine;

  @Param({"0", "1", "16", "128", "1024"})
  public int length;

  @Param({"0", "50", "100"})
  public int selectedPercent;

  private Engines engines;
  private String source;
  private PreparedProgram template;
  private Activation input;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    engines = new Engines(engine);
    int cutoff = length - length * selectedPercent / 100;
    source = "request.amounts.filter(v, v >= " + cutoff + ").map(v, v * 2)";
    var request = Request.newBuilder();
    var expected = new ArrayList<Long>();
    for (long i = 0; i < length; i++) {
      request.addAmounts(i);
      if (i >= cutoff) expected.add(i * 2);
    }
    input = Activation.snapshot(Map.of("request", Workloads.Native.ADAPTER.input(request.build())));
    template = engines.environment.prepare(source);
    engines.truffle = template.instantiate();
    if (!expected.equals(engines.truffle.evaluate(input))) throw new AssertionError(source);
    engines.peak(i -> engines.truffle.evaluate(input));
  }

  @Benchmark
  public Object evaluate() {
    return engines.truffle.evaluate(input);
  }

  @Benchmark
  public Object prepare() {
    return engines.environment.prepare(source);
  }

  @Benchmark
  public Object instantiate() {
    return template.instantiate();
  }

  @TearDown(Level.Trial)
  public void teardown() {
    engines.verifyTier(true);
    engines.close();
  }
}
