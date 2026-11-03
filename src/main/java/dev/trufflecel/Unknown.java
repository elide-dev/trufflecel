package dev.trufflecel;

import java.util.Set;
import java.util.TreeSet;

/** Explicit unknown attributes. Unknowns propagate without turning into evaluation errors. */
public record Unknown(Set<String> attributes) {
  public Unknown {
    attributes = Set.copyOf(attributes);
  }

  public static Unknown of(String attribute) {
    return new Unknown(Set.of(attribute));
  }

  Unknown merge(Unknown other) {
    var merged = new TreeSet<>(attributes);
    merged.addAll(other.attributes);
    return new Unknown(merged);
  }
}
