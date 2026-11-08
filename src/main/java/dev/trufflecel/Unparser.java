package dev.trufflecel;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Source reconstruction from syntax, including original macro calls when available. */
public final class Unparser {
  private Unparser() {}

  public static String unparse(Ast ast) {
    return expression(ast.root());
  }

  private static String expression(Ast.Node node) {
    if (node.macroCall() != null) return expression(node.macroCall());
    List<Ast.Node> children = node.children();
    return switch (node.kind()) {
      case CONSTANT -> literal(node.value());
      case IDENT -> node.name();
      case SELECT ->
          node.extensionSelect()
              ? "proto.getExt(" + expression(children.get(0)) + ", " + node.name() + ")"
              : "("
                  + expression(children.get(0))
                  + ")."
                  + (Boolean.TRUE.equals(node.value()) ? "?" : "")
                  + field(node.name());
      case INDEX ->
          "("
              + expression(children.get(0))
              + ")["
              + (Boolean.TRUE.equals(node.value()) ? "?" : "")
              + expression(children.get(1))
              + "]";
      case CONDITIONAL ->
          "("
              + expression(children.get(0))
              + " ? "
              + expression(children.get(1))
              + " : "
              + expression(children.get(2))
              + ")";
      case CALL -> call(node);
      case LIST ->
          "[" + children.stream().map(Unparser::entry).collect(Collectors.joining(", ")) + "]";
      case MAP -> {
        var entries = new ArrayList<String>();
        for (int i = 0; i < children.size(); i += 2)
          entries.add(
              (children.get(i + 1).optionalEntry() ? "?" : "")
                  + expression(children.get(i))
                  + ": "
                  + expression(children.get(i + 1)));
        yield "{" + String.join(", ", entries) + "}";
      }
      case OBJECT -> {
        var fields = (List<?>) node.value();
        var entries = new ArrayList<String>();
        for (int i = 0; i < children.size(); i++)
          entries.add(
              (children.get(i).optionalEntry() ? "?" : "")
                  + field((String) fields.get(i))
                  + ": "
                  + expression(children.get(i)));
        yield node.name() + "{" + String.join(", ", entries) + "}";
      }
      case BLOCK_INDEX -> "cel.index(" + node.value() + ")";
      case BLOCK ->
          "cel.block(["
              + children.subList(0, children.size() - 1).stream()
                  .map(Unparser::expression)
                  .collect(Collectors.joining(", "))
              + "], "
              + expression(children.get(children.size() - 1))
              + ")";
      case MACRO -> expression(macroCall(node));
      case COMPREHENSION ->
          throw new IllegalArgumentException(
              "A comprehension has no CEL surface syntax; original macro_calls are required");
    };
  }

  static Ast.Node macroCall(Ast.Node node) {
    var args = new ArrayList<Ast.Node>();
    if (node.name().equals("has")) {
      Ast.Node select = node.children().get(0);
      if (select.extensionSelect())
        return Ast.Node.call(
            "proto.hasExt", select.children().get(0), Ast.Node.identifier(select.name()));
      return Ast.Node.call("has", select);
    }
    for (Object name : (List<?>) node.value()) args.add(Ast.Node.identifier((String) name));
    int start = node.name().equals("bind") ? 0 : 1;
    args.addAll(node.children().subList(start, node.children().size()));
    return node.name().equals("bind")
        ? Ast.Node.call("cel.bind", args.toArray(Ast.Node[]::new))
        : Ast.Node.receiverCall(node.children().get(0), node.name(), args.toArray(Ast.Node[]::new));
  }

  private static String entry(Ast.Node node) {
    return (node.optionalEntry() ? "?" : "") + expression(node);
  }

  private static String call(Ast.Node node) {
    var args = node.children();
    String name = node.name();
    if (!Boolean.TRUE.equals(node.value())) {
      if ((name.equals("!") || name.equals("neg")) && args.size() == 1)
        return (name.equals("neg") ? "-" : "!") + "(" + expression(args.get(0)) + ")";
      if (Set.of("+", "-", "*", "/", "%", "<", "<=", ">", ">=", "==", "!=", "&&", "||", "in")
              .contains(name)
          && args.size() == 2)
        return "(" + expression(args.get(0)) + " " + name + " " + expression(args.get(1)) + ")";
      return name
          + "("
          + args.stream().map(Unparser::expression).collect(Collectors.joining(", "))
          + ")";
    }
    return "("
        + expression(args.get(0))
        + ")."
        + name
        + "("
        + args.subList(1, args.size()).stream()
            .map(Unparser::expression)
            .collect(Collectors.joining(", "))
        + ")";
  }

  private static String field(String field) {
    return field.matches("[a-zA-Z_][a-zA-Z_0-9]*") ? field : "`" + field.replace("`", "\\`") + "`";
  }

  private static String literal(Object value) {
    if (value == CelNull.INSTANCE) return "null";
    if (value instanceof String text) return quote(text);
    if (value instanceof Bytes bytes) {
      var text = new StringBuilder("b\"");
      for (byte b : bytes.toByteArray())
        text.append(String.format(Locale.ROOT, "\\x%02x", b & 255));
      return text.append('"').toString();
    }
    if (value instanceof UInt number) return Long.toUnsignedString(number.bits()) + "u";
    if (value instanceof Double number && !Double.isFinite(number))
      return "double(" + quote(number.toString()) + ")";
    if (value instanceof Instant instant) return "timestamp(" + quote(instant.toString()) + ")";
    if (value instanceof Duration duration)
      return "duration(" + quote(Functions.durationString(duration)) + ")";
    if (value instanceof CelType type) return type.name();
    return value.toString();
  }

  private static String quote(String value) {
    var text = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> text.append("\\\\");
        case '"' -> text.append("\\\"");
        case '\n' -> text.append("\\n");
        case '\r' -> text.append("\\r");
        case '\t' -> text.append("\\t");
        default -> {
          if (c < 32 || c == 127) text.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else text.append(c);
        }
      }
    }
    return text.append('"').toString();
  }
}
