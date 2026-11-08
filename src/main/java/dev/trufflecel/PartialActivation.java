package dev.trufflecel;

import java.util.*;

/** Owned bindings plus unknown patterns and immutable, incrementally supplied attribute values. */
public final class PartialActivation {
  final Activation activation;
  final List<AttributePattern> patterns;
  final Map<Attribute, Object> resolutions;

  private PartialActivation(
      Activation activation, List<AttributePattern> patterns, Map<Attribute, Object> resolutions) {
    this.activation = activation;
    this.patterns = patterns;
    this.resolutions = resolutions;
  }

  public static PartialActivation of(Map<String, ?> bindings, AttributePattern... unknowns) {
    return new PartialActivation(Activation.snapshot(bindings), List.of(unknowns), Map.of());
  }

  public List<AttributePattern> unknownPatterns() {
    return patterns;
  }

  /** Return a new snapshot; this activation remains unchanged and safe for concurrent reuse. */
  public PartialActivation resolve(Map<Attribute, ?> values) {
    var resolved = new LinkedHashMap<>(resolutions);
    for (var entry : values.entrySet()) {
      Attribute attribute = Objects.requireNonNull(entry.getKey());
      boolean applicable = false;
      for (AttributePattern pattern : patterns) applicable |= pattern.intersects(attribute);
      if (!applicable)
        throw new IllegalArgumentException("attribute is outside unknown patterns: " + attribute);
      Object value = Values.in(entry.getValue());
      if (value instanceof Unknown || value instanceof CelException)
        throw new IllegalArgumentException("resolution must be a known value");
      // A newly supplied parent replaces previous child resolutions. A child may refine a parent.
      resolved.keySet().removeIf(attribute::prefixOf);
      resolved.put(attribute, value);
    }
    return new PartialActivation(activation, patterns, Collections.unmodifiableMap(resolved));
  }
}
