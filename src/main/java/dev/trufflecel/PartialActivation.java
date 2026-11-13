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

  private static void requireKnown(Object value) {
    if (value instanceof Unknown || value instanceof CelException)
      throw new IllegalArgumentException("resolution must contain only known values");
    if (value instanceof List<?> list) for (Object element : list) requireKnown(element);
    else if (value instanceof Map<?, ?> map)
      for (var entry : map.entrySet()) {
        requireKnown(entry.getKey());
        requireKnown(entry.getValue());
      }
    else if (value instanceof OptionalValue optional && optional.present())
      requireKnown(optional.value());
  }

  /** Return a new snapshot; this activation remains unchanged and safe for concurrent reuse. */
  public PartialActivation resolve(Map<Attribute, ?> values) {
    var resolved = new LinkedHashMap<>(resolutions);
    var supplied = new LinkedHashMap<Attribute, Object>();
    for (var entry : values.entrySet()) {
      Attribute attribute = Objects.requireNonNull(entry.getKey());
      boolean applicable = false;
      for (AttributePattern pattern : patterns) applicable |= pattern.intersects(attribute);
      if (!applicable)
        throw new IllegalArgumentException("attribute is outside unknown patterns: " + attribute);
      Object value = Values.in(entry.getValue());
      requireKnown(value);
      supplied.put(attribute, value);
    }
    // A newly supplied parent replaces previous child resolutions. A child may refine a parent.
    // Drop only pre-existing resolutions covered by a supplied attribute, then insert the
    // supplied entries ancestors before descendants so the outcome is caller-order independent.
    for (Attribute attribute : supplied.keySet()) resolved.keySet().removeIf(attribute::prefixOf);
    supplied.entrySet().stream()
        .sorted(Comparator.comparingInt(e -> e.getKey().qualifiers().size()))
        .forEach(e -> resolved.put(e.getKey(), e.getValue()));
    return new PartialActivation(activation, patterns, Collections.unmodifiableMap(resolved));
  }
}
