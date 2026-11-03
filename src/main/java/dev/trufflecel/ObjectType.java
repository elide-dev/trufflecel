package dev.trufflecel;

import java.util.Map;

/**
 * Explicit object adapter. Implement using generated protobuf getters/builders; no reflection
 * needed.
 */
public interface ObjectType {
  String name();

  Map<String, CelType> fields();

  Object construct(Map<String, Object> fields);

  Object get(Object value, String field);

  boolean has(Object value, String field);

  boolean accepts(Object value);

  /** Whether an object is empty for optional.ofNonZeroValue; adapters may refine this. */
  default boolean isZero(Object value) {
    for (String field : fields().keySet()) if (has(value, field)) return false;
    return true;
  }

  default Object decode(Bytes bytes) {
    throw new CelException(
        CelException.Code.INVALID_ARGUMENT, "binary decoding is not registered for " + name());
  }

  default boolean equal(Object a, Object b) {
    return a.equals(b);
  }
}
