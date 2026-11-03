package dev.trufflecel;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Descriptor-derived test fixture adapters, deliberately excluded from the shipping JAR. */
final class TestObjects {
  static final JsonObject SCHEMAS;

  static {
    try {
      SCHEMAS =
          JsonParser.parseString(Files.readString(Path.of("third_party/cel-spec/schemas.json")))
              .getAsJsonObject();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static final ProtoSchema PROTOS = schemas();

  static ProtoSchema schemas() {
    var messages = new LinkedHashMap<String, Map<String, ProtoSchema.Field>>();
    for (var m : SCHEMAS.getAsJsonObject("messages").entrySet()) {
      var fields = new LinkedHashMap<String, ProtoSchema.Field>();
      for (var entry : m.getValue().getAsJsonObject().entrySet()) {
        JsonObject f = entry.getValue().getAsJsonObject();
        Object d = f.has("default") ? scalar(f, f.get("default")) : null;
        fields.put(
            entry.getKey(),
            new ProtoSchema.Field(
                f.get("number").getAsInt(),
                f.get("type").getAsInt(),
                f.has("message") ? f.get("message").getAsString() : "",
                f.get("repeated").getAsBoolean(),
                f.has("map") && f.get("map").getAsBoolean(),
                f.get("presence").getAsBoolean(),
                d));
      }
      messages.put(m.getKey(), fields);
    }
    return new ProtoSchema(messages);
  }

  static void register(Environment.Builder builder) {
    for (var e : SCHEMAS.getAsJsonObject("enums").entrySet())
      builder.constant(e.getKey(), e.getValue().getAsLong());
    PROTOS.register(builder);
  }

  static CelType fieldType(JsonObject field) {
    if (field.has("map") && field.get("map").getAsBoolean()) {
      var entry =
          SCHEMAS.getAsJsonObject("messages").getAsJsonObject(field.get("message").getAsString());
      return CelType.map(
          fieldType(entry.getAsJsonObject("key")), fieldType(entry.getAsJsonObject("value")));
    }
    CelType type = scalarType(field);
    return field.get("repeated").getAsBoolean() ? CelType.list(type) : type;
  }

  static CelType scalarType(JsonObject f) {
    return switch (f.get("type").getAsInt()) {
      case 1, 2 -> CelType.DOUBLE;
      case 4, 6, 7, 13 -> CelType.UINT;
      case 8 -> CelType.BOOL;
      case 9 -> CelType.STRING;
      case 12 -> CelType.BYTES;
      case 11 -> WellKnownTypes.mapped(f.get("message").getAsString());
      default -> CelType.INT;
    };
  }

  static Object defaultValue(JsonObject f) {
    if (f.has("map") && f.get("map").getAsBoolean()) return Map.of();
    if (f.get("repeated").getAsBoolean()) return List.of();
    if (f.has("message")) {
      String name = f.get("message").getAsString();
      if (name.endsWith("Value") || name.endsWith("Any")) return CelNull.INSTANCE;
      if (name.equals("google.protobuf.Timestamp")) return Instant.EPOCH;
      if (name.equals("google.protobuf.Duration")) return Duration.ZERO;
      if (name.equals("google.protobuf.Struct")) return Map.of();
      return new ProtoSchema.Message(name, Map.of(), new Bytes(new byte[0]));
    }
    return scalar(f, f.get("default"));
  }

  static Object scalar(JsonObject f, JsonElement value) {
    return switch (f.get("type").getAsInt()) {
      case 1 -> value.getAsDouble();
      case 2 -> (double) value.getAsFloat();
      case 4, 6, 7, 13 -> UInt.parse(value.getAsString());
      case 8 -> value.getAsBoolean();
      case 9 -> value.getAsString();
      case 12 -> new Bytes(Base64.getDecoder().decode(value.getAsString()));
      case 11 -> object(f.get("message").getAsString(), value);
      default -> value.getAsLong();
    };
  }

  static Object object(JsonObject value) {
    String name = value.get("@type").getAsString();
    name = name.substring(name.lastIndexOf('/') + 1);
    return object(name, value);
  }

  static Object object(String name, JsonElement input) {
    if (name.startsWith("google.protobuf.")) {
      JsonElement v =
          input.isJsonObject() && input.getAsJsonObject().has("value")
              ? input.getAsJsonObject().get("value")
              : input;
      return switch (name.substring(16)) {
        case "Timestamp" -> Instant.parse(v.getAsString());
        case "Duration" -> Functions.duration(v.getAsString());
        case "Int32Value", "Int64Value" -> v.getAsLong();
        case "UInt32Value", "UInt64Value" -> UInt.parse(v.getAsString());
        case "FloatValue" -> (double) v.getAsFloat();
        case "DoubleValue" -> v.getAsDouble();
        case "BoolValue" -> v.getAsBoolean();
        case "StringValue" -> v.getAsString();
        case "BytesValue" -> new Bytes(Base64.getDecoder().decode(v.getAsString()));
        case "Value", "Struct", "ListValue" -> json(v);
        case "Any" -> object(v.getAsJsonObject());
        default -> throw new AssertionError("unsupported object " + name);
      };
    }
    JsonObject schema = SCHEMAS.getAsJsonObject("messages").getAsJsonObject(name);
    var fields = new LinkedHashMap<String, Object>();
    for (var entry : input.getAsJsonObject().entrySet()) {
      if (entry.getKey().equals("@type")) continue;
      String key = entry.getKey();
      if (key.startsWith("[")) key = key.substring(1, key.length() - 1);
      JsonObject f = schema.getAsJsonObject(key);
      if (f == null)
        for (var candidate : schema.entrySet())
          if (candidate.getValue().getAsJsonObject().get("json_name").getAsString().equals(key)) {
            key = candidate.getKey();
            f = candidate.getValue().getAsJsonObject();
            break;
          }
      if (f == null) throw new AssertionError("unknown fixture field " + key);
      Object value;
      if (f.has("map") && f.get("map").getAsBoolean()) {
        var item =
            SCHEMAS.getAsJsonObject("messages").getAsJsonObject(f.get("message").getAsString());
        var map = new LinkedHashMap<Object, Object>();
        for (var e : entry.getValue().getAsJsonObject().entrySet())
          map.put(
              scalar(item.getAsJsonObject("key"), new JsonPrimitive(e.getKey())),
              scalar(item.getAsJsonObject("value"), e.getValue()));
        value = map;
      } else if (f.get("repeated").getAsBoolean()) {
        var list = new ArrayList<Object>();
        for (var e : entry.getValue().getAsJsonArray()) list.add(scalar(f, e));
        value = list;
      } else value = scalar(f, entry.getValue());
      fields.put(key, value);
    }
    return new ProtoSchema.Message(name, fields, new Bytes(new byte[0]));
  }

  static Object json(JsonElement value) {
    if (value.isJsonNull()) return CelNull.INSTANCE;
    if (value.isJsonArray()) {
      var l = new ArrayList<Object>();
      value.getAsJsonArray().forEach(e -> l.add(json(e)));
      return l;
    }
    if (value.isJsonObject()) {
      var m = new LinkedHashMap<String, Object>();
      value.getAsJsonObject().entrySet().forEach(e -> m.put(e.getKey(), json(e.getValue())));
      return m;
    }
    var p = value.getAsJsonPrimitive();
    if (p.isBoolean()) return p.getAsBoolean();
    if (p.isNumber()) return p.getAsDouble();
    return p.getAsString();
  }

  static boolean same(ProtoSchema.Message a, ProtoSchema.Message b) {
    return PROTOS.messageEqual(a, b);
  }
}
