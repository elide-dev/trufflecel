package dev.trufflecel;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import java.util.*;

/** A reusable Truffle call target. Evaluations have independent activations and budgets. */
public final class Program {
  private final String source;
  private final Set<String> variables;
  private final String bindingHint;
  private final String[] scalarBindingHints;
  private final CelType resultType;
  private final Environment environment;
  private final boolean contextInArguments;
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
    scalarBindingHints =
        variables.size() > 1 && variables.size() <= 4 ? variables.toArray(String[]::new) : null;
    String identifier = body.firstIdentifier();
    bindingHint =
        identifier != null
            ? (identifier.startsWith(".") ? identifier.substring(1) : identifier)
            : variables.size() == 1 ? variables.iterator().next() : null;
    this.resultType = resultType;
    this.environment = environment;
    // Tooling receives the complete source tree, including every literal and operator event.
    if (truffleSource == null) body = body.lowerUninstrumented();
    body.resolveBlocks(null);
    boolean hasBindings = body.hasBindings();
    boolean frameLocals = hasBindings && truffleSource == null && !body.escapesContext();
    FrameDescriptor frameDescriptor = frameLocals ? new FrameDescriptor() : null;
    if (hasBindings) body.resolveLocals(new ArrayList<>(), frameDescriptor);
    contextInArguments = truffleSource != null || hasBindings && !frameLocals;
    if (contextInArguments)
      body.accept(
          node -> {
            if (node instanceof ExprNode expression) expression.boundaryReads = true;
            return true;
          });
    body.rootBody = true;
    target =
        new ExpressionRoot(
                body, environment, contextInArguments, language, truffleSource, frameDescriptor)
            .getCallTarget();
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
    return target.call(arguments(variables));
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private Object[] arguments(Map<String, ?> variables) {
    if (variables.isEmpty()) return arguments(Map.of(), null);
    if (variables.size() == 1) {
      // This is only a lookup hint; unmatched single-entry inputs retain their actual key.
      if (bindingHint != null) {
        Object value = variables.get(bindingHint);
        if (value != null || variables.containsKey(bindingHint))
          return arguments(bindingHint, Values.in(value));
      }
      var binding = variables.entrySet().iterator().next();
      return arguments(binding.getKey(), Values.in(binding.getValue()));
    }
    if (variables.size() <= 4) {
      var snapshot = immutableScalarBindings(variables);
      if (snapshot != null) return arguments(snapshot, null);
    }
    var activation = new HashMap<String, Object>();
    variables.forEach((name, value) -> activation.put(name, Values.in(value)));
    return arguments(activation, null);
  }

  private Object[] arguments(Object binding, Object value) {
    // Keep observable or escaping state outside guest compilation.
    // Source-backed programs also retain it in the frame for late-attached tooling.
    return contextInArguments
        ? new Object[] {context(environment, binding, value)}
        : new Object[] {binding, value};
  }

  @SuppressWarnings("unchecked")
  private static EvalContext context(Environment environment, Object binding, Object value) {
    return binding instanceof String name
        ? new EvalContext(environment, name, value)
        : new EvalContext(environment, (Map<String, Object>) binding);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> immutableScalarBindings(Map<String, ?> variables) {
    Map<String, ?> snapshot;
    try {
      snapshot = Map.copyOf(variables);
    } catch (NullPointerException e) {
      // Host nulls remain supported by the normalizing path.
      return null;
    }
    // Map.copyOf excludes nulls. Count matches so extra, undeclared inputs still get validated.
    if (scalarBindingHints != null) {
      int matches = 0;
      for (String name : scalarBindingHints) {
        Object value = snapshot.get(name);
        if (value != null) {
          if (!normalizedScalar(value)) return null;
          matches++;
        }
      }
      if (matches == snapshot.size()) return (Map<String, Object>) snapshot;
    }
    for (Object value : snapshot.values()) if (!normalizedScalar(value)) return null;
    return (Map<String, Object>) snapshot;
  }

  private static boolean normalizedScalar(Object value) {
    return value instanceof Long
        || value instanceof Boolean
        || value instanceof String
        || value instanceof Double;
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
    private final Environment environment;
    private final boolean contextInArguments;

    ExpressionRoot(
        ExprNode body,
        Environment environment,
        boolean contextInArguments,
        CelLanguage language,
        Source source,
        FrameDescriptor frameDescriptor) {
      super(language, frameDescriptor);
      this.body = body;
      this.environment = environment;
      this.contextInArguments = contextInArguments;
      section = source == null ? null : source.createSection(0, source.getLength());
    }

    @Override
    public SourceSection getSourceSection() {
      return section;
    }

    @Override
    public Object execute(VirtualFrame frame) {
      // Creating the context inside the call target lets partial escape analysis remove it.
      EvalContext context =
          contextInArguments
              ? (EvalContext) frame.getArguments()[0]
              : context(environment, frame.getArguments()[0], frame.getArguments()[1]);
      return body.execute(frame, context);
    }

    @Override
    public String getName() {
      return "CEL expression";
    }
  }
}
