package dev.trufflecel;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import java.math.BigInteger;

final class Values {
  private Values() {}

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static Object in(Object v) {
    // Canonical scalar inputs have no nested data to count or snapshot.
    if (v instanceof Long || v instanceof Boolean || v instanceof String || v instanceof Double)
      return v;
    return in(v, 0, new int[] {1_000_000});
  }

  private static Object in(Object v, int depth, int[] remaining) {
    if (depth > 100 || --remaining[0] < 0)
      throw error(CelException.Code.RESOURCE_EXHAUSTED, "host data limit exceeded");
    if (v == null) return CelNull.INSTANCE;
    if (v instanceof Byte || v instanceof Short || v instanceof Integer)
      return ((Number) v).longValue();
    if (v instanceof Float f) return f.doubleValue();
    if (v instanceof byte[] b) return new Bytes(b);
    if (v instanceof Map<?, ?> m) {
      var r = new LinkedHashMap<Object, Object>();
      m.forEach((k, value) -> r.put(in(k, depth + 1, remaining), in(value, depth + 1, remaining)));
      return Collections.unmodifiableMap(r);
    }
    if (v instanceof List<?> values) {
      int size = values.size();
      if (size == 0) return List.of();
      if (size > remaining[0])
        throw error(CelException.Code.RESOURCE_EXHAUSTED, "host data limit exceeded");
      Object[] snapshot = values.toArray();
      for (int i = 0; i < snapshot.length; i++) snapshot[i] = in(snapshot[i], depth + 1, remaining);
      return new InputList(snapshot);
    }
    if (v instanceof Iterable<?> values) {
      var r = new ArrayList<Object>();
      values.forEach(value -> r.add(in(value, depth + 1, remaining)));
      return List.copyOf(r);
    }
    if (v instanceof Object[] values) return in(Arrays.asList(values), depth + 1, remaining);
    return v;
  }

  /** Internal read-only iteration may share our owned snapshot; public toArray still copies. */
  static Object[] listRange(List<?> list) {
    return list instanceof InputList input ? input.values : list.toArray();
  }

  /** Owns a normalized snapshot; no host array or mutable list is retained. */
  private static final class InputList extends AbstractList<Object> implements RandomAccess {
    private final Object[] values;

    InputList(Object[] values) {
      this.values = values;
    }

    @Override
    public Object get(int index) {
      return values[index];
    }

    @Override
    public int size() {
      return values.length;
    }

    @Override
    public Object[] toArray() {
      return values.clone();
    }
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static Object out(Object v) {
    if (v == CelNull.INSTANCE) return null;
    if (v instanceof Bytes b) return b.toByteArray();
    if (v instanceof List<?> l) {
      var r = new ArrayList<Object>();
      l.forEach(value -> r.add(out(value)));
      return Collections.unmodifiableList(r);
    }
    if (v instanceof Map<?, ?> m) {
      var r = new LinkedHashMap<Object, Object>();
      m.forEach((k, value) -> r.put(out(k), out(value)));
      return Collections.unmodifiableMap(r);
    }
    return v;
  }

  static CelType type(Object v) {
    if (v instanceof Boolean) return CelType.BOOL;
    if (v instanceof Long) return CelType.INT;
    if (v instanceof UInt) return CelType.UINT;
    if (v instanceof Double) return CelType.DOUBLE;
    if (v instanceof String) return CelType.STRING;
    if (v instanceof Bytes) return CelType.BYTES;
    if (v == CelNull.INSTANCE) return CelType.NULL;
    if (v instanceof List<?>) return CelType.of("list");
    if (v instanceof Map<?, ?>) return CelType.of("map");
    if (v instanceof Instant) return CelType.TIMESTAMP;
    if (v instanceof Duration) return CelType.DURATION;
    if (v instanceof CelType) return CelType.TYPE;
    if (v instanceof NetworkValue.Address) return CelType.of("net.IP");
    if (v instanceof NetworkValue.Prefix) return CelType.of("net.CIDR");
    if (v instanceof OptionalValue) return CelType.of("optional_type");
    return CelType.DYN;
  }

  static boolean numeric(Object v) {
    return v instanceof Long || v instanceof UInt || v instanceof Double;
  }

  static BigDecimal decimal(Object v) {
    if (v instanceof Long l) return BigDecimal.valueOf(l);
    if (v instanceof UInt u) return new BigDecimal(u.toBigInteger());
    return new BigDecimal((Double) v);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static boolean equal(Object a, Object b) {
    if (a instanceof OptionalValue x && b instanceof OptionalValue y)
      return x.present() == y.present() && (!x.present() || equal(x.value(), y.value()));
    if (a instanceof Long x && b instanceof Long y) return x.longValue() == y.longValue();
    if (a instanceof UInt x && b instanceof UInt y) return x.bits() == y.bits();
    if (a instanceof Double x && b instanceof Double y) return x.doubleValue() == y.doubleValue();
    if (numeric(a) && numeric(b)) {
      // Double/double equality was handled above; an integer cannot equal a non-finite double.
      if (a instanceof Double d && !Double.isFinite(d)) return false;
      if (b instanceof Double d && !Double.isFinite(d)) return false;
      return decimal(a).compareTo(decimal(b)) == 0;
    }
    if (a instanceof CelType x && b instanceof CelType y) return x.name().equals(y.name());
    if (a instanceof List<?> x && b instanceof List<?> y) {
      if (x.size() != y.size()) return false;
      for (int i = 0; i < x.size(); i++) if (!equal(x.get(i), y.get(i))) return false;
      return true;
    }
    if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
      if (x.size() != y.size()) return false;
      for (var entry : x.entrySet()) {
        Object key = key(y, entry.getKey());
        if (key == null || !equal(entry.getValue(), y.get(key))) return false;
      }
      return true;
    }
    return a.equals(b);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static Object key(Map<?, ?> map, Object key) {
    if (map.containsKey(key)) return key;
    if (numeric(key))
      for (Object candidate : map.keySet()) if (equal(candidate, key)) return candidate;
    return null;
  }

  static boolean validKey(Object key) {
    return key instanceof Boolean
        || key instanceof Long
        || key instanceof UInt
        || key instanceof String;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static Object index(Object value, Object index) {
    if (value instanceof Map<?, ?> m) {
      Object k = key(m, index);
      if (k == null) throw error(CelException.Code.NO_SUCH_KEY, "no such key: " + index);
      return m.get(k);
    }
    if (value instanceof List<?> l && numeric(index)) {
      BigDecimal i;
      try {
        i = decimal(index);
      } catch (NumberFormatException e) {
        throw error(CelException.Code.INDEX, "invalid list index");
      }
      int n;
      try {
        n = i.intValueExact();
      } catch (ArithmeticException e) {
        throw error(CelException.Code.INDEX, "invalid list index");
      }
      if (n < 0 || n >= l.size()) throw error(CelException.Code.INDEX, "index out of bounds: " + n);
      return l.get(n);
    }
    throw overload("index");
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static CelException error(CelException.Code code, String message) {
    return new CelException(code, message);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static CelException overload(String name) {
    return error(CelException.Code.NO_OVERLOAD, "no matching overload: " + name);
  }
}
