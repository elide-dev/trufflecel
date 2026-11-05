package dev.trufflecel;

import java.util.*;

/**
 * Reusable input bindings. Host collections and bytes are snapshotted at preparation time, not on
 * each evaluation. Adapter and opaque values retain their own ownership and thread-safety
 * contracts; use immutable adapter values when sharing an activation. Evaluation scopes and budgets
 * are never stored here.
 */
public final class Activation {
  final Object binding;
  final Object value;

  private Activation(Object binding, Object value) {
    this.binding = binding;
    this.value = value;
  }

  /**
   * Eagerly snapshot all supplied bindings, including unused ones. Unlike borrowed map evaluation,
   * validation happens during preparation, using the same per-value normalization and bounds. Do
   * not mutate inputs while preparing the snapshot. Subsequent host collection mutations are not
   * visible to evaluations using this activation.
   */
  public static Activation snapshot(Map<String, ?> variables) {
    var owned = new HashMap<String, Object>();
    variables.forEach((name, value) -> owned.put(Objects.requireNonNull(name), Values.in(value)));
    if (owned.size() == 1) {
      var entry = owned.entrySet().iterator().next();
      return new Activation(entry.getKey(), entry.getValue());
    }
    return new Activation(Map.copyOf(owned), null);
  }
}
