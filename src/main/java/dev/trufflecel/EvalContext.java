package dev.trufflecel;

import java.util.*;

final class EvalContext {
  final Environment environment;
  private final Object activation;
  private final String singleName;
  // Allocate only for expressions with local bindings; reuse slots across loop iterations.
  private Object[] scopes;
  private int scopeSize;
  private long remaining, initialBudget;
  private EvaluationState evaluation;
  private EvalContext budgetParent;
  ExprNode.BlockScope block;

  EvalContext fork() {
    EvalContext copy =
        singleName == null
            ? new EvalContext(environment, activation)
            : new EvalContext(environment, singleName, activation);
    copy.scopes = scopes == null ? null : scopes.clone();
    copy.scopeSize = scopeSize;
    copy.budgetParent = budgetParent == null ? this : budgetParent;
    copy.block = block;
    copy.evaluation = evaluation;
    return copy;
  }

  EvalContext(Environment environment, Object activation) {
    this.environment = environment;
    this.activation = activation;
    singleName = null;
    initialBudget = remaining = environment.budget;
  }

  EvalContext(Environment environment, String name, Object value) {
    this.environment = environment;
    singleName = name;
    activation = value;
    initialBudget = remaining = environment.budget;
  }

  void configure(EvaluationOptions options) {
    if (options.budget != 0) initialBudget = remaining = options.budget;
    evaluation = new EvaluationState(environment, options);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> activationMap() {
    return (Map<String, Object>) activation;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Map<String, Object> visibleBindings() {
    var visible = new LinkedHashMap<String, Object>();
    if (singleName != null) visible.put(singleName, activation);
    else if (activation instanceof BorrowedBindings borrowed) visible.putAll(borrowed.visible());
    else visible.putAll(activationMap());
    for (int i = 0; i < scopeSize; i += 2) visible.put((String) scopes[i], scopes[i + 1]);
    return Collections.unmodifiableMap(visible);
  }

  void tick() {
    charge(1);
  }

  /** Budget consumed so far by this root context; plan-time folding records it as node cost. */
  long charged() {
    return initialBudget - remaining;
  }

  void charge(long cost) {
    // Dispatch on the owner without merging it into a phi: a phi that mixes the (virtual) root
    // context with a loaded parent would force escape analysis to materialize the context in every
    // compiled loop, now that error handlers rejoin the main control flow.
    EvalContext parent = budgetParent;
    if (parent == null) chargeOwn(cost);
    else parent.chargeOwn(cost);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object invoke(Environment.FunctionOverload signature, Object[] arguments) {
    var implementation = signature.binding().implementation();
    if (signature.lateBound() && evaluation != null) {
      var supplied = evaluation.functions.get(signature.id());
      if (supplied != null) implementation = supplied;
    }
    return FunctionOverloads.invoke(implementation, arguments);
  }

  void checkControl() {
    if (evaluation != null && evaluation.control != null) evaluation.control.check();
  }

  private void chargeOwn(long cost) {
    checkControl();
    if (cost < 0 || cost > remaining)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "evaluation budget exceeded");
    remaining -= cost;
  }

  // Scoped reads remain opaque to avoid expanding dynamic scope walks in each compiled loop.
  // Observable reads use the same boundary so instruments can safely materialize frames.
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object getBoundary(String name) {
    if (name.startsWith(".")) name = name.substring(1);
    else {
      for (int i = scopeSize - 2; i >= 0; i -= 2) if (name.equals(scopes[i])) return scopes[i + 1];
    }
    return rootBinding(name);
  }

  Object get(String name) {
    if (scopeSize != 0 || name.startsWith(".")) return getBoundary(name);
    return rootBinding(name);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object getLocal(int slot) {
    return scopes[slot * 2 + 1];
  }

  private Object rootBinding(String name) {
    // Program normalizes host nulls to CelNull, so null denotes an absent binding.
    Object value =
        singleName == null ? lookup(activation, name) : singleName.equals(name) ? activation : null;
    if (value != null) return value;
    throw undeclared(name);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private static CelException undeclared(String name) {
    return Values.error(CelException.Code.UNDECLARED, "no value for variable: " + name);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  @SuppressWarnings("unchecked")
  private static Object lookup(Object activation, String name) {
    return activation instanceof BorrowedBindings borrowed
        ? borrowed.get(name)
        : ((Map<String, Object>) activation).get(name);
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
