package dev.trufflecel;

/** A value or CEL failure plus the successfully charged operation cost, including failed calls. */
public final class EvaluationResult {
  private final Object value;
  private final CelException error;
  private final long actualCost;

  EvaluationResult(Object value, CelException error, long actualCost) {
    this.value = value;
    this.error = error;
    this.actualCost = actualCost;
  }

  /** Return the owned result, or raise the original located CEL failure. */
  public Object value() {
    if (error != null) throw error;
    return value;
  }

  /** Null on success. */
  public CelException error() {
    return error;
  }

  public long actualCost() {
    return actualCost;
  }
}
