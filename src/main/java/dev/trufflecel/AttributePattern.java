package dev.trufflecel;

import java.util.*;

/** A qualified unknown-attribute pattern; a wildcard matches exactly one field or index. */
public record AttributePattern(String variable, List<Object> qualifiers) {
  public enum Wildcard {
    ANY
  }

  public AttributePattern {
    if (Objects.requireNonNull(variable).isEmpty())
      throw new IllegalArgumentException("empty variable");
    var normalized = new ArrayList<Object>();
    for (Object qualifier : qualifiers)
      normalized.add(qualifier == Wildcard.ANY ? qualifier : Attribute.normalize(qualifier));
    qualifiers = List.copyOf(normalized);
  }

  public static AttributePattern of(String variable) {
    return new AttributePattern(variable, List.of());
  }

  public AttributePattern qualify(Object qualifier) {
    var path = new ArrayList<>(qualifiers);
    path.add(qualifier);
    return new AttributePattern(variable, path);
  }

  public AttributePattern wildcard() {
    return qualify(Wildcard.ANY);
  }

  /** True when the pattern names this attribute or one of its ancestors. */
  public boolean matches(Attribute attribute) {
    return qualifiers.size() <= attribute.qualifiers().size() && intersects(attribute);
  }

  /** True when this attribute or a descendant may be unknown. */
  public boolean intersects(Attribute attribute) {
    if (!variable.equals(attribute.variable())) return false;
    for (int i = 0; i < Math.min(qualifiers.size(), attribute.qualifiers().size()); i++) {
      Object qualifier = qualifiers.get(i);
      if (qualifier != Wildcard.ANY && !Attribute.same(qualifier, attribute.qualifiers().get(i)))
        return false;
    }
    return true;
  }
}
