package dev.trufflecel;

import com.oracle.truffle.api.nodes.NodeUtil;
import java.util.Set;

/**
 * Immutable checked AST template; runtime targets and execution state are created on instantiation.
 */
public final class PreparedProgram {
  private final String source;
  private final Set<String> variables;
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
    this.result = result;
    this.expression = expression;
    this.environment = environment;
  }

  /** Create an independent target. Call this at runtime for a template stored in a native image. */
  public Program instantiate() {
    return new Program(source, variables, result, NodeUtil.cloneNode(expression), environment);
  }

  // Consume a freshly parsed tree only; public templates always clone before adoption.
  Program adopt() {
    return new Program(source, variables, result, expression, environment);
  }
}
