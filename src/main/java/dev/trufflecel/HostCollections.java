package dev.trufflecel;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.math.BigInteger;
import java.util.*;

/** Host collection operations; AST child evaluation stays outside these boundaries. */
final class HostCollections {
  private HostCollections() {}

  @TruffleBoundary
  static <K> Map<K, Object> map() {
    return new LinkedHashMap<>();
  }

  @TruffleBoundary
  static NumericKeyMap numericKeyMap() {
    return new NumericKeyMap();
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

  /**
   * Insertion-ordered map accumulator that detects cross-type numeric ({@code Long}/{@code UInt})
   * duplicate keys in O(1) per entry, and charges each merged entry to the evaluation budget.
   *
   * <p>The canonical numeric-key index mirrors the cross-numeric equality used by {@link
   * Values#equal}: {@code Long} and {@code UInt} collide iff their {@link BigInteger} canonical
   * values match (e.g. {@code Long 1} and {@code UInt 1} share {@code BigInteger(1)}, while
   * negative {@code Long}s and the {@code UInt}s that share their bit pattern never collide).
   * Non-numeric keys fall back to the underlying map's {@link Map#containsKey} semantics.
   *
   * <p>Without this index, the prior implementation linearly scanned the accumulated key set on
   * every numeric-key insertion (via {@link Values#key}), making {@code transformMapEntry} and
   * {@code cel.@mapInsert} build n-entry maps with ~n²/2 uncharged comparisons — bypassing the
   * evaluation budget's bounded-CPU guarantee. Defense in depth: each merged entry pays one {@link
   * EvalContext#tick()} so any remaining super-linear host work stays visible to the bound.
   */
  static final class NumericKeyMap {
    final Map<Object, Object> map = new LinkedHashMap<>();
    private Set<BigInteger> numerics;

    /**
     * Append without duplicate detection; silently overwrites same-type keys and tolerates
     * cross-type numeric "duplicates" side by side, matching the prior {@code transformMap}
     * behavior (keys originate from a receiver whose host snapshot may already contain both).
     */
    @TruffleBoundary
    void put(Object key, Object value) {
      if (indexable(key)) indexAdd(key);
      map.put(key, value);
    }

    /** Merge entries with strict cross-type duplicate detection; charges one tick per entry. */
    @TruffleBoundary
    void mergeEntries(Map<?, ?> entries, EvalContext ctx) {
      for (var entry : entries.entrySet()) {
        Object key = entry.getKey();
        ctx.tick();
        if (!Values.validKey(key) || has(key))
          throw Values.error(
              CelException.Code.INVALID_ARGUMENT, "invalid or duplicate transformed key");
        if (indexable(key)) indexAdd(key);
        map.put(key, entry.getValue());
      }
    }

    /**
     * Append one literal entry with strict cross-type duplicate detection. Map literals are bounded
     * by AST size, so defense-in-depth charging stays in {@link #mergeEntries} where {@code
     * transformMapEntry}/{@code cel.@mapInsert} can amplify it across data-driven iterations.
     */
    @TruffleBoundary
    void putLiteral(Object key, Object value) {
      if (!Values.validKey(key))
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid map key");
      if (has(key)) throw Values.error(CelException.Code.INVALID_ARGUMENT, "duplicate map key");
      if (indexable(key)) indexAdd(key);
      map.put(key, value);
    }

    Map<?, ?> freeze() {
      return Collections.unmodifiableMap(map);
    }

    private boolean has(Object key) {
      if (map.containsKey(key)) return true;
      if (!indexable(key) || numerics == null) return false;
      return numerics.contains(canonical(key));
    }

    private void indexAdd(Object key) {
      if (numerics == null) numerics = new HashSet<>();
      numerics.add(canonical(key));
    }

    private static boolean indexable(Object key) {
      // Doubles are not valid map keys (Values.validKey rejects them) and have no cross-type
      // counterpart at construction time; restrict the index to Long/UInt for safe casts.
      return key instanceof Long || key instanceof UInt;
    }

    private static BigInteger canonical(Object key) {
      return key instanceof Long l ? BigInteger.valueOf(l) : ((UInt) key).toBigInteger();
    }
  }
}
