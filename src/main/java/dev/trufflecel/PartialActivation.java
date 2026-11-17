package dev.trufflecel;

import java.util.*;

/** Owned bindings plus unknown patterns and immutable, incrementally supplied attribute values. */
public final class PartialActivation {
  /** Stackless argument error: avoids filling a stack trace on a Native Image slow path. */
  private static final class StacklessIllegalArgumentException extends IllegalArgumentException {
    StacklessIllegalArgumentException(String message) {
      super(message);
    }

    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  }

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
      // Attribute's record equality uses List.equals, which disagrees with same()/prefixOf() for a
      // non-negative Long and a UInt with equal bits: two such keys can coexist as distinct map
      // entries with one resolve() call. The size-based sort below cannot break that tie, and the
      // last one in caller iteration order would silently win. Collapse same()-equivalent supplies
      // to a single canonical entry; reject conflicting values so the outcome is caller-order
      // independent instead of silently dependent on insertion order.
      Attribute duplicate = null;
      for (var suppliedEntry : supplied.entrySet()) {
        Attribute existing = suppliedEntry.getKey();
        if (attribute.prefixOf(existing) && existing.prefixOf(attribute)) {
          duplicate = existing;
          break;
        }
      }
      if (duplicate != null) {
        if (!Values.equal(supplied.get(duplicate), value))
          throw new StacklessIllegalArgumentException(
              "conflicting resolutions for the same attribute: " + attribute);
        // Same value: keep the existing canonical key and discard the duplicate.
      } else supplied.put(attribute, value);
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
