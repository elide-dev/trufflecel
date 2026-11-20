package dev.trufflecel;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/** Attribute metadata is per evaluation and never stored in program or caller values. */
final class PartialState {
  static final Object NONE = new Object();
  static final Object MISSING = new Object();

  record Slot(Object value, Attribute attribute) {}

  private final PartialActivation activation;
  Attribute attribute;

  PartialState(PartialActivation activation) {
    this.activation = activation;
  }

  @TruffleBoundary
  Object override(Attribute path, boolean presence) {
    attribute = path;
    if (path == null) return NONE;
    Attribute parent = null;
    // Resolution insertion order places ancestors before any retained child refinements.
    for (Attribute candidate : activation.resolutions.keySet())
      if (candidate.prefixOf(path)) parent = candidate;
    // A resolved ancestor has already supplied the receiver to its selector. Let the normal
    // selector read it once, preserving optional access, presence and adapter callback semantics.
    if (parent != null)
      return parent.qualifiers().size() == path.qualifiers().size()
          ? presence ? true : activation.resolutions.get(parent)
          : NONE;
    Unknown unknown = null;
    for (AttributePattern pattern : activation.patterns)
      if (pattern.matches(path)) {
        var root =
            new Attribute(
                path.variable(), path.qualifiers().subList(0, pattern.qualifiers().size()));
        Unknown next = Unknown.of(root.toString());
        unknown = unknown == null ? next : unknown.merge(next);
      }
    return unknown == null ? NONE : unknown;
  }

  /**
   * Overlap-based fallback used at SELECT/INDEX hops when {@link #override} did not match a
   * shallower-or-equal pattern. Returns an {@link Unknown} rooted at the longest common prefix of
   * the hop path and each intersecting pattern (i.e. at the hop path when the pattern is deeper,
   * and at the pattern granularity when the pattern is shallower-or-equal, mirroring {@code
   * override}), so that deep unknown patterns with missing ancestors surface an {@code Unknown} for
   * the documented resolve-and-retry loop instead of a hard {@code UNDECLARED} / {@code
   * NO_SUCH_KEY} / {@code NO_SUCH_FIELD} error. A resolution that supplies (or has supplied) the
   * receiver is left to steer the normal selector read; the caller falls back to its existing error
   * path. This matches the bidirectional overlap semantics already used by {@link #consume} and
   * {@link #containsUnknown}, and mirrors cel-go / cel-java where the pattern check runs against
   * the resolved attribute path before the activation is consulted.
   */
  @TruffleBoundary
  Object unknownIntersect(Attribute path) {
    if (path == null) return NONE;
    // A resolution that is an ancestor of (or equal to) the path has already supplied the
    // receiver to its selector; surface NO_SUCH_KEY / NO_SUCH_FIELD rather than masking the
    // missing descendant with an Unknown.
    for (Attribute candidate : activation.resolutions.keySet())
      if (candidate.prefixOf(path)) return NONE;
    Unknown unknown = null;
    for (AttributePattern pattern : activation.patterns)
      if (pattern.intersects(path)) {
        int common = Math.min(pattern.qualifiers().size(), path.qualifiers().size());
        var root = new Attribute(path.variable(), path.qualifiers().subList(0, common));
        Unknown next = Unknown.of(root.toString());
        unknown = unknown == null ? next : unknown.merge(next);
      }
    return unknown == null ? NONE : unknown;
  }

  @TruffleBoundary
  Object consume(Object value) {
    Attribute path = attribute;
    if (path == null || value instanceof Unknown) return value;
    for (Attribute resolved : activation.resolutions.keySet())
      if (resolved.prefixOf(path)) return value;
    for (AttributePattern pattern : activation.patterns)
      if (pattern.intersects(path)) return Unknown.of(path.toString());
    return value;
  }

  @TruffleBoundary
  boolean containsUnknown(Attribute path) {
    for (AttributePattern pattern : activation.patterns) if (pattern.intersects(path)) return true;
    return false;
  }
}
