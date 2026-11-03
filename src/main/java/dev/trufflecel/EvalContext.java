package dev.trufflecel;

import java.util.*;

final class EvalContext {
  final Environment environment;
  final Map<String, Object> activation;
  // Allocate only for expressions with local bindings; reuse slots across loop iterations.
  private Object[] scopes;
  private int scopeSize;
  private long remaining;

  EvalContext(Environment environment, Map<String, Object> activation) {
    this.environment = environment;
    this.activation = activation;
    remaining = environment.budget;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Map<String, Object> visibleBindings() {
    var visible = new LinkedHashMap<String, Object>(activation);
    for (int i = 0; i < scopeSize; i += 2) visible.put((String) scopes[i], scopes[i + 1]);
    return Collections.unmodifiableMap(visible);
  }

  void tick() {
    charge(1);
  }

  void charge(long cost) {
    if (cost < 0 || cost > remaining)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "evaluation budget exceeded");
    remaining -= cost;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object get(String name) {
    if (name.startsWith(".")) name = name.substring(1);
    else {
      for (int i = scopeSize - 2; i >= 0; i -= 2) if (name.equals(scopes[i])) return scopes[i + 1];
    }
    // Program normalizes host nulls to CelNull, so null denotes an absent binding.
    Object value = activation.get(name);
    if (value != null) return value;
    throw Values.error(CelException.Code.UNDECLARED, "no value for variable: " + name);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  void push(String name, Object value) {
    if (scopes == null) scopes = new Object[8];
    else if (scopeSize == scopes.length) scopes = Arrays.copyOf(scopes, scopeSize * 2);
    scopes[scopeSize++] = name;
    scopes[scopeSize++] = value;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  void pop() {
    scopes[--scopeSize] = null;
    scopes[--scopeSize] = null;
  }
}
