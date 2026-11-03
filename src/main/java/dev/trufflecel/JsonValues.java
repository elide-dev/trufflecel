package dev.trufflecel;

import java.math.*;
import java.util.*;
import org.graalvm.shadowed.org.json.JSONArray;
import org.graalvm.shadowed.org.json.JSONObject;

/** Bounded, immutable CEL snapshots of Oracle's Truffle JSON objects. */
public final class JsonValues {
  private JsonValues() {}

  public static Object from(Object json) {
    return from(json, 0, new int[] {1_000_000});
  }

  private static Object from(Object value, int depth, int[] remaining) {
    if (depth > 100 || --remaining[0] < 0)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "JSON input limit exceeded");
    if (value == JSONObject.NULL || value == null) return CelNull.INSTANCE;
    if (value instanceof JSONObject object) {
      var result = new LinkedHashMap<String, Object>();
      for (String key : object.keySet())
        result.put(key, from(object.get(key), depth + 1, remaining));
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof JSONArray array) {
      var result = new ArrayList<Object>();
      for (int i = 0; i < array.length(); i++) result.add(from(array.get(i), depth + 1, remaining));
      return List.copyOf(result);
    }
    if (value instanceof BigInteger integer) {
      try {
        return integer.longValueExact();
      } catch (ArithmeticException e) {
        throw Values.error(
            CelException.Code.INVALID_ARGUMENT, "JSON integer exceeds CEL int range");
      }
    }
    if (value instanceof BigDecimal decimal) {
      double result = decimal.doubleValue();
      if (!Double.isFinite(result))
        throw Values.error(
            CelException.Code.INVALID_ARGUMENT, "JSON number exceeds CEL double range");
      return result;
    }
    if (value instanceof Double || value instanceof Float) {
      double number = ((Number) value).doubleValue();
      if (!Double.isFinite(number))
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "Non-finite JSON number");
      return number;
    }
    if (value instanceof String
        || value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) return Values.in(value);
    throw Values.error(CelException.Code.INVALID_ARGUMENT, "Unsupported JSON value");
  }
}
