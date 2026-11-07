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
  private final Map<String, Integer> bindingLayout;
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
    var layout = new LinkedHashMap<String, Integer>();
    for (String name : variables) layout.put(name, layout.size());
    bindingLayout = Map.copyOf(layout);
    String identifier = body.firstIdentifier();
    bindingHint =
        identifier != null
            ? (identifier.startsWith(".") ? identifier.substring(1) : identifier)
            : variables.size() == 1 ? variables.iterator().next() : null;
    this.resultType = resultType;
    this.environment = environment;
    body.instantiateFieldPlans();
    // Tooling receives the complete source tree, including every literal and operator event;
    // plain targets replace folded subtrees with their prebuilt values and failures.
    if (truffleSource == null) body = Optimizer.rewrite(body.lowerUninstrumented());
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

  /**
   * Evaluate borrowed bindings. Keep the map and reachable data stable until this call returns,
   * including during host callbacks. Read bindings are normalized once per evaluation; unused
   * bindings are not traversed. Use {@link Activation#snapshot(Map)} for eager validation and
   * reuse.
   */
  public Object evaluate(Map<String, ?> variables) {
    // The Java entry has no guest AST call site. Keep frame tracking without an ambient lookup.
    return Values.out(target.call(null, arguments(variables)));
  }

  /** Evaluate a reusable snapshot with fresh scopes and an independent operation budget. */
  public Object evaluate(Activation activation) {
    return Values.out(target.call(null, arguments(activation.binding, activation.value)));
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
    if (variables.size() == 1 && bindingHint != null) {
      // Canonical scalars need no normalization or collection ownership. Keep the tiny-input path.
      Object value = variables.get(bindingHint);
      if (normalizedScalar(value)) return arguments(bindingHint, value);
    }
    return arguments(new BorrowedBindings(variables, bindingLayout), null);
  }

  private Object[] arguments(Object binding, Object value) {
    // Keep observable or escaping state outside guest compilation.
    // Source-backed programs also retain it in the frame for late-attached tooling.
    return contextInArguments
        ? new Object[] {context(environment, binding, value)}
        : new Object[] {binding, value};
  }

  private static EvalContext context(Environment environment, Object binding, Object value) {
    return binding instanceof String name
        ? new EvalContext(environment, name, value)
        : new EvalContext(environment, binding);
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

  /** Test a reusable snapshot; the result must be boolean. */
  public boolean test(Activation activation) {
    Object result = evaluate(activation);
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
      this.body = insert(body);
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
      try {
        return body.execute(frame, context);
      } catch (CelException e) {
        // Budget accounting may aggregate lowered visits; attribute exhaustion to the policy root.
        throw e.at(body.errorOffset, body.canonicalId, body.errorLine, body.errorColumn);
      }
    }

    @Override
    public String getName() {
      return "CEL expression";
    }
  }
}
