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
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean optionalEntry;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean extensionCall;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean optionalFallback;
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
      case LIST -> list(ctx);
      case MAP -> map(ctx);
      case OBJECT -> object(ctx);
      case CONDITIONAL -> {
        Object condition = children[0].execute(ctx);
        if (condition instanceof Unknown) yield condition;
        if (!(condition instanceof Boolean b)) throw Values.overload("conditional");
        yield b ? children[1].execute(ctx) : children[2].execute(ctx);
      }
      case CALL -> call(ctx);
      case MACRO -> macro(ctx);
    };
  }

  private Object select(EvalContext ctx, boolean presence) {
    return selectValue(ctx, presence, children[0].execute(ctx));
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
  private Object call(EvalContext ctx) {
    if (name.equals("&&") || name.equals("||")) return logical(ctx);
    if (optionalFallback && children.length == 2) {
      Object first = children[0].execute(ctx);
      if (first instanceof Unknown) return first;
      OptionalValue o = optional(first);
      if (o.present()) return name.equals("or") ? o : o.value();
      Object fallback = children[1].execute(ctx);
      return name.equals("or") && !(fallback instanceof Unknown) ? optional(fallback) : fallback;
    }
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
    if (extensionCall) return ExtensionFunctions.apply(name, args, ctx);
    return Functions.apply(name, args, ctx.environment);
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
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

  static OptionalValue optional(Object value) {
    if (value instanceof OptionalValue o) return o;
    throw Values.overload("optional");
  }

  private Object macro(EvalContext ctx) {
    if (name.equals("has")) return children[0].select(ctx, true);
    Object range = children[0].execute(ctx);
    if (range instanceof Unknown) return range;
    String[] variables = (String[]) value;
    if (name.equals("optMap") || name.equals("optFlatMap")) {
      OptionalValue o = optional(range);
      if (!o.present()) return o;
      ctx.push(variables[0], o.value());
      try {
        Object mapped = children[1].execute(ctx);
        if (mapped instanceof Unknown) return mapped;
        return name.equals("optMap") ? OptionalValue.of(mapped) : optional(mapped);
      } finally {
        ctx.pop();
      }
    }
    Object[] values = HostCollections.range(range);
    var mapped = HostCollections.<Object>map();
    var sortKeys = HostCollections.list();
    long index = 0;
    var result = HostCollections.list();
    long count = 0;
    Unknown unknown = null;
    CelException error = null;
    for (Object item : values) {
      ctx.tick();
      Object key = range instanceof List<?> && variables.length == 2 ? index : item;
      ctx.push(variables[0], key);
      if (variables.length == 2)
        ctx.push(variables[1], range instanceof Map<?, ?> m ? HostCollections.get(m, item) : item);
      index++;
      try {
        Object predicate = children[1].execute(ctx);
        if (predicate instanceof Unknown u) {
          unknown = unknown == null ? u : unknown.merge(u);
          continue;
        }
        if (name.equals("sortBy")) {
          HostCollections.add(sortKeys, predicate);
          HostCollections.add(result, item);
          continue;
        }
        if (name.equals("map") || name.startsWith("transform")) {
          if (children.length == 3) {
            if (!(predicate instanceof Boolean b)) throw Values.overload(name);
            if (!b) continue;
            predicate = children[2].execute(ctx);
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
          if (name.equals("filter")) HostCollections.add(result, item);
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
  private Object list(EvalContext ctx) {
    var list = HostCollections.list();
    Unknown unknown = null;
    for (ExprNode child : children) {
      Object v = child.execute(ctx);
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
  private Object map(EvalContext ctx) {
    var map = HostCollections.<Object>map();
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
  private Object object(EvalContext ctx) {
    var fields = HostCollections.<String>map();
    String[] names = (String[]) value;
    for (int i = 0; i < children.length; i++) {
      Object v = children[i].execute(ctx);
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
