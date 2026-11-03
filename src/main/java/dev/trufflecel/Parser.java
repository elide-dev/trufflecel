package dev.trufflecel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Parser {
  private record Token(String kind, String text, Object value, int offset) {}

  private static final Set<String> RESERVED =
      Set.of(
          "as",
          "break",
          "const",
          "continue",
          "else",
          "for",
          "function",
          "if",
          "import",
          "let",
          "loop",
          "package",
          "namespace",
          "return",
          "var",
          "void",
          "while");
  private final String source;
  private final Environment env;
  private int cursor, depth;
  private Token token;

  Parser(String source, Environment env) {
    this.source = source;
    this.env = env;
    token = next();
  }

  ExprNode parse() {
    ExprNode result = expression(0);
    expect("EOF");
    return result;
  }

  private ExprNode expression(int min) {
    if (++depth > env.maxDepth) throw error("expression nesting limit exceeded");
    try {
      ExprNode left = unary();
      while (precedence(token.kind) >= min && precedence(token.kind) > 0) {
        Token op = take();
        int prec = precedence(op.kind);
        ExprNode right = expression(prec + 1);
        left = new ExprNode(ExprNode.Kind.CALL, op.kind, false, op.offset, left, right);
      }
      if (min == 0 && accept("?")) {
        ExprNode yes = expression(0);
        expect(":");
        ExprNode no = expression(0);
        left = new ExprNode(ExprNode.Kind.CONDITIONAL, "", null, left.offset, left, yes, no);
      }
      return left;
    } finally {
      depth--;
    }
  }

  private static int precedence(String op) {
    return switch (op) {
      case "||" -> 1;
      case "&&" -> 2;
      case "==", "!=", "<", "<=", ">", ">=", "in" -> 3;
      case "+", "-" -> 4;
      case "*", "/", "%" -> 5;
      default -> 0;
    };
  }

  private ExprNode unary() {
    if (token.kind.equals("!") || token.kind.equals("-")) {
      Token op = take();
      int count = 1;
      while (accept(op.kind)) {
        if (++count > env.maxDepth) throw error("unary nesting limit exceeded");
      }
      if (op.kind.equals("-") && count % 2 == 1 && token.kind.equals("NUMBER")) {
        Token number = take();
        return postfix(number(number, true));
      }
      ExprNode child = primary();
      child = postfix(child);
      if (count % 2 == 0) return child;
      return new ExprNode(
          ExprNode.Kind.CALL, op.kind.equals("-") ? "neg" : "!", false, op.offset, child);
    }
    return postfix(primary());
  }

  private ExprNode primary() {
    Token t = take();
    switch (t.kind) {
      case "NUMBER":
        return number(t, false);
      case "VALUE":
        return ExprNode.constant(t.value, t.offset);
      case "IDENT", ".":
        {
          String name = t.kind.equals(".") ? "." + identifier(false) : t.text;
          if (RESERVED.contains(name)) throw error("reserved identifier: " + name);
          if (accept("(")) return call(name, false, t.offset, arguments(")"));
          return new ExprNode(ExprNode.Kind.IDENT, name, null, t.offset);
        }
      case "(":
        {
          ExprNode child = expression(0);
          expect(")");
          return child;
        }
      case "[":
        return new ExprNode(
            ExprNode.Kind.LIST, "", null, t.offset, aggregate("]").toArray(ExprNode[]::new));
      case "{":
        {
          var entries = new ArrayList<ExprNode>();
          if (!accept("}"))
            do {
              entries.add(expression(0));
              expect(":");
              entries.add(expression(0));
              if (accept("}")) break;
              expect(",");
              if (accept("}")) break;
            } while (true);
          return new ExprNode(
              ExprNode.Kind.MAP, "", null, t.offset, entries.toArray(ExprNode[]::new));
        }
      default:
        throw error("expected expression, found " + t.text);
    }
  }

  private ExprNode postfix(ExprNode node) {
    int chain = 0;
    while (true) {
      if (++chain > env.maxDepth) throw error("selection nesting limit exceeded");
      if (accept(".")) {
        String name = identifier(true);
        if (accept("(")) {
          var args = arguments(")");
          args.add(0, node);
          node = call(name, true, node.offset, args);
        } else node = new ExprNode(ExprNode.Kind.SELECT, name, null, node.offset, node);
      } else if (accept("[")) {
        ExprNode index = expression(0);
        expect("]");
        node = new ExprNode(ExprNode.Kind.INDEX, "", null, node.offset, node, index);
      } else if (token.kind.equals("{") && node.qualifiedName() != null) {
        take();
        String name = node.qualifiedName();
        var names = new ArrayList<String>();
        var values = new ArrayList<ExprNode>();
        if (!accept("}"))
          do {
            String field = identifier(true);
            if (names.contains(field)) throw error("duplicate field: " + field);
            names.add(field);
            expect(":");
            values.add(expression(0));
            if (accept("}")) break;
            expect(",");
            if (accept("}")) break;
          } while (true);
        node =
            new ExprNode(
                ExprNode.Kind.OBJECT,
                name,
                names.toArray(String[]::new),
                node.offset,
                values.toArray(ExprNode[]::new));
      } else return node;
    }
  }

  private ExprNode call(String name, boolean receiver, int offset, ArrayList<ExprNode> args) {
    if (env.macros) {
      if (name.equals("has") && !receiver && args.size() == 1) {
        if (args.get(0).kind != ExprNode.Kind.SELECT) throw error("has requires a field selection");
        return new ExprNode(ExprNode.Kind.MACRO, name, null, offset, args.toArray(ExprNode[]::new));
      }
      if (receiver && Set.of("all", "exists", "exists_one", "map", "filter").contains(name)) {
        if (args.size() != 3 && !(name.equals("map") && args.size() == 4))
          throw error("invalid macro arguments");
        ExprNode variable = args.remove(1);
        if (variable.kind != ExprNode.Kind.IDENT || variable.name.contains("."))
          throw error("macro variable must be an identifier");
        return new ExprNode(
            ExprNode.Kind.MACRO, name, variable.name, offset, args.toArray(ExprNode[]::new));
      }
    }
    return new ExprNode(ExprNode.Kind.CALL, name, receiver, offset, args.toArray(ExprNode[]::new));
  }

  private ArrayList<ExprNode> arguments(String end) {
    var result = new ArrayList<ExprNode>();
    if (!accept(end)) {
      do {
        result.add(expression(0));
      } while (accept(","));
      expect(end);
    }
    return result;
  }

  private ArrayList<ExprNode> aggregate(String end) {
    var result = new ArrayList<ExprNode>();
    if (!accept(end))
      do {
        result.add(expression(0));
        if (accept(end)) break;
        expect(",");
        if (accept(end)) break;
      } while (true);
    return result;
  }

  private ExprNode number(Token t, boolean negative) {
    String s = t.text;
    try {
      boolean unsigned = s.endsWith("u") || s.endsWith("U");
      if (unsigned) s = s.substring(0, s.length() - 1);
      boolean hex = s.startsWith("0x") || s.startsWith("0X");
      if (!hex && (s.contains(".") || s.contains("e") || s.contains("E"))) {
        if (unsigned) throw error("floating point uint literal");
        double d = Double.parseDouble((negative ? "-" : "") + s);
        if (!Double.isFinite(d)) throw error("floating point literal out of range");
        return ExprNode.constant(d, t.offset);
      }
      if (unsigned && negative) throw error("negative unsigned literal");
      int base = hex ? 16 : 10;
      if (hex) s = s.substring(2);
      if (unsigned) return ExprNode.constant(new UInt(Long.parseUnsignedLong(s, base)), t.offset);
      return ExprNode.constant(Long.parseLong((negative ? "-" : "") + s, base), t.offset);
    } catch (NumberFormatException e) {
      throw new CelException(
          CelException.Code.SYNTAX, "invalid or out-of-range number: " + t.text, t.offset);
    }
  }

  private String identifier(boolean selector) {
    if (!(token.kind.equals("IDENT") || selector && token.kind.equals("QUOTED"))
        || (!selector && RESERVED.contains(token.text))) throw error("expected identifier");
    return take().text;
  }

  private boolean accept(String kind) {
    if (!token.kind.equals(kind)) return false;
    take();
    return true;
  }

  private void expect(String kind) {
    if (!accept(kind)) throw error("expected " + kind + ", found " + token.text);
  }

  private Token take() {
    Token result = token;
    token = next();
    return result;
  }

  private CelException error(String message) {
    return new CelException(
        CelException.Code.SYNTAX, message, token == null ? cursor : token.offset);
  }

  private Token next() {
    while (cursor < source.length()) {
      char c = source.charAt(cursor);
      if (" \t\r\n\f".indexOf(c) >= 0) {
        cursor++;
        continue;
      }
      if (source.startsWith("//", cursor)) {
        while (cursor < source.length() && source.charAt(cursor) != '\n') cursor++;
        continue;
      }
      break;
    }
    int start = cursor;
    if (cursor == source.length()) return new Token("EOF", "", null, cursor);
    char c = source.charAt(cursor);
    int quote = cursor;
    boolean bytes = false, raw = false;
    if (c == 'b' || c == 'B') {
      bytes = true;
      quote++;
    }
    if (quote < source.length() && (source.charAt(quote) == 'r' || source.charAt(quote) == 'R')) {
      raw = true;
      quote++;
    }
    if (quote < source.length() && (source.charAt(quote) == '\'' || source.charAt(quote) == '"')) {
      cursor = quote;
      Object literal = string(bytes, raw);
      return new Token("VALUE", source.substring(start, cursor), literal, start);
    }
    if (c == '`') {
      cursor++;
      int begin = cursor;
      while (cursor < source.length() && source.charAt(cursor) != '`') {
        if (source.charAt(cursor) == '\n'
            || source.charAt(cursor) == '\r'
            || source.charAt(cursor) == '\\') throw error("invalid quoted field");
        cursor++;
      }
      if (cursor == source.length() || cursor == begin) throw error("invalid quoted field");
      String field = source.substring(begin, cursor++);
      return new Token("QUOTED", field, null, start);
    }
    if (asciiLetter(c) || c == '_') {
      cursor++;
      while (cursor < source.length()
          && (asciiLetter(source.charAt(cursor))
              || asciiDigit(source.charAt(cursor))
              || source.charAt(cursor) == '_')) cursor++;
      String s = source.substring(start, cursor);
      return switch (s) {
        case "true" -> new Token("VALUE", s, true, start);
        case "false" -> new Token("VALUE", s, false, start);
        case "null" -> new Token("VALUE", s, CelNull.INSTANCE, start);
        case "in" -> new Token("in", s, null, start);
        default -> new Token("IDENT", s, null, start);
      };
    }
    if (asciiDigit(c)
        || (c == '.' && cursor + 1 < source.length() && asciiDigit(source.charAt(cursor + 1)))) {
      if (source.startsWith("0x", cursor) || source.startsWith("0X", cursor)) {
        cursor += 2;
        while (cursor < source.length() && digit(source.charAt(cursor), 16) >= 0) cursor++;
      } else {
        while (cursor < source.length() && asciiDigit(source.charAt(cursor))) cursor++;
        if (cursor < source.length()
            && source.charAt(cursor) == '.'
            && cursor + 1 < source.length()
            && asciiDigit(source.charAt(cursor + 1))) {
          cursor++;
          while (cursor < source.length() && asciiDigit(source.charAt(cursor))) cursor++;
        }
        if (cursor < source.length()
            && (source.charAt(cursor) == 'e' || source.charAt(cursor) == 'E')) {
          cursor++;
          if (cursor < source.length()
              && (source.charAt(cursor) == '+' || source.charAt(cursor) == '-')) cursor++;
          while (cursor < source.length() && asciiDigit(source.charAt(cursor))) cursor++;
        }
      }
      if (cursor < source.length()
          && (source.charAt(cursor) == 'u' || source.charAt(cursor) == 'U')) cursor++;
      return new Token("NUMBER", source.substring(start, cursor), null, start);
    }
    for (String op : List.of("&&", "||", "==", "!=", "<=", ">="))
      if (source.startsWith(op, cursor)) {
        cursor += 2;
        return new Token(op, op, null, start);
      }
    if ("+-*/%<>!?:.,()[]{}".indexOf(c) >= 0) {
      cursor++;
      return new Token(String.valueOf(c), String.valueOf(c), null, start);
    }
    throw new CelException(CelException.Code.SYNTAX, "unexpected character: " + c, cursor);
  }

  private static boolean asciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static int digit(char c, int radix) {
    if (c >= '0' && c <= '9') return c - '0' < radix ? c - '0' : -1;
    if (radix == 16 && c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (radix == 16 && c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  }

  private static boolean asciiLetter(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }

  private Object string(boolean bytes, boolean raw) {
    char quote = source.charAt(cursor++);
    boolean triple =
        cursor + 1 < source.length()
            && source.charAt(cursor) == quote
            && source.charAt(cursor + 1) == quote;
    if (triple) cursor += 2;
    String end = triple ? String.valueOf(quote).repeat(3) : String.valueOf(quote);
    var text = new StringBuilder();
    var binary = new ByteArrayOutputStream();
    while (cursor < source.length()) {
      if (source.startsWith(end, cursor)) {
        cursor += end.length();
        return bytes ? new Bytes(binary.toByteArray()) : text.toString();
      }
      int cp = source.codePointAt(cursor);
      cursor += Character.charCount(cp);
      if (!triple && (cp == '\n' || cp == '\r')) throw error("newline in string literal");
      boolean byteEscape = false;
      if (cp == '\\' && !raw) {
        if (cursor == source.length()) throw error("unterminated escape");
        char escape = source.charAt(cursor++);
        cp =
            switch (escape) {
              case 'a' -> 7;
              case 'b' -> 8;
              case 'f' -> 12;
              case 'n' -> 10;
              case 'r' -> 13;
              case 't' -> 9;
              case 'v' -> 11;
              case '\\', '?', '"', '\'', '`' -> escape;
              case 'x', 'X' -> {
                byteEscape = true;
                yield digits(2, 16);
              }
              case 'u', 'U' -> {
                if (bytes) throw error("Unicode escape in bytes literal");
                yield digits(escape == 'u' ? 4 : 8, 16);
              }
              default -> {
                if (escape < '0' || escape > '3') throw error("invalid escape");
                byteEscape = true;
                yield (escape - '0') * 64 + digits(2, 8);
              }
            };
      } else if (cp == '\\' && raw && cursor < source.length()) {
        // A raw backslash preserves (and protects) the next character, including a quote.
        text.append('\\');
        binary.write('\\');
        cp = source.codePointAt(cursor);
        cursor += Character.charCount(cp);
      }
      if (!Character.isValidCodePoint(cp) || cp >= 0xd800 && cp <= 0xdfff)
        throw error("invalid Unicode scalar");
      if (bytes) {
        if (byteEscape) binary.write(cp);
        else binary.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
      } else text.appendCodePoint(cp);
    }
    throw error("unterminated string literal");
  }

  private int digits(int count, int radix) {
    long value = 0;
    for (int i = 0; i < count; i++) {
      if (cursor == source.length()) throw error("truncated escape");
      int d = digit(source.charAt(cursor++), radix);
      if (d < 0) throw error("invalid escape digit");
      value = value * radix + d;
    }
    if (value > 0x10ffff) throw error("invalid Unicode scalar");
    return (int) value;
  }
}
