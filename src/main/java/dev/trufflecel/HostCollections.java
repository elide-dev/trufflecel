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

  static Values.ListBuilder list() {
    return new Values.ListBuilder();
  }

  static void add(Values.ListBuilder list, Object value) {
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

  static List<?> freezeList(Values.ListBuilder list) {
    return list.finish();
  }

  static Values.OwnedList range(Object range) {
    if (range instanceof Values.OwnedList list) return list;
    return snapshotRange(range);
  }

  @TruffleBoundary
  private static Values.OwnedList snapshotRange(Object range) {
    if (range instanceof List<?> list) return Values.listRange(list);
    if (range instanceof Map<?, ?> map) return Values.mapRange(map);
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
