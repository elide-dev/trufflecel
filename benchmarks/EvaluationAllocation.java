package dev.trufflecel;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.graalvm.polyglot.Context;

/** Local fixed-work allocation diagnostic; does not claim timing or retained-heap measurements. */
public final class EvaluationAllocation {
  private static volatile Object sink;

  private record Case(String name, Supplier<Object> operation, Object expected) {}

  public static void main(String[] args) {
    boolean fallback = Boolean.getBoolean("truffle.UseFallbackRuntime");
    Context context =
        fallback
            ? null
            : Context.newBuilder("cel")
                .allowExperimentalOptions(true)
                .option("engine.Compilation", "false")
                .build();
    if (context != null) {
      context.initialize("cel");
      context.enter();
    }
    try {
      measure();
    } finally {
      if (context != null) {
        context.leave();
        context.close();
      }
    }
  }

  private static void measure() {
    var scalar = Cel.compile("x * 2 + 1");
    System.out.println("runtime=" + scalar.callTarget().getClass().getName());
    System.out.println("java=" + System.getProperty("java.runtime.version"));
    System.out.println("vm=" + System.getProperty("java.vm.version"));
    System.out.println("fallback=" + Boolean.getBoolean("truffle.UseFallbackRuntime"));
    var input = Map.<String, Object>of("x", 42L);
    var wide = Map.<String, Object>of("x", 42L, "unused", List.of(1L, 2L, 3L));
    var snapshot = Activation.snapshot(input);
    var wideSnapshot = Activation.snapshot(wide);
    var options = EvaluationOptions.newBuilder().budget(20).build();
    var lazy = Cel.compile("cel.bind(v, x + 1, v * 2 - 1)");
    var capture = Cel.compile("cel.bind(v, [x].map(n, n + 1)[0], v * 2 - 1)");
    var comprehension = Cel.compile("xs.filter(v, v % 2 == 0).map(v, v * v).size()");
    var list = Map.<String, Object>of("xs", List.of(1L, 2L, 3L, 4L));
    var cse =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .commonSubexpressions(true)
            .build()
            .compile("(x + 1) * (x + 1)");
    var reusedEnvironment = Cel.newBuilder().variable("x", CelType.INT).build();
    var converted = Map.<String, Object>of("x", 42);
    var narrowDeclarations = convertedScalar(1);
    var wideDeclarations = convertedScalar(69);
    var largeDeclarations = convertedScalar(1000);
    var cases =
        List.of(
            new Case("scalar-map", () -> scalar.evaluate(input), 85L),
            new Case("scalar-snapshot", () -> scalar.evaluate(snapshot), 85L),
            new Case("scalar-wide-map", () -> scalar.evaluate(wide), 85L),
            new Case("scalar-wide-snapshot", () -> scalar.evaluate(wideSnapshot), 85L),
            new Case("scalar-controlled", () -> scalar.evaluate(input, options), 85L),
            new Case("scalar-controlled-snapshot", () -> scalar.evaluate(snapshot, options), 85L),
            new Case("simple-lazy", () -> lazy.evaluate(input), 85L),
            new Case("captured-lazy", () -> capture.evaluate(input), 85L),
            new Case("comprehension", () -> comprehension.evaluate(list), 2L),
            new Case("cse", () -> cse.evaluate(input), 1849L),
            new Case("converted-1-declaration", () -> narrowDeclarations.evaluate(converted), 84L),
            new Case("converted-69-declarations", () -> wideDeclarations.evaluate(converted), 84L),
            new Case(
                "converted-1000-declarations", () -> largeDeclarations.evaluate(converted), 84L),
            new Case(
                "compile-scalar-inferred", () -> Cel.compile("x * 2 + 1").evaluate(input), 85L),
            new Case(
                "compile-scalar-reused-environment",
                () -> reusedEnvironment.compile("x * 2 + 1").evaluate(input),
                85L));
    var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemorySupported())
      throw new IllegalStateException("per-thread allocation accounting is required");
    bean.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    for (Case c : cases) {
      for (int i = 0; i < 100_000; i++) sink = c.operation.get();
      if (!c.expected.equals(sink)) throw new AssertionError(c.name);
      for (int sample = 0; sample < 7; sample++) {
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 50_000; i++) sink = c.operation.get();
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        if (!c.expected.equals(sink)) throw new AssertionError(c.name);
        System.out.println(
            c.name + " sample=" + sample + " bytes/op=" + allocated / 50_000.0 + " result=" + sink);
      }
    }
  }

  private static Program convertedScalar(int declarations) {
    var builder = Cel.newBuilder().variable("x", CelType.INT);
    for (int i = 1; i < declarations; i++) builder.variable("unused" + i, CelType.INT);
    return builder.build().compile("x + x");
  }
}
