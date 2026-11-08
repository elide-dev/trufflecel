package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class LazyBindingTest {
  @Test
  public void unusedInitializersHaveNoEffectsAndReadsMemoize() {
    var calls = new AtomicInteger();
    var env =
        Cel.newBuilder()
            .variable("missing", CelType.INT)
            .function("next", List.of(), CelType.INT, a -> (long) calls.incrementAndGet())
            .build();
    assertEquals(true, env.compile("cel.bind(x, 1/0, true)").evaluate());
    assertEquals(true, env.compile("cel.bind(x, missing, true)").evaluate());
    assertEquals(true, env.compile("cel.bind(x, next(), true)").evaluate());
    assertEquals(0, calls.get());
    assertEquals(2L, env.compile("cel.bind(x, next(), x + x)").evaluate());
    assertEquals(1, calls.get());
    assertEquals(4L, env.compile("cel.bind(x, next(), x + x)").evaluate());
    assertEquals(2, calls.get());
  }

  @Test
  public void lazyValuesCaptureOuterScopesAcrossNestedLoopsAndShadowing() {
    var program = Cel.compile("[1, 2].map(x, cel.bind(y, x + 1, [3, 4].map(x, y + x)))");
    assertEquals(List.of(List.of(5L, 6L), List.of(6L, 7L)), program.evaluate());
    assertEquals(3L, Cel.compile("cel.bind(x, 1, cel.bind(x, x + 1, x + 1))").evaluate());
    assertEquals(
        5L, Cel.compile("cel.block([3], cel.bind(x, cel.index(0) + 1, x + 1))").evaluate());
    assertEquals(
        Unknown.of("u"), Cel.compile("cel.bind(x, u, x)").evaluate(Map.of("u", Unknown.of("u"))));
    assertEquals(false, Cel.compile("cel.bind(x, 1/0, x == 1 && false)").evaluate());
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(CelException.class, () -> Cel.compile("cel.bind(x, 1/0, x)").evaluate())
            .code());
  }

  @Test
  public void perCallMemoizationDoesNotShareConcurrentInputsOrBudgets() throws Exception {
    var program = Cel.compile("cel.bind(x, input + 1, x + x)");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var work = new ArrayList<Callable<Object>>();
      for (long i = 0; i < 80; i++) {
        long input = i;
        work.add(() -> program.evaluate(Map.of("input", input)));
      }
      var results = executor.invokeAll(work);
      for (int i = 0; i < results.size(); i++) assertEquals(2L * (i + 1), results.get(i).get());
    }
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    program.evaluate(
                        Map.of("input", 1L), EvaluationOptions.newBuilder().budget(1).build()))
            .code());
    assertEquals(
        true,
        Cel.compile("cel.bind(x, [1,2,3].map(v,v+1), true)")
            .evaluate(Map.of(), EvaluationOptions.newBuilder().budget(2).build()));
  }

  @Test
  public void generatedBlockSeamReplaysCostAndDoesNotCacheFailures() {
    var env = Cel.newBuilder().build();
    var context = new EvalContext(env, Map.of());
    var value = ExprNode.constant(3L, 0);
    var scope = new ExprNode.BlockScope(new ExprNode[] {value, value}, null, context, true);
    assertEquals(3L, scope.read(value, 0, null));
    assertEquals(1L, context.charged());
    assertEquals(3L, scope.read(value, 0, null));
    assertEquals(2L, context.charged());
    assertEquals(2L, context.fork().charged());
    var failure = new ExprNode(ExprNode.Kind.IDENT, "absent", null, 0);
    var failing = new ExprNode.BlockScope(new ExprNode[] {failure, value}, null, context, true);
    assertThrows(CelException.class, () -> failing.read(failure, 0, null));
    assertThrows(CelException.class, () -> failing.read(failure, 0, null));
    assertEquals(4L, context.charged());
  }
}
