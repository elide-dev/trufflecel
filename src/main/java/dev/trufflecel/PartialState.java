package dev.trufflecel;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/** Attribute metadata is per evaluation and never stored in program or caller values. */
final class PartialState {
  static final Object NONE = new Object();
  static final Object MISSING = new Object();

  record Slot(Object value, Attribute attribute) {}

  private final Environment environment;
  private final PartialActivation activation;
  Attribute attribute;

  PartialState(Environment environment, PartialActivation activation) {
    this.environment = environment;
    this.activation = activation;
  }

  @TruffleBoundary
  Object override(Attribute path, boolean presence) {
    attribute = path;
    if (path == null) return NONE;
    Attribute parent = null;
    for (Attribute candidate : activation.resolutions.keySet())
      if (candidate.prefixOf(path)
          && (parent == null || candidate.qualifiers().size() > parent.qualifiers().size()))
        parent = candidate;
    if (parent != null) {
      Object value = activation.resolutions.get(parent);
      try {
        for (int i = parent.qualifiers().size(); i < path.qualifiers().size(); i++)
          value =
              select(
                  value, path.qualifiers().get(i), presence && i == path.qualifiers().size() - 1);
        return presence && parent.qualifiers().size() == path.qualifiers().size() ? true : value;
      } catch (CelException error) {
        if (presence
            && (error.code() == CelException.Code.NO_SUCH_FIELD
                || error.code() == CelException.Code.NO_SUCH_KEY
                || error.code() == CelException.Code.INDEX)) return false;
        throw error;
      }
    }
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

  private Object select(Object value, Object qualifier, boolean presence) {
    if (qualifier instanceof String field)
      for (ObjectType type : environment.runtimeObjectTypes)
        if (type.accepts(value)) {
          if (!type.fields().containsKey(field))
            throw Values.error(CelException.Code.NO_SUCH_FIELD, "no such field: " + field);
          return presence ? type.has(value, field) : Values.in(type.get(value, field));
        }
    Object result = Values.index(value, qualifier);
    return presence ? true : result;
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
