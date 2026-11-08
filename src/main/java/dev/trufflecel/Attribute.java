package dev.trufflecel;

import java.util.*;

/** An immutable qualified CEL variable path. Map keys and list indices are typed qualifiers. */
public record Attribute(String variable, List<Object> qualifiers) {
  public Attribute {
    if (Objects.requireNonNull(variable).isEmpty())
      throw new IllegalArgumentException("empty variable");
    var normalized = new ArrayList<Object>();
    for (Object qualifier : qualifiers) normalized.add(normalize(qualifier));
    qualifiers = List.copyOf(normalized);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  public static Attribute of(String variable) {
    return new Attribute(variable, List.of());
  }

  public Attribute qualify(Object qualifier) {
    var path = new ArrayList<>(qualifiers);
    path.add(normalize(qualifier));
    return new Attribute(variable, path);
  }

  static Object normalize(Object qualifier) {
    if (qualifier instanceof Byte || qualifier instanceof Short || qualifier instanceof Integer)
      return ((Number) qualifier).longValue();
    if (qualifier instanceof String
        || qualifier instanceof Long
        || qualifier instanceof Boolean
        || qualifier instanceof UInt) return qualifier;
    throw new IllegalArgumentException("attribute qualifier must be string, int, uint or bool");
  }

  static boolean same(Object left, Object right) {
    if (Objects.equals(left, right)) return true;
    if (left instanceof Long number && right instanceof UInt unsigned)
      return number >= 0 && number == unsigned.bits();
    if (right instanceof Long number && left instanceof UInt unsigned)
      return number >= 0 && number == unsigned.bits();
    return false;
  }

  boolean prefixOf(Attribute path) {
    if (!variable.equals(path.variable) || qualifiers.size() > path.qualifiers.size()) return false;
    for (int i = 0; i < qualifiers.size(); i++)
      if (!same(qualifiers.get(i), path.qualifiers.get(i))) return false;
    return true;
  }

  @Override
  public String toString() {
    var text = new StringBuilder(variable);
    for (Object qualifier : qualifiers) {
      if (qualifier instanceof String field) {
        if (field.matches("[A-Za-z_][A-Za-z_0-9]*")) text.append('.').append(field);
        else
          text.append("['")
              .append(
                  field
                      .replace("\\", "\\\\")
                      .replace("'", "\\'")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t"))
              .append("']");
      } else if (qualifier instanceof UInt unsigned)
        text.append('[').append(Long.toUnsignedString(unsigned.bits())).append("u]");
      else text.append('[').append(qualifier).append(']');
    }
    return text.toString();
  }
}
