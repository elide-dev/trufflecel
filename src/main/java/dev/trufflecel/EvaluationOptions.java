package dev.trufflecel;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

/**
 * Immutable per-call controls. Timeouts start at evaluation entry; callbacks run on that thread.
 */
public final class EvaluationOptions {
  final long budget;
  final Map<String, Function<List<Object>, Object>> functions;
  final TraceListener trace;
  final PartialActivation partial;
  private final Duration timeout;
  private final Instant deadline;
  private final BooleanSupplier cancelled;

  private EvaluationOptions(Builder builder) {
    budget = builder.budget;
    functions = Map.copyOf(builder.functions);
    trace = builder.trace;
    partial = builder.partial;
    timeout = builder.timeout;
    deadline = builder.deadline;
    cancelled = builder.cancelled;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  EvaluationOptions withPartial(PartialActivation activation) {
    Builder builder = copy();
    builder.partial = activation;
    return builder.build();
  }

  EvaluationOptions withTrace(TraceListener listener) {
    Builder builder = copy();
    builder.trace = Objects.requireNonNull(listener);
    return builder.build();
  }

  EvaluationOptions withCancellation(BooleanSupplier additional) {
    Builder builder = copy();
    builder.cancelled =
        () -> additional.getAsBoolean() || cancelled != null && cancelled.getAsBoolean();
    return builder.build();
  }

  private Builder copy() {
    Builder builder = new Builder();
    builder.budget = budget;
    builder.timeout = timeout;
    builder.deadline = deadline;
    builder.cancelled = cancelled;
    builder.functions.putAll(functions);
    builder.trace = trace;
    builder.partial = partial;
    return builder;
  }

  Control control() {
    if (timeout == null && deadline == null && cancelled == null) return null;
    long nanos = timeout == null ? Long.MAX_VALUE : timeout.toNanos();
    if (deadline != null) {
      Duration remaining = Duration.between(Instant.now(), deadline);
      if (remaining.isNegative()) nanos = 0;
      else if (remaining.compareTo(Duration.ofNanos(nanos)) < 0) nanos = remaining.toNanos();
    }
    return new Control(cancelled, System.nanoTime(), nanos);
  }

  static final class Control {
    private final BooleanSupplier cancelled;
    private final long start, timeout;

    Control(BooleanSupplier cancelled, long start, long timeout) {
      this.cancelled = cancelled;
      this.start = start;
      this.timeout = timeout;
    }

    @TruffleBoundary
    void check() {
      if (cancelled != null && cancelled.getAsBoolean())
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "evaluation cancelled");
      if (timeout != Long.MAX_VALUE && System.nanoTime() - start >= timeout)
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "evaluation deadline exceeded");
    }
  }

  public static final class Builder {
    private long budget;
    private final Map<String, Function<List<Object>, Object>> functions = new LinkedHashMap<>();
    private TraceListener trace;
    private PartialActivation partial;
    private Duration timeout;
    private Instant deadline;
    private BooleanSupplier cancelled;

    private Builder() {}

    public Builder budget(long steps) {
      if (steps <= 0) throw new IllegalArgumentException("budget must be positive");
      budget = steps;
      return this;
    }

    public Builder timeout(Duration duration) {
      Objects.requireNonNull(duration);
      if (duration.toNanos() < 0)
        throw new IllegalArgumentException("timeout must not be negative");
      timeout = duration;
      return this;
    }

    public Builder deadline(Instant instant) {
      deadline = Objects.requireNonNull(instant);
      return this;
    }

    public Builder cancelled(BooleanSupplier predicate) {
      cancelled = Objects.requireNonNull(predicate);
      return this;
    }

    /** Supply one declared late-bound overload, identified by its stable overload ID. */
    public Builder function(String overloadId, Function<List<Object>, Object> implementation) {
      if (Objects.requireNonNull(overloadId).isEmpty())
        throw new IllegalArgumentException("empty overload id");
      functions.put(overloadId, Objects.requireNonNull(implementation));
      return this;
    }

    public Builder trace(TraceListener listener) {
      trace = Objects.requireNonNull(listener);
      return this;
    }

    public EvaluationOptions build() {
      return new EvaluationOptions(this);
    }
  }
}
