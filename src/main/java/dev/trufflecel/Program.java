package dev.trufflecel;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;

/** A reusable Truffle call target. Evaluations have independent activations and budgets. */
public final class Program {
  private final String source;
  private final Set<String> variables;
  private final CelType resultType;
  private final Environment environment;
  private final RootCallTarget target;

  Program(
      String source,
      Set<String> variables,
      CelType resultType,
      ExprNode body,
      Environment environment) {
    this.source = source;
    this.variables = Set.copyOf(variables);
    this.resultType = resultType;
    this.environment = environment;
    target = new ExpressionRoot(body).getCallTarget();
  }

  public String source() {
    return source;
  }

  public Set<String> variables() {
    return variables;
  }

  public CelType resultType() {
    return resultType;
  }

  public Object evaluate() {
    return evaluate(Map.of());
  }

  public Object evaluate(Map<String, ?> variables) {
    return Values.out(evaluateInternal(variables));
  }

  RootCallTarget callTarget() {
    return target;
  }

  Object evaluateInternal(Map<String, ?> variables) {
    return target.call(activation(variables));
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private EvalContext activation(Map<String, ?> variables) {
    if (variables.isEmpty()) return new EvalContext(environment, Map.of());
    if (variables.size() == 1) {
      var binding = variables.entrySet().iterator().next();
      return new EvalContext(
          environment, Collections.singletonMap(binding.getKey(), Values.in(binding.getValue())));
    }
    var activation = new HashMap<String, Object>();
    variables.forEach((name, value) -> activation.put(name, Values.in(value)));
    return new EvalContext(environment, activation);
  }

  public boolean test() {
    return test(Map.of());
  }

  public boolean test(Map<String, ?> variables) {
    Object result = evaluate(variables);
    if (result instanceof Boolean b) return b;
    throw Values.error(CelException.Code.TYPE, "expected a boolean result");
  }

  private static final class ExpressionRoot extends RootNode {
    @Child private ExprNode body;

    ExpressionRoot(ExprNode body) {
      super(null);
      this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
      return body.execute((EvalContext) frame.getArguments()[0]);
    }

    @Override
    public String getName() {
      return "CEL expression";
    }
  }
}
