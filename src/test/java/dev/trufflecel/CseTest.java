package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.api.source.Source;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.junit.Test;

public final class CseTest {
  private static Environment environment(boolean enabled) {
    return Cel.newBuilder().inferVariables(true).commonSubexpressions(enabled).build();
  }

  private static void equivalent(String source, Map<String, ?> bindings) {
    var options = EvaluationOptions.newBuilder().budget(10_000).build();
    var expected = environment(false).compile(source).evaluateWithDetails(bindings, options);
    var actual = environment(true).compile(source).evaluateWithDetails(bindings, options);
    assertEquals(source, expected.actualCost(), actual.actualCost());
    if (expected.error() == null) assertEquals(source, expected.value(), actual.value());
    else {
      assertNotNull(source, actual.error());
      assertEquals(source, expected.error().code(), actual.error().code());
      assertEquals(source, expected.error().offset(), actual.error().offset());
      assertEquals(source, expected.error().expressionId(), actual.error().expressionId());
    }
  }

  private static Object outcome(java.util.function.Supplier<Object> evaluation) {
    try {
      return evaluation.get();
    } catch (CelException failure) {
      return List.of(failure.code(), failure.offset(), failure.expressionId());
    }
  }

  @Test
  public void environmentBudgetsPreserveOrdinaryMapAndSnapshotFailureLocations() {
    String source = "(x + 1) * (x + 1)";
    var bindings = Map.of("x", 2L);
    var snapshot = Activation.snapshot(bindings);
    for (int budget = 1; budget <= 12; budget++) {
      var plain = environment(false).toBuilder().evaluationBudget(budget).build().compile(source);
      var optimized =
          environment(true).toBuilder().evaluationBudget(budget).build().compile(source);
      assertEquals(
          "map budget " + budget,
          outcome(() -> plain.evaluate(bindings)),
          outcome(() -> optimized.evaluate(bindings)));
      assertEquals(
          "snapshot budget " + budget,
          outcome(() -> plain.evaluate(snapshot)),
          outcome(() -> optimized.evaluate(snapshot)));
      var controls = EvaluationOptions.newBuilder().build();
      assertEquals(
          "cost budget " + budget,
          plain.evaluateWithDetails(bindings, controls).actualCost(),
          optimized.evaluateWithDetails(bindings, controls).actualCost());
    }
  }

  @Test
  public void repeatedPureExpressionsKeepValuesErrorsUnknownsAndCosts() {
    for (long x : new long[] {-10, 0, 2, Long.MAX_VALUE}) {
      equivalent("(x + 1) * (x + 1) + (x + 1)", Map.of("x", x));
      equivalent("10 / x > 0 || 10 / x > 0", Map.of("x", x));
    }
    equivalent("(x + 1) * (x + 1)", Map.of("x", Unknown.of("x")));
    equivalent("enabled ? (x + 1) * (x + 1) : 0", Map.of("enabled", false));
    equivalent("(x > 0 ? x + 1 : 0) + (x > 0 ? x + 1 : 0)", Map.of("x", 2));
    equivalent("[s.matches('a+'), s.matches('a+')]", Map.of("s", "aa"));
    equivalent("[(x + 1), (y + 1), (x + 1), (y + 1)]", Map.of("x", 1, "y", 2));
    var expected = environment(false).compile("(x + 1) * (x + 1)");
    var actual = environment(true).compile("(x + 1) * (x + 1)");
    for (int budget = 1; budget <= 9; budget++) {
      var options = EvaluationOptions.newBuilder().budget(budget).build();
      var first = expected.evaluateWithDetails(Map.of("x", 2), options);
      var second = actual.evaluateWithDetails(Map.of("x", 2), options);
      assertEquals(budget + "", first.error() == null, second.error() == null);
      assertEquals(budget + "", first.actualCost(), second.actualCost());
      if (first.error() != null) {
        assertEquals(budget + "", first.error().code(), second.error().code());
        assertEquals(budget + "", first.error().offset(), second.error().offset());
        assertEquals(budget + "", first.error().expressionId(), second.error().expressionId());
      }
    }
  }

  @Test
  public void scopesAndHostCallsRetainTheirOwnExecution() {
    equivalent("xs.map(x, (x + 1) * (x + 1))", Map.of("xs", List.of(1, 2)));
    equivalent(
        "xs.map(x, ys.map(y, (x+y) * (x+y)))", Map.of("xs", List.of(1, 2), "ys", List.of(3, 4)));
    equivalent("cel.bind(v, x+1, (v+1) * (v+1))", Map.of("x", 2));
    equivalent("[(x+1), (x+1), xs.map(y, (y+1)*(y+1))]", Map.of("x", 2, "xs", List.of(1, 2)));
    equivalent("[(x+1), (x+1), cel.block([x], cel.index(0))]", Map.of("x", 2));
    equivalent("cel.block([x + 1], cel.index(0) + cel.index(0))", Map.of("x", 2));
    equivalent("has({'x': (x+1)+(x+1)}.x)", Map.of("x", 2));
    var calls = new AtomicInteger();
    var custom =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .commonSubexpressions(true)
            .function(
                "f", List.of(CelType.INT), CelType.INT, args -> (long) calls.incrementAndGet())
            .build()
            .compile("f(x + 1) + f(x + 1)");
    assertEquals(3L, custom.evaluate(Map.of("x", 1)));
    assertEquals(2, calls.get());
    var overloaded =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .commonSubexpressions(true)
            .function(
                "+",
                "add_custom",
                false,
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                args -> {
                  calls.incrementAndGet();
                  return (Long) args.get(0) + (Long) args.get(1);
                })
            .build()
            .compile("(x + 1) * (x + 1)");
    assertEquals(4L, overloaded.evaluate(Map.of("x", 1)));
    assertEquals(4, calls.get());
  }

  @Test
  public void emitsCanonicalBlocksWithDistinctIdsAndRoundTrips() {
    var environment = environment(true);
    var parsed = CanonicalAst.parse(environment, "(x + 1) * (x + 1)");
    var checked = CanonicalAst.check(environment, parsed);
    assertTrue(checked.toString(), checked.toString().contains("function: \"cel.block\""));
    assertTrue(checked.toString().contains("function: \"cel.index\""));
    assertEquals(9L, CanonicalAst.program(environment, checked).evaluate(Map.of("x", 2)));
    assertFalse(CanonicalAst.check(environment(false), parsed).toString().contains("cel.block"));
    assertTrue(environment.toBuilder().build().commonSubexpressions);
    var macro =
        CanonicalAst.check(
            environment(false), CanonicalAst.parse(environment(false), "xs.map(x, (x+1)*(x+1))"));
    assertEquals(
        List.of(4L, 9L),
        CanonicalAst.program(environment, macro).evaluate(Map.of("xs", List.of(1, 2))));
  }

  @Test
  public void restrictedLibraryPolicyExportsWithoutCseAndRoundTrips() {
    // Reproduces 2b55594: commonSubexpressions(true) combined with a restricted LibraryPolicy
    // emits a CheckedExpr the same environment cannot re-import because cel.block/cel.index were
    // injected after the original gate-check. The optimizer now falls back, so the export omits
    // synthetic blocks and the same restricted environment consumes its own export.
    String source = "(a + b + c) + (a + b + c) * 2";
    var bindings = Map.of("a", 1L, "b", 2L, "c", 3L);
    var restricted =
        Cel.newBuilder()
            .variable("a", CelType.INT)
            .variable("b", CelType.INT)
            .variable("c", CelType.INT)
            .libraries(Map.of())
            .commonSubexpressions(true)
            .build();
    var parsed = CanonicalAst.parse(restricted, source);
    var checked = CanonicalAst.check(restricted, parsed);
    // CSE falls back: no cel.block is emitted into the restricted export.
    assertFalse(checked.toString(), checked.toString().contains("cel.block"));
    assertFalse(checked.toString().contains("cel.index"));
    assertEquals(18L, CanonicalAst.program(restricted, checked).evaluate(bindings));
    // The same restricted environment (without CSE) round-trips unchanged.
    var plain =
        Cel.newBuilder()
            .variable("a", CelType.INT)
            .variable("b", CelType.INT)
            .variable("c", CelType.INT)
            .libraries(Map.of())
            .commonSubexpressions(false)
            .build();
    var plainChecked = CanonicalAst.check(plain, CanonicalAst.parse(plain, source));
    assertFalse(plainChecked.toString().contains("cel.block"));
    assertEquals(18L, CanonicalAst.program(plain, plainChecked).evaluate(bindings));
    // In-process compile under restricted policy + CSE still evaluates (no UNDECLARED thrown).
    assertEquals(18L, restricted.compile(source).evaluate(bindings));
  }

  @Test
  public void restrictedFunctionAllowListExportsWithoutCseAndRoundTrips() {
    String source = "(a + b + c) + (a + b + c) * 2";
    var bindings = Map.of("a", 1L, "b", 2L, "c", 3L);
    // Minimal allow-list covering the source operators but neither cel.block nor cel.index.
    var restricted =
        Cel.newBuilder()
            .variable("a", CelType.INT)
            .variable("b", CelType.INT)
            .variable("c", CelType.INT)
            .allowedFunctions(Set.of("+", "*"))
            .commonSubexpressions(true)
            .build();
    var checked = CanonicalAst.check(restricted, CanonicalAst.parse(restricted, source));
    assertFalse(checked.toString(), checked.toString().contains("cel.block"));
    assertFalse(checked.toString(), checked.toString().contains("cel.index"));
    assertEquals(18L, CanonicalAst.program(restricted, checked).evaluate(bindings));
    // Allowing cel.block but not cel.index also forces the fallback (CSE emits both).
    var halfAllowed =
        Cel.newBuilder()
            .variable("a", CelType.INT)
            .variable("b", CelType.INT)
            .variable("c", CelType.INT)
            .allowedFunctions(Set.of("+", "*", "cel.block"))
            .commonSubexpressions(true)
            .build();
    var halfChecked = CanonicalAst.check(halfAllowed, CanonicalAst.parse(halfAllowed, source));
    assertFalse(halfChecked.toString(), halfChecked.toString().contains("cel.block"));
    assertFalse(halfChecked.toString(), halfChecked.toString().contains("cel.index"));
    assertEquals(18L, CanonicalAst.program(halfAllowed, halfChecked).evaluate(bindings));
  }

  @Test
  public void bindingsLibraryAndExplicitAllowListExportCseAndRoundTrip() {
    String source = "(a + b + c) + (a + b + c) * 2";
    var bindings = Map.of("a", 1L, "b", 2L, "c", 3L);
    // libraries(Map.of("bindings", 0)) lets cel.block/cel.index through: CSE applies both ways.
    var bindingsEnv =
        Cel.newBuilder()
            .variable("a", CelType.INT)
            .variable("b", CelType.INT)
            .variable("c", CelType.INT)
            .libraries(Map.of("bindings", 0))
            .commonSubexpressions(true)
            .build();
    var bindingsChecked = CanonicalAst.check(bindingsEnv, CanonicalAst.parse(bindingsEnv, source));
    assertTrue(bindingsChecked.toString(), bindingsChecked.toString().contains("cel.block"));
    assertTrue(bindingsChecked.toString().contains("cel.index"));
    assertEquals(18L, CanonicalAst.program(bindingsEnv, bindingsChecked).evaluate(bindings));
    // An explicit allow-list with both cel.block and cel.index, plus the default latest library
    // set (which already provides bindings), also permits CSE round-trip.
    var allowEnv =
        Cel.newBuilder()
            .variable("a", CelType.INT)
            .variable("b", CelType.INT)
            .variable("c", CelType.INT)
            .allowedFunctions(Set.of("+", "*", "cel.block", "cel.index"))
            .commonSubexpressions(true)
            .build();
    var allowChecked = CanonicalAst.check(allowEnv, CanonicalAst.parse(allowEnv, source));
    assertTrue(allowChecked.toString(), allowChecked.toString().contains("cel.block"));
    assertTrue(allowChecked.toString().contains("cel.index"));
    assertEquals(18L, CanonicalAst.program(allowEnv, allowChecked).evaluate(bindings));
  }

  @Test
  public void cseFallbackDoesNotBypassUserWrittenBlockGate() {
    // A user-written cel.block under a restricted policy is rejected by the checker before
    // CSE synthesis runs; the fallback must not make pre-existing source validate.
    var restricted =
        Cel.newBuilder()
            .variable("a", CelType.INT)
            .libraries(Map.of())
            .commonSubexpressions(true)
            .build();
    var parsed = CanonicalAst.parse(Cel.newBuilder().build(), "cel.block([a + 1], cel.index(0))");
    CelException failure =
        assertThrows(CelException.class, () -> CanonicalAst.check(restricted, parsed));
    assertEquals(CelException.Code.UNDECLARED, failure.code());
    assertEquals(
        CelException.Code.UNDECLARED,
        assertThrows(
                CelException.class, () -> restricted.compile("cel.block([a + 1], cel.index(0))"))
            .code());
  }

  @Test
  public void sourceInstrumentationAndConcurrentReuseRemainIndependent() throws Exception {
    Environment environment = environment(true);
    String source = "(x + 1) * (x + 1)";
    var prepared = environment.prepare(source);
    var program = prepared.instantiate();
    var originalEvents = new ArrayList<TraceEvent>();
    var optimizedEvents = new ArrayList<TraceEvent>();
    environment(false).compile(source).trace(originalEvents::add).evaluate(Map.of("x", 2));
    program.trace(optimizedEvents::add).evaluate(Map.of("x", 2));
    assertEquals(originalEvents, optimizedEvents);
    try (var executor = Executors.newFixedThreadPool(4)) {
      var work = new ArrayList<Callable<Object>>();
      for (long i = 0; i < 32; i++) {
        long value = i;
        work.add(() -> program.evaluate(Map.of("x", value)));
      }
      var results = executor.invokeAll(work);
      for (long i = 0; i < results.size(); i++)
        assertEquals((i + 1) * (i + 1), results.get((int) i).get());
    }
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        var instrumented =
            prepared.instantiate(Source.newBuilder("cel", source, "cse.cel").build());
        assertEquals(9L, instrumented.evaluate(Map.of("x", 2)));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void boundsAndUnsupportedExpressionsKeepTheOriginalTree() {
    Environment shallow =
        Cel.newBuilder().variable("x", CelType.INT).maxDepth(4).commonSubexpressions(true).build();
    var bounded = Ast.parse(environment(false), "[(x+1)*(x+1), -(x*x)]");
    assertEquals(List.of(9L, -4L), bounded.compile(shallow).evaluate(Map.of("x", 2)));
    equivalent("1+2", Map.of());
    equivalent("[(x+1), (x+1), (1+2)]", Map.of("x", 2));
    equivalent("[!x, !x]", Map.of("x", true));
    var declared = Cel.newBuilder().inferVariables(true).commonSubexpressions(true).build();
    assertEquals(CelType.INT, declared.compile("int").evaluate());
    equivalent("x == x", Map.of("x", 2));
    equivalent("[(x+1), (x+1), x.a]", Map.of("x", 2));
    for (Object scalar :
        List.of(
            1L,
            1.0,
            true,
            "s",
            new UInt(1),
            new Bytes(new byte[] {1}),
            Instant.EPOCH,
            Duration.ZERO,
            CelType.INT,
            CelNull.INSTANCE)) {
      var env =
          Cel.newBuilder()
              .constant("c", scalar)
              .variable("x", CelType.INT)
              .commonSubexpressions(true)
              .build();
      assertEquals(
          List.of(4L, scalar), env.compile("[(x+1)*(x+1), c]").evaluateInternal(Map.of("x", 1)));
    }
    var aggregate =
        Cel.newBuilder().constant("c", Map.of("a", 1)).commonSubexpressions(true).build();
    assertEquals(Map.of("a", 1L), aggregate.compile("c").evaluate());
  }
}
