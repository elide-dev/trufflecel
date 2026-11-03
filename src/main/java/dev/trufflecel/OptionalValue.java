package dev.trufflecel;

/** A CEL optional, distinct from null and unknown. */
public record OptionalValue(boolean present, Object value) {
  public static OptionalValue none() {
    return new OptionalValue(false, CelNull.INSTANCE);
  }

  public static OptionalValue of(Object value) {
    return new OptionalValue(true, Values.in(value));
  }
}
