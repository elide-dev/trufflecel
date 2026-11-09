package dev.trufflecel;

import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.graalvm.polyglot.Context;
import com.oracle.truffle.api.source.Source;

public class EvaluationControlsTest {
  private static EvaluationOptions budget(long value) {
    return EvaluationOptions.newBuilder().budget(value).build();
  }

  @Test
  public void instrumentableProgramsRetainPerCallControls() {
    try (var context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      context.enter();
      try {
        var source = Source.newBuilder("cel", "x+1", "controls.cel").build();
        var program = Cel.newBuilder().variable("x", CelType.INT).build().compile(source);
        assertEquals(3L, program.evaluateWithDetails(Map.of("x", 2L), budget(3)).value());
        assertEquals(
            CelException.Code.RESOURCE_EXHAUSTED,
            program.evaluateWithDetails(Map.of("x", 2L), budget(2)).error().code());
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void costAndBudgetAreIndependentForEveryCall() throws Exception {
    var program =
        Cel.newBuilder().variable("x", CelType.INT).evaluationBudget(1).build().compile("x+1");
    var input = Map.<String, Object>of("x", 2L);
    assertThrows(CelException.class, () -> program.evaluate(input));
    var result = program.evaluateWithDetails(input, budget(3));
    assertEquals(3L, result.value());
    assertEquals(3L, result.actualCost());
    assertNull(result.error());
    assertEquals(3L, program.evaluate(Activation.snapshot(input), budget(3)));
    var failed = program.evaluateWithDetails(input, budget(2));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, failed.error().code());
    assertEquals(2L, failed.actualCost());
    assertSame(failed.error(), assertThrows(CelException.class, failed::value));
    assertTrue(failed.error().expressionId() > 0);
    assertThrows(
        CelException.class, () -> program.evaluate(input, EvaluationOptions.newBuilder().build()));
    try (var executor = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<EvaluationResult>>();
      for (int i = 0; i < 40; i++) {
        long limit = i % 2 == 0 ? 2 : 3;
        tasks.add(() -> program.evaluateWithDetails(input, budget(limit)));
      }
      var results = executor.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++)
        assertEquals(i % 2 == 0, results.get(i).get().error() != null);
    }
    assertThrows(IllegalArgumentException.class, () -> budget(0));
    assertThrows(NullPointerException.class, () -> program.evaluate(input, null));
  }

  @Test
  public void foldedWorkLazyScopesAndErrorsReportCost() {
    var folded = Cel.compile("1+2").evaluateWithDetails(Map.of(), budget(3));
    assertEquals(3L, folded.value());
    assertEquals(3L, folded.actualCost());
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    for (String source :
        List.of("cel.block([x+1],cel.index(0)+cel.index(0))", "[x].map(v,v+1)[0]")) {
      var p = env.compile(source);
      var activation = Activation.snapshot(Map.of("x", 2L));
      var result = p.evaluateWithDetails(activation, budget(100));
      assertEquals(p.evaluate(activation), result.value());
      assertTrue(result.actualCost() > 0);
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          p.evaluateWithDetails(activation, budget(1)).error().code());
    }
    var failure = env.compile("10/x").evaluateWithDetails(Map.of("x", 0L), budget(100));
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, failure.error().code());
    assertEquals(3L, failure.actualCost());
  }

  @Test
  public void reentrantBlocksKeepArgumentsScopesAndBudgetsIndependent() {
    var reference = new AtomicReference<Program>();
    var environment =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .function(
                "recurse",
                List.of(CelType.INT),
                CelType.INT,
                arguments -> {
                  long x = (Long) arguments.get(0);
                  return x == 0 ? 0L : reference.get().evaluate(Map.of("x", x - 1));
                })
            .build();
    var program = environment.compile("cel.block([x+1], cel.index(0) + recurse(x))");
    reference.set(program);
    var input = Map.<String, Object>of("x", 3L);
    var snapshot = Activation.snapshot(input);
    assertEquals(10L, program.evaluate(input));
    assertEquals(10L, program.evaluate(snapshot));
    assertEquals(10L, program.evaluate(Map.of("x", 3L, "unused", List.of(1L))));
    long cost = program.evaluateWithDetails(Map.of("x", 0L), budget(100)).actualCost();
    for (var options :
        List.of(
            budget(cost), EvaluationOptions.newBuilder().budget(cost).trace(event -> {}).build())) {
      var result = program.evaluateWithDetails(snapshot, options);
      assertEquals(10L, result.value());
      assertEquals(cost, result.actualCost());
      assertEquals(10L, program.evaluate(input, options));
    }
    var exhausted = program.evaluateWithDetails(snapshot, budget(cost - 1));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, exhausted.error().code());
    assertEquals(cost - 1, exhausted.actualCost());
    assertEquals(10L, program.evaluate(snapshot));
  }

  @Test
  public void cancellationAndDeadlinesAreCheckedBeforeReturning() {
    var program = Cel.compile("1");
    for (EvaluationOptions options :
        List.of(
            EvaluationOptions.newBuilder().timeout(Duration.ZERO).build(),
            EvaluationOptions.newBuilder().deadline(Instant.EPOCH).build(),
            EvaluationOptions.newBuilder().cancelled(() -> true).build())) {
      var result = program.evaluateWithDetails(Map.of(), options);
      assertEquals(CelException.Code.RESOURCE_EXHAUSTED, result.error().code());
      assertEquals(0L, result.actualCost());
    }
    var future =
        EvaluationOptions.newBuilder()
            .timeout(Duration.ofDays(1))
            .deadline(Instant.now().plusSeconds(3600))
            .cancelled(() -> false)
            .build();
    assertEquals(1L, program.evaluate(Map.of(), future));
    assertEquals(
        1L,
        program.evaluate(Map.of(), EvaluationOptions.newBuilder().deadline(Instant.MAX).build()));
    var count = new AtomicInteger();
    var loop =
        Cel.newBuilder().variable("xs", CelType.list(CelType.INT)).build().compile("xs.all(x,x>0)");
    var stopped =
        loop.evaluateWithDetails(
            Map.of("xs", List.of(1L, 2L, 3L)),
            EvaluationOptions.newBuilder().cancelled(() -> count.incrementAndGet() > 4).build());
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, stopped.error().code());
    assertTrue(stopped.actualCost() > 0);
    var cancelled = new AtomicBoolean();
    var host =
        Cel.newBuilder()
            .function(
                "f",
                List.of(),
                CelType.INT,
                a -> {
                  cancelled.set(true);
                  return 1L;
                })
            .build()
            .compile("f()");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        host.evaluateWithDetails(
                Map.of(), EvaluationOptions.newBuilder().cancelled(cancelled::get).build())
            .error()
            .code());
    assertThrows(
        IllegalArgumentException.class,
        () -> EvaluationOptions.newBuilder().timeout(Duration.ofNanos(-1)));
    assertThrows(
        ArithmeticException.class,
        () -> EvaluationOptions.newBuilder().timeout(Duration.ofDays(Long.MAX_VALUE)));
    assertThrows(NullPointerException.class, () -> EvaluationOptions.newBuilder().deadline(null));
    assertThrows(NullPointerException.class, () -> EvaluationOptions.newBuilder().cancelled(null));
  }

  @Test
  public void snapshotCopyIsChargedAndCapped() {
    // Non-OwnedList ranges charge the snapshot copy before the first tick, closing the
    // budget-bypass where an early-exiting macro performed an uncharged O(n) copy per call.
    var env = Cel.newBuilder().build();
    // lists.range(10000) charges 10000 to produce the range; .all then charges 10000 for the
    // snapshot copy. A budget that covers range production but not the snapshot fails.
    var listRange = env.compile("lists.range(10000).all(i, false)");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        listRange.evaluateWithDetails(Map.of(), budget(10_100)).error().code());
    // With a budget that covers both the range and the snapshot, the macro short-circuits.
    assertEquals(false, listRange.evaluateWithDetails(Map.of(), budget(20_100)).value());
    // OwnedList ranges (list literals) skip the snapshot charge and stay on the fast path.
    var owned = env.compile("[1, 2, 3].all(i, false)");
    assertEquals(false, owned.evaluateWithDetails(Map.of(), budget(10_100)).value());
    // Host maps pay mapRange's keySet snapshot: a 100-entry map charges 100 before iteration.
    var mapEnv =
        Cel.newBuilder().variable("m", CelType.map(CelType.STRING, CelType.STRING)).build();
    var mapProgram = mapEnv.compile("m.all(k, false)");
    var map = new LinkedHashMap<String, String>();
    for (int i = 0; i < 100; i++) map.put("key" + i, "value" + i);
    assertEquals(false, mapProgram.evaluateWithDetails(Map.of("m", map), budget(200)).value());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        mapProgram.evaluateWithDetails(Map.of("m", map), budget(10)).error().code());
    // The >=1M snapshot cap is independent of the budget: even with 50M ticks, a 1M-element
    // concatenated range throws RESOURCE_EXHAUSTED from listRange's cap, matching the
    // sibling materializers (scalarRange/ownedList/ownedMap).
    var capped = env.compile("cel.bind(r, lists.range(500000), (r + r).all(i, false))");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        capped.evaluateWithDetails(Map.of(), budget(50_000_000)).error().code());
    // Values.listRange/mapRange enforce the same >=1M cap as the sibling materializers.
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () -> Values.listRange(List.copyOf(Collections.nCopies(1_000_000, 1L))))
            .code());
    var smallMap = new LinkedHashMap<String, Long>();
    smallMap.put("a", 1L);
    smallMap.put("b", 2L);
    assertEquals(2, Values.mapRange(smallMap).size());
    assertEquals("a", Values.mapRange(smallMap).get(0));
    assertEquals("b", Values.mapRange(smallMap).get(1));
  }

  @Test
  public void snapshotAmplificationIsKilled() {
    // Reproduces the original exploit: lists.range(10000).map(i, m.all(k, false)) where m is a
    // 400,000-entry host map. Before the fix, each outer iteration charged ~1 tick while
    // performing an uncharged O(n) keySet().toArray() copy of 400k elements (~41 seconds under
    // the default budget). After the fix, the snapshot charge exhausts the budget after 2-3
    // outer iterations instead of running 10,000 uncharged copies.
    var map = new LinkedHashMap<String, String>();
    for (int i = 0; i < 400_000; i++) map.put("key" + i, "value" + i);
    var env = Cel.newBuilder().variable("m", CelType.map(CelType.STRING, CelType.STRING)).build();
    var program = env.compile("lists.range(10000).map(i, m.all(k, false))");
    var start = System.currentTimeMillis();
    var result = program.evaluateWithDetails(Map.of("m", map), budget(1_000_000));
    var elapsed = System.currentTimeMillis() - start;
    // The expression must throw RESOURCE_EXHAUSTED (budget exhausted by snapshot charges),
    // not run for tens of seconds with 10,000 uncharged 400k-element copies.
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, result.error().code());
    assertTrue("amplification took " + elapsed + "ms (expected sub-second)", elapsed < 5_000);
  }
}
