package dev.trufflecel;

import java.util.*;

/** Immutable partial result; advancing evaluates again with additional owned attribute values. */
public final class PartialResult {
  final Program program;
  private final PartialActivation activation;
  private final EvaluationOptions options;
  private final EvaluationResult result;

  PartialResult(
      Program program,
      PartialActivation activation,
      EvaluationOptions options,
      EvaluationResult result) {
    this.program = program;
    this.activation = activation;
    this.options = options;
    this.result = result;
  }

  public boolean isComplete() {
    return result.error() == null && !(result.value() instanceof Unknown);
  }

  public Object value() {
    return result.value();
  }

  public CelException error() {
    return result.error();
  }

  public long actualCost() {
    return result.actualCost();
  }

  public Set<String> unknownAttributes() {
    return result.error() == null && result.value() instanceof Unknown unknown
        ? unknown.attributes()
        : Set.of();
  }

  public PartialResult advanceEvaluation(Map<Attribute, ?> values) {
    return program.partialEvaluate(activation.resolve(values), options);
  }
}
