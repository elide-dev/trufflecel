package dev.trufflecel;

import java.util.Set;

/** Entry points matching Elide's compile-once/evaluate-many embedding model. */
public final class Cel {
  private static final Environment DEFAULT = new Environment.Builder().inferVariables(true).build();

  private Cel() {}

  public static Environment.Builder newBuilder() {
    return new Environment.Builder();
  }

  /** Infer free identifiers as dyn. Use an explicit environment for strict checking. */
  public static Program compile(String expression) {
    return DEFAULT.compile(expression);
  }

  public static Program compile(String expression, Set<String> variables) {
    var builder = newBuilder();
    variables.forEach(name -> builder.variable(name, CelType.DYN));
    return builder.build().compile(expression);
  }
}
