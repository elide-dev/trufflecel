package dev.trufflecel;

import java.util.*;

final class EvalContext {
  final Environment environment;
  private final Object activation;
  private final String singleName;
  // Allocate only for expressions with local bindings; reuse slots across loop iterations.
  private Object[] scopes;
  private int scopeSize;
  private long remaining;

  EvalContext(Environment environment, Map<String, Object> activation) {
    this.environment = environment;
    this.activation = activation;
    singleName = null;
    remaining = environment.budget;
  }

  EvalContext(Environment environment, String name, Object value) {
    this.environment = environment;
    singleName = name;
    activation = value;
    remaining = environment.budget;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> activationMap() {
    return (Map<String, Object>) activation;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Map<String, Object> visibleBindings() {
    var visible = new LinkedHashMap<String, Object>();
    if (singleName != null) visible.put(singleName, activation);
    else visible.putAll(activationMap());
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

  // Preserve stable guest compilation when instruments materialize frames and inspect bindings.
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object getObserved(String name) {
    return get(name);
  }

  Object get(String name) {
    if (name.startsWith(".")) name = absoluteName(name);
    else {
      for (int i = scopeSize - 2; i >= 0; i -= 2) if (name.equals(scopes[i])) return scopes[i + 1];
    }
    // Program normalizes host nulls to CelNull, so null denotes an absent binding.
    Object value =
        singleName == null
            ? lookup(activationMap(), name)
            : singleName.equals(name) ? activation : null;
    if (value != null) return value;
    throw undeclared(name);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private static CelException undeclared(String name) {
    return Values.error(CelException.Code.UNDECLARED, "no value for variable: " + name);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private static String absoluteName(String name) {
    return name.substring(1);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private static Object lookup(Map<String, Object> activation, String name) {
    return activation.get(name);
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
