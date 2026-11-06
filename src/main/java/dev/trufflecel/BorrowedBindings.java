package dev.trufflecel;

import java.util.*;

/**
 * Evaluation-local lazy normalization. The caller keeps the source stable until evaluation ends.
 */
final class BorrowedBindings {
  private static final Object MISSING = new Object();
  private final Map<String, ?> source;
  private final Map<String, Integer> layout;
  private Object[] normalized;

  BorrowedBindings(Map<String, ?> source, Map<String, Integer> layout) {
    this.source = source;
    this.layout = layout;
  }

  Object get(String name) {
    Integer slot = name == null ? null : layout.get(name);
    if (slot != null && normalized != null) {
      Object value = normalized[slot];
      if (value != null) return value == MISSING ? null : value;
    }
    Object raw = source.get(name);
    Object value = raw != null || source.containsKey(name) ? Values.in(raw) : MISSING;
    // Already canonical values need no conversion cache under the stable-input contract.
    if (value == raw) return value;
    if (slot != null) {
      if (normalized == null) normalized = new Object[layout.size()];
      normalized[slot] = value;
    }
    return value == MISSING ? null : value;
  }

  Map<String, Object> visible() {
    var values = new LinkedHashMap<String, Object>();
    for (String name : source.keySet()) values.put(name, get(name));
    return values;
  }
}
