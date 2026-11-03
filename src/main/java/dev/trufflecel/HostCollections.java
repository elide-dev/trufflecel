package dev.trufflecel;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.*;

/** Host collection operations; AST child evaluation stays outside these boundaries. */
final class HostCollections {
  private HostCollections() {}

  @TruffleBoundary
  static <K> Map<K, Object> map() {
    return new LinkedHashMap<>();
  }

  @TruffleBoundary
  static List<Object> list() {
    return new ArrayList<>();
  }

  @TruffleBoundary
  static void add(List<Object> list, Object value) {
    list.add(value);
  }

  @TruffleBoundary
  static <K> void put(Map<K, Object> map, K key, Object value) {
    map.put(key, value);
  }

  @TruffleBoundary
  static Object get(Map<?, ?> map, Object key) {
    return map.get(key);
  }

  @TruffleBoundary
  static Map<?, ?> freezeMap(Map<?, ?> map) {
    return Collections.unmodifiableMap(map);
  }

  @TruffleBoundary
  static List<?> freezeList(List<?> list) {
    return List.copyOf(list);
  }

  @TruffleBoundary
  static Object[] range(Object range) {
    if (range instanceof List<?> list) return list.toArray();
    if (range instanceof Map<?, ?> map) return map.keySet().toArray();
    throw Values.overload("comprehension");
  }

  @TruffleBoundary
  static void mergeEntries(Map<Object, Object> map, Map<?, ?> entries) {
    for (var entry : entries.entrySet()) {
      if (!Values.validKey(entry.getKey()) || Values.key(map, entry.getKey()) != null)
        throw Values.error(
            CelException.Code.INVALID_ARGUMENT, "invalid or duplicate transformed key");
      map.put(entry.getKey(), entry.getValue());
    }
  }
}
