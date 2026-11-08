package dev.trufflecel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Parser {
  private record Token(String kind, String text, Object value, int offset) {}

  static final Set<String> RESERVED =
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
  private final int baseOffset;
  private int cursor, depth, consumedEnd;
  private Token token;
  private final List<CelException.Issue> issues = new ArrayList<>();

  Parser(String source, Environment env) {
    this(source, env, 0);
  }

  Parser(String source, Environment env, int baseOffset) {
    if (baseOffset < 0 || baseOffset > Integer.MAX_VALUE - source.length())
      throw new IllegalArgumentException("invalid source base offset");
    this.source = source;
    this.env = env;
    this.baseOffset = baseOffset;
    token = next();
  }

  ExprNode parse() {
    ExprNode result;
    try {
      result = expression(0);
      expect("EOF");
    } catch (CelException failure) {
      if (issues.isEmpty() || failure.code() != CelException.Code.SYNTAX) throw failure;
      record(failure);
      throw new CelException(issues);
    }
    if (!issues.isEmpty()) throw new CelException(issues);
    return result;
  }

  private void record(CelException failure) {
    if (failure.code() != CelException.Code.SYNTAX || failure.issues().size() > 1) throw failure;
    issues.add(failure.issues().get(0));
    if (issues.size() == 100) throw new CelException(issues);
  }

  /** Resume at the next entry boundary, retaining nesting and all configured work limits. */
  private void recover(CelException failure, String end) {
    record(failure);
    int nesting = 0;
    while (!token.kind.equals("EOF")) {
      if (nesting == 0 && (token.kind.equals(",") || token.kind.equals(end))) return;
      if (Set.of("(", "[", "{").contains(token.kind)) nesting++;
      else if (nesting > 0 && Set.of(")", "]", "}").contains(token.kind)) nesting--;
      take();
    }
  }

  private ExprNode entryExpression(String end) {
    try {
      return expression(0);
    } catch (CelException failure) {
      recover(failure, end);
      return constant(CelNull.INSTANCE, failure.offset());
    }
  }

  private int nodeCount;

  private ExprNode created(ExprNode node) {
    if (++nodeCount > env.maxAstNodes) throw limit("AST node limit exceeded");
    return node;
  }

  private ExprNode node(
      ExprNode.Kind kind, String name, Object value, int offset, ExprNode... children) {
    return created(new ExprNode(kind, name, value, offset, children));
  }

  private ExprNode constant(Object value, int offset) {
    return created(ExprNode.constant(value, offset));
  }

  private ExprNode expression(int min) {
    if (++depth > env.maxDepth) throw limit("expression nesting limit exceeded");
    try {
      ExprNode left = unary();
      while (precedence(token.kind) >= min && precedence(token.kind) > 0) {
        Token op = take();
        int prec = precedence(op.kind);
        ExprNode right = expression(prec + 1);
        left =
            created(ExprNode.binary(op.kind, op.offset, left, right))
                .sourceRange(left.sourceStart, right.sourceEnd);
      }
      if (min == 0 && accept("?")) {
        ExprNode yes = expression(0);
        expect(":");
        ExprNode no = expression(0);
        yes.statement = true;
        no.statement = true;
        left =
            node(ExprNode.Kind.CONDITIONAL, "", null, left.offset, left, yes, no)
                .sourceRange(left.sourceStart, no.sourceEnd);
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
    int start = token.offset;
    return unaryValue().sourceRange(start, consumedEnd);
  }

  private ExprNode unaryValue() {
    if (token.kind.equals("!") || token.kind.equals("-")) {
      Token op = take();
      int count = 1;
      // CEL collapses a run by parity; its token count is not AST nesting. Source length
      // bounds this iterative scan, while the resulting tree still passes depth/node limits.
      while (accept(op.kind)) count++;
      if (op.kind.equals("-") && count % 2 == 1 && token.kind.equals("NUMBER")) {
        Token number = take();
        return postfix(number(number, true).sourceRange(op.offset, consumedEnd));
      }
      ExprNode child = primary();
      child = postfix(child);
      if (count % 2 == 0) return child;
      return node(ExprNode.Kind.CALL, op.kind.equals("-") ? "neg" : "!", false, op.offset, child);
    }
    return postfix(primary());
  }

  private ExprNode primary() {
    int start = token.offset;
    return primaryValue().sourceRange(start, consumedEnd);
  }

  private ExprNode primaryValue() {
    if (Set.of(",", ")", "]", "}", "EOF").contains(token.kind))
      throw error("expected expression, found " + token.text);
    Token t = take();
    switch (t.kind) {
      case "NUMBER":
        return number(t, false);
      case "VALUE":
        return constant(t.value, t.offset);
      case "IDENT", ".":
        {
          String name = t.kind.equals(".") ? "." + identifier(false) : t.text;
          if (RESERVED.contains(name)) throw error("reserved identifier: " + name);
          if (accept("(")) return call(name, false, t.offset, arguments(")"));
          return node(ExprNode.Kind.IDENT, name, null, t.offset);
        }
      case "(":
        {
          ExprNode child = expression(0);
          expect(")");
          return child;
        }
      case "[":
        return node(
            ExprNode.Kind.LIST, "", null, t.offset, aggregate("]").toArray(ExprNode[]::new));
      case "{":
        {
          var entries = new ArrayList<ExprNode>();
          if (accept(",")) expect("}");
          else if (!accept("}"))
            do {
              try {
                boolean optional = accept("?");
                ExprNode key = expression(0);
                expect(":");
                ExprNode entry = entryExpression("}");
                entry.optionalEntry = optional;
                entries.add(key);
                entries.add(entry);
              } catch (CelException failure) {
                recover(failure, "}");
              }
              if (accept("}")) break;
              expect(",");
              if (accept("}")) break;
            } while (true);
          return node(ExprNode.Kind.MAP, "", null, t.offset, entries.toArray(ExprNode[]::new));
        }
      default:
        throw error("expected expression, found " + t.text);
    }
  }

  private ExprNode postfix(ExprNode node) {
    int chain = 0;
    int start = node.sourceStart;
    while (true) {
      if (++chain > env.maxDepth) throw limit("selection nesting limit exceeded");
      if (accept(".")) {
        boolean optional = accept("?");
        String name = identifier(true);
        if (accept("(")) {
          var args = arguments(")");
          args.add(0, node);
          node = call(name, true, node.offset, args);
        } else node = node(ExprNode.Kind.SELECT, name, optional, node.offset, node);
      } else if (accept("[")) {
        boolean optional = accept("?");
        ExprNode index = expression(0);
        expect("]");
        node = node(ExprNode.Kind.INDEX, "", optional, node.offset, node, index);
      } else if (token.kind.equals("{") && node.qualifiedName() != null) {
        take();
        String name = node.qualifiedName();
        var names = new ArrayList<String>();
        var values = new ArrayList<ExprNode>();
        if (accept(",")) expect("}");
        else if (!accept("}"))
          do {
            try {
              boolean optional = accept("?");
              String field = identifier(true);
              if (names.contains(field)) throw error("duplicate field: " + field);
              expect(":");
              ExprNode entry = entryExpression("}");
              entry.optionalEntry = optional;
              names.add(field);
              values.add(entry);
            } catch (CelException failure) {
              recover(failure, "}");
            }
            if (accept("}")) break;
            expect(",");
            if (accept("}")) break;
          } while (true);
        node =
            node(
                ExprNode.Kind.OBJECT,
                name,
                names.toArray(String[]::new),
                node.offset,
                values.toArray(ExprNode[]::new));
      } else return node;
      node.sourceRange(start, consumedEnd);
    }
  }

  private ExprNode call(String name, boolean receiver, int offset, ArrayList<ExprNode> args) {
    if (!issues.isEmpty())
      return node(ExprNode.Kind.CALL, name, receiver, offset, args.toArray(ExprNode[]::new));
    if (env.macros) {
      String prefix = receiver ? args.get(0).qualifiedName() : null;
      ExprNode custom = customMacro(name, receiver, prefix, offset, args);
      if (custom != null) return custom;
      if (("cel".equals(prefix) || ".cel".equals(prefix))
          && env.standardMacros.contains("cel." + name)) {
        if (name.equals("index") || name.equals("iterVar")) {
          int count = name.equals("index") ? 2 : 3;
          if (args.size() != count) throw error("invalid cel." + name + " arguments");
          String id = "$iter";
          for (int i = 1; i < count; i++) {
            ExprNode arg = args.get(i);
            if (!(arg.value instanceof Long number) || number < 0 || number > Integer.MAX_VALUE)
              throw error("cel." + name + " requires nonnegative integer literals");
            id += ":" + number;
          }
          return name.equals("index")
              ? node(ExprNode.Kind.BLOCK_INDEX, "cel.index", args.get(1).value, offset)
              : node(ExprNode.Kind.IDENT, id, null, offset);
        }
        if (name.equals("block")) {
          if (args.size() != 3 || args.get(1).kind != ExprNode.Kind.LIST)
            throw error("cel.block requires a literal list and a result expression");
          var slots = args.get(1).children;
          for (ExprNode slot : slots) if (slot.optionalEntry) throw error("optional block slot");
          ExprNode[] children = Arrays.copyOf(slots, slots.length + 1);
          children[slots.length] = args.get(2);
          return node(ExprNode.Kind.BLOCK, "cel.block", null, offset, children);
        }
        if (name.equals("bind")) {
          if (args.size() != 4
              || args.get(1).kind != ExprNode.Kind.IDENT
              || args.get(1).name.contains(".")) throw error("invalid cel.bind arguments");
          ExprNode variable = args.get(1);
          ExprNode node =
              node(
                  ExprNode.Kind.MACRO,
                  "bind",
                  new String[] {variable.name},
                  offset,
                  args.get(2),
                  args.get(3));
          node.bindings =
              new WriteNode[] {
                new WriteNode(variable.name, variable.sourceStart, variable.sourceEnd)
              };
          node.children[1].statement = true;
          return node;
        }
      }
      if (("proto".equals(prefix) || ".proto".equals(prefix))
          && env.standardMacros.contains("proto." + name)
          && (name.equals("getExt") || name.equals("hasExt"))) {
        env.libraryPolicy.name("proto." + name, offset);
        if (args.size() != 3 || args.get(2).qualifiedName() == null)
          throw error("protobuf extension requires a qualified name");
        String field = args.get(2).qualifiedName();
        ExprNode select = node(ExprNode.Kind.SELECT, field, false, offset, args.get(1));
        select.extensionSelect = true;
        return name.equals("getExt")
            ? select
            : node(ExprNode.Kind.MACRO, "has", null, offset, select);
      }
      if (env.standardMacros.contains(name)
          && name.equals("has")
          && !receiver
          && args.size() == 1) {
        if (args.get(0).kind != ExprNode.Kind.SELECT) throw error("has requires a field selection");
        return node(ExprNode.Kind.MACRO, name, null, offset, args.toArray(ExprNode[]::new));
      }
      if (receiver
          && env.standardMacros.contains(name)
          && Set.of(
                  "all",
                  "exists",
                  "exists_one",
                  "existsOne",
                  "map",
                  "filter",
                  "transformList",
                  "transformMap",
                  "transformMapEntry",
                  "optMap",
                  "optFlatMap",
                  "sortBy")
              .contains(name)) {
        boolean two =
            name.startsWith("transform")
                || Set.of("all", "exists", "existsOne").contains(name) && args.size() == 4;
        int variables = two ? 2 : 1;
        int minimum = variables + 2;
        boolean filtered =
            Set.of("map", "transformList", "transformMap", "transformMapEntry").contains(name);
        if (args.size() != minimum && !(filtered && args.size() == minimum + 1))
          throw error("invalid macro arguments");
        String[] names = new String[variables];
        WriteNode[] bindings = new WriteNode[variables];
        for (int i = 0; i < variables; i++) {
          ExprNode variable = args.remove(1);
          if (variable.kind != ExprNode.Kind.IDENT || variable.name.contains("."))
            throw error("macro variable must be an identifier");
          names[i] = variable.name;
          bindings[i] = new WriteNode(variable.name, variable.sourceStart, variable.sourceEnd);
        }
        if (two && names[0].equals(names[1])) throw error("macro variables must differ");
        ExprNode macro =
            node(ExprNode.Kind.MACRO, name, names, offset, args.toArray(ExprNode[]::new));
        macro.bindings = bindings;
        for (int i = 1; i < macro.children.length; i++) macro.children[i].statement = true;
        return macro;
      }
    }
    return node(ExprNode.Kind.CALL, name, receiver, offset, args.toArray(ExprNode[]::new));
  }

  private long macroNames;

  private ExprNode customMacro(
      String name, boolean receiver, String prefix, int offset, List<ExprNode> args) {
    Macro selected = null;
    int selectedRank = -1;
    boolean globalNamespace = false;
    int count = args.size() - (receiver ? 1 : 0);
    for (Macro macro : env.customMacros) {
      boolean namespace =
          receiver
              && !macro.receiver()
              && prefix != null
              && macro
                  .name()
                  .equals((prefix.startsWith(".") ? prefix.substring(1) : prefix) + "." + name);
      String function = !receiver && name.startsWith(".") ? name.substring(1) : name;
      if (!(namespace || macro.receiver() == receiver && macro.name().equals(function))) continue;
      if (macro.variadic() ? count < macro.argumentCount() : count != macro.argumentCount())
        continue;
      // Prefer fixed arity, then a qualified global match, independently of registration order.
      int rank = (macro.variadic() ? 0 : 2) + (namespace ? 1 : 0);
      if (rank > selectedRank) {
        selected = macro;
        selectedRank = rank;
        globalNamespace = namespace;
      }
    }
    if (selected == null) return null;
    var original = new ArrayList<Ast.Node>();
    for (ExprNode argument : args) {
      env.checkDepth(argument);
      original.add(Ast.snapshot(argument));
    }
    boolean target = receiver && !globalNamespace;
    List<Ast.Node> arguments = List.copyOf(original.subList(receiver ? 1 : 0, original.size()));
    Ast.Node expansion =
        Objects.requireNonNull(
            selected
                .expander()
                .expand(
                    new Macro.Context(offset, () -> ++macroNames),
                    target ? original.get(0) : null,
                    arguments),
            "macro expansion");
    int added = Ast.validate(expansion, env.maxAstNodes - nodeCount, env.maxDepth);
    nodeCount += added;
    ExprNode result = Ast.lower(expansion, env, offset);
    result.macroCall = new Ast.Node(0, Ast.Kind.CALL, name, receiver, original, false, offset);
    return result;
  }

  private ArrayList<ExprNode> arguments(String end) {
    var result = new ArrayList<ExprNode>();
    if (!accept(end)) {
      do {
        result.add(entryExpression(end));
      } while (accept(","));
      expect(end);
    }
    return result;
  }

  private ArrayList<ExprNode> aggregate(String end) {
    var result = new ArrayList<ExprNode>();
    // The grammar admits a lone trailing comma: "[" [ExprList] [","] "]".
    if (accept(",")) {
      expect(end);
      return result;
    }
    if (!accept(end))
      do {
        boolean optional = accept("?");
        ExprNode entry = entryExpression(end);
        entry.optionalEntry = optional;
        result.add(entry);
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
        return constant(d, t.offset);
      }
      if (unsigned && negative) throw error("negative unsigned literal");
      int base = hex ? 16 : 10;
      if (hex) s = s.substring(2);
      if (unsigned) return constant(new UInt(Long.parseUnsignedLong(s, base)), t.offset);
      return constant(Long.parseLong((negative ? "-" : "") + s, base), t.offset);
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
    consumedEnd = cursor + baseOffset;
    token = next();
    return result;
  }

  private CelException error(String message) {
    return new CelException(
        CelException.Code.SYNTAX, message, token == null ? cursor + baseOffset : token.offset);
  }

  // Nesting limits are resource controls, categorized like the other configured limits. They are
  // only reached while a token is current.
  private CelException limit(String message) {
    return new CelException(CelException.Code.RESOURCE_EXHAUSTED, message, token.offset);
  }

  private Token next() {
    Token next = nextRaw();
    return new Token(next.kind, next.text, next.value, next.offset + baseOffset);
  }

  private Token nextRaw() {
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
    // Share operator spellings instead of allocating a table and strings for each token.
    String op =
        switch (c) {
          case '&' -> followedBy('&') ? "&&" : null;
          case '|' -> followedBy('|') ? "||" : null;
          case '=' -> followedBy('=') ? "==" : null;
          case '!' -> followedBy('=') ? "!=" : "!";
          case '<' -> followedBy('=') ? "<=" : "<";
          case '>' -> followedBy('=') ? ">=" : ">";
          case '+' -> "+";
          case '-' -> "-";
          case '*' -> "*";
          case '/' -> "/";
          case '%' -> "%";
          case '?' -> "?";
          case ':' -> ":";
          case '.' -> ".";
          case ',' -> ",";
          case '(' -> "(";
          case ')' -> ")";
          case '[' -> "[";
          case ']' -> "]";
          case '{' -> "{";
          case '}' -> "}";
          default -> null;
        };
    if (op != null) {
      cursor += op.length();
      return new Token(op, op, null, start);
    }
    throw new CelException(
        CelException.Code.SYNTAX, "unexpected character: " + c, cursor + baseOffset);
  }

  private boolean followedBy(char expected) {
    return cursor + 1 < source.length() && source.charAt(cursor + 1) == expected;
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
      } else if (cp == '\\' && cursor < source.length()) {
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
