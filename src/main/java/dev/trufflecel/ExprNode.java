package dev.trufflecel;

import com.oracle.truffle.api.nodes.Node;
import java.util.*;

/** Each parsed expression becomes an adopted Truffle AST node, never a delegated interpreter. */
final class ExprNode extends Node {
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
  final int offset;
  @Children ExprNode[] children;

  ExprNode(Kind kind, String name, Object value, int offset, ExprNode... children) {
    this.kind = kind;
    this.name = name;
    this.value = value;
    this.offset = offset;
    this.children = children;
  }

  static ExprNode constant(Object value, int offset) {
    return new ExprNode(Kind.CONSTANT, "", value, offset);
  }

  String qualifiedName() {
    if (kind == Kind.IDENT) return name;
    if (kind == Kind.SELECT) {
      String prefix = children[0].qualifiedName();
      if (prefix != null) return prefix + "." + name;
    }
    return null;
  }

  void free(Set<String> bound, Set<String> out) {
    if (kind == Kind.IDENT && !bound.contains(name)) out.add(name);
    if (kind == Kind.MACRO && !name.equals("has")) {
      children[0].free(bound, out);
      var inner = new HashSet<>(bound);
      inner.add((String) value);
      for (int i = 1; i < children.length; i++) children[i].free(inner, out);
    } else for (ExprNode child : children) child.free(bound, out);
  }

  Object execute(EvalContext ctx) {
    ctx.tick();
    return switch (kind) {
      case CONSTANT -> value;
      case IDENT -> ctx.get(name);
      case SELECT -> select(ctx, false);
      case INDEX -> {
        Object a = children[0].execute(ctx), b = children[1].execute(ctx);
        if (a instanceof Unknown u) yield u;
        if (b instanceof Unknown u) yield u;
        yield Values.index(a, b);
      }
      case LIST -> {
        var list = new ArrayList<Object>(children.length);
        Unknown unknown = null;
        for (ExprNode child : children) {
          Object v = child.execute(ctx);
          if (v instanceof Unknown u) unknown = unknown == null ? u : unknown.merge(u);
          list.add(v);
        }
        yield unknown == null ? List.copyOf(list) : unknown;
      }
      case MAP -> {
        var map = new LinkedHashMap<Object, Object>();
        Unknown unknown = null;
        for (int i = 0; i < children.length; i += 2) {
          Object k = children[i].execute(ctx), v = children[i + 1].execute(ctx);
          if (k instanceof Unknown u) {
            unknown = unknown == null ? u : unknown.merge(u);
            continue;
          }
          if (v instanceof Unknown u) {
            unknown = unknown == null ? u : unknown.merge(u);
            continue;
          }
          if (!Values.validKey(k))
            throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid map key");
          if (Values.key(map, k) != null)
            throw Values.error(CelException.Code.INVALID_ARGUMENT, "duplicate map key: " + k);
          map.put(k, v);
        }
        yield unknown == null ? Collections.unmodifiableMap(map) : unknown;
      }
      case OBJECT -> {
        var fields = new LinkedHashMap<String, Object>();
        String[] names = (String[]) value;
        for (int i = 0; i < children.length; i++) fields.put(names[i], children[i].execute(ctx));
        try {
          Object constructed =
              ctx.environment.objectTypes.get(name).construct(Collections.unmodifiableMap(fields));
          if (constructed instanceof WellKnownTypes.Any any) {
            String type = any.typeUrl().substring(any.typeUrl().lastIndexOf('/') + 1);
            ObjectType adapter = ctx.environment.objectTypes.get(type);
            if (adapter == null)
              throw Values.error(
                  CelException.Code.INVALID_ARGUMENT, "unregistered Any type: " + type);
            constructed = adapter.decode(any.bytes());
          }
          yield constructed;
        } catch (ArithmeticException | java.time.DateTimeException e) {
          throw Values.error(CelException.Code.OVERFLOW, "protobuf value out of range");
        }
      }
      case CONDITIONAL -> {
        Object condition = children[0].execute(ctx);
        if (condition instanceof Unknown) yield condition;
        if (!(condition instanceof Boolean b)) throw Values.overload("conditional");
        yield children[b ? 1 : 2].execute(ctx);
      }
      case CALL -> call(ctx);
      case MACRO -> macro(ctx);
    };
  }

  private Object select(EvalContext ctx, boolean presence) {
    Object operand = children[0].execute(ctx);
    if (operand instanceof Unknown) return operand;
    if (operand instanceof Map<?, ?> map) {
      if (presence) return map.containsKey(name);
      if (!map.containsKey(name))
        throw Values.error(CelException.Code.NO_SUCH_KEY, "no such key: " + name);
      return map.get(name);
    }
    for (ObjectType type : ctx.environment.objectTypes.values())
      if (type.accepts(operand)) {
        if (!type.fields().containsKey(name))
          throw Values.error(CelException.Code.NO_SUCH_FIELD, "no such field: " + name);
        return presence ? type.has(operand, name) : Values.in(type.get(operand, name));
      }
    throw Values.overload("select " + name);
  }

  private Object call(EvalContext ctx) {
    if (name.equals("&&") || name.equals("||")) return logical(ctx);
    Object[] args = new Object[children.length];
    CelException error = null;
    Unknown unknown = null;
    for (int i = 0; i < children.length; i++) {
      try {
        args[i] = children[i].execute(ctx);
        if (args[i] instanceof Unknown u) unknown = unknown == null ? u : unknown.merge(u);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        error = e;
      }
    }
    if (unknown != null) return unknown;
    if (error != null) throw error;
    return Functions.apply(name, args, ctx.environment);
  }

  private Object logical(EvalContext ctx) {
    boolean decisive = name.equals("||");
    CelException error = null;
    Unknown unknown = null;
    for (ExprNode child : children) {
      try {
        Object result = child.execute(ctx);
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

  private Object macro(EvalContext ctx) {
    if (name.equals("has")) return children[0].select(ctx, true);
    Object range = children[0].execute(ctx);
    if (range instanceof Unknown) return range;
    Iterable<?> values;
    if (range instanceof List<?> list) values = list;
    else if (range instanceof Map<?, ?> map) values = map.keySet();
    else throw Values.overload(name);
    var result = new ArrayList<Object>();
    long count = 0;
    Unknown unknown = null;
    CelException error = null;
    for (Object item : values) {
      ctx.tick();
      ctx.push((String) value, item);
      try {
        Object predicate = children[1].execute(ctx);
        if (predicate instanceof Unknown u) {
          unknown = unknown == null ? u : unknown.merge(u);
          continue;
        }
        if (name.equals("map") && children.length == 2) {
          result.add(predicate);
          continue;
        }
        if (!(predicate instanceof Boolean b)) throw Values.overload(name);
        if (name.equals("all") && !b) return false;
        if (name.equals("exists") && b) return true;
        if (b) {
          count++;
          if (name.equals("filter")) result.add(item);
          if (name.equals("map")) result.add(children[2].execute(ctx));
        }
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        if (!name.equals("all") && !name.equals("exists")) throw e;
        error = e;
      } finally {
        ctx.pop();
      }
    }
    if (unknown != null) return unknown;
    if (error != null) throw error;
    return switch (name) {
      case "all" -> true;
      case "exists" -> false;
      case "exists_one" -> count == 1;
      default -> List.copyOf(result);
    };
  }
}
