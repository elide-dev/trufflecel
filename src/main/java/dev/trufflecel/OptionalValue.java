package dev.trufflecel;

import java.time.Duration;
import java.time.Instant;

/** A CEL optional, distinct from null and unknown. */
public record OptionalValue(boolean present, Object value) {
  public OptionalValue {
    if (!present) {
      value = CelNull.INSTANCE;
    } else if (!(value instanceof Long
        || value instanceof Boolean
        || value instanceof String
        || value instanceof Double
        || value instanceof Bytes
        || value instanceof UInt
        || value == CelNull.INSTANCE
        || value instanceof OptionalValue
        || value instanceof CelType
        || value instanceof Instant
        || value instanceof Duration)) {
      value = Values.in(value);
    }
  }

  public static OptionalValue none() {
    return new OptionalValue(false, CelNull.INSTANCE);
  }

  public static OptionalValue of(Object value) {
    return new OptionalValue(true, value);
  }
}
