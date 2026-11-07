package dev.trufflecel;

import java.util.List;

/** CEL types, including parameterized lists, maps, and explicitly registered object types. */
public record CelType(String name, List<CelType> parameters) {
  public static final CelType DYN = of("dyn"),
      BOOL = of("bool"),
      INT = of("int"),
      UINT = of("uint"),
      DOUBLE = of("double"),
      STRING = of("string"),
      BYTES = of("bytes"),
      NULL = of("null_type"),
      TYPE = of("type"),
      DURATION = of("google.protobuf.Duration"),
      TIMESTAMP = of("google.protobuf.Timestamp");
  static final CelType UNBOUND = of("$unbound");

  public CelType {
    parameters = List.copyOf(parameters);
  }

  public static CelType of(String name) {
    return new CelType(name, List.of());
  }

  public static CelType list(CelType element) {
    return new CelType("list", List.of(element));
  }

  public static CelType map(CelType key, CelType value) {
    return new CelType("map", List.of(key, value));
  }

  public static CelType type(CelType value) {
    return new CelType("type", List.of(value));
  }

  public static CelType parameter(String name) {
    return of("$" + name);
  }

  public static CelType wrapper(CelType type) {
    return new CelType("wrapper", List.of(type));
  }

  CelType unwrapped() {
    return name.equals("wrapper") ? parameters.get(0) : this;
  }

  CelType finish() {
    return name.startsWith("$")
        ? DYN
        : new CelType(name, parameters.stream().map(CelType::finish).toList());
  }

  public boolean isDyn() {
    return name.equals("dyn") || name.startsWith("$");
  }

  public boolean isNumeric() {
    return equals(INT) || equals(UINT) || equals(DOUBLE);
  }

  boolean accepts(CelType other) {
    if (isDyn() || other.isDyn()) return true;
    if (name.equals("wrapper")) return other.equals(NULL) || unwrapped().accepts(other.unwrapped());
    if (other.name.equals("wrapper")) return accepts(other.unwrapped());
    if (!name.equals(other.name)) return false;
    if (parameters.isEmpty() || other.parameters.isEmpty()) return true;
    if (parameters.size() != other.parameters.size()) return false;
    for (int i = 0; i < parameters.size(); i++)
      if (!parameters.get(i).accepts(other.parameters.get(i))) return false;
    return true;
  }

  static CelType join(CelType a, CelType b) {
    if (a.equals(b)) return a;
    if (a.name.startsWith("$")) return b;
    if (b.name.startsWith("$")) return a;
    if (a.equals(NULL) && b.nullable()) return b;
    if (b.equals(NULL) && a.nullable()) return a;
    if (a.name.equals("wrapper") && a.unwrapped().equals(b)) return a;
    if (b.name.equals("wrapper") && b.unwrapped().equals(a)) return b;
    if (a.name.equals("wrapper") && b.name.equals("wrapper")) return DYN;
    if (a.name.equals(b.name)) {
      int n = Math.max(a.parameters.size(), b.parameters.size());
      var args = new java.util.ArrayList<CelType>();
      for (int i = 0; i < n; i++)
        args.add(
            join(
                i < a.parameters.size() ? a.parameters.get(i) : UNBOUND,
                i < b.parameters.size() ? b.parameters.get(i) : UNBOUND));
      return new CelType(a.name, args);
    }
    return DYN;
  }

  boolean nullable() {
    return name.equals("wrapper")
        || name.equals("optional_type")
        || name.contains(".")
        || !parameters.isEmpty()
            && !name.equals("list")
            && !name.equals("map")
            && !name.equals("type");
  }

  @Override
  public String toString() {
    return name + (parameters.isEmpty() ? "" : parameters.toString());
  }
}
