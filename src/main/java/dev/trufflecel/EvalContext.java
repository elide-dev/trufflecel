package dev.trufflecel;

import java.util.*;

sealed class EvalContext {
  final Environment environment;
  // Ordinary roots borrow their already allocated call arguments; scoped entries can hold a map.
  private final Object activation;
  private long remaining;
  private EvaluationState evaluation;

  /** Only escaping or observable evaluation paths need lexical scopes and a budget owner. */
  static final class Scoped extends EvalContext {
    private final String singleName;
    private Object[] scopes;
    private int scopeSize;
    private final EvalContext budgetParent;

    /** The binding that pushed each scope pair, parallel to {@code scopes}; null if unowned. */
    private WriteNode[] owners;

    ExprNode.BlockScope block;

    /** Remove the pair pushed by {@code node}, and anything above it, if it is on the stack. */
    private void truncateTo(WriteNode node) {
      if (owners == null) return;
      for (int pair = (scopeSize >> 1) - 1; pair >= 0; pair--) {
        if (owners[pair] != node) continue;
        while (scopeSize > pair * 2) {
          scopes[--scopeSize] = null;
          scopes[--scopeSize] = null;
          owners[scopeSize >> 1] = null;
        }
        return;
      }
    }

    Scoped(Environment environment, Object activation) {
      this(environment, null, activation);
    }

    Scoped(Environment environment, String name, Object value) {
      super(environment, value);
      singleName = name;
      budgetParent = null;
    }

    private Scoped(EvalContext original) {
      super(original);
      if (original instanceof Scoped scoped) {
        singleName = scoped.singleName;
        scopes = scoped.scopes == null ? null : scoped.scopes.clone();
        owners = scoped.owners == null ? null : scoped.owners.clone();
        scopeSize = scoped.scopeSize;
        budgetParent = scoped.budgetParent == null ? original : scoped.budgetParent;
        block = scoped.block;
      } else {
        singleName = null;
        budgetParent = original;
      }
    }
  }

  final Scoped fork() {
    return new Scoped(this);
  }

  final ExprNode.BlockScope block() {
    return ((Scoped) this).block;
  }

  EvalContext(Environment environment, Object activation) {
    this.environment = environment;
    this.activation = activation;
    remaining = environment.budget;
  }

  private EvalContext(EvalContext original) {
    environment = original.environment;
    activation = original.activation;
    remaining = environment.budget;
    evaluation = original.evaluation;
  }

  void configure(EvaluationOptions options) {
    evaluation = new EvaluationState(environment, options);
    remaining = evaluation.budget;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> activationMap(Object activation) {
    return (Map<String, Object>) activation;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Map<String, Object> visibleBindings() {
    var visible = new LinkedHashMap<String, Object>();
    Object binding = activation;
    Object value = null;
    if (activation instanceof Object[] arguments) {
      binding = arguments[0];
      value = arguments[1];
    } else if (this instanceof Scoped scoped && scoped.singleName != null) {
      binding = scoped.singleName;
      value = activation;
    }
    if (binding instanceof String name) visible.put(name, value);
    else if (binding instanceof BorrowedBindings borrowed) visible.putAll(borrowed.visible());
    else visible.putAll(activationMap(binding));
    if (this instanceof Scoped scoped) {
      for (int i = 0; i < scoped.scopeSize; i += 2) {
        Object local = scoped.scopes[i + 1];
        if (local instanceof PartialState.Slot slot) local = slot.value();
        visible.put(
            (String) scoped.scopes[i],
            local instanceof ExprNode.LazyBinding lazy ? lazy.display() : local);
      }
    }
    return Collections.unmodifiableMap(visible);
  }

  void tick() {
    charge(1);
  }

  /** Budget consumed so far by this root context; plan-time folding records it as node cost. */
  long charged() {
    if (this instanceof Scoped scoped && scoped.budgetParent != null)
      return scoped.budgetParent.chargedOwn();
    return chargedOwn();
  }

  private long chargedOwn() {
    return (evaluation == null ? environment.budget : evaluation.budget) - remaining;
  }

  boolean canCharge(long cost) {
    if (this instanceof Scoped scoped && scoped.budgetParent != null)
      return cost <= scoped.budgetParent.remaining;
    return cost <= remaining;
  }

  void charge(long cost) {
    // Keep root and parent dispatch separate: merging the virtual root with a loaded parent
    // forces escape analysis to materialize the root in compiled loops.
    if (this instanceof Scoped scoped && scoped.budgetParent != null)
      scoped.budgetParent.chargeOwn(cost);
    else chargeOwn(cost);
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

  Attribute attribute() {
    return evaluation == null || evaluation.partial == null ? null : evaluation.partial.attribute;
  }

  void attribute(Attribute attribute) {
    if (evaluation != null && evaluation.partial != null) evaluation.partial.attribute = attribute;
  }

  Object consume(Object value) {
    return evaluation == null || evaluation.partial == null
        ? value
        : evaluation.partial.consume(value);
  }

  Object attributeRead(Attribute path, boolean presence) {
    return evaluation == null || evaluation.partial == null
        ? PartialState.NONE
        : evaluation.partial.override(path, presence);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static Attribute qualify(Attribute parent, Object qualifier) {
    if (parent == null) return null;
    if (qualifier instanceof Integer number) qualifier = number.longValue();
    if (qualifier instanceof Double number) {
      if (!Double.isFinite(number) || number != (long) number.doubleValue()) return null;
      qualifier = (long) number.doubleValue();
    }
    if (!(qualifier instanceof String
        || qualifier instanceof Long
        || qualifier instanceof UInt
        || qualifier instanceof Boolean)) return null;
    return parent.qualify(qualifier);
  }

  Object iterationValue(Object value, Attribute path) {
    Object override = attributeRead(path, false);
    return override == PartialState.NONE ? value : override;
  }

  Object store(Object value) {
    Attribute path = attribute();
    return path == null ? value : new PartialState.Slot(value, path);
  }

  Object readStored(Object value) {
    if (value instanceof PartialState.Slot slot) {
      attribute(slot.attribute());
      return slot.value();
    }
    attribute(null);
    return value;
  }

  void trace(ExprNode node, Object value, CelException error) {
    if (evaluation != null && evaluation.trace != null) reportTrace(node, value, error);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private void reportTrace(ExprNode node, Object value, CelException error) {
    evaluation.trace.onEvent(
        new TraceEvent(
            node.canonicalId,
            node.errorOffset,
            node.errorLine,
            node.errorColumn,
            node.kind.name(),
            error == null ? Values.out(consume(value)) : null,
            error,
            node.errorOffsetEncoding));
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

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object getRootBoundary(String name) {
    return getRoot(name);
  }

  Object getRoot(String name) {
    return rootBinding(name.startsWith(".") ? absoluteName(name) : name);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private static String absoluteName(String name) {
    return name.substring(1);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object getLocal(int slot) {
    return readStored(((Scoped) this).scopes[slot * 2 + 1]);
  }

  private Object rootBinding(String name) {
    Attribute path = evaluation == null || evaluation.partial == null ? null : Attribute.of(name);
    Object override = attributeRead(path, false);
    if (override != PartialState.NONE) return override;
    // Program normalizes host nulls to CelNull, so null denotes an absent binding.
    Object value;
    if (activation instanceof Object[] arguments) {
      Object binding = arguments[0];
      value =
          binding instanceof String single
              ? single.equals(name) ? arguments[1] : null
              : lookup(binding, name);
    } else if (this instanceof Scoped scoped && scoped.singleName != null)
      value = scoped.singleName.equals(name) ? activation : null;
    else value = lookup(activation, name);
    if (value != null) return value;
    if (path != null && evaluation.partial.containsUnknown(path)) return PartialState.MISSING;
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
    push(null, name, value);
  }

  private void push(WriteNode owner, String name, Object value) {
    Scoped scoped = (Scoped) this;
    if (scoped.scopes == null) {
      scoped.scopes = new Object[8];
      scoped.owners = new WriteNode[4];
    } else if (scoped.scopeSize == scoped.scopes.length) {
      scoped.scopes = Arrays.copyOf(scoped.scopes, scoped.scopeSize * 2);
      scoped.owners = Arrays.copyOf(scoped.owners, scoped.scopeSize);
    }
    scoped.owners[scoped.scopeSize >> 1] = owner;
    scoped.scopes[scoped.scopeSize++] = name;
    scoped.scopes[scoped.scopeSize++] = value;
  }

  /**
   * Re-entrant safe push for {@link WriteNode}. Each pair records the binding that pushed it. A
   * re-enter ({@code ProbeNode.UNWIND_ACTION_REENTER} re-runs {@code execute} without {@code
   * clear}) first removes the pair that binding already pushed, so the stack stays balanced.
   * Ownership is per-{@code EvalContext}, so concurrent evaluations of one program do not race.
   */
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  void pushReentrant(WriteNode node, String name, Object value) {
    ((Scoped) this).truncateTo(node);
    push(node, name, value);
  }

  /** Counterpart to {@link #pushReentrant}: a no-op when {@code node} never pushed its pair. */
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  void clearReentrant(WriteNode node) {
    ((Scoped) this).truncateTo(node);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  void pop() {
    Scoped scoped = (Scoped) this;
    scoped.scopes[--scoped.scopeSize] = null;
    scoped.scopes[--scoped.scopeSize] = null;
    scoped.owners[scoped.scopeSize >> 1] = null;
  }
}
