package dev.trufflecel;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.*;
import java.util.*;

/** Each parsed expression becomes an adopted Truffle AST node, never a delegated interpreter. */
@GenerateWrapper
class ExprNode extends CelNode {
  boolean rootBody, statement;
  @Children WriteNode[] bindings;

  ExprNode() {
    this(Kind.CONSTANT, "", null, 0);
  }

  ExprNode sourceRange(int start, int end) {
    sourceStart = start;
    sourceEnd = end;
    return this;
  }

  @GenerateWrapper.OutgoingConverter
  final Object instrumentResult(Object result) {
    return GuestValue.wrap(result);
  }

  @Override
  public WrapperNode createWrapper(ProbeNode probe) {
    var wrapper = new ExprNodeWrapper(this, probe);
    // Collection/object parents inspect this flag after executing a child through its probe.
    wrapper.optionalEntry = optionalEntry;
    return wrapper;
  }

  @Override
  public boolean hasTag(Class<? extends Tag> tag) {
    return tag == StandardTags.ExpressionTag.class
        || (rootBody || statement) && tag == StandardTags.StatementTag.class
        || rootBody && (tag == StandardTags.RootTag.class || tag == StandardTags.RootBodyTag.class)
        || kind == Kind.CALL && tag == StandardTags.CallTag.class
        || kind == Kind.IDENT && tag == StandardTags.ReadVariableTag.class;
  }

  @Override
  public Object getNodeObject() {
    return kind == Kind.IDENT
        ? GuestValue.wrap(Map.of(StandardTags.ReadVariableTag.NAME, name))
        : null;
  }

  enum Kind {
    CONSTANT,
    IDENT,
    SELECT,
    INDEX,
    CALL,
    LIST,
    MAP,
    OBJECT,
    CONDITIONAL,
    MACRO
  }

  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal Kind kind;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal String name;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal Object value;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean optionalEntry;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean extensionCall;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean optionalFallback;
  final int offset;
  private final int scalarOperator;
  @Children ExprNode[] children;

  ExprNode(Kind kind, String name, Object value, int offset, ExprNode... children) {
    scalarOperator = kind == Kind.CALL ? scalarCode(name) : 0;
    this.kind = kind;
    this.name = name;
    this.value = value;
    this.offset = offset;
    this.children = children;
  }

  private static int scalarCode(String name) {
    return switch (name) {
      case "+", "-", "*", "/", "%", "<", ">" -> name.charAt(0);
      case "<=" -> 'L';
      case ">=" -> 'G';
      case "==" -> 'E';
      case "!=" -> 'N';
      default -> 0;
    };
  }

  static ExprNode binary(String name, int offset, ExprNode left, ExprNode right) {
    return scalarCode(name) == 0 || name.equals("+") || name.equals("-") || name.equals("*")
        ? new ExprNode(Kind.CALL, name, false, offset, left, right)
        : new ScalarBinaryNode(name, offset, left, right);
  }

  private static final class ScalarBinaryNode extends ExprNode {
    ScalarBinaryNode(String name, int offset, ExprNode left, ExprNode right) {
      super(Kind.CALL, name, false, offset, left, right);
    }

    @Override
    Object execute(VirtualFrame frame) {
      EvalContext ctx = (EvalContext) frame.getArguments()[0];
      ctx.tick();
      return scalarBinary(frame, ctx);
    }
  }

  static ExprNode constant(Object value, int offset) {
    return new LiteralNode(value, offset);
  }

  ExprNode lowerArithmetic() {
    int count = 0;
    ExprNode leaf = this;
    while (leaf.kind == Kind.CALL
        && !Boolean.TRUE.equals(leaf.value)
        && leaf.children.length == 2
        && (leaf.name.equals("+") || leaf.name.equals("-") || leaf.name.equals("*"))
        && leaf.children[1].kind == Kind.CONSTANT
        && leaf.children[1].value instanceof Long) {
      count++;
      leaf = leaf.children[0];
    }
    if (count >= 2) return new ArithmeticChain(this, count, leaf.lowerArithmetic());
    for (int i = 0; i < children.length; i++) children[i] = children[i].lowerArithmetic();
    return this;
  }

  /**
   * Constant-right arithmetic keeps intermediates unboxed and preserves source evaluation order.
   */
  private static final class ArithmeticChain extends ExprNode {
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final int[] operations;

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final long[] constants;

    private final String firstOperation;

    ArithmeticChain(ExprNode outer, int count, ExprNode leaf) {
      super(Kind.CALL, outer.name, false, outer.offset, leaf);
      operations = new int[count];
      constants = new long[count];
      ExprNode node = outer;
      for (int i = count - 1; i >= 0; i--) {
        operations[i] = node.name.charAt(0);
        constants[i] = (Long) node.children[1].value;
        node = node.children[0];
      }
      firstOperation =
          switch (operations[0]) {
            case '+' -> "+";
            case '-' -> "-";
            default -> "*";
          };
      sourceRange(outer.sourceStart, outer.sourceEnd);
      optionalEntry = outer.optionalEntry;
      statement = outer.statement;
    }

    @Override
    @com.oracle.truffle.api.nodes.ExplodeLoop
    Object execute(VirtualFrame frame) {
      EvalContext ctx = (EvalContext) frame.getArguments()[0];
      // The original outer-to-inner operator visits all precede evaluation of the left operand.
      ctx.charge(operations.length);
      Object left;
      try {
        left = children[0].execute(frame);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        ctx.charge(operations.length);
        throw e;
      }
      if (left instanceof Unknown) {
        ctx.charge(operations.length);
        return left;
      }
      if (left instanceof Long number) {
        long result = number;
        for (int i = 0; i < operations.length; i++) {
          ctx.tick(); // The right literal's visit still consumes its original budget.
          try {
            result =
                switch (operations[i]) {
                  case '+' -> Math.addExact(result, constants[i]);
                  case '-' -> Math.subtractExact(result, constants[i]);
                  default -> Math.multiplyExact(result, constants[i]);
                };
          } catch (ArithmeticException e) {
            // Outer constants are evaluated even when an inner operation fails.
            ctx.charge(operations.length - i - 1);
            throw Values.error(CelException.Code.OVERFLOW, "numeric overflow");
          }
        }
        return result;
      }
      // These operators require matching numeric types; every right operand is a CEL int.
      ctx.charge(operations.length);
      throw Values.overload(firstOperation);
    }
  }

  private static final class LiteralNode extends ExprNode {
    LiteralNode(Object value, int offset) {
      super(Kind.CONSTANT, "", value, offset);
    }

    @Override
    Object execute(VirtualFrame frame) {
      ((EvalContext) frame.getArguments()[0]).tick();
      return value;
    }
  }

  String qualifiedName() {
    if (kind == Kind.IDENT) return name;
    if (kind == Kind.SELECT && !Boolean.TRUE.equals(value)) {
      String prefix = children[0].qualifiedName();
      if (prefix != null) return prefix + "." + name;
    }
    return null;
  }

  void free(Set<String> bound, Set<String> out) {
    String qualified = qualifiedName();
    if (qualified != null
        && Environment.builtinType(qualified) != null
        && !bound.contains(qualified)) return;
    if (kind == Kind.CALL && Boolean.TRUE.equals(value)) {
      String prefix = children[0].qualifiedName();
      if (prefix != null && prefix.startsWith(".")) prefix = prefix.substring(1);
      if (ExtensionFunctions.namespaced(prefix, name)
          || "optional".equals(prefix) && Set.of("of", "none", "ofNonZeroValue").contains(name)) {
        for (int i = 1; i < children.length; i++) children[i].free(bound, out);
        return;
      }
    }
    if (kind == Kind.IDENT && !bound.contains(name)) out.add(name);
    if (kind == Kind.MACRO && !name.equals("has")) {
      children[0].free(bound, out);
      var inner = new HashSet<>(bound);
      inner.addAll(Arrays.asList((String[]) value));
      for (int i = 1; i < children.length; i++) children[i].free(inner, out);
    } else for (ExprNode child : children) child.free(bound, out);
  }

  Object execute(VirtualFrame frame) {
    EvalContext ctx = (EvalContext) frame.getArguments()[0];
    ctx.tick();
    return switch (kind) {
      case CONSTANT -> value;
      case IDENT -> ctx.get(name);
      case SELECT -> select(frame, ctx, false);
      case INDEX -> {
        Object a = children[0].execute(frame), b = children[1].execute(frame);
        if (a instanceof Unknown u) yield u;
        if (b instanceof Unknown u) yield u;
        boolean optional = Boolean.TRUE.equals(value) || a instanceof OptionalValue;
        if (a instanceof OptionalValue o) {
          if (!o.present()) yield o;
          a = o.value();
        }
        try {
          Object result = Values.index(a, b);
          yield optional ? OptionalValue.of(result) : result;
        } catch (CelException e) {
          if (optional
              && (e.code() == CelException.Code.NO_SUCH_KEY || e.code() == CelException.Code.INDEX))
            yield OptionalValue.none();
          throw e;
        }
      }
      case LIST -> list(frame, ctx);
      case MAP -> map(frame, ctx);
      case OBJECT -> object(frame, ctx);
      case CONDITIONAL -> {
        Object condition = children[0].execute(frame);
        if (condition instanceof Unknown) yield condition;
        if (!(condition instanceof Boolean b)) throw Values.overload("conditional");
        yield b ? children[1].execute(frame) : children[2].execute(frame);
      }
      case CALL -> call(frame, ctx);
      case MACRO -> macro(frame, ctx);
    };
  }

  Object executePresence(VirtualFrame frame) {
    return select(frame, (EvalContext) frame.getArguments()[0], true);
  }

  private Object select(VirtualFrame frame, EvalContext ctx, boolean presence) {
    return selectValue(ctx, presence, children[0].execute(frame));
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private Object selectValue(EvalContext ctx, boolean presence, Object operand) {
    if (operand instanceof Unknown) return operand;
    boolean lifted = Boolean.TRUE.equals(value) || operand instanceof OptionalValue;
    if (operand instanceof OptionalValue o) {
      if (!o.present()) return presence ? false : o;
      operand = o.value();
    }
    if (operand instanceof Map<?, ?> map) {
      boolean present = map.containsKey(name);
      if (presence) return present;
      if (lifted) return present ? OptionalValue.of(map.get(name)) : OptionalValue.none();
      if (!present) throw Values.error(CelException.Code.NO_SUCH_KEY, "no such key: " + name);
      return map.get(name);
    }
    for (ObjectType type : ctx.environment.runtimeObjectTypes)
      if (type.accepts(operand)) {
        if (!type.fields().containsKey(name))
          throw Values.error(CelException.Code.NO_SUCH_FIELD, "no such field: " + name);
        if (presence) return type.has(operand, name);
        if (lifted)
          return type.has(operand, name)
              ? OptionalValue.of(type.get(operand, name))
              : OptionalValue.none();
        return Values.in(type.get(operand, name));
      }
    throw Values.overload("select " + name);
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object call(VirtualFrame frame, EvalContext ctx) {
    if (scalarOperator != 0 && children.length == 2 && !extensionCall)
      return scalarBinary(frame, ctx);
    if (name.equals("&&") || name.equals("||")) return logical(frame, ctx);
    if (optionalFallback && children.length == 2) {
      Object first = children[0].execute(frame);
      if (first instanceof Unknown) return first;
      OptionalValue o = optional(first);
      if (o.present()) return name.equals("or") ? o : o.value();
      Object fallback = children[1].execute(frame);
      return name.equals("or") && !(fallback instanceof Unknown) ? optional(fallback) : fallback;
    }
    Object[] args = new Object[children.length];
    CelException error = null;
    Unknown unknown = null;
    for (int i = 0; i < children.length; i++) {
      try {
        args[i] = children[i].execute(frame);
        if (args[i] instanceof Unknown u) unknown = unknown == null ? u : unknown.merge(u);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        error = e;
      }
    }
    if (unknown != null) return unknown;
    if (error != null) throw error;
    if (extensionCall) return ExtensionFunctions.apply(name, args, ctx);
    return Functions.apply(name, args, ctx.environment);
  }

  // Avoid temporary argument arrays and repeated string dispatch in the interpreter.
  final Object scalarBinary(VirtualFrame frame, EvalContext ctx) {
    Object left = null, right = null;
    CelException error = null;
    try {
      left = children[0].execute(frame);
    } catch (CelException e) {
      if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
      error = e;
    }
    try {
      right = children[1].execute(frame);
    } catch (CelException e) {
      if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
      error = e;
    }
    if (left instanceof Unknown u) return right instanceof Unknown v ? u.merge(v) : u;
    if (right instanceof Unknown) return right;
    if (error != null) throw error;
    if (left instanceof Long x && right instanceof Long y) {
      try {
        return switch (scalarOperator) {
          case '+' -> Math.addExact(x, y);
          case '-' -> Math.subtractExact(x, y);
          case '<' -> x < y;
          case '>' -> x > y;
          case 'L' -> x <= y;
          case 'G' -> x >= y;
          case 'E' ->
              ctx.environment.runtimeObjectTypes.length == 0
                  ? x.longValue() == y.longValue()
                  : Functions.apply(name, new Object[] {left, right}, ctx.environment);
          case 'N' ->
              ctx.environment.runtimeObjectTypes.length == 0
                  ? x.longValue() != y.longValue()
                  : Functions.apply(name, new Object[] {left, right}, ctx.environment);
          case '/' -> {
            if (y == 0) throw Values.error(CelException.Code.DIVIDE_BY_ZERO, "division by zero");
            if (x == Long.MIN_VALUE && y == -1) throw new ArithmeticException();
            yield x / y;
          }
          case '%' -> {
            if (y == 0) throw Values.error(CelException.Code.DIVIDE_BY_ZERO, "division by zero");
            yield x % y;
          }
          default -> Math.multiplyExact(x, y);
        };
      } catch (ArithmeticException e) {
        throw Values.error(CelException.Code.OVERFLOW, "numeric overflow");
      }
    }
    return Functions.apply(name, new Object[] {left, right}, ctx.environment);
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object logical(VirtualFrame frame, EvalContext ctx) {
    boolean decisive = name.equals("||");
    CelException error = null;
    Unknown unknown = null;
    for (ExprNode child : children) {
      try {
        Object result = child.execute(frame);
        if (result instanceof Boolean b) {
          if (b == decisive) return b;
        } else if (result instanceof Unknown u) unknown = unknown == null ? u : unknown.merge(u);
        else error = Values.overload(name);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        error = e;
      }
    }
    if (unknown != null) return unknown;
    if (error != null) throw error;
    return !decisive;
  }

  static OptionalValue optional(Object value) {
    if (value instanceof OptionalValue o) return o;
    throw Values.overload("optional");
  }

  private Object macro(VirtualFrame frame, EvalContext ctx) {
    if (name.equals("has")) return children[0].executePresence(frame);
    Object range = children[0].execute(frame);
    if (range instanceof Unknown) return range;
    String[] variables = (String[]) value;
    if (name.equals("optMap") || name.equals("optFlatMap")) {
      OptionalValue o = optional(range);
      if (!o.present()) return o;
      bindings[0].execute(frame, o.value());
      try {
        Object mapped = children[1].execute(frame);
        if (mapped instanceof Unknown) return mapped;
        return name.equals("optMap") ? OptionalValue.of(mapped) : optional(mapped);
      } finally {
        ctx.pop();
      }
    }
    Object[] values = HostCollections.range(range);
    boolean mapResult = name.equals("transformMap") || name.equals("transformMapEntry");
    boolean sorting = name.equals("sortBy");
    boolean mapping = name.equals("map") || name.startsWith("transform");
    boolean filtering = name.equals("filter");
    var mapped = mapResult ? HostCollections.<Object>map() : null;
    var sortKeys = sorting ? HostCollections.list() : null;
    long index = 0;
    var result = !mapResult && (sorting || mapping || filtering) ? HostCollections.list() : null;
    long count = 0;
    Unknown unknown = null;
    CelException error = null;
    for (Object item : values) {
      ctx.tick();
      Object key = range instanceof List<?> && variables.length == 2 ? index : item;
      bindings[0].execute(frame, key);
      if (variables.length == 2)
        bindings[1].execute(
            frame, range instanceof Map<?, ?> m ? HostCollections.get(m, item) : item);
      index++;
      try {
        Object predicate = children[1].execute(frame);
        if (predicate instanceof Unknown u) {
          unknown = unknown == null ? u : unknown.merge(u);
          continue;
        }
        if (sorting) {
          HostCollections.add(sortKeys, predicate);
          HostCollections.add(result, item);
          continue;
        }
        if (mapping) {
          if (children.length == 3) {
            if (!(predicate instanceof Boolean b)) throw Values.overload(name);
            if (!b) continue;
            predicate = children[2].execute(frame);
            if (predicate instanceof Unknown u) {
              unknown = unknown == null ? u : unknown.merge(u);
              continue;
            }
          }
          if (name.equals("transformMap")) HostCollections.put(mapped, key, predicate);
          else if (name.equals("transformMapEntry")) {
            if (!(predicate instanceof Map<?, ?> entries)) throw Values.overload(name);
            HostCollections.mergeEntries(mapped, entries);
          } else HostCollections.add(result, predicate);
          continue;
        }
        if (!(predicate instanceof Boolean b)) throw Values.overload(name);
        if (name.equals("all") && !b) return false;
        if (name.equals("exists") && b) return true;
        if (b) {
          count++;
          if (filtering) HostCollections.add(result, item);
        }
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        if (!name.equals("all") && !name.equals("exists")) throw e;
        error = e;
      } finally {
        for (String ignored : variables) ctx.pop();
      }
    }
    if (unknown != null) return unknown;
    if (error != null) throw error;
    return switch (name) {
      case "all" -> true;
      case "exists" -> false;
      case "exists_one", "existsOne" -> count == 1;
      case "transformMap", "transformMapEntry" -> HostCollections.freezeMap(mapped);
      case "sortBy" -> ExtensionFunctions.sortBy(result, sortKeys, ctx);
      default -> HostCollections.freezeList(result);
    };
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object list(VirtualFrame frame, EvalContext ctx) {
    var list = HostCollections.list();
    Unknown unknown = null;
    for (ExprNode child : children) {
      Object v = child.execute(frame);
      if (v instanceof Unknown u) unknown = unknown == null ? u : unknown.merge(u);
      if (child.optionalEntry && !(v instanceof Unknown)) {
        OptionalValue o = optional(v);
        if (!o.present()) continue;
        v = o.value();
      }
      HostCollections.add(list, v);
    }
    return unknown == null ? HostCollections.freezeList(list) : unknown;
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object map(VirtualFrame frame, EvalContext ctx) {
    var map = HostCollections.<Object>map();
    Unknown unknown = null;
    for (int i = 0; i < children.length; i += 2) {
      Object k = children[i].execute(frame), v = children[i + 1].execute(frame);
      if (k instanceof Unknown u) {
        unknown = unknown == null ? u : unknown.merge(u);
        continue;
      }
      if (v instanceof Unknown u) {
        unknown = unknown == null ? u : unknown.merge(u);
        continue;
      }
      if (children[i + 1].optionalEntry) {
        OptionalValue o = optional(v);
        if (!o.present()) continue;
        v = o.value();
      }
      if (!Values.validKey(k))
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid map key");
      if (Values.key(map, k) != null)
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "duplicate map key");
      HostCollections.put(map, k, v);
    }
    return unknown == null ? HostCollections.freezeMap(map) : unknown;
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object object(VirtualFrame frame, EvalContext ctx) {
    var fields = HostCollections.<String>map();
    String[] names = (String[]) value;
    for (int i = 0; i < children.length; i++) {
      Object v = children[i].execute(frame);
      if (children[i].optionalEntry && !(v instanceof Unknown)) {
        OptionalValue o = optional(v);
        if (!o.present()) continue;
        v = o.value();
      }
      if (v instanceof Unknown) return v;
      HostCollections.put(fields, names[i], v);
    }
    return construct(ctx, fields);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private Object construct(EvalContext ctx, Map<String, Object> fields) {
    try {
      Object constructed =
          ctx.environment.objectTypes.get(name).construct(Collections.unmodifiableMap(fields));
      if (constructed instanceof WellKnownTypes.Any any) {
        String type = any.typeUrl().substring(any.typeUrl().lastIndexOf('/') + 1);
        ObjectType adapter = ctx.environment.objectTypes.get(type);
        if (adapter == null)
          throw Values.error(CelException.Code.INVALID_ARGUMENT, "unregistered Any type: " + type);
        constructed = adapter.decode(any.bytes());
      }
      return constructed;
    } catch (ArithmeticException | java.time.DateTimeException e) {
      throw Values.error(CelException.Code.OVERFLOW, "protobuf value out of range");
    }
  }
}
