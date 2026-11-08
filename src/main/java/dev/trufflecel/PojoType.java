package dev.trufflecel;

import java.util.*;
import java.util.function.Function;

/** Base for generated adapters. CEL values own snapshots; they never retain Java bean instances. */
public abstract class PojoType<T> implements ObjectType {
  private final String name;
  private final Map<String, CelType> fields;

  protected PojoType(String name, Map<String, CelType> fields) {
    this.name = Objects.requireNonNull(name);
    this.fields = Map.copyOf(fields);
  }

  public final String name() {
    return name;
  }

  public final Map<String, CelType> fields() {
    return fields;
  }

  /** Register this generated schema; register nested schemas explicitly if they are selected. */
  public final Environment.Builder register(Environment.Builder environment) {
    return environment.objectType(this);
  }

  public final Value snapshot(T value) {
    return snapshot(value, 100, 1_000_000);
  }

  /** Snapshot one stable host graph, bounding all nested objects and collections together. */
  public final Value snapshot(T value, int maxDepth, int maxNodes) {
    Objects.requireNonNull(value);
    var context = new SnapshotContext(maxDepth, maxNodes);
    return (Value) context.object(value, this);
  }

  protected abstract Map<String, Object> snapshotFields(T value, SnapshotContext context);

  public final boolean accepts(Object value) {
    return value instanceof Value snapshot && name.equals(snapshot.type);
  }

  public final Object construct(Map<String, Object> values) {
    var owned = new LinkedHashMap<String, Object>();
    var context = new SnapshotContext(100, 1_000_000);
    for (var entry : values.entrySet()) {
      CelType type = field(entry.getKey());
      owned.put(entry.getKey(), context.owned(entry.getValue(), type));
    }
    return new Value(name, owned);
  }

  private static CelType valueType(Object value) {
    return value instanceof Value snapshot ? CelType.of(snapshot.type) : Values.type(value);
  }

  private CelType field(String field) {
    CelType type = fields.get(field);
    if (type == null) throw Values.error(CelException.Code.NO_SUCH_KEY, field);
    return type;
  }

  private Value value(Object value) {
    if (!accepts(value)) throw Values.error(CelException.Code.TYPE, "expected snapshot of " + name);
    return (Value) value;
  }

  public final Object get(Object value, String field) {
    field(field);
    Object result = value(value).fields.get(field);
    if (result == null) throw Values.error(CelException.Code.NO_SUCH_KEY, field);
    return result;
  }

  public final boolean has(Object value, String field) {
    field(field);
    return value(value).fields.containsKey(field);
  }

  /** Immutable named object value, suitable for activation snapshots and concurrent evaluation. */
  public static final class Value {
    private final String type;
    private final Map<String, Object> fields;

    private Value(String type, Map<String, Object> fields) {
      this.type = type;
      this.fields = Map.copyOf(fields);
    }

    public String typeName() {
      return type;
    }

    public Map<String, Object> fields() {
      return fields;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Value value && type.equals(value.type) && fields.equals(value.fields);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, fields);
    }

    @Override
    public String toString() {
      return type + fields;
    }
  }

  /**
   * Traversal state shared by generated adapters; temporary host references leave with each call.
   */
  public static final class SnapshotContext {
    private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
    private final int maxDepth;
    private int remaining, depth;

    public SnapshotContext(int maxDepth, int maxNodes) {
      if (maxDepth < 1 || maxNodes < 1)
        throw new IllegalArgumentException("positive snapshot limits required");
      this.maxDepth = maxDepth;
      remaining = maxNodes;
    }

    private void count() {
      if (--remaining < 0)
        throw Values.error(
            CelException.Code.RESOURCE_EXHAUSTED, "POJO snapshot node limit exceeded");
    }

    private void enter(Object value) {
      count();
      if (depth >= maxDepth)
        throw Values.error(
            CelException.Code.RESOURCE_EXHAUSTED, "POJO snapshot depth limit exceeded");
      if (active.put(value, Boolean.TRUE) != null)
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "cyclic POJO snapshot");
      depth++;
    }

    private void leave(Object value) {
      active.remove(value);
      depth--;
    }

    public Object scalar(Object value) {
      count();
      Object scalar =
          Values.in(value instanceof Character character ? character.toString() : value);
      if (!(scalar instanceof Long
          || scalar instanceof Double
          || scalar instanceof Boolean
          || scalar instanceof String
          || scalar instanceof UInt
          || scalar instanceof Bytes
          || scalar instanceof java.time.Instant
          || scalar instanceof java.time.Duration
          || scalar == CelNull.INSTANCE))
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "unsupported POJO scalar");
      return scalar;
    }

    private Object owned(Object value, CelType type) {
      Object result;
      if (value instanceof List<?> values) {
        if (!type.name().equals("list")) throw invalidValue();
        result = list(values, item -> owned(item, Checker.parameter(type, 0)));
      } else if (value instanceof Map<?, ?> values) {
        if (!type.name().equals("map")) throw invalidValue();
        result =
            map(
                values,
                key -> owned(key, Checker.parameter(type, 0)),
                item -> owned(item, Checker.parameter(type, 1)));
      } else if (value instanceof Value) {
        count();
        result = value;
      } else result = scalar(value);
      if (result != CelNull.INSTANCE && !type.accepts(valueType(result))) throw invalidValue();
      return result;
    }

    private static CelException invalidValue() {
      return Values.error(CelException.Code.TYPE, "invalid POJO field value");
    }

    public Object enumeration(Enum<?> value) {
      return scalar(value == null ? null : value.name());
    }

    public <T> Object object(T value, PojoType<T> adapter) {
      if (value == null) return scalar(null);
      enter(value);
      try {
        return new Value(adapter.name, adapter.snapshotFields(value, this));
      } finally {
        leave(value);
      }
    }

    public <T> Object list(List<T> value, Function<T, Object> element) {
      if (value == null) return scalar(null);
      enter(value);
      try {
        var result = new ArrayList<Object>();
        for (T item : value) result.add(element.apply(item));
        return List.copyOf(result);
      } finally {
        leave(value);
      }
    }

    public <K, V> Object map(
        Map<K, V> value, Function<K, Object> key, Function<V, Object> element) {
      if (value == null) return scalar(null);
      enter(value);
      try {
        var result = new LinkedHashMap<Object, Object>();
        value.forEach((k, v) -> result.put(key.apply(k), element.apply(v)));
        return Collections.unmodifiableMap(result);
      } finally {
        leave(value);
      }
    }
  }
}
