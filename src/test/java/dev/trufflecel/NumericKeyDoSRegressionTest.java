package dev.trufflecel;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

/**
 * Regression tests for the numeric-key map construction budget bypass (quadratic CPU / DoS).
 *
 * <p>Pre-fix, {@code Values.key} linearly scanned the accumulated key set on every numeric-key
 * insert, making {@code transformMapEntry} and {@code cel.@mapInsert} build n-entry maps with ~n²/2
 * uncharged comparisons while {@code actualCost()} grew only linearly. Post-fix, an O(1) {@code
 * BigInteger}-indexed set in {@link HostCollections.NumericKeyMap} detects cross-type {@code
 * Long}/{@code UInt} duplicates, and {@code mergeEntries} charges one {@code ctx.tick()} per merged
 * entry so the budget observes the per-entry work.
 */
// jspecify is not yet a dependency (dev.trufflecel is not @NullMarked package-by-package;
// see docs/TRUFFLECHECK_BASELINE.md), so this top-level test class is not @NullMarked.
@SuppressWarnings("RequireExplicitNullMarking")
public class NumericKeyDoSRegressionTest {

  @Test
  public void exploitScenarioFromBugReportAborts() {
    var env = Cel.newBuilder().build(); // default 1,000,000-op budget
    var program = env.compile("lists.range(160000).transformMapEntry(i, v, {i: v})");
    var start = System.currentTimeMillis();
    var ex = assertThrows(CelException.class, program::evaluate);
    var elapsed = System.currentTimeMillis() - start;
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, ex.code());
    assertTrue("Expected sub-second abort; was " + elapsed + "ms", elapsed < 5_000);
  }

  @Test
  public void largeTransformMapEntryWithNumericKeysIsLinearAndFast() {
    var env = Cel.newBuilder().evaluationBudget(10_000_000L).build();
    int n = 80_000;
    var program = env.compile("lists.range(" + n + ").transformMapEntry(i, v, {i: v})");
    var start = System.currentTimeMillis();
    var result = program.evaluateWithDetails(Map.of(), EvaluationOptions.newBuilder().build());
    var elapsed = System.currentTimeMillis() - start;
    assertEquals(n, ((Map<?, ?>) result.value()).size());
    assertTrue("Expected sub-second; was " + elapsed + "ms", elapsed < 5_000);
    assertTrue(
        "Expected linear charged cost (~7n); was " + result.actualCost(),
        result.actualCost() < 10L * n);
  }

  @Test
  public void smallBudgetAbortsTransformMapEntryWithNumericKeys() {
    var env = Cel.newBuilder().evaluationBudget(1).build();
    var program = env.compile("lists.range(20).transformMapEntry(i, v, {i: v})");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, program::evaluate).code());
  }

  @Test
  public void crossTypeNumericDuplicateKeysRejectedInMapLiteral() {
    var env = Cel.newBuilder().build();
    for (String expr :
        List.of(
            "{1: 'a', 1u: 'b'}",
            "{1u: 'a', 1: 'b'}",
            "{'a': 1, 'a': 2}",
            "{0: 'a', 0u: 'b'}",
            "{0u: 'a', 0: 'b'}",
            "{9223372036854775807: 'a', 9223372036854775807u: 'b'}"))
      assertEquals(
          "Expected INVALID_ARGUMENT for: " + expr,
          CelException.Code.INVALID_ARGUMENT,
          assertThrows(CelException.class, () -> env.compile(expr).evaluate()).code());
    // Negative Long and bit-identical UInt do NOT collide (signed vs unsigned BigInteger).
    assertEquals(
        2, ((Map<?, ?>) env.compile("{-1: 'a', 18446744073709551615u: 'b'}").evaluate()).size());
  }

  @Test
  public void crossTypeNumericDuplicateKeysRejectedInTransformMapEntry() {
    var env = Cel.newBuilder().build();
    for (String expr :
        List.of(
            "[0, 1].transformMapEntry(i, v, i == 0 ? {0u: 'x'} : {0: 'y'})",
            "[0, 1].transformMapEntry(i, v, i == 0 ? {0: 'x'} : {0u: 'y'})"))
      assertEquals(
          CelException.Code.INVALID_ARGUMENT,
          assertThrows(CelException.class, () -> env.compile(expr).evaluate()).code());
  }

  @Test
  public void mapInsertCanonicalPathBoundsAtSmallN() {
    var env = Cel.newBuilder().build();
    var parsed = CanonicalAst.parse(env, "lists.range(4000).transformMapEntry(i, v, {i: v})");
    var program = CanonicalAst.program(env, parsed);
    var budget = EvaluationOptions.newBuilder().budget(50_000).build();
    var start = System.currentTimeMillis();
    var result = program.evaluateWithDetails(Map.of(), budget);
    var elapsed = System.currentTimeMillis() - start;
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, result.error().code());
    assertTrue("Expected sub-second abort; was " + elapsed + "ms", elapsed < 5_000);
  }

  @Test
  public void transformMapAndMapLiteralsWithMixedNumericKeysStillWork() {
    var env = Cel.newBuilder().build();
    assertEquals(
        Map.of(0L, "a", 1L, "b"), env.compile("{0: 'a', 1: 'b'}.transformMap(k, v, v)").evaluate());
    assertEquals(
        CelException.Code.INVALID_ARGUMENT,
        assertThrows(CelException.class, () -> env.compile("{'a': 1, 'a': 2}").evaluate()).code());
    assertEquals(2, ((Map<?, ?>) env.compile("{1: 'a', 2: 'b'}").evaluate()).size());
  }

  @Test
  public void transformMapEntryWithLargeNStillProducesCorrectResult() {
    var env = Cel.newBuilder().evaluationBudget(10_000_000L).build();
    int n = 5_000;
    var result =
        (Map<?, ?>)
            env.compile("lists.range(" + n + ").transformMapEntry(i, v, {i: v})").evaluate();
    assertEquals(n, result.size());
    assertEquals(0L, result.get(0L));
    assertEquals((long) (n - 1), result.get((long) (n - 1)));
    assertEquals((long) (n / 2), result.get((long) (n / 2)));
  }

  @Test
  public void mapInsertDirectRejectsCrossTypeNumericDuplicates() {
    // `cel.@mapInsert` is a canonical-internal function (not reachable from CEL source, which
    // rejects `@` with SYNTAX). Dispatch it directly via ExtensionFunctions.apply — the same
    // dispatch the canonical-AST path uses when transformMapEntry desugars to cel.@mapInsert.
    var env = Cel.newBuilder().build();
    var ctx = new EvalContext(env, Map.of());
    assertEquals(
        CelException.Code.INVALID_ARGUMENT,
        assertThrows(
                CelException.class,
                () ->
                    ExtensionFunctions.apply(
                        "cel.@mapInsert",
                        new Object[] {Map.of(1L, "a"), Map.of(new UInt(1L), "b")},
                        ctx))
            .code());
    // Distinct cross-type keys merge fine.
    var ctx2 = new EvalContext(env, Map.of());
    Object merged =
        ExtensionFunctions.apply(
            "cel.@mapInsert", new Object[] {Map.of(1L, "a"), Map.of(2L, "b")}, ctx2);
    assertEquals(Map.of(1L, "a", 2L, "b"), merged);
  }

  @Test
  public void stringKeyedTransformMapEntryStillAbortsUnderBudget() {
    var env = Cel.newBuilder().evaluationBudget(1).build();
    var program = env.compile("lists.range(20).transformMapEntry(i, v, {'k' + string(i): v})");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, program::evaluate).code());
  }
}
