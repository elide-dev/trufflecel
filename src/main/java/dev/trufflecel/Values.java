package dev.trufflecel;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import dev.elide.truffledata.DataArray;

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
      // Host size is only a bounded allocation hint; traversal enforces the actual node budget.
      int size = Math.clamp(m.size(), 0, 1024);
      OwnedMap snapshot = new OwnedMap(m, size, depth, remaining);
      if (snapshot.fallback != null) return Collections.unmodifiableMap(snapshot.fallback);
      return snapshot.length == 0 ? OwnedMap.EMPTY : Collections.unmodifiableMap(snapshot);
    }
    if (v instanceof List<?> values) {
      int size = values.size();
      if (size == 0) return List.of();
      if (size > remaining[0])
        throw error(CelException.Code.RESOURCE_EXHAUSTED, "host data limit exceeded");
      Object[] snapshot = values.toArray();
      for (int i = 0; i < snapshot.length; i++) snapshot[i] = in(snapshot[i], depth + 1, remaining);
      return new OwnedList(snapshot);
    }
    if (v instanceof Iterable<?> values) {
      var r = new ArrayList<Object>();
      values.forEach(value -> r.add(in(value, depth + 1, remaining)));
      return List.copyOf(r);
    }
    if (v instanceof Object[] values) return in(Arrays.asList(values), depth + 1, remaining);
    return v;
  }

  /** Compact insertion-ordered snapshots, with bounded hash chains and a collision fallback. */
  private static final class OwnedMap extends AbstractMap<Object, Object> {
    private static final Map<Object, Object> EMPTY =
        Collections.unmodifiableMap(new OwnedMap(Map.of(), 0, 0, new int[] {0}));
    private final Object[] entries;
    // Bucket heads followed by one next-link per entry; zero denotes the end of a chain.
    private final int[] index;
    private final int buckets;
    private int length;
    private Map<Object, Object> fallback;

    private OwnedMap(Map<?, ?> source, int size, int depth, int[] remaining) {
      buckets = Math.max(1, Integer.highestOneBit(Math.max(1, size) - 1) << 1);
      entries = new Object[size * 2];
      index = new int[buckets + size];
      source.forEach(
          (key, value) ->
              putNormalized(in(key, depth + 1, remaining), in(value, depth + 1, remaining)));
    }

    private int bucket(Object key) {
      int hash = Objects.hashCode(key);
      return (hash ^ (hash >>> 16)) & (buckets - 1);
    }

    private void putNormalized(Object key, Object value) {
      if (fallback != null) {
        fallback.put(key, value);
        return;
      }
      int bucket = bucket(key);
      int probes = 0;
      for (int link = index[bucket]; link != 0; link = index[buckets + link - 1]) {
        probes++;
        if (Objects.equals(key, entries[(link - 1) * 2])) {
          entries[(link - 1) * 2 + 1] = value;
          return;
        }
      }
      // Keep attacker-chosen hash collisions on the JDK's established map implementation.
      if (probes >= 8 || length * 2 == entries.length) {
        fallback = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) fallback.put(entries[i * 2], entries[i * 2 + 1]);
        fallback.put(key, value);
        return;
      }
      entries[length * 2] = key;
      entries[length * 2 + 1] = value;
      index[buckets + length] = index[bucket];
      index[bucket] = ++length;
    }

    private int find(Object key) {
      for (int link = index[bucket(key)]; link != 0; link = index[buckets + link - 1])
        if (Objects.equals(key, entries[(link - 1) * 2])) return (link - 1) * 2 + 1;
      return -1;
    }

    @Override
    public Object get(Object key) {
      int found = find(key);
      return found < 0 ? null : entries[found];
    }

    @Override
    public boolean containsKey(Object key) {
      return find(key) >= 0;
    }

    @Override
    public int size() {
      return length;
    }

    @Override
    public void forEach(java.util.function.BiConsumer<? super Object, ? super Object> consumer) {
      Objects.requireNonNull(consumer);
      for (int i = 0; i < length; i++) consumer.accept(entries[i * 2], entries[i * 2 + 1]);
    }

    @Override
    public Set<Entry<Object, Object>> entrySet() {
      return new AbstractSet<>() {
        public int size() {
          return length;
        }

        public Iterator<Entry<Object, Object>> iterator() {
          return new Iterator<>() {
            private int cursor;

            public boolean hasNext() {
              return cursor < length;
            }

            public Entry<Object, Object> next() {
              if (!hasNext()) throw new NoSuchElementException();
              int position = cursor++ * 2;
              return new SimpleImmutableEntry<>(entries[position], entries[position + 1]);
            }
          };
        }
      };
    }
  }

  /** Internal read-only iteration may share our owned snapshot; public toArray still copies. */
  static OwnedList listRange(List<?> list) {
    return new OwnedList(list.toArray());
  }

  static OwnedList mapRange(Map<?, ?> map) {
    return new OwnedList(map.keySet().toArray());
  }

  /** Only a qualified protobuf scalar field may supply already canonical immutable elements. */
  static OwnedList scalarRange(DataArray values) {
    if (values.size() >= 1_000_000)
      throw error(CelException.Code.RESOURCE_EXHAUSTED, "host data limit exceeded");
    return values.size() == 0 ? OwnedList.EMPTY : new OwnedList(values);
  }

  /** Transfer internal, canonical storage; callers must not retain mutable aliases. */
  static OwnedList ownedList(Object[] values) {
    if (values.length >= 1_000_000)
      throw error(CelException.Code.RESOURCE_EXHAUSTED, "host data limit exceeded");
    return values.length == 0 ? OwnedList.EMPTY : new OwnedList(values);
  }

  static Map<?, ?> ownedMap(Map<Object, Object> values) {
    if (values.size() >= 500_000)
      throw error(CelException.Code.RESOURCE_EXHAUSTED, "host data limit exceeded");
    return Collections.unmodifiableMap(values);
  }

  /** Owned immutable array or an immutable, canonical protobuf scalar range. */
  static final class OwnedList extends AbstractList<Object> implements RandomAccess {
    private static final OwnedList EMPTY = new OwnedList(new Object[0]);
    private final Object storage;
    private final int length;

    private OwnedList(Object storage) {
      this(
          storage, storage instanceof Object[] array ? array.length : ((DataArray) storage).size());
    }

    private OwnedList(Object storage, int length) {
      this.storage = storage;
      this.length = length;
    }

    @Override
    public Object get(int index) {
      Objects.checkIndex(index, length);
      return storage instanceof Object[] values ? values[index] : ((DataArray) storage).get(index);
    }

    @Override
    public int size() {
      return length;
    }

    @Override
    public Object[] toArray() {
      if (storage instanceof Object[] values) return Arrays.copyOf(values, length);
      Object[] copy = new Object[size()];
      for (int i = 0; i < copy.length; i++) copy[i] = get(i);
      return copy;
    }
  }

  /** Evaluation-local output storage. Finishing transfers ownership and resets the builder. */
  static final class ListBuilder extends AbstractList<Object> {
    private static final Object[] EMPTY = new Object[0];
    private Object[] values = EMPTY;
    private int length;

    public boolean add(Object value) {
      if (length == values.length) grow();
      values[length++] = value;
      return true;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private void grow() {
      values = Arrays.copyOf(values, values.length == 0 ? 8 : Math.multiplyExact(values.length, 2));
    }

    public Object get(int index) {
      return values[Objects.checkIndex(index, length)];
    }

    public int size() {
      return length;
    }

    OwnedList finish() {
      var result = length == 0 ? OwnedList.EMPTY : new OwnedList(values, length);
      values = EMPTY;
      length = 0;
      return result;
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
