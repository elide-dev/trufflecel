package dev.trufflecel;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Protobuf well-known CEL mappings, without loading protobuf descriptors. */
final class WellKnownTypes {
  private WellKnownTypes() {}

  static void register(Environment.Builder builder) {
    wrapper(builder, "BoolValue", CelType.BOOL, false);
    wrapper(builder, "Int32Value", CelType.INT, 0L);
    wrapper(builder, "Int64Value", CelType.INT, 0L);
    wrapper(builder, "UInt32Value", CelType.UINT, new UInt(0));
    wrapper(builder, "UInt64Value", CelType.UINT, new UInt(0));
    wrapper(builder, "FloatValue", CelType.DOUBLE, 0.0);
    wrapper(builder, "DoubleValue", CelType.DOUBLE, 0.0);
    wrapper(builder, "StringValue", CelType.STRING, "");
    wrapper(builder, "BytesValue", CelType.BYTES, new Bytes(new byte[0]));
    add(
        builder,
        "Timestamp",
        Map.of("seconds", CelType.INT, "nanos", CelType.INT),
        f -> time(f, false));
    add(
        builder,
        "Duration",
        Map.of("seconds", CelType.INT, "nanos", CelType.INT),
        f -> time(f, true));
    add(
        builder,
        "ListValue",
        Map.of("values", CelType.list(CelType.DYN)),
        f -> f.getOrDefault("values", List.of()));
    add(
        builder,
        "Struct",
        Map.of("fields", CelType.map(CelType.STRING, CelType.DYN)),
        f -> f.getOrDefault("fields", Map.of()));
    add(
        builder,
        "Value",
        Map.of(
            "null_value",
            CelType.INT,
            "bool_value",
            CelType.BOOL,
            "number_value",
            CelType.DOUBLE,
            "string_value",
            CelType.STRING,
            "list_value",
            CelType.list(CelType.DYN),
            "struct_value",
            CelType.map(CelType.STRING, CelType.DYN)),
        f -> {
          if (f.size() > 1)
            throw Values.error(CelException.Code.INVALID_ARGUMENT, "multiple Value alternatives");
          return f.isEmpty() || f.containsKey("null_value")
              ? CelNull.INSTANCE
              : f.values().iterator().next();
        });
    add(
        builder,
        "Any",
        Map.of("type_url", CelType.STRING, "value", CelType.BYTES),
        f ->
            new Any(
                (String) f.getOrDefault("type_url", ""),
                (Bytes) f.getOrDefault("value", new Bytes(new byte[0]))));
    add(builder, "Empty", Map.of(), f -> Map.of());
    add(
        builder,
        "FieldMask",
        Map.of("paths", CelType.list(CelType.STRING)),
        f ->
            String.join(
                ",",
                ((List<?>) f.getOrDefault("paths", List.of()))
                    .stream().map(Object::toString).toList()));
    builder.constant("google.protobuf.NullValue.NULL_VALUE", 0L);
  }

  record Any(String typeUrl, Bytes bytes) {}

  static Object time(Map<String, Object> f, boolean duration) {
    long seconds = (long) f.getOrDefault("seconds", 0L), nanos = (long) f.getOrDefault("nanos", 0L);
    if (duration) {
      if (seconds < -315576000000L
          || seconds > 315576000000L
          || nanos <= -1000000000L
          || nanos >= 1000000000L
          || seconds > 0 && nanos < 0
          || seconds < 0 && nanos > 0)
        throw Values.error(CelException.Code.OVERFLOW, "invalid protobuf Duration");
      return Functions.checkedDuration(Duration.ofSeconds(seconds, nanos));
    }
    if (nanos < 0 || nanos >= 1000000000L)
      throw Values.error(CelException.Code.OVERFLOW, "invalid protobuf Timestamp nanos");
    return Functions.checkedTimestamp(Instant.ofEpochSecond(seconds, nanos));
  }

  static Object wrapperValue(String name, Object value) {
    CelType expected = mapped("google.protobuf." + name);
    if (!expected.equals(Values.type(value))) throw Values.overload(name);
    if (name.equals("Int32Value")
        && ((Long) value < Integer.MIN_VALUE || (Long) value > Integer.MAX_VALUE))
      throw Values.error(CelException.Code.OVERFLOW, "int32 overflow");
    if (name.equals("UInt32Value") && Long.compareUnsigned(((UInt) value).bits(), 0xffffffffL) > 0)
      throw Values.error(CelException.Code.OVERFLOW, "uint32 overflow");
    if (name.equals("FloatValue")) return (double) ((Double) value).floatValue();
    return value;
  }

  private static void wrapper(
      Environment.Builder b, String name, CelType type, Object defaultValue) {
    add(
        b,
        name,
        Map.of("value", type),
        f -> wrapperValue(name, f.getOrDefault("value", defaultValue)));
  }

  private static void add(
      Environment.Builder b,
      String shortName,
      Map<String, CelType> fields,
      Function<Map<String, Object>, Object> factory) {
    b.constructorType(
        new ObjectType() {
          @Override
          public String name() {
            return "google.protobuf." + shortName;
          }

          @Override
          public Map<String, CelType> fields() {
            return fields;
          }

          @Override
          public Object construct(Map<String, Object> values) {
            return factory.apply(values);
          }

          @Override
          public Object get(Object value, String field) {
            throw Values.overload("well-known field selection");
          }

          @Override
          public boolean has(Object value, String field) {
            return false;
          }

          @Override
          public boolean accepts(Object value) {
            return false;
          }
        });
  }

  static CelType mapped(String name) {
    if (!name.startsWith("google.protobuf.")) return CelType.of(name);
    return switch (name.substring(16)) {
      case "BoolValue" -> CelType.BOOL;
      case "Int32Value", "Int64Value" -> CelType.INT;
      case "UInt32Value", "UInt64Value" -> CelType.UINT;
      case "FloatValue", "DoubleValue" -> CelType.DOUBLE;
      case "StringValue" -> CelType.STRING;
      case "BytesValue" -> CelType.BYTES;
      case "ListValue" -> CelType.list(CelType.DYN);
      case "Struct" -> CelType.map(CelType.STRING, CelType.DYN);
      case "Value", "Any" -> CelType.DYN;
      default -> CelType.of(name);
    };
  }
}
