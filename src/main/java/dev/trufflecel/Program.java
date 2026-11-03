package dev.trufflecel;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import java.util.*;

/** A reusable Truffle call target. Evaluations have independent activations and budgets. */
public final class Program {
  private final String source;
  private final Set<String> variables;
  private final String singleVariable;
  private final CelType resultType;
  private final Environment environment;
  private final RootCallTarget target;

  Program(
      String source,
      Set<String> variables,
      CelType resultType,
      ExprNode body,
      Environment environment) {
    this(source, variables, resultType, body, environment, null, null);
  }

  Program(
      String source,
      Set<String> variables,
      CelType resultType,
      ExprNode body,
      Environment environment,
      CelLanguage language,
      Source truffleSource) {
    this.source = source;
    this.variables = Set.copyOf(variables);
    singleVariable = variables.size() == 1 ? variables.iterator().next() : null;
    this.resultType = resultType;
    this.environment = environment;
    // Tooling receives the complete source tree, including every literal and operator event.
    if (truffleSource == null) body = body.lowerArithmetic();
    body.rootBody = true;
    target = new ExpressionRoot(body, language, truffleSource).getCallTarget();
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
      if (singleVariable != null) {
        Object value = variables.get(singleVariable);
        if (value != null || variables.containsKey(singleVariable))
          return new EvalContext(environment, singleVariable, Values.in(value));
      }
      var binding = variables.entrySet().iterator().next();
      return new EvalContext(environment, binding.getKey(), Values.in(binding.getValue()));
    }
    if (variables.size() <= 4) {
      var snapshot = immutableScalarBindings(variables);
      if (snapshot != null) return new EvalContext(environment, snapshot);
    }
    var activation = new HashMap<String, Object>();
    variables.forEach((name, value) -> activation.put(name, Values.in(value)));
    return new EvalContext(environment, activation);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> immutableScalarBindings(Map<String, ?> variables) {
    Map<String, ?> snapshot;
    try {
      snapshot = Map.copyOf(variables);
    } catch (NullPointerException e) {
      // Host nulls remain supported by the normalizing path.
      return null;
    }
    for (Object value : snapshot.values())
      if (!(value instanceof Long
          || value instanceof Boolean
          || value instanceof String
          || value instanceof Double)) return null;
    return (Map<String, Object>) snapshot;
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

    private final SourceSection section;

    ExpressionRoot(ExprNode body, CelLanguage language, Source source) {
      super(language);
      this.body = body;
      section = source == null ? null : source.createSection(0, source.getLength());
    }

    @Override
    public SourceSection getSourceSection() {
      return section;
    }

    @Override
    public Object execute(VirtualFrame frame) {
      return body.execute(frame);
    }

    @Override
    public String getName() {
      return "CEL expression";
    }
  }
}
