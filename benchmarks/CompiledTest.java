package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.List;
import dev.elide.truffledata.*;
import org.graalvm.polyglot.Context;
import org.junit.Test;

/** Mandatory guest compilation, bounded AST growth, and end-to-end allocation budgets. */
public final class CompiledTest {
  private static volatile Object sink;

  @Test
  public void compiledScalarAndLazyPathsStayWithinBudgets() {
    try (Context context =
        Context.newBuilder("cel")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.TreatPerformanceWarningsAsErrors", "all")
            .option("compiler.MaximumGraalGraphSize", "10000")
            .build()) {
      context.initialize("cel");
      context.enter();
      try {
        check("x * 2 + 1 > 100", Map.of("x", 60L), true, Map.of("x", 2L), false, 64);
        check(
            "enabled && 10 / x > 1",
            Map.of("enabled", true, "x", 2L),
            true,
            Map.of("enabled", false, "x", 0L),
            false,
            64);
        check("optional.of(x).orValue(0) > 5", Map.of("x", 10L), true, Map.of("x", 0L), false, 192);
        check("cel.bind(v, x + 1, v > 5)", Map.of("x", 10L), true, Map.of("x", 0L), false, 64);
        check(
            "cel.block([x + 1], cel.index(0) > 5)",
            Map.of("x", 10L),
            true,
            Map.of("x", 0L),
            false,
            768);
        check(
            "json.parse(s).value().a == 1",
            Map.of("s", "{\"a\":1}"),
            true,
            Map.of("s", "{\"a\":2}"),
            false,
            16384);
        check(
            "yaml.parse(s).value().a == 1",
            Map.of("s", "a: 1"),
            true,
            Map.of("s", "a: 2"),
            false,
            65536);
        check("sets.contains([1,2,3], [x])", Map.of("x", 2L), true, Map.of("x", 4L), false, 1536);
        check(
            "s.matches('^[a-z]+@[a-z]+[.]com$')",
            Map.of("s", "person@example.com"),
            true,
            Map.of("s", "person@example.org"),
            false,
            4096);
        check(
            "xs.all(x, x > 0)",
            Map.of("xs", List.of(1L, 2L, 3L)),
            true,
            Map.of("xs", List.of(1L, -2L)),
            false,
            160);
        check(
            "xs.filter(v, v % 2 == 0).map(v, v * v).size()",
            Map.of("xs", List.of(1L, 2L, 3L, 4L)), 2L, Map.of("xs", List.of(2L, 4L, 6L)), 3L, 320);
        check("[x, x + 1].size() == 2", Map.of("x", 10L), true, Map.of("x", -1L), true, 320);
        check("{'v': x}.v > 5", Map.of("x", 10L), true, Map.of("x", 0L), false, 512);
        check(
            "cidr(s).containsIP('10.1.2.3')",
            Map.of("s", "10.0.0.0/8"),
            true,
            Map.of("s", "192.168.0.0/16"),
            false,
            20000);
        check(
            "enabled ? x + 1 : x - 1",
            Map.of("enabled", true, "x", 10L),
            11L,
            Map.of("enabled", false, "x", 10L),
            9L,
            64);
        check(
            "x + y",
            Map.of("x", 1L, "y", 2L),
            3L,
            Map.of("x", Unknown.of("x"), "y", 2L),
            Unknown.of("x"),
            64);
        var schema = Schema.of(Field.requiredLong("id"));
        var type = new DataType("Row", schema);
        var env = Cel.newBuilder().objectType(type).variable("r", CelType.of("Row")).build();
        var rows =
            Batch.copyOf(
                schema, List.<Object[]>of(new Object[] {42L}, new Object[] {0L}), Limits.DEFAULT);
        check(
            env.compile("r.id > 5"),
            Map.of("r", rows.row(0)),
            true,
            Map.of("r", rows.row(1)),
            false,
            640);
        var protoType = new ProtobufType(com.google.protobuf.Type.getDescriptor());
        var proto =
            protoType
                .register(Cel.newBuilder())
                .variable("p", CelType.of("google.protobuf.Type"))
                .build();
        check(
            proto.compile("p.name == 'CEL'"),
            Map.of(
                "p",
                protoType.input(
                    protoType
                        .plan()
                        .decode(
                            com.google.protobuf.Type.newBuilder()
                                .setName("CEL")
                                .build()
                                .toByteArray()))),
            true,
            Map.of("p", protoType.input(protoType.plan().newBuilder().build())),
            false,
            1024);
        check(
            "j.id > 5",
            Map.of(
                "j", JsonValues.from(new org.graalvm.shadowed.org.json.JSONObject("{\"id\":10}"))),
            true,
            Map.of(
                "j", JsonValues.from(new org.graalvm.shadowed.org.json.JSONObject("{\"id\":0}"))),
            false,
            1024);
        // A program that has already raised errors (so its compiled code carries real exception
        // paths) must keep the same happy-path budgets as a program that never has.
        Program recovered = Cel.compile("xs.all(x, 10 / x > 0)");
        assertEquals(
            CelException.Code.DIVIDE_BY_ZERO,
            assertThrows(
                    CelException.class, () -> recovered.evaluate(Map.of("xs", List.of(0L, 1L))))
                .code());
        check(
            recovered,
            Map.of("xs", List.of(1L, 2L, 3L)),
            true,
            Map.of("xs", List.of(1L, -2L)),
            false,
            160);
        Program limited =
            Cel.newBuilder()
                .variable("enabled", CelType.BOOL)
                .evaluationBudget(4)
                .build()
                .compile("enabled || (1+2+3+4 > 0)");
        for (int i = 0; i < 100000; i++) assertTrue(limited.test(Map.of("enabled", true)));
        OptimizedCallTarget limitedTarget = (OptimizedCallTarget) limited.callTarget();
        limitedTarget.compile(true);
        limitedTarget.waitForCompilation();
        assertTrue(limitedTarget.isValidLastTier());
        assertEquals(
            CelException.Code.RESOURCE_EXHAUSTED,
            assertThrows(CelException.class, () -> limited.evaluate(Map.of("enabled", false)))
                .code());
      } finally {
        context.leave();
      }
    }
  }

  private static void check(
      String source,
      Map<String, ?> input,
      Object expected,
      Map<String, ?> changed,
      Object changedExpected,
      long budget) {
    check(Cel.compile(source), input, expected, changed, changedExpected, budget);
  }

  private static void check(
      Program program,
      Map<String, ?> input,
      Object expected,
      Map<String, ?> changed,
      Object changedExpected,
      long budget) {
    String source = program.source();
    assertTrue(
        "Optimizing Truffle runtime required", program.callTarget() instanceof OptimizedCallTarget);
    OptimizedCallTarget target = (OptimizedCallTarget) program.callTarget();
    for (int i = 0; i < 100000; i++) sink = program.evaluate(input);
    target.compile(true);
    target.waitForCompilation();
    assertTrue("Guest last-tier compilation required: " + source, target.isValidLastTier());
    assertEquals(expected, program.evaluate(input));
    assertEquals(changedExpected, program.evaluate(changed));
    // Rewarm after changing profiles; invalidation must not turn the measurement into interpreter
    // work.
    for (int i = 0; i < 100000; i++) sink = program.evaluate(input);
    target.compile(true);
    target.waitForCompilation();
    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    assertTrue("Allocation accounting is mandatory", bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    long id = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(id);
    for (int i = 0; i < 10000; i++) sink = program.evaluate(input);
    double bytes = (bean.getThreadAllocatedBytes(id) - before) / 10000.0;
    int nodes = NodeUtil.countNodes(target.getRootNode());
    System.out.printf("COMPILED %s: %.2f bytes/evaluation, %d nodes%n", source, bytes, nodes);
    assertTrue("Allocation budget exceeded: " + bytes + " > " + budget, bytes <= budget);
    assertTrue("AST growth budget exceeded", nodes <= 128);
    assertTrue("Guest code must remain compiled during measurement", target.isValidLastTier());
  }
}
