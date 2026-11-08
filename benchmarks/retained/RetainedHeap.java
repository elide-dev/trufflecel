package dev.trufflecel.bench;

import dev.trufflecel.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.graalvm.polyglot.Context;

/** Post-collection live heap for a cache of evaluated Programs; never a peak-RSS proxy. */
public final class RetainedHeap {
  private static volatile Program[] retained;
  private static final TraceListener DISCARD = event -> {};
  private static final Map<String, Object> INPUT =
      Map.of("x", 42L, "xs", List.of(40L, 41L, 42L, 43L, 44L));
  private static final PartialActivation PARTIAL =
      PartialActivation.of(INPUT, AttributePattern.of("x"));

  private record Fixture(String source, long expected, boolean complete) {}

  private static Fixture fixture(String name) {
    return switch (name) {
      case "chain" -> new Fixture("x * 2 + 1", 85, false);
      case "folded" -> new Fixture("[1, 2, 3, 4].map(n, n * 2).filter(n, n > 4).size()", 2, true);
      case "comprehension" -> new Fixture("xs.filter(n, n > x).map(n, n * 2).size()", 2, false);
      case "cse" -> new Fixture("((x + 1) * (x + 1)) + ((x + 1) * (x + 1))", 3698, false);
      case "macro" -> new Fixture("twice(x + 1)", 86, false);
      default -> throw new IllegalArgumentException(name);
    };
  }

  private static Environment environment(String name, int declarations) {
    var builder =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .variable("xs", CelType.list(CelType.INT))
            .commonSubexpressions(name.equals("cse"))
            .macro(
                Macro.global(
                    "twice",
                    1,
                    (context, target, args) ->
                        Ast.Node.call("+", args.getFirst(), args.getFirst())));
    for (int i = 2; i < declarations; i++) builder.variable("unused" + i, CelType.INT);
    return builder.build();
  }

  private static void exercise(Program program, Fixture fixture, String mode) {
    Object value = program.evaluate(INPUT);
    if (!Objects.equals(value, fixture.expected())) throw new AssertionError(value);
    switch (mode) {
      case "default" -> {}
      case "trace" -> {
        if (!Objects.equals(program.trace(DISCARD).evaluate(INPUT), value))
          throw new AssertionError("trace result");
      }
      case "partial" -> {
        PartialResult result = program.partialEvaluate(PARTIAL);
        if (result.error() != null || result.isComplete() != fixture.complete())
          throw new AssertionError("partial result: " + result.error());
        if (result.isComplete() && !Objects.equals(result.value(), value))
          throw new AssertionError("complete partial result");
        if (!result.isComplete() && !result.unknownAttributes().contains("x"))
          throw new AssertionError(result.unknownAttributes());
      }
      default -> throw new IllegalArgumentException(mode);
    }
  }

  private static long[] collect() throws InterruptedException {
    long[] samples = new long[5];
    for (int i = 0; i < samples.length; i++) {
      System.gc();
      Thread.sleep(50);
      samples[i] = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
    return samples;
  }

  private static long minimum(long[] samples) {
    // Runtime weak-target bookkeeping can disappear between collections. Preserve every sample
    // in the evidence and use the settled floor rather than charging dead targets to the cache.
    return Arrays.stream(samples).min().orElseThrow();
  }

  // Kept out of the sampling frame with CompileCommand=dontinline: an enhanced-for's
  // synthetic array local must not keep the released cache alive across the following GC.
  private static void populate(
      Environment environment,
      PreparedProgram prepared,
      Fixture fixture,
      String mode,
      String sharing,
      int count) {
    retained = new Program[count];
    for (int i = 0; i < count; i++) {
      Program program =
          sharing.equals("template")
              ? prepared.instantiate()
              : environment.compile(fixture.source() + " // distinct " + i);
      exercise(program, fixture, mode);
      retained[i] = program;
    }
  }

  private static void release(Fixture fixture, String mode) {
    for (Program program : retained) exercise(program, fixture, mode);
    retained = null;
  }

  private static void run(String name, String mode, String sharing, int declarations, int count)
      throws InterruptedException {
    Fixture fixture = fixture(name);
    Environment environment = environment(name, declarations);
    PreparedProgram prepared = environment.prepare(fixture.source());
    // Prime parser, plans, target cloning, traces/partial results and heap-observation classes.
    for (int i = 0; i < 2_000; i++) {
      exercise(environment.compile(fixture.source() + " // warm " + i), fixture, mode);
      exercise(prepared.instantiate(), fixture, mode);
    }
    collect();
    for (int round = 0; round < 3; round++) {
      long[] before = collect();
      populate(environment, prepared, fixture, mode, sharing, count);
      long[] live = collect();
      // Verify the entire retained cache remains usable after collection.
      release(fixture, mode);
      long[] released = collect();
      long delta = minimum(live) - minimum(before);
      long drift = minimum(released) - minimum(before);
      System.out.printf(
          Locale.ROOT,
          "{\"case\":\"%s\",\"mode\":\"%s\",\"sharing\":\"%s\",\"declarations\":%d,"
              + "\"count\":%d,\"round\":%d,\"before\":%s,\"live\":%s,\"released\":%s,"
              + "\"retainedBytes\":%d,\"bytesPerProgram\":%.3f,\"baselineDrift\":%d,\"loadAverage\":%.3f}%n",
          name,
          mode,
          sharing,
          declarations,
          count,
          round,
          Arrays.toString(before),
          Arrays.toString(live),
          Arrays.toString(released),
          delta,
          (double) delta / count,
          drift,
          ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }
  }

  public static void main(String[] args) throws Exception {
    try (var context =
        Context.newBuilder("cel")
            .allowExperimentalOptions(true)
            .option("engine.Compilation", "false")
            .option("engine.BackgroundCompilation", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.TreatPerformanceWarningsAsErrors", "all")
            .option("compiler.MaximumGraalGraphSize", "10000")
            .build()) {
      context.initialize("cel");
      context.enter();
      try {
        if (args.length == 1 && args[0].equals("--smoke")) {
          for (String name : List.of("chain", "folded", "comprehension", "cse", "macro"))
            for (String mode : List.of("default", "trace", "partial")) {
              Fixture fixture = fixture(name);
              Environment environment = environment(name, 69);
              exercise(environment.compile(fixture.source()), fixture, mode);
              exercise(environment.prepare(fixture.source()).instantiate(), fixture, mode);
            }
          return;
        }
        if (args.length != 5 || !Set.of("template", "distinct").contains(args[2]))
          throw new IllegalArgumentException(
              "CASE default|trace|partial template|distinct DECLARATIONS COUNT");
        int declarations = Integer.parseInt(args[3]);
        int count = Integer.parseInt(args[4]);
        if (declarations < 2 || count < 10_000)
          throw new IllegalArgumentException("minimum: 2 declarations, 10000 programs");
        System.out.println(
            "# Diagnostic live-heap delta, shared host; not peak RSS or a timing benchmark.");
        System.out.println(
            "# "
                + System.getProperty("java.vm.name")
                + " "
                + System.getProperty("java.runtime.version"));
        System.out.println("# JVM " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        run(args[0], args[1], args[2], declarations, count);
      } finally {
        context.leave();
      }
    }
  }
}
