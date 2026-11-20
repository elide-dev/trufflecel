package dev.trufflecel;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Regression test for the deep unknown-pattern dead-end in field-level partial evaluation.
 *
 * <p>When a declared unknown {@link AttributePattern} is deeper than the receiver chain evaluated
 * at some attribute hop, {@code PartialState.override()} previously only consulted patterns where
 * {@link AttributePattern#matches} returned true (pattern shallower-or-equal to the hop), so a
 * {@code PartialState#MISSING} receiver fell through to a hard {@code UNDECLARED} / {@code
 * NO_SUCH_KEY} / {@code NO_SUCH_FIELD} error instead of an {@link Unknown}. That dead-ended the
 * documented resolve-and-retry loop: {@link PartialResult#isComplete()} was {@code false} while
 * {@link PartialResult#unknownAttributes()} returned an empty set, and the caller had no attribute
 * to resolve.
 *
 * <p>The fix adds an overlap-based fallback at the SELECT and INDEX hops (mirroring {@code
 * PartialState.consume} / {@code PartialState.containsUnknown}) so any pattern that intersects the
 * hop path returns an {@link Unknown} rooted at the longest common prefix, matching cel-go /
 * cel-java, where the pattern check runs against the resolved attribute path before the activation
 * is consulted.
 */
public class DeepPatternReproTest {

  private static final AttributePattern SECRET_DEEP =
      AttributePattern.of("data").qualify("secret").qualify("deep");

  /**
   * A deep unknown pattern with no bindings yields an {@link Unknown} on every read shape that
   * intersects the pattern, instead of a hard {@code UNDECLARED} error.
   */
  @Test
  public void deepPatternWithMissingAncestorsYieldsUnknownInsteadOfUndeclared() {
    var activation = PartialActivation.of(Map.of(), SECRET_DEEP);
    for (String expr :
        List.of("data.secret", "data['secret']", "data.secret.deep", "has(data.secret)")) {
      var partial = Cel.compile(expr).partialEvaluate(activation);
      assertNull(expr + " should not produce a hard error", partial.error());
      assertTrue(expr + " should yield an Unknown", partial.value() instanceof Unknown);
      assertFalse(expr + " should be incomplete", partial.isComplete());
      assertFalse(
          expr + " should report resolvable unknown attributes",
          partial.unknownAttributes().isEmpty());
    }
  }

  /** A deep pattern with a present container with a missing intermediate key still Unknowns. */
  @Test
  public void deepPatternWithPresentContainerMissingKeyYieldsUnknownInsteadOfNoSuchKey() {
    var activation = PartialActivation.of(Map.of("data", Map.of("other", 1L)), SECRET_DEEP);
    for (String expr : List.of("data.secret", "data['secret']", "data.secret.deep")) {
      var partial = Cel.compile(expr).partialEvaluate(activation);
      assertNull(expr + " should not produce a hard error", partial.error());
      assertTrue(expr + " should yield an Unknown", partial.value() instanceof Unknown);
      assertFalse(
          expr + " should report resolvable unknown attributes",
          partial.unknownAttributes().isEmpty());
    }
  }

  /** Resolving the exact patterned leaf and retrying completes the evaluation. */
  @Test
  public void resolvingExactLeafAfterDeepPatternCompletes() {
    var activation = PartialActivation.of(Map.of(), SECRET_DEEP);
    var partial = Cel.compile("data.secret.deep").partialEvaluate(activation);
    assertTrue(partial.value() instanceof Unknown);
    var advanced =
        partial.advanceEvaluation(
            Map.of(Attribute.of("data").qualify("secret").qualify("deep"), 4L));
    assertTrue("after resolving the deep leaf, evaluation should complete", advanced.isComplete());
    assertEquals(4L, advanced.value());
    assertEquals(Set.of(), advanced.unknownAttributes());
  }

  /** Resolving an ancestor of the deep pattern also completes the evaluation. */
  @Test
  public void resolvingAncestorAfterDeepPatternCompletes() {
    var activation = PartialActivation.of(Map.of(), SECRET_DEEP);
    var partial = Cel.compile("data.secret.deep").partialEvaluate(activation);
    assertTrue(partial.value() instanceof Unknown);
    var advanced =
        partial.advanceEvaluation(
            Map.of(Attribute.of("data"), Map.of("secret", Map.of("deep", 7L))));
    assertTrue("after resolving an ancestor, evaluation should complete", advanced.isComplete());
    assertEquals(7L, advanced.value());
  }

  /** Wildcard-deep patterns behave the same as concrete-deep patterns. */
  @Test
  public void wildcardDeepPatternYieldsUnknownInsteadOfUndeclared() {
    var activation =
        PartialActivation.of(Map.of(), AttributePattern.of("data").wildcard().wildcard());
    var partial = Cel.compile("data.secret.deep").partialEvaluate(activation);
    assertNull("wildcard-deep pattern should not produce a hard error", partial.error());
    assertTrue(partial.value() instanceof Unknown);
    assertFalse(partial.isComplete());
    assertFalse(partial.unknownAttributes().isEmpty());
  }

  /** Reverse-depth reads still produce an Unknown rooted at the pattern (existing behavior). */
  @Test
  public void shallowPatternWithDeeperReadStillUnknownsAtPatternGranularity() {
    var activation = PartialActivation.of(Map.of(), AttributePattern.of("data").qualify("secret"));
    assertEquals(
        Unknown.of("data.secret"),
        Cel.compile("data.secret.deep").partialEvaluate(activation).value());
  }

  /** A non-intersecting sibling still raises the existing hard {@code UNDECLARED} error. */
  @Test
  public void nonIntersectingSiblingStillRaisesUndeclared() {
    var activation = PartialActivation.of(Map.of(), SECRET_DEEP);
    var partial = Cel.compile("data.known").partialEvaluate(activation);
    assertNotNull("non-intersecting sibling should still raise UNDECLARED", partial.error());
    assertEquals(CelException.Code.UNDECLARED, partial.error().code());
    assertEquals(Set.of(), partial.unknownAttributes());
  }

  /**
   * A resolved ancestor that supplies (or has supplied) the receiver still surfaces the existing
   * {@code NO_SUCH_KEY} error for non-intersecting descendants.
   */
  @Test
  public void resolvedAncestorStillRaisesNoSuchKeyForMissing() {
    var activation =
        PartialActivation.of(Map.of(), AttributePattern.of("data").qualify("secret"))
            .resolve(Map.of(Attribute.of("data"), Map.of()));
    var partial = Cel.compile("data.secret").partialEvaluate(activation);
    assertNotNull(
        "resolved ancestor with missing key should still raise NO_SUCH_KEY", partial.error());
    assertEquals(CelException.Code.NO_SUCH_KEY, partial.error().code());
    assertEquals(Set.of(), partial.unknownAttributes());
  }

  /**
   * A deep unknown pattern inside a comprehension produces a merged {@link Unknown} across
   * iterations instead of a hard {@code NO_SUCH_KEY} inside the loop body.
   */
  @Test
  public void deepPatternInComprehensionMergesUnknownsAcrossIterations() {
    var activation =
        PartialActivation.of(
            Map.of("rows", List.of(Map.of("known", 1L), Map.of("known", 2L))),
            AttributePattern.of("rows").wildcard().qualify("leaf").qualify("deep"));
    var partial = Cel.compile("rows.map(r, r.leaf.deep)").partialEvaluate(activation);
    assertNull("comprehension with deep pattern should not raise a hard error", partial.error());
    assertTrue(partial.value() instanceof Unknown);
    var unknowns = partial.unknownAttributes();
    assertEquals(Set.of("rows[0].leaf.deep", "rows[1].leaf.deep"), unknowns);
    assertFalse(partial.isComplete());
    var advanced =
        partial.advanceEvaluation(
            Map.of(
                Attribute.of("rows").qualify(0).qualify("leaf").qualify("deep"), 11L,
                Attribute.of("rows").qualify(1).qualify("leaf").qualify("deep"), 22L));
    assertTrue(advanced.isComplete());
    assertEquals(List.of(11L, 22L), advanced.value());
  }

  /** A deep unknown pattern via INDEX also yields an {@link Unknown} instead of a hard error. */
  @Test
  public void deepPatternViaIndexYieldsUnknownInsteadOfNoSuchKey() {
    var activation = PartialActivation.of(Map.of(), SECRET_DEEP);
    var partial = Cel.compile("data['secret']['deep']").partialEvaluate(activation);
    assertNull("INDEX path with deep pattern should not raise a hard error", partial.error());
    assertTrue(partial.value() instanceof Unknown);
    assertFalse(partial.unknownAttributes().isEmpty());
    assertFalse(partial.isComplete());
  }

  /**
   * The optional null-coalescing path is unchanged when an ancestor resolution supplies the
   * receiver: a missing optional key still yields {@link OptionalValue#none()} rather than an
   * {@link Unknown}. The deep-pattern fallback consults the resolved-ancestor check first and
   * declines to surface an Unknown.
   */
  @Test
  public void optionalAccessPreservesNullCoalescingForResolvedAncestor() {
    var activation =
        PartialActivation.of(Map.of(), SECRET_DEEP).resolve(Map.of(Attribute.of("data"), Map.of()));
    var partial = Cel.compile("data[?'secret']").partialEvaluate(activation);
    assertNull(partial.error());
    assertEquals(OptionalValue.none(), partial.value());
    assertTrue(partial.isComplete());
    assertEquals(Set.of(), partial.unknownAttributes());
  }

  /**
   * When the receiver is missing (undeclared) and the index key is itself an {@link Unknown}, the
   * unknown key propagates instead of a hard {@code UNDECLARED} error, so resolve-and-retry can
   * supply the key's attribute. No pattern intersects the read path (the key is the unknown), so
   * the overlap fallback declines and the {@code b instanceof Unknown} arm fires.
   */
  @Test
  public void missingReceiverWithUnknownIndexKeyYieldsUnknownKey() {
    var activation =
        PartialActivation.of(
            Map.of(), AttributePattern.of("data").qualify("secret"), AttributePattern.of("k"));
    var partial = Cel.compile("data[k]").partialEvaluate(activation);
    assertNull("missing receiver with unknown key should not raise a hard error", partial.error());
    assertTrue(partial.value() instanceof Unknown);
    assertFalse(partial.isComplete());
  }

  /**
   * A deep unknown pattern intersecting a missing field on a runtime {@link ObjectType} surfaces an
   * {@link Unknown} instead of a hard {@code NO_SUCH_FIELD}; the field is reached via {@code dyn}
   * so the static type checker does not reject it.
   */
  @Test
  public void deepPatternOnRuntimeObjectMissingFieldYieldsUnknownInsteadOfNoSuchField() {
    var schema =
        new ProtoSchema(
            Map.of(
                "Msg", Map.of("count", new ProtoSchema.Field(1, 5, "", false, false, false, 0L))));
    var builder = Cel.newBuilder();
    schema.register(builder);
    var env = builder.variable("m", CelType.of("Msg")).build();
    var activation =
        PartialActivation.of(
            Map.of("m", schema.construct("Msg", Map.of("count", 1L))),
            AttributePattern.of("m").qualify("missing").qualify("deep"));
    var partial = env.compile("dyn(m).missing").partialEvaluate(activation);
    assertNull(
        "missing field on runtime object with deep pattern should not raise a hard error",
        partial.error());
    assertTrue(partial.value() instanceof Unknown);
    assertFalse(partial.isComplete());
  }

  /**
   * Two deeper-than-the-hop patterns intersecting the same SELECT path merge their {@link Unknown}
   * roots via {@link PartialState#unknownIntersect}, exercising the multi-pattern merge arm.
   */
  @Test
  public void multipleDeepPatternsIntersectingSameHopMergeUnknowns() {
    var activation =
        PartialActivation.of(
            Map.of(),
            AttributePattern.of("data").qualify("secret").qualify("deep"),
            AttributePattern.of("data").qualify("secret").qualify("deeper"));
    var partial = Cel.compile("data.secret").partialEvaluate(activation);
    assertNull("merged deep patterns should not produce a hard error", partial.error());
    assertTrue(partial.value() instanceof Unknown);
    assertFalse(partial.isComplete());
    assertFalse(partial.unknownAttributes().isEmpty());
  }

  /**
   * The intersect fallback is a no-op outside partial evaluation: a non-partial read of a missing
   * map key still raises {@code NO_SUCH_KEY}. This exercises the {@code evaluation.partial == null}
   * arm of {@link EvalContext#attributeIntersect} so the PR's new error-path call is covered. The
   * {@code evaluate(Map, EvaluationOptions)} entry is used (not the bare {@code evaluate(Map)})
   * because only the options-bearing path configures an {@link EvaluationState} with a non-null
   * {@code evaluation} and a null {@code partial}.
   */
  @Test
  public void nonPartialMissingKeyStillThrowsNoSuchKey() {
    var env = Cel.newBuilder().variable("m", CelType.map(CelType.STRING, CelType.INT)).build();
    var thrown =
        assertThrows(
            CelException.class,
            () ->
                env.compile("m.missing")
                    .evaluate(
                        Map.of("m", Map.of("other", 1L)), EvaluationOptions.newBuilder().build()));
    assertEquals(CelException.Code.NO_SUCH_KEY, thrown.code());
  }
}
