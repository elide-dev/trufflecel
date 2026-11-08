package dev.trufflecel;

import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.Map;
import java.util.Set;

/**
 * Immutable checked AST template; runtime targets and execution state are created on instantiation.
 */
public final class PreparedProgram {
  private final String source;
  private final Set<String> variables;
  private final Map<String, Integer> bindingLayout;
  private final CelType result;
  private final ExprNode expression;
  private final Environment environment;

  PreparedProgram(
      String source,
      Set<String> variables,
      CelType result,
      ExprNode expression,
      Environment environment) {
    this.source = source;
    this.variables = Set.copyOf(variables);
    this.bindingLayout = environment.bindingLayout(this.variables);
    this.result = result;
    this.expression = expression;
    this.environment = environment;
  }

  /** Create an independent target. Call this at runtime for a template stored in a native image. */
  public Program instantiate() {
    return new Program(
        source, variables, bindingLayout, result, NodeUtil.cloneNode(expression), environment);
  }

  /** Instantiate with instrumentation in an entered Polyglot context; initializes CEL if needed. */
  public Program instantiate(com.oracle.truffle.api.source.Source truffleSource) {
    if (!source.contentEquals(truffleSource.getCharacters()))
      throw new IllegalArgumentException("Source text differs from the prepared expression");
    return new Program(
        source,
        variables,
        bindingLayout,
        result,
        NodeUtil.cloneNode(expression),
        environment,
        CelLanguage.current(truffleSource),
        truffleSource);
  }

  Program adopt(CelLanguage language, com.oracle.truffle.api.source.Source truffleSource) {
    return new Program(
        source, variables, bindingLayout, result, expression, environment, language, truffleSource);
  }

  // Consume a freshly parsed tree only; public templates always clone before adoption.
  Program adopt() {
    return new Program(source, variables, bindingLayout, result, expression, environment);
  }

  // Compiler validation inspects the optimized template before it is exposed to callers.
  ExprNode expression() {
    return expression;
  }
}
