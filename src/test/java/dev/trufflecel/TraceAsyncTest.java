package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.api.source.Source;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.graalvm.polyglot.Context;
import org.junit.Test;

public class TraceAsyncTest {
  @Test
  public void traceRestoresFoldedAndLoweredNodesAndPreservesShortCircuitOrder() {
    var events = new ArrayList<TraceEvent>();
    assertEquals(7L, Cel.compile("1 + 2 * 3").trace(events::add).evaluate());
    assertEquals(List.of(1L, 2L, 3L, 6L, 7L), events.stream().map(TraceEvent::value).toList());
    assertEquals(5, events.stream().map(TraceEvent::expressionId).distinct().count());
    assertTrue(events.stream().allMatch(e -> e.line() == 1 && e.column() > 0 && e.error() == null));
    events.clear();
    assertEquals(false, Cel.compile("false && (1/0 == 0)").trace(events::add).evaluate());
    assertEquals(2, events.size());
    assertTrue(events.stream().allMatch(e -> Boolean.FALSE.equals(e.value())));
    events.clear();
    var arithmetic = Cel.compile("x + 1 + 2 + 3");
    assertEquals(10L, arithmetic.trace(events::add).evaluate(Map.of("x", 4L)));
    assertEquals(7, events.size());
    assertEquals(7, events.stream().map(TraceEvent::expressionId).distinct().count());
    assertEquals(10L, arithmetic.evaluate(Map.of("x", 4L)));
    events.clear();
    assertEquals(
        2L,
        Cel.compile("xs.map(x,x+1).size()")
            .trace(events::add)
            .evaluate(Map.of("xs", List.of(1L, 2L))));
    assertTrue(events.stream().anyMatch(e -> List.of(2L, 3L).equals(e.value())));
    assertTrue(events.stream().anyMatch(e -> e.kind().equals("MACRO")));
  }

  @Test
  public void traceRecordsFailuresAndLeavesOrdinaryExecutionIndependent() {
    var events = new ArrayList<TraceEvent>();
    var program = Cel.compile("x && (1 / 0 == 0)");
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(
                CelException.class, () -> program.trace(events::add).evaluate(Map.of("x", true)))
            .code());
    assertTrue(
        events.stream()
            .anyMatch(
                e -> e.error() != null && e.error().code() == CelException.Code.DIVIDE_BY_ZERO));
    assertTrue(events.stream().filter(e -> e.error() != null).allMatch(e -> e.value() == null));
    int count = events.size();
    assertEquals(false, program.evaluate(Map.of("x", false)));
    assertEquals(count, events.size());
    assertEquals(
        false, program.trace(events::add).evaluate(Activation.snapshot(Map.of("x", false))));
    var details =
        program
            .trace(events::add)
            .evaluateWithDetails(Map.of("x", false), EvaluationOptions.newBuilder().build());
    assertEquals(false, details.value());
    assertTrue(details.actualCost() > 0);
    assertThrows(
        IllegalStateException.class,
        () ->
            Cel.compile("1")
                .trace(
                    e -> {
                      throw new IllegalStateException("listener");
                    })
                .evaluate());
    assertThrows(NullPointerException.class, () -> program.trace(null));
    var bytes = Cel.compile("b'ab'");
    bytes.trace(e -> ((byte[]) e.value())[0] = 0).evaluate();
    assertArrayEquals(new byte[] {97, 98}, (byte[]) bytes.evaluate());
  }

  @Test
  public void preparedInstancesTraceConcurrentlyAndInstrumentedProgramsNeedNoContextForTracing()
      throws Exception {
    var prepared =
        Cel.newBuilder().variable("x", CelType.INT).build().prepare("cel.bind(v,x+1,v+v)");
    var first = prepared.instantiate();
    var second = prepared.instantiate();
    var events = new ConcurrentLinkedQueue<TraceEvent>();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Object>>();
      for (long i = 0; i < 40; i++) {
        long x = i;
        jobs.add(() -> (x % 2 == 0 ? first : second).trace(events::add).evaluate(Map.of("x", x)));
      }
      var results = pool.invokeAll(jobs);
      for (int i = 0; i < results.size(); i++) assertEquals(2L * (i + 1), results.get(i).get());
    }
    assertTrue(events.size() > 40);
    Program instrumented;
    try (var context = Context.newBuilder("cel").allowExperimentalOptions(true).build()) {
      context.enter();
      try {
        instrumented =
            Cel.newBuilder()
                .build()
                .compile(Source.newBuilder("cel", "[1,2].map(x,x+1).size()", "trace.cel").build());
        assertEquals(2L, instrumented.evaluate());
      } finally {
        context.leave();
      }
    }
    assertEquals(2L, instrumented.trace(events::add).evaluate());
  }

  @Test
  public void asyncOwnsInputsAndPropagatesErrorsRejectionAndCancellation() throws Exception {
    var queued = new ArrayList<Runnable>();
    Executor executor = queued::add;
    var input = new ArrayList<Long>(List.of(1L, 2L));
    var program = Cel.compile("xs.size()");
    var result = program.evalAsync(Map.of("xs", input), executor);
    input.clear();
    queued.removeFirst().run();
    assertEquals(2L, result.get());
    var cancelled = program.evalAsync(Map.of("xs", input), executor);
    assertTrue(cancelled.cancel(true));
    queued.removeFirst().run();
    assertThrows(CancellationException.class, cancelled::join);
    var failed = Cel.compile("1/0").evalAsync(Map.of(), Runnable::run);
    assertTrue(
        assertThrows(CompletionException.class, failed::join).getCause() instanceof CelException);
    assertThrows(
        RejectedExecutionException.class,
        () ->
            program.evalAsync(
                Map.of(),
                task -> {
                  throw new RejectedExecutionException();
                }));
    assertEquals(
        0L,
        program
            .evalAsync(
                Activation.snapshot(Map.of("xs", input)),
                EvaluationOptions.newBuilder().build(),
                Runnable::run)
            .get());
    assertThrows(NullPointerException.class, () -> program.evalAsync(Map.of(), null));
  }

  @Test
  public void asyncCancellationIsCooperativeDuringEvaluationAndPreservesOtherOptions() {
    var future = new AtomicReference<CompletableFuture<Object>>();
    var calls = new AtomicInteger();
    var env = Cel.newBuilder().lateFunction("step", "step", false, List.of(), CelType.INT).build();
    var tasks = new ArrayList<Runnable>();
    var options =
        EvaluationOptions.newBuilder()
            .function(
                "step",
                a -> {
                  calls.incrementAndGet();
                  future.get().cancel(false);
                  return 1L;
                })
            .build();
    future.set(env.compile("step() + step()").evalAsync(Map.of(), options, tasks::add));
    tasks.getFirst().run();
    assertTrue(future.get().isCancelled());
    assertEquals(1, calls.get());
    var externallyCancelled =
        Cel.compile("1")
            .evalAsync(
                Map.of(),
                EvaluationOptions.newBuilder().cancelled(() -> true).build(),
                Runnable::run);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        ((CelException)
                assertThrows(CompletionException.class, externallyCancelled::join).getCause())
            .code());
  }
}
