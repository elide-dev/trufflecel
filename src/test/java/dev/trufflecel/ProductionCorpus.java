package dev.trufflecel;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import dev.cel.expr.conformance.proto2.TestAllTypesExtensions;
import dev.elide.truffledata.Limits;
import java.time.*;
import java.time.Duration;
import java.util.*;

/** Independent protobuf-java fixture construction, evaluated through the shipping adapter. */
final class ProductionCorpus {
  static final Map<String, Descriptor> MESSAGES = new LinkedHashMap<>();
  static final List<FieldDescriptor> EXTENSIONS = new ArrayList<>();
  static final Map<String, ProtobufType> ADAPTERS = new LinkedHashMap<>();

  static {
    files(TestAllTypesExtensions.getDescriptor(), new HashSet<>());
    files(dev.cel.expr.conformance.proto3.TestAllTypesProto.getDescriptor(), new HashSet<>());
    Descriptor[] all = MESSAGES.values().toArray(Descriptor[]::new);
    // Include every extension's extendee in each model, including Any payload types.
    var limits = new Limits(1024, 512, 524288, 8 * 1024 * 1024);
    for (Descriptor d : all)
      ADAPTERS.put(d.getFullName(), new ProtobufType(d, EXTENSIONS, limits, all));
  }

  private static void files(FileDescriptor file, Set<String> seen) {
    if (!seen.add(file.getName())) return;
    file.getDependencies().forEach(d -> files(d, seen));
    file.getMessageTypes().forEach(ProductionCorpus::message);
    for (FieldDescriptor e : file.getExtensions()) if (!EXTENSIONS.contains(e)) EXTENSIONS.add(e);
  }

  private static void message(Descriptor d) {
    MESSAGES.put(d.getFullName(), d);
    d.getNestedTypes().forEach(ProductionCorpus::message);
    for (FieldDescriptor e : d.getExtensions()) if (!EXTENSIONS.contains(e)) EXTENSIONS.add(e);
  }

  static void register(Environment.Builder b) {
    ADAPTERS.values().iterator().next().register(b);
    // The corpus accepts enum type-qualified names as well as protobuf's value-qualified names.
    TestObjects.SCHEMAS
        .getAsJsonObject("enums")
        .entrySet()
        .forEach(e -> b.constant(e.getKey(), e.getValue().getAsLong()));
  }

  static Object input(Object value) {
    if (value instanceof ProtoSchema.Message m)
      return ADAPTERS.get(m.typeName()).input(wireMessage(MESSAGES.get(m.typeName()), m));
    if (value instanceof List<?> list) return list.stream().map(ProductionCorpus::input).toList();
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<Object, Object>();
      map.forEach((k, v) -> result.put(input(k), input(v)));
      return result;
    }
    return value;
  }

  static Object expected(Object value) {
    if (value instanceof ProtoSchema.Message m) return wireMessage(MESSAGES.get(m.typeName()), m);
    if (value instanceof List<?> list)
      return list.stream().map(ProductionCorpus::expected).toList();
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<Object, Object>();
      map.forEach((k, v) -> result.put(expected(k), expected(v)));
      return result;
    }
    return value;
  }

  static Object export(Object value) {
    if (value instanceof ProtobufType.Value p) return p.message();
    if (value instanceof List<?> list) return list.stream().map(ProductionCorpus::export).toList();
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<Object, Object>();
      map.forEach((k, v) -> result.put(export(k), export(v)));
      return result;
    }
    return value;
  }

  private static Message wireMessage(Descriptor d, Object value) {
    Map<String, ?> fields;
    if (value instanceof ProtoSchema.Message m && !d.getFullName().equals("google.protobuf.Any"))
      fields = m.fields();
    else if (d.getFullName().equals("google.protobuf.Timestamp")) {
      Instant t = (Instant) value;
      fields = Map.of("seconds", t.getEpochSecond(), "nanos", (long) t.getNano());
    } else if (d.getFullName().equals("google.protobuf.Duration")) {
      Duration t = (Duration) value;
      long seconds = t.getSeconds();
      int nanos = t.getNano();
      if (seconds < 0 && nanos > 0) {
        seconds++;
        nanos -= 1_000_000_000;
      }
      fields = Map.of("seconds", seconds, "nanos", (long) nanos);
    } else if (d.getFullName().equals("google.protobuf.Any")) {
      if (!(value instanceof ProtoSchema.Message m))
        throw new AssertionError("Unsupported Any fixture: " + value);
      Message payload = wireMessage(MESSAGES.get(m.typeName()), m);
      fields =
          Map.of(
              "type_url",
              "type.googleapis.com/" + m.typeName(),
              "value",
              new Bytes(payload.toByteArray()));
    } else if (d.getFullName().equals("google.protobuf.Struct")) fields = Map.of("fields", value);
    else if (d.getFullName().equals("google.protobuf.ListValue")) fields = Map.of("values", value);
    else if (d.getFullName().equals("google.protobuf.Value")) {
      String field =
          value == CelNull.INSTANCE
              ? "null_value"
              : value instanceof Boolean
                  ? "bool_value"
                  : value instanceof Number
                      ? "number_value"
                      : value instanceof String
                          ? "string_value"
                          : value instanceof Map ? "struct_value" : "list_value";
      fields = Map.of(field, value == CelNull.INSTANCE ? 0L : value);
    } else fields = Map.of("value", value);
    var out = DynamicMessage.newBuilder(d);
    for (var entry : fields.entrySet()) {
      FieldDescriptor f = d.findFieldByName(entry.getKey());
      if (f == null)
        f =
            EXTENSIONS.stream()
                .filter(e -> e.getContainingType() == d && e.getFullName().equals(entry.getKey()))
                .findFirst()
                .orElseThrow();
      Object v = entry.getValue();
      if (f.isMapField()) {
        var items = new ArrayList<Message>();
        for (var e : ((Map<?, ?>) v).entrySet())
          items.add(
              DynamicMessage.newBuilder(f.getMessageType())
                  .setField(
                      f.getMessageType().findFieldByName("key"),
                      scalar(f.getMessageType().findFieldByName("key"), e.getKey()))
                  .setField(
                      f.getMessageType().findFieldByName("value"),
                      scalar(f.getMessageType().findFieldByName("value"), e.getValue()))
                  .build());
        out.setField(f, items);
      } else if (f.isRepeated()) {
        for (Object item : (List<?>) v) out.addRepeatedField(f, scalar(f, item));
      } else out.setField(f, scalar(f, v));
    }
    return out.build();
  }

  private static Object scalar(FieldDescriptor f, Object value) {
    return switch (f.getJavaType()) {
      case INT -> value instanceof UInt u ? (int) u.bits() : ((Number) value).intValue();
      case LONG -> value instanceof UInt u ? u.bits() : ((Number) value).longValue();
      case FLOAT -> ((Number) value).floatValue();
      case DOUBLE -> ((Number) value).doubleValue();
      case BYTE_STRING -> ByteString.copyFrom(((Bytes) value).toByteArray());
      case ENUM -> f.getEnumType().findValueByNumberCreatingIfUnknown(((Number) value).intValue());
      case MESSAGE -> wireMessage(f.getMessageType(), value);
      default -> value;
    };
  }
}
