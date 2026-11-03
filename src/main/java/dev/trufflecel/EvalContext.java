package dev.trufflecel;

import java.util.*;

final class EvalContext {
  final Environment environment;
  final Map<String, Object> activation;
  private final Deque<Map<String, Object>> scopes = new ArrayDeque<>();
  private long remaining;

  EvalContext(Environment environment, Map<String, Object> activation) {
    this.environment = environment;
    this.activation = activation;
    remaining = environment.budget;
  }

  void tick() {
    charge(1);
  }

  void charge(long cost) {
    if (cost < 0 || cost > remaining)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "evaluation budget exceeded");
    remaining -= cost;
  }

  Object get(String name) {
    if (name.startsWith(".")) {
      name = name.substring(1);
      if (activation.containsKey(name)) return activation.get(name);
      throw Values.error(CelException.Code.UNDECLARED, "no value for variable: " + name);
    }
    for (var scope : scopes) if (scope.containsKey(name)) return scope.get(name);
    if (activation.containsKey(name)) return activation.get(name);
    throw Values.error(CelException.Code.UNDECLARED, "no value for variable: " + name);
  }

  void push(String name, Object value) {
    scopes.push(Map.of(name, value));
  }

  void pop() {
    scopes.pop();
  }
}
