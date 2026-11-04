package dev.trufflecel;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.*;

/**
 * CEL's canonical protobuf AST interchange, in the optional protobuf artifact. Messages use the
 * pinned cel.expr schemas; callers may also exchange their serialized bytes with generated protobuf
 * classes. Imported trees are bounded and checked against the supplied environment, rather than
 * trusting externally supplied type annotations.
 */
public final class CanonicalAst {
  private CanonicalAst() {}

  private static final Map<String, String> OPERATORS =
      Map.ofEntries(
          Map.entry("+", "_+_"),
          Map.entry("-", "_-_"),
          Map.entry("*", "_*_"),
          Map.entry("/", "_/_"),
          Map.entry("%", "_%_"),
          Map.entry("==", "_==_"),
          Map.entry("!=", "_!=_"),
          Map.entry("<", "_<_"),
          Map.entry("<=", "_<=_"),
          Map.entry(">", "_>_"),
          Map.entry(">=", "_>=_"),
          Map.entry("&&", "_&&_"),
          Map.entry("||", "_||_"),
          Map.entry("!", "!_"),
          Map.entry("neg", "-_"),
          Map.entry("in", "@in"),
          Map.entry("@not_strictly_false", "@not_strictly_false"));

  /**
   * An empty message of a pinned canonical schema, including conformance request/response types.
   */
  public static Message prototype(String fullName) {
    Descriptor descriptor = CanonicalSchema.MESSAGES.get(fullName);
    if (descriptor == null)
      throw new IllegalArgumentException("Unknown canonical schema: " + fullName);
    return DynamicMessage.getDefaultInstance(descriptor);
  }

  /** Parse source independently; macro expansion is represented as canonical accumulator loops. */
  public static Message parse(Environment environment, String source) {
    if (source.length() > environment.maxSourceLength)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "source length limit exceeded");
    ExprNode tree = new Parser(source, environment).parse();
    environment.prepare(source, NodeUtil.cloneNode(tree), false);
    var writer = new Writer();
    Message expression = writer.expression(tree);
    var offsets = new ArrayList<Integer>();
    for (int i = 0, position = 0; i < source.length(); ) {
      int cp = source.codePointAt(i);
      i += Character.charCount(cp);
      position++;
      if (cp == '\n') offsets.add(position);
    }
    offsets.add(source.codePointCount(0, source.length()) + 1);
    writer.positions.replaceAll(
        (id, offset) -> source.codePointCount(0, Math.min(offset, source.length())));
    Message info =
        message("cel.expr.SourceInfo", "line_offsets", offsets, "positions", writer.positions);
    return message("cel.expr.ParsedExpr", "expr", expression, "source_info", info);
  }

  /** Check a canonical ParsedExpr and return a canonical CheckedExpr. */
  public static Message check(Environment environment, Message parsed) {
    requireSchema(parsed, "cel.expr.ParsedExpr");
    size(parsed);
    var reader = new Reader(environment);
    ExprNode tree = reader.expression(child(parsed, "expr"), 0);
    environment.prepare("", tree, true);
    var writer = new Writer();
    writer.reserved.addAll(reader.ids);
    Message expr = writer.expression(tree);
    return message(
        "cel.expr.CheckedExpr",
        "expr",
        expr,
        "source_info",
        child(parsed, "source_info"),
        "type_map",
        writer.types,
        "reference_map",
        writer.references,
        "expr_version",
        "1.0");
  }

  /** Import ParsedExpr or CheckedExpr. Checked input is revalidated using this environment. */
  public static Program program(Environment environment, Message ast) {
    size(ast);
    String schema = ast.getDescriptorForType().getFullName();
    if (!schema.equals("cel.expr.ParsedExpr") && !schema.equals("cel.expr.CheckedExpr"))
      throw new IllegalArgumentException("Expected ParsedExpr or CheckedExpr");
    var reader = new Reader(environment);
    if (schema.equals("cel.expr.CheckedExpr"))
      for (Message entry : messages(ast, "reference_map"))
        reader.references.put((Long) field(entry, "key"), child(entry, "value"));
    ExprNode tree = reader.expression(child(ast, "expr"), 0);
    return environment.prepare("", tree, schema.equals("cel.expr.CheckedExpr")).adopt();
  }

  /** Parse serialized canonical protobuf data with bounded protobuf recursion and size. */
  public static Message decode(String schema, Bytes bytes) {
    if (bytes.size() > 8 * 1024 * 1024)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "AST wire size limit exceeded");
    try {
      var input = CodedInputStream.newInstance(bytes.toByteArray());
      input.setRecursionLimit(512);
      return prototype(schema).newBuilderForType().mergeFrom(input).build();
    } catch (java.io.IOException e) {
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "Invalid canonical protobuf");
    }
  }

  private static void size(Message ast) {
    if (ast.getSerializedSize() > 8 * 1024 * 1024)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "AST wire size limit exceeded");
  }

  static void requireSchema(Message value, String schema) {
    if (!value.getDescriptorForType().getFullName().equals(schema))
      throw new IllegalArgumentException("Expected " + schema);
  }

  /** Descriptor-driven construction, with no reflection or class discovery. */
  public static Message message(String schema, Object... fields) {
    var builder = prototype(schema).newBuilderForType();
    for (int i = 0; i < fields.length; i += 2) {
      FieldDescriptor field = builder.getDescriptorForType().findFieldByName((String) fields[i]);
      Object value = fields[i + 1];
      if (field.isMapField() && value instanceof Map<?, ?> map) {
        for (var entry : map.entrySet()) {
          var item = DynamicMessage.newBuilder(field.getMessageType());
          item.setField(field.getMessageType().findFieldByName("key"), entry.getKey());
          item.setField(field.getMessageType().findFieldByName("value"), entry.getValue());
          builder.addRepeatedField(field, item.build());
        }
      } else if (field.isRepeated()) {
        for (Object item : (Iterable<?>) value) builder.addRepeatedField(field, item);
      } else {
        if (value instanceof String name && field.getJavaType() == FieldDescriptor.JavaType.ENUM)
          value = field.getEnumType().findValueByName(name);
        builder.setField(field, value);
      }
    }
    return builder.build();
  }

  public static Object field(Message value, String name) {
    return value.getField(value.getDescriptorForType().findFieldByName(name));
  }

  static Message child(Message value, String name) {
    return (Message) field(value, name);
  }

  static String string(Message value, String name) {
    return (String) field(value, name);
  }

  @SuppressWarnings("unchecked")
  static List<Message> messages(Message value, String name) {
    return (List<Message>) field(value, name);
  }

  static boolean has(Message value, String name) {
    return value.hasField(value.getDescriptorForType().findFieldByName(name));
  }

  /** Convert a CEL type to the canonical protobuf Type. */
  public static Message type(CelType type) {
    String primitive =
        switch (type.name()) {
          case "bool" -> "BOOL";
          case "int" -> "INT64";
          case "uint" -> "UINT64";
          case "double" -> "DOUBLE";
          case "string" -> "STRING";
          case "bytes" -> "BYTES";
          default -> null;
        };
    if (primitive != null) return message("cel.expr.Type", "primitive", primitive);
    if (type.name().startsWith("$"))
      return message("cel.expr.Type", "type_param", type.name().substring(1));
    if (type.isDyn()) return message("cel.expr.Type", "dyn", prototype("google.protobuf.Empty"));
    return switch (type.name()) {
      case "null_type" -> message("cel.expr.Type", "null", "NULL_VALUE");
      case "list" ->
          message(
              "cel.expr.Type",
              "list_type",
              message("cel.expr.Type.ListType", "elem_type", type(Checker.parameter(type, 0))));
      case "map" ->
          message(
              "cel.expr.Type",
              "map_type",
              message(
                  "cel.expr.Type.MapType",
                  "key_type",
                  type(Checker.parameter(type, 0)),
                  "value_type",
                  type(Checker.parameter(type, 1))));
      case "type" ->
          message(
              "cel.expr.Type",
              "type",
              type.parameters().isEmpty()
                  ? prototype("cel.expr.Type")
                  : type(type.parameters().get(0)));
      case "wrapper" ->
          message("cel.expr.Type", "wrapper", field(type(type.parameters().get(0)), "primitive"));
      case "google.protobuf.Timestamp" -> message("cel.expr.Type", "well_known", "TIMESTAMP");
      case "google.protobuf.Duration" -> message("cel.expr.Type", "well_known", "DURATION");
      case "google.protobuf.Any" -> message("cel.expr.Type", "well_known", "ANY");
      default ->
          type.parameters().isEmpty() && !type.name().equals("optional_type")
              ? message("cel.expr.Type", "message_type", type.name())
              : message(
                  "cel.expr.Type",
                  "abstract_type",
                  message(
                      "cel.expr.Type.AbstractType",
                      "name",
                      type.name(),
                      "parameter_types",
                      type.parameters().stream().map(CanonicalAst::type).toList()));
    };
  }

  public static CelType type(Message type) {
    if (has(type, "primitive") || has(type, "wrapper")) {
      String name =
          ((EnumValueDescriptor) field(type, has(type, "primitive") ? "primitive" : "wrapper"))
              .getName();
      CelType scalar =
          switch (name) {
            case "BOOL" -> CelType.BOOL;
            case "INT64" -> CelType.INT;
            case "UINT64" -> CelType.UINT;
            case "DOUBLE" -> CelType.DOUBLE;
            case "STRING" -> CelType.STRING;
            case "BYTES" -> CelType.BYTES;
            default -> throw new IllegalArgumentException("Unspecified primitive type");
          };
      return has(type, "wrapper") ? CelType.wrapper(scalar) : scalar;
    }
    if (has(type, "dyn")) return CelType.DYN;
    if (has(type, "null")) return CelType.NULL;
    if (has(type, "list_type"))
      return CelType.list(type(child(child(type, "list_type"), "elem_type")));
    if (has(type, "map_type")) {
      var map = child(type, "map_type");
      return CelType.map(type(child(map, "key_type")), type(child(map, "value_type")));
    }
    if (has(type, "type"))
      return child(type, "type").getAllFields().isEmpty()
          ? CelType.TYPE
          : CelType.type(type(child(type, "type")));
    if (has(type, "type_param")) return CelType.parameter(string(type, "type_param"));
    if (has(type, "message_type")) return CelType.of(string(type, "message_type"));
    if (has(type, "abstract_type")) {
      var abstractType = child(type, "abstract_type");
      return new CelType(
          string(abstractType, "name"),
          messages(abstractType, "parameter_types").stream().map(CanonicalAst::type).toList());
    }
    if (has(type, "well_known"))
      return switch (((EnumValueDescriptor) field(type, "well_known")).getName()) {
        case "TIMESTAMP" -> CelType.TIMESTAMP;
        case "DURATION" -> CelType.DURATION;
        case "ANY" -> CelType.of("google.protobuf.Any");
        default -> throw new IllegalArgumentException("Unspecified well-known type");
      };
    throw new IllegalArgumentException("Unsupported canonical type");
  }

  private static final class Reader {
    final Environment environment;
    final Set<Long> ids = new HashSet<>();
    final Map<Long, Message> references = new HashMap<>();
    int count;

    Reader(Environment environment) {
      this.environment = environment;
    }

    ExprNode expression(Message expr, int depth) {
      if (++count > environment.maxSourceLength || depth > environment.maxDepth)
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "AST size/depth limit exceeded");
      long id = (Long) field(expr, "id");
      if (id <= 0 || !ids.add(id))
        throw new IllegalArgumentException("AST ids must be positive and unique");
      ExprNode node;
      if (has(expr, "const_expr")) node = ExprNode.constant(constant(child(expr, "const_expr")), 0);
      else if (has(expr, "ident_expr"))
        node =
            new ExprNode(ExprNode.Kind.IDENT, string(child(expr, "ident_expr"), "name"), null, 0);
      else if (has(expr, "select_expr")) {
        Message select = child(expr, "select_expr");
        node =
            new ExprNode(
                ExprNode.Kind.SELECT,
                string(select, "field"),
                false,
                0,
                expression(child(select, "operand"), depth + 1));
        if ((Boolean) field(select, "test_only"))
          node = new ExprNode(ExprNode.Kind.MACRO, "has", new String[0], 0, node);
      } else if (has(expr, "list_expr")) {
        Message list = child(expr, "list_expr");
        var children =
            messages(list, "elements").stream()
                .map(x -> expression(x, depth + 1))
                .toArray(ExprNode[]::new);
        for (Object index : (List<?>) field(list, "optional_indices")) {
          int i = (Integer) index;
          if (i < 0 || i >= children.length || children[i].optionalEntry)
            throw new IllegalArgumentException("Invalid optional index");
          children[i].optionalEntry = true;
        }
        node = new ExprNode(ExprNode.Kind.LIST, "", null, 0, children);
      } else if (has(expr, "struct_expr")) {
        Message struct = child(expr, "struct_expr");
        String name = string(struct, "message_name");
        var children = new ArrayList<ExprNode>();
        var fields = new ArrayList<String>();
        for (Message entry : messages(struct, "entries")) {
          long entryId = (Long) field(entry, "id");
          if (entryId <= 0 || !ids.add(entryId))
            throw new IllegalArgumentException("Invalid entry id");
          if (name.isEmpty()) {
            if (!has(entry, "map_key")) throw new IllegalArgumentException("Missing map key");
            children.add(expression(child(entry, "map_key"), depth + 1));
          } else {
            if (!has(entry, "field_key")) throw new IllegalArgumentException("Missing field key");
            fields.add(string(entry, "field_key"));
          }
          ExprNode value = expression(child(entry, "value"), depth + 1);
          value.optionalEntry = (Boolean) field(entry, "optional_entry");
          children.add(value);
        }
        node =
            new ExprNode(
                name.isEmpty() ? ExprNode.Kind.MAP : ExprNode.Kind.OBJECT,
                name,
                name.isEmpty() ? null : fields.toArray(String[]::new),
                0,
                children.toArray(ExprNode[]::new));
      } else if (has(expr, "comprehension_expr")) {
        Message loop = child(expr, "comprehension_expr");
        String acc = string(loop, "accu_var"),
            iter = string(loop, "iter_var"),
            iter2 = string(loop, "iter_var2");
        if (acc.isEmpty()
            || iter.isEmpty()
            || acc.equals(iter)
            || acc.equals(iter2)
            || iter.equals(iter2))
          throw new IllegalArgumentException("Invalid comprehension bindings");
        node =
            new ComprehensionNode(
                new String[] {acc, iter, iter2},
                expression(child(loop, "iter_range"), depth + 1),
                expression(child(loop, "accu_init"), depth + 1),
                expression(child(loop, "loop_condition"), depth + 1),
                expression(child(loop, "loop_step"), depth + 1),
                expression(child(loop, "result"), depth + 1));
      } else if (has(expr, "call_expr")) {
        Message call = child(expr, "call_expr");
        String function = string(call, "function");
        boolean receiver = has(call, "target");
        var children = new ArrayList<ExprNode>();
        if (receiver) children.add(expression(child(call, "target"), depth + 1));
        for (Message arg : messages(call, "args")) children.add(expression(arg, depth + 1));
        String name = function;
        for (var operator : OPERATORS.entrySet())
          if (operator.getValue().equals(function)) name = operator.getKey();
        if (function.equals("_?_:_")) {
          arity(children, 3);
          node =
              new ExprNode(
                  ExprNode.Kind.CONDITIONAL, "", null, 0, children.toArray(ExprNode[]::new));
        } else if (function.equals("_[_]") || function.equals("_[?_]")) {
          arity(children, 2);
          node =
              new ExprNode(
                  ExprNode.Kind.INDEX,
                  "",
                  function.equals("_[?_]"),
                  0,
                  children.toArray(ExprNode[]::new));
        } else if (function.equals("_?._")) {
          arity(children, 2);
          if (children.get(1).kind != ExprNode.Kind.CONSTANT
              || !(children.get(1).value instanceof String))
            throw new IllegalArgumentException("Invalid optional field");
          node =
              new ExprNode(
                  ExprNode.Kind.SELECT, (String) children.get(1).value, true, 0, children.get(0));
        } else if (function.equals("cel.block")) {
          arity(children, 2);
          if (children.get(0).kind != ExprNode.Kind.LIST)
            throw new IllegalArgumentException("Invalid block slots");
          var slots = new ArrayList<>(Arrays.asList(children.get(0).children));
          slots.add(children.get(1));
          node =
              new ExprNode(ExprNode.Kind.BLOCK, function, null, 0, slots.toArray(ExprNode[]::new));
        } else if (function.equals("cel.index")) {
          arity(children, 1);
          if (children.get(0).kind != ExprNode.Kind.CONSTANT
              || !(children.get(0).value instanceof Long n)
              || n < 0
              || n > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid block index");
          node = new ExprNode(ExprNode.Kind.BLOCK_INDEX, function, children.get(0).value, 0);
        } else {
          node =
              new ExprNode(
                  ExprNode.Kind.CALL, name, receiver, 0, children.toArray(ExprNode[]::new));
        }
      } else throw new IllegalArgumentException("Missing expression kind");
      Message reference = references.get(id);
      if (reference != null
          && (node.kind == ExprNode.Kind.IDENT || node.kind == ExprNode.Kind.SELECT)) {
        if (has(reference, "value")) {
          node.kind = ExprNode.Kind.CONSTANT;
          node.value = constant(child(reference, "value"));
          node.children = new ExprNode[0];
        } else if (!string(reference, "name").isEmpty()) {
          node.kind = ExprNode.Kind.IDENT;
          node.name = string(reference, "name");
          node.children = new ExprNode[0];
        }
      }
      node.canonicalId = id;
      return node;
    }
  }

  private static void arity(List<?> args, int count) {
    if (args.size() != count)
      throw new IllegalArgumentException("Invalid canonical operator arity");
  }

  private static Object constant(Message constant) {
    for (var entry : constant.getAllFields().entrySet())
      return switch (entry.getKey().getName()) {
        case "null_value" -> CelNull.INSTANCE;
        case "uint64_value" -> new UInt((Long) entry.getValue());
        case "bytes_value" -> new Bytes(((ByteString) entry.getValue()).toByteArray());
        case "duration_value" -> {
          Message duration = (Message) entry.getValue();
          yield WellKnownTypes.time(
              Map.of(
                  "seconds",
                  field(duration, "seconds"),
                  "nanos",
                  ((Integer) field(duration, "nanos")).longValue()),
              true);
        }
        case "timestamp_value" -> {
          Message timestamp = (Message) entry.getValue();
          yield WellKnownTypes.time(
              Map.of(
                  "seconds",
                  field(timestamp, "seconds"),
                  "nanos",
                  ((Integer) field(timestamp, "nanos")).longValue()),
              false);
        }
        default -> entry.getValue();
      };
    throw new IllegalArgumentException("Missing constant kind");
  }

  /** Serializes compiler IR independently of source parsing. */
  static final class Writer {
    long nextId = 1;
    final Set<Long> reserved = new HashSet<>();

    long freshId() {
      while (reserved.contains(nextId)) nextId++;
      return nextId++;
    }

    final Map<Long, Integer> positions = new LinkedHashMap<>();
    final Map<Long, Message> types = new LinkedHashMap<>(), references = new LinkedHashMap<>();

    Message wrap(String kind, Message value) {
      return message("cel.expr.Expr", "id", freshId(), kind, value);
    }

    Message ident(String name) {
      return wrap("ident_expr", message("cel.expr.Expr.Ident", "name", name));
    }

    Message literal(Object value) {
      String field;
      Object data = value;
      if (value == CelNull.INSTANCE) {
        field = "null_value";
        data = "NULL_VALUE";
      } else if (value instanceof Boolean) field = "bool_value";
      else if (value instanceof Long) field = "int64_value";
      else if (value instanceof UInt u) {
        field = "uint64_value";
        data = u.bits();
      } else if (value instanceof Double) field = "double_value";
      else if (value instanceof String) field = "string_value";
      else if (value instanceof Bytes b) {
        field = "bytes_value";
        data = ByteString.copyFrom(b.toByteArray());
      } else if (value instanceof java.time.Duration d) {
        field = "duration_value";
        long seconds = d.getSeconds();
        int nanos = d.getNano();
        if (seconds < 0 && nanos != 0) {
          seconds++;
          nanos -= 1_000_000_000;
        }
        data = message("google.protobuf.Duration", "seconds", seconds, "nanos", nanos);
      } else if (value instanceof java.time.Instant t) {
        field = "timestamp_value";
        data =
            message(
                "google.protobuf.Timestamp", "seconds", t.getEpochSecond(), "nanos", t.getNano());
      } else if (value instanceof CelType t) return ident(t.name());
      else throw new IllegalArgumentException("Unsupported canonical constant");
      return wrap("const_expr", message("cel.expr.Constant", field, data));
    }

    Message call(String name, Message... args) {
      return call(name, null, Arrays.asList(args));
    }

    Message call(String name, Message target, List<Message> args) {
      return wrap(
          "call_expr",
          target == null
              ? message("cel.expr.Expr.Call", "function", name, "args", args)
              : message("cel.expr.Expr.Call", "function", name, "target", target, "args", args));
    }

    Message list(Message... elements) {
      return wrap(
          "list_expr", message("cel.expr.Expr.CreateList", "elements", Arrays.asList(elements)));
    }

    Message loop(
        String iter,
        String iter2,
        Message range,
        String acc,
        Message init,
        Message condition,
        Message step,
        Message result) {
      return wrap(
          "comprehension_expr",
          message(
              "cel.expr.Expr.Comprehension",
              "iter_var",
              iter,
              "iter_var2",
              iter2,
              "iter_range",
              range,
              "accu_var",
              acc,
              "accu_init",
              init,
              "loop_condition",
              condition,
              "loop_step",
              step,
              "result",
              result));
    }

    Message expression(ExprNode node) {
      Message result =
          switch (node.kind) {
            case CONSTANT -> literal(node.value);
            case IDENT -> ident(node.name);
            case SELECT ->
                Boolean.TRUE.equals(node.value)
                    ? call("_?._", expression(node.children[0]), literal(node.name))
                    : wrap(
                        "select_expr",
                        message(
                            "cel.expr.Expr.Select",
                            "operand",
                            expression(node.children[0]),
                            "field",
                            node.name));
            case INDEX ->
                call(
                    Boolean.TRUE.equals(node.value) ? "_[?_]" : "_[_]",
                    expression(node.children[0]),
                    expression(node.children[1]));
            case CONDITIONAL ->
                call(
                    "_?_:_",
                    expression(node.children[0]),
                    expression(node.children[1]),
                    expression(node.children[2]));
            case CALL -> {
              var args = new ArrayList<Message>();
              for (ExprNode child : node.children) args.add(expression(child));
              Message target = Boolean.TRUE.equals(node.value) ? args.remove(0) : null;
              yield call(OPERATORS.getOrDefault(node.name, node.name), target, args);
            }
            case LIST -> {
              var elements = new ArrayList<Message>();
              var optional = new ArrayList<Integer>();
              for (int i = 0; i < node.children.length; i++) {
                elements.add(expression(node.children[i]));
                if (node.children[i].optionalEntry) optional.add(i);
              }
              yield wrap(
                  "list_expr",
                  message(
                      "cel.expr.Expr.CreateList",
                      "elements",
                      elements,
                      "optional_indices",
                      optional));
            }
            case MAP, OBJECT -> {
              var entries = new ArrayList<Message>();
              for (int i = 0; i < node.children.length; ) {
                Object key =
                    node.kind == ExprNode.Kind.MAP
                        ? expression(node.children[i++])
                        : ((String[]) node.value)[i];
                ExprNode value = node.children[i++];
                entries.add(
                    message(
                        "cel.expr.Expr.CreateStruct.Entry",
                        "id",
                        freshId(),
                        node.kind == ExprNode.Kind.MAP ? "map_key" : "field_key",
                        key,
                        "value",
                        expression(value),
                        "optional_entry",
                        value.optionalEntry));
              }
              yield wrap(
                  "struct_expr",
                  message(
                      "cel.expr.Expr.CreateStruct", "message_name", node.name, "entries", entries));
            }
            case BLOCK_INDEX -> call("cel.index", literal(node.value));
            case BLOCK -> {
              var slots = new ArrayList<Message>();
              for (int i = 0; i < node.children.length - 1; i++)
                slots.add(expression(node.children[i]));
              yield call(
                  "cel.block",
                  wrap("list_expr", message("cel.expr.Expr.CreateList", "elements", slots)),
                  expression(node.children[node.children.length - 1]));
            }
            case COMPREHENSION -> {
              String[] names = (String[]) node.value;
              yield loop(
                  names[1],
                  names[2],
                  expression(node.children[0]),
                  names[0],
                  expression(node.children[1]),
                  expression(node.children[2]),
                  expression(node.children[3]),
                  expression(node.children[4]));
            }
            case MACRO -> macro(node);
          };
      long id = (Long) field(result, "id");
      // Imported ids remain stable, including ids which index caller-provided SourceInfo.
      if (node.canonicalId != 0) {
        id = node.canonicalId;
        result =
            result.toBuilder()
                .setField(result.getDescriptorForType().findFieldByName("id"), id)
                .build();
      }
      positions.put(id, node.offset);
      if (node.checkedType != null) types.put(id, type(node.checkedType.finish()));
      if (node.kind == ExprNode.Kind.IDENT)
        references.put(id, message("cel.expr.Reference", "name", node.name));
      if (node.kind == ExprNode.Kind.CALL)
        references.put(
            id,
            message("cel.expr.Reference", "name", OPERATORS.getOrDefault(node.name, node.name)));
      return result;
    }

    Message macro(ExprNode node) {
      if (node.name.equals("has")) {
        ExprNode select = node.children[0];
        if (select.kind != ExprNode.Kind.SELECT)
          throw new IllegalArgumentException("Canonical has requires a field selection");
        return wrap(
            "select_expr",
            message(
                "cel.expr.Expr.Select",
                "operand",
                expression(select.children[0]),
                "field",
                select.name,
                "test_only",
                true));
      }
      String[] names = (String[]) node.value;
      String acc = "@trufflecel_acc_" + nextId;
      if (node.name.equals("bind"))
        return loop(
            "@unused",
            "",
            list(),
            names[0],
            expression(node.children[0]),
            literal(false),
            ident(names[0]),
            expression(node.children[1]));
      Message range = expression(node.children[0]);
      Message init, condition, step, result;
      boolean all = node.name.equals("all"), exists = node.name.equals("exists");
      if (all || exists) {
        init = literal(all);
        condition = call("@not_strictly_false", exists ? call("!_", ident(acc)) : ident(acc));
        step = call(all ? "_&&_" : "_||_", ident(acc), expression(node.children[1]));
        result = ident(acc);
      } else if (node.name.equals("exists_one") || node.name.equals("existsOne")) {
        init = literal(0L);
        condition = literal(true);
        step =
            call(
                "_?_:_",
                expression(node.children[1]),
                call("_+_", ident(acc), literal(1L)),
                ident(acc));
        result = call("_==_", ident(acc), literal(1L));
      } else if (Set.of("map", "filter", "transformList", "transformMap", "transformMapEntry")
          .contains(node.name)) {
        boolean map = node.name.equals("transformMap") || node.name.equals("transformMapEntry");
        init = map ? wrap("struct_expr", prototype("cel.expr.Expr.CreateStruct")) : list();
        condition = literal(true);
        Message mapped =
            node.name.equals("filter")
                ? ident(names[0])
                : expression(node.children[node.children.length - 1]);
        step =
            node.name.equals("transformMap")
                ? call("cel.@mapInsert", ident(acc), ident(names[0]), mapped)
                : node.name.equals("transformMapEntry")
                    ? call("cel.@mapInsert", ident(acc), mapped)
                    : call("_+_", ident(acc), list(mapped));
        if (node.name.equals("filter") || node.children.length == 3)
          step = call("_?_:_", expression(node.children[1]), step, ident(acc));
        result = ident(acc);
      } else if (node.name.equals("optMap") || node.name.equals("optFlatMap")) {
        Message mapped = expression(node.children[1]);
        if (node.name.equals("optMap")) mapped = call("optional.of", mapped);
        Message binding =
            loop(
                "@unused",
                "",
                list(),
                names[0],
                call("value", ident(acc), List.of()),
                literal(false),
                ident(names[0]),
                mapped);
        Message conditional =
            call("_?_:_", call("hasValue", ident(acc), List.of()), binding, call("optional.none"));
        return loop("@unused", "", list(), acc, range, literal(false), ident(acc), conditional);
      } else if (node.name.equals("sortBy")) {
        String keysAcc = "@keys_" + nextId;
        Message keys =
            loop(
                names[0],
                "",
                ident(acc),
                keysAcc,
                list(),
                literal(true),
                call("_+_", ident(keysAcc), list(expression(node.children[1]))),
                ident(keysAcc));
        Message sorted = call("@sortByAssociatedKeys", ident(acc), List.of(keys));
        return loop("@unused", "", list(), acc, range, literal(false), ident(acc), sorted);
      } else throw new IllegalArgumentException("Unsupported macro: " + node.name);

      return loop(
          names[0], names.length == 2 ? names[1] : "", range, acc, init, condition, step, result);
    }
  }
}
