package dev.trufflecel;

import java.util.*;

/** Bounded CEL type declarations, including list<T>, map<K,V> and optional<T>. */
public final class TypeSyntax {
  private TypeSyntax() {}

  public static CelType parse(String source) {
    Objects.requireNonNull(source);
    if (source.length() > 4096) throw new IllegalArgumentException("type declaration is too long");
    var parser = new Reader(source);
    CelType type = parser.type(0);
    parser.space();
    if (parser.position != source.length()) throw parser.error();
    return type;
  }

  public static String format(CelType type) {
    return format(type, 0);
  }

  private static String format(CelType type, int depth) {
    if (depth > 64) throw new IllegalArgumentException("type nesting limit exceeded");
    String name = type.name().equals("optional_type") ? "optional" : type.name();
    if (type.parameters().isEmpty()) return name;
    var parameters = new ArrayList<String>();
    for (CelType parameter : type.parameters()) parameters.add(format(parameter, depth + 1));
    return name + "<" + String.join(", ", parameters) + ">";
  }

  private static final class Reader {
    private final String source;
    private int position;

    Reader(String source) {
      this.source = source;
    }

    private IllegalArgumentException error() {
      return new IllegalArgumentException("invalid CEL type at offset " + position);
    }

    private void space() {
      while (position < source.length() && Character.isWhitespace(source.charAt(position)))
        position++;
    }

    private boolean take(char ch) {
      space();
      if (position == source.length() || source.charAt(position) != ch) return false;
      position++;
      return true;
    }

    private CelType type(int depth) {
      if (depth > 64) throw error();
      space();
      int start = position;
      while (position < source.length()) {
        char ch = source.charAt(position);
        if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == '$')) break;
        position++;
      }
      if (start == position) throw error();
      String name = source.substring(start, position);
      if (!name.matches("\\$?[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)*")) throw error();
      name =
          switch (name) {
            case "optional" -> "optional_type";
            case "timestamp" -> "google.protobuf.Timestamp";
            case "duration" -> "google.protobuf.Duration";
            default -> name;
          };
      var parameters = new ArrayList<CelType>();
      if (take('<')) {
        do parameters.add(type(depth + 1));
        while (take(','));
        if (!take('>')) throw error();
      }
      int arity =
          switch (name) {
            case "map" -> 2;
            case "list", "optional_type", "wrapper" -> 1;
            case "type" -> parameters.isEmpty() ? 0 : 1;
            default -> 0;
          };
      if (parameters.size() != arity || name.startsWith("$") && name.contains(".")) throw error();
      return new CelType(name, parameters);
    }
  }
}
