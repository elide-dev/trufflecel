package dev.trufflecel;

import java.util.*;

final class Checker {
  private final Environment env;
  private final Map<String, CelType> declarations;
  private final boolean strict;

  Checker(Environment env, Map<String, CelType> declarations, boolean strict) {
    this.env = env;
    this.declarations = declarations;
    this.strict = strict;
  }

  CelType check(ExprNode n, Map<String, CelType> locals) {
    String qualified = n.qualifiedName();
    if (qualified != null && !locals.containsKey(qualified.split("\\.")[0])) {
      String name = env.resolve(qualified, declarations.keySet());
      if (name != null) {
        n.kind = ExprNode.Kind.IDENT;
        n.name = qualified.startsWith(".") ? "." + name : name;
        return declarations.get(name);
      }
      name = env.resolve(qualified, env.constants.keySet());
      if (name != null) {
        n.kind = ExprNode.Kind.CONSTANT;
        n.value = env.constants.get(name);
        return Values.type(n.value);
      }
      name = env.resolve(qualified, env.objectTypes.keySet());
      CelType type =
          name == null
              ? Environment.builtinType(
                  qualified.startsWith(".") ? qualified.substring(1) : qualified)
              : CelType.of(name);
      if (type != null) {
        n.kind = ExprNode.Kind.CONSTANT;
        n.value = type;
        return CelType.type(type);
      }
    }
    return switch (n.kind) {
      case CONSTANT -> n.value instanceof CelType t ? CelType.type(t) : Values.type(n.value);
      case IDENT -> {
        if (locals.containsKey(n.name)) yield locals.get(n.name);
        if (strict) throw failure(n, "undeclared reference: " + n.name);
        yield CelType.DYN;
      }
      case SELECT, INDEX -> {
        CelType operand = check(n.children[0], locals);
        boolean optional = Boolean.TRUE.equals(n.value) || operand.name().equals("optional_type");
        if (operand.name().equals("optional_type")) operand = parameter(operand, 0);
        CelType result = CelType.DYN;
        if (n.kind == ExprNode.Kind.SELECT) {
          if (operand.name().equals("map")) result = parameter(operand, 1);
          else if (!operand.isDyn()) {
            ObjectType type = env.objectTypes.get(operand.name());
            require(
                n, type != null && type.fields().containsKey(n.name), "undefined field: " + n.name);
            if (type != null) result = type.fields().getOrDefault(n.name, CelType.DYN);
          }
        } else {
          CelType index = check(n.children[1], locals);
          if (operand.name().equals("list")) {
            require(n, CelType.INT.accepts(index), "list index must be int");
            result = parameter(operand, 0);
          } else if (operand.name().equals("map")) {
            require(n, parameter(operand, 0).accepts(index), "map key type mismatch");
            result = parameter(operand, 1);
          } else require(n, operand.isDyn(), "cannot index " + operand);
        }
        yield optional ? optional(result) : result;
      }
      case LIST -> {
        CelType element = null;
        for (ExprNode child : n.children) {
          CelType t = entryType(child, locals);
          element = element == null ? t : CelType.join(element, t);
        }
        yield CelType.list(element == null ? CelType.UNBOUND : element);
      }
      case MAP -> {
        CelType key = null, value = null;
        for (int i = 0; i < n.children.length; i += 2) {
          CelType k = check(n.children[i], locals), v = entryType(n.children[i + 1], locals);
          require(
              n,
              k.isDyn()
                  || Set.of(CelType.INT, CelType.UINT, CelType.BOOL, CelType.STRING).contains(k),
              "invalid map key type: " + k);
          key = key == null ? k : CelType.join(key, k);
          value = value == null ? v : CelType.join(value, v);
        }
        yield CelType.map(
            key == null ? CelType.UNBOUND : key, value == null ? CelType.UNBOUND : value);
      }
      case OBJECT -> {
        String name = env.resolve(n.name, env.objectTypes.keySet());
        if (name == null) throw failure(n, "unknown object type: " + n.name);
        n.name = name;
        ObjectType type = env.objectTypes.get(name);
        String[] fields = (String[]) n.value;
        for (int i = 0; i < n.children.length; i++) {
          CelType actual = entryType(n.children[i], locals),
              expected = type.fields().get(fields[i]);
          if (expected == null) throw failure(n, "undefined field: " + fields[i]);
          require(
              n,
              expected.accepts(actual) || actual.equals(CelType.NULL),
              "field type mismatch: " + fields[i]);
        }
        yield WellKnownTypes.mapped(name);
      }
      case CONDITIONAL -> {
        require(n, CelType.BOOL.accepts(check(n.children[0], locals)), "condition must be bool");
        CelType yes = check(n.children[1], locals), no = check(n.children[2], locals);
        yield CelType.join(yes, no);
      }
      case MACRO -> {
        if (n.name.equals("has")) {
          check(n.children[0], locals);
          yield CelType.BOOL;
        }
        CelType range = check(n.children[0], locals);
        String[] variables = (String[]) n.value;
        var scope = new HashMap<>(locals);
        if (n.name.equals("optMap") || n.name.equals("optFlatMap")) {
          require(
              n,
              range.isDyn() || range.name().equals("optional_type"),
              "optional receiver required");
          scope.put(variables[0], parameter(range, 0));
          CelType body = check(n.children[1], scope);
          if (n.name.equals("optMap")) yield optional(body);
          require(
              n, body.isDyn() || body.name().equals("optional_type"), "optional result required");
          yield body;
        }
        require(
            n,
            range.isDyn() || range.name().equals("list") || range.name().equals("map"),
            "macro range must be list or map");
        scope.put(
            variables[0],
            variables.length == 2 && range.name().equals("list")
                ? CelType.INT
                : parameter(range, 0));
        if (variables.length == 2)
          scope.put(variables[1], parameter(range, range.name().equals("map") ? 1 : 0));
        CelType body = check(n.children[1], scope);
        if (n.name.equals("sortBy")) {
          require(n, range.isDyn() || range.name().equals("list"), "sortBy requires list");
          require(n, ExtensionFunctions.comparable(body), "sortBy key must be comparable");
          yield range;
        }
        if (n.name.equals("map") || n.name.startsWith("transform")) {
          if (n.children.length == 3) {
            require(n, CelType.BOOL.accepts(body), "macro predicate must be bool");
            body = check(n.children[2], scope);
          }
          if (n.name.equals("transformMap")) yield CelType.map(scope.get(variables[0]), body);
          if (n.name.equals("transformMapEntry")) {
            require(
                n,
                body.isDyn() || body.name().equals("map"),
                "transformMapEntry requires map result");
            yield body;
          }
          yield CelType.list(body);
        }
        require(n, CelType.BOOL.accepts(body), "macro predicate must be bool");
        if (n.name.equals("filter")) yield CelType.list(parameter(range, 0));
        yield CelType.BOOL;
      }
      case CALL -> call(n, locals);
    };
  }

  private CelType call(ExprNode n, Map<String, CelType> locals) {
    if (Boolean.TRUE.equals(n.value)) {
      String prefix = n.children[0].qualifiedName();
      if (prefix != null && prefix.startsWith(".")) prefix = prefix.substring(1);
      if (ExtensionFunctions.namespaced(prefix, n.name)
          || "optional".equals(prefix) && Set.of("of", "none", "ofNonZeroValue").contains(n.name)) {
        n.name = prefix + "." + n.name;
        n.children = Arrays.copyOfRange(n.children, 1, n.children.length);
        n.value = false;
      }
      String resolved =
          prefix == null ? null : env.resolve(prefix + "." + n.name, env.functions.keySet());
      if (resolved != null) {
        n.name = resolved;
        n.children = Arrays.copyOfRange(n.children, 1, n.children.length);
        n.value = false;
      }
    } else {
      String resolved = env.resolve(n.name, env.functions.keySet());
      if (resolved != null) n.name = resolved;
    }
    n.extensionCall = ExtensionFunctions.supports(n.name);
    n.optionalFallback = n.name.equals("or") || n.name.equals("orValue");
    n.stringPredicate =
        n.name.equals("contains") || n.name.equals("startsWith") || n.name.equals("endsWith");
    var types = new ArrayList<CelType>();
    for (ExprNode child : n.children) types.add(check(child, locals));
    var custom = env.functions.get(n.name);
    if (custom != null) {
      require(n, types.size() == custom.arguments().size(), "incorrect argument count");
      var bindings = new HashMap<String, CelType>();
      if (types.size() == custom.arguments().size())
        for (int i = 0; i < types.size(); i++)
          bind(n, custom.arguments().get(i), types.get(i), bindings);
      return substitute(custom.result(), bindings);
    }
    int count = types.size();
    CelType a = count > 0 ? types.get(0).unwrapped() : CelType.DYN,
        b = count > 1 ? types.get(1).unwrapped() : CelType.DYN;
    CelType extension = ExtensionFunctions.check(n.name, types, strict, n.offset);
    if (extension != null) return extension;
    switch (n.name) {
      case "value", "hasValue", "or", "orValue":
        require(
            n,
            count == (n.name.startsWith("or") ? 2 : 1)
                && (a.isDyn() || a.name().equals("optional_type")),
            "invalid optional arguments");
        if (n.name.equals("hasValue")) return CelType.BOOL;
        if (n.name.equals("or")) {
          require(n, a.accepts(b), "optional fallback type mismatch");
          return CelType.join(a, b);
        }
        if (n.name.equals("orValue")) {
          require(n, parameter(a, 0).accepts(b), "optional fallback type mismatch");
          return CelType.join(parameter(a, 0), b);
        }
        return parameter(a, 0);
      case "&&", "||":
        require(
            n,
            count == 2 && CelType.BOOL.accepts(a) && CelType.BOOL.accepts(b),
            "logical operands must be bool");
        return CelType.BOOL;
      case "!":
        require(n, count == 1 && CelType.BOOL.accepts(a), "negation requires bool");
        return CelType.BOOL;
      case "neg":
        require(
            n,
            count == 1 && (a.isDyn() || a.equals(CelType.INT) || a.equals(CelType.DOUBLE)),
            "negation requires int or double");
        return a;
      case "==", "!=":
        require(
            n,
            count == 2
                && (a.name().equals("type") && b.name().equals("type")
                    || a.accepts(b)
                    || a.isNumeric() && b.isNumeric()
                    || a.equals(CelType.NULL)
                    || b.equals(CelType.NULL)),
            "incompatible equality operands");
        return CelType.BOOL;
      case "<", "<=", ">", ">=":
        require(
            n,
            count == 2
                && (a.isDyn()
                    || b.isDyn()
                    || a.isNumeric() && b.isNumeric()
                    || a.equals(b)
                        && Set.of(
                                CelType.STRING,
                                CelType.BYTES,
                                CelType.BOOL,
                                CelType.TIMESTAMP,
                                CelType.DURATION)
                            .contains(a)),
            "incompatible comparison operands");
        return CelType.BOOL;
      case "in":
        require(
            n,
            count == 2 && (b.isDyn() || b.name().equals("list") || b.name().equals("map")),
            "membership requires list or map");
        return CelType.BOOL;
      case "+", "-", "*", "/", "%":
        {
          require(n, count == 2, "binary operator requires two arguments");
          if (a.isDyn() || b.isDyn()) return CelType.DYN;
          if (n.name.equals("+") && a.equals(CelType.DURATION) && b.equals(CelType.TIMESTAMP))
            return CelType.TIMESTAMP;
          if ((n.name.equals("+") || n.name.equals("-"))
              && a.equals(CelType.TIMESTAMP)
              && b.equals(CelType.DURATION)) return CelType.TIMESTAMP;
          if (n.name.equals("-") && a.equals(CelType.TIMESTAMP) && b.equals(CelType.TIMESTAMP))
            return CelType.DURATION;
          if (n.name.equals("+") && a.name().equals("list") && b.name().equals("list"))
            return CelType.join(a, b);
          boolean valid =
              a.equals(b)
                  && (a.isNumeric() && (!n.name.equals("%") || !a.equals(CelType.DOUBLE))
                      || n.name.equals("+") && (a.equals(CelType.STRING) || a.equals(CelType.BYTES))
                      || (n.name.equals("+") || n.name.equals("-")) && a.equals(CelType.DURATION));
          require(n, valid, "no matching overload: " + n.name);
          return a;
        }
      case "optional.none":
        require(n, count == 0, "optional.none takes no arguments");
        return new CelType("optional_type", List.of(CelType.UNBOUND));
      case "optional.of", "optional.ofNonZeroValue":
        require(n, count == 1, "optional.of takes one argument");
        return new CelType("optional_type", List.of(a));
      case "dyn":
        require(n, count == 1, "dyn requires one argument");
        return CelType.DYN;
      case "type":
        require(n, count == 1, "type requires one argument");
        return CelType.type(a);
      case "size":
        require(
            n,
            count == 1
                && (a.isDyn() || Set.of("string", "bytes", "list", "map").contains(a.name())),
            "size requires string, bytes, list, or map");
        return CelType.INT;
      case "contains", "startsWith", "endsWith", "matches":
        require(
            n,
            count == 2 && CelType.STRING.accepts(a) && CelType.STRING.accepts(b),
            "string function requires strings");
        return CelType.BOOL;
      case "int", "uint", "double", "bool", "string", "bytes", "timestamp", "duration":
        require(n, count == 1, "conversion requires one argument");
        Set<CelType> accepted =
            switch (n.name) {
              case "int" ->
                  Set.of(
                      CelType.INT, CelType.UINT, CelType.DOUBLE, CelType.STRING, CelType.TIMESTAMP);
              case "uint", "double" ->
                  Set.of(CelType.INT, CelType.UINT, CelType.DOUBLE, CelType.STRING);
              case "bool" -> Set.of(CelType.BOOL, CelType.STRING);
              case "string" ->
                  Set.of(
                      CelType.BOOL,
                      CelType.INT,
                      CelType.UINT,
                      CelType.DOUBLE,
                      CelType.STRING,
                      CelType.BYTES,
                      CelType.TIMESTAMP,
                      CelType.DURATION,
                      CelType.of("net.IP"),
                      CelType.of("net.CIDR"));
              case "bytes" -> Set.of(CelType.BYTES, CelType.STRING);
              case "timestamp" -> Set.of(CelType.TIMESTAMP, CelType.INT, CelType.STRING);
              default -> Set.of(CelType.DURATION, CelType.STRING);
            };
        require(n, a.isDyn() || accepted.contains(a), "invalid conversion input: " + a);
        return switch (n.name) {
          case "timestamp" -> CelType.TIMESTAMP;
          case "duration" -> CelType.DURATION;
          default -> Environment.builtinType(n.name);
        };
      case "getFullYear",
      "getMonth",
      "getDayOfYear",
      "getDayOfMonth",
      "getDate",
      "getDayOfWeek",
      "getHours",
      "getMinutes",
      "getSeconds",
      "getMilliseconds":
        require(
            n,
            (count == 1 || count == 2 && CelType.STRING.accepts(b))
                && (a.isDyn() || a.equals(CelType.TIMESTAMP) || a.equals(CelType.DURATION)),
            "invalid date/time function arguments");
        return CelType.INT;
      default:
        require(n, false, "undeclared function: " + n.name);
        return CelType.DYN;
    }
  }

  private static CelType optional(CelType type) {
    return new CelType("optional_type", List.of(type));
  }

  private CelType entryType(ExprNode n, Map<String, CelType> locals) {
    CelType type = check(n, locals);
    if (!n.optionalEntry) return type;
    require(
        n,
        type.isDyn() || type.name().equals("optional_type"),
        "optional entry requires optional value");
    return parameter(type, 0);
  }

  private void bind(ExprNode n, CelType expected, CelType actual, Map<String, CelType> bindings) {
    if (expected.name().startsWith("$")) {
      bindings.merge(expected.name(), actual, CelType::join);
      return;
    }
    require(n, expected.accepts(actual), "function argument type mismatch");
    if (actual.isDyn()) {
      for (CelType child : expected.parameters()) bind(n, child, CelType.DYN, bindings);
      return;
    }
    for (int i = 0; i < expected.parameters().size(); i++)
      bind(n, expected.parameters().get(i), parameter(actual, i), bindings);
  }

  private CelType substitute(CelType type, Map<String, CelType> bindings) {
    if (type.name().startsWith("$")) return bindings.getOrDefault(type.name(), CelType.UNBOUND);
    return new CelType(
        type.name(), type.parameters().stream().map(t -> substitute(t, bindings)).toList());
  }

  static CelType parameter(CelType type, int index) {
    return type.parameters().size() > index ? type.parameters().get(index) : CelType.DYN;
  }

  private void require(ExprNode n, boolean condition, String message) {
    if (strict && !condition) throw failure(n, message);
  }

  private CelException failure(ExprNode n, String message) {
    return new CelException(CelException.Code.TYPE, message, n.offset);
  }
}
