package dev.trufflecel;

import dev.cel.common.CelAbstractSyntaxTree;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Upstream stage diagnosis; do not label these individual stages as a cross-engine speedup. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@State(Scope.Thread)
public class FrontendStages {
  @Param({"cel-java-optimized", "cel-java-pratt-optimized"})
  public String engine;

  @Param({"true", "x * 2 + 1"})
  public String source;

  private Engines engines;
  private CelAbstractSyntaxTree parsed, checked, optimized;

  @Setup
  public void setup() throws Exception {
    engines = new Engines(engine);
    parsed = engines.compiler.parse(source).getAst();
    checked = engines.compiler.check(parsed).getAst();
    optimized = engines.optimizer.optimize(checked);
  }

  @Benchmark
  public Object parse() throws Exception {
    return engines.compiler.parse(source).getAst();
  }

  @Benchmark
  public Object check() throws Exception {
    return engines.compiler.check(parsed).getAst();
  }

  @Benchmark
  public Object optimize() throws Exception {
    return engines.optimizer.optimize(checked);
  }

  @Benchmark
  public Object plan() throws Exception {
    return engines.runtime.createProgram(optimized);
  }

  @TearDown
  public void close() {
    engines.close();
  }
}
