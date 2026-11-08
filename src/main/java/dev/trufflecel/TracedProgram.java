package dev.trufflecel;

import java.util.Map;

/** A reusable listener view; the listener is called synchronously on each evaluation thread. */
public final class TracedProgram {
  private final Program program;
  private final TraceListener listener;

  TracedProgram(Program program, TraceListener listener) {
    this.program = program;
    this.listener = listener;
  }

  public Object evaluate() {
    return evaluate(Map.of());
  }

  public Object evaluate(Map<String, ?> bindings) {
    return evaluate(bindings, EvaluationOptions.newBuilder().build());
  }

  public Object evaluate(Map<String, ?> bindings, EvaluationOptions options) {
    return program.evaluate(bindings, options.withTrace(listener));
  }

  public Object evaluate(Activation activation) {
    return program.evaluate(activation, EvaluationOptions.newBuilder().trace(listener).build());
  }

  public EvaluationResult evaluateWithDetails(Map<String, ?> bindings, EvaluationOptions options) {
    return program.evaluateWithDetails(bindings, options.withTrace(listener));
  }
}
