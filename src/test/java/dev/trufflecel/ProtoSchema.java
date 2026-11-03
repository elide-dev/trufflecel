package dev.trufflecel;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Test-only protobuf fixture adapter for upstream conformance. Production protobuf objects belong
 * to Truffledata and enter through ObjectType. This class is not shipped.
 */
public final class ProtoSchema {
  /** Scalar numbers follow google.protobuf.FieldDescriptorProto.Type. */
  public record Field(
      int number,
      int scalar,
      String message,
      boolean repeated,
      boolean map,
      boolean presence,
      Object defaultValue) {
    public Field {
      defaultValue = Values.in(defaultValue);
    }
  }

  public record Message(String typeName, Map<String, Object> fields, Bytes unknownFields) {
    public Message {
      fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
  }

  private final Map<String, Map<String, Field>> schemas;

  public ProtoSchema(Map<String, Map<String, Field>> schemas) {
    var copy = new LinkedHashMap<String, Map<String, Field>>();
    schemas.forEach((n, f) -> copy.put(n, Map.copyOf(f)));
    this.schemas = Map.copyOf(copy);
  }

  public void register(Environment.Builder builder) {
    schemas.forEach(
        (name, fields) -> {
          if (!name.startsWith("google.protobuf.")) builder.objectType(new Adapter(name, fields));
        });
  }

  public Object decode(String name, byte[] bytes) {
    return decode(name, new Wire(bytes), 0);
  }

  public Object construct(String name, Map<String, ?> fields) {
    return new Adapter(name, schemas.get(name)).construct(new LinkedHashMap<>(fields));
  }

  private CelType scalarType(Field f) {
    return switch (f.scalar) {
      case 1, 2 -> CelType.DOUBLE;
      case 4, 6, 7, 13 -> CelType.UINT;
      case 8 -> CelType.BOOL;
      case 9 -> CelType.STRING;
      case 12 -> CelType.BYTES;
      case 11 ->
          f.message.startsWith("google.protobuf.")
                  && f.message.endsWith("Value")
                  && !Set.of("google.protobuf.Value", "google.protobuf.ListValue")
                      .contains(f.message)
              ? CelType.wrapper(WellKnownTypes.mapped(f.message))
              : WellKnownTypes.mapped(f.message);
      default -> CelType.INT;
    };
  }

  private CelType fieldType(Field f) {
    if (f.map) {
      var entry = schemas.get(f.message);
      return CelType.map(scalarType(entry.get("key")), scalarType(entry.get("value")));
    }
    return f.repeated ? CelType.list(scalarType(f)) : scalarType(f);
  }

  private Object defaultValue(Field f) {
    if (f.map) return Map.of();
    if (f.repeated) return List.of();
    if (f.message != null && !f.message.isEmpty()) {
      return switch (f.message) {
        case "google.protobuf.Timestamp" -> Instant.EPOCH;
        case "google.protobuf.Duration" -> Duration.ZERO;
        case "google.protobuf.ListValue" -> List.of();
        case "google.protobuf.Struct" -> Map.of();
        default ->
            f.message.startsWith("google.protobuf.")
                ? CelNull.INSTANCE
                : new Message(f.message, Map.of(), new Bytes(new byte[0]));
      };
    }
    return f.defaultValue;
  }

  private Object normalize(Field f, Object v) {
    v = Values.in(v);
    if (f.map) {
      if (!(v instanceof Map<?, ?> m)) throw Values.overload("protobuf map assignment");
      var entry = schemas.get(f.message);
      Field key = entry.get("key"), value = entry.get("value");
      var result = new LinkedHashMap<Object, Object>();
      for (var e : m.entrySet()) {
        Object x = normalize(value, e.getValue());
        if (x != CelNull.INSTANCE
            || Set.of("google.protobuf.Any", "google.protobuf.Value").contains(value.message))
          result.put(normalize(key, e.getKey()), x);
      }
      return Collections.unmodifiableMap(result);
    }
    if (f.repeated) {
      if (!(v instanceof List<?> list)) throw Values.overload("protobuf repeated assignment");
      var result = new ArrayList<Object>();
      Field item = new Field(f.number, f.scalar, f.message, false, false, false, f.defaultValue);
      for (Object element : list) {
        Object x = normalize(item, element);
        if (x != CelNull.INSTANCE
            || Set.of("google.protobuf.Any", "google.protobuf.Value").contains(item.message))
          result.add(x);
      }
      return List.copyOf(result);
    }
    if (f.scalar == 11) {
      if (f.message.equals("google.protobuf.Value")) return json(v);
      if (f.message.equals("google.protobuf.Struct")) {
        if (!(v instanceof Map<?, ?> m)) throw Values.overload("protobuf Struct assignment");
        return json(m);
      }
      if (f.message.equals("google.protobuf.ListValue")) {
        if (!(v instanceof List<?> l)) throw Values.overload("protobuf ListValue assignment");
        return json(l);
      }
      if (v == CelNull.INSTANCE) return v;
      String shortName = f.message.startsWith("google.protobuf.") ? f.message.substring(16) : "";
      if (shortName.endsWith("Value")) return WellKnownTypes.wrapperValue(shortName, v);
      if (f.message.equals("google.protobuf.Any")) return v;
      if (f.message.equals("google.protobuf.Timestamp") && v instanceof Instant
          || f.message.equals("google.protobuf.Duration") && v instanceof Duration) return v;
      if (v instanceof Message m && m.typeName.equals(f.message)) return v;
      throw Values.overload("protobuf message assignment");
    }
    CelType t = scalarType(f);
    if (!t.equals(Values.type(v))) throw Values.overload("protobuf scalar assignment");
    if (Set.of(5, 14, 15, 17).contains(f.scalar)
        && v instanceof Long l
        && (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE))
      throw Values.error(CelException.Code.OVERFLOW, "int32 overflow");
    if (Set.of(7, 13).contains(f.scalar)
        && v instanceof UInt u
        && Long.compareUnsigned(u.bits(), 0xffffffffL) > 0)
      throw Values.error(CelException.Code.OVERFLOW, "uint32 overflow");
    if (f.scalar == 2) return (double) ((Double) v).floatValue();
    return v;
  }

  static Object json(Object v) {
    if (v instanceof Long l)
      return Math.abs((double) l) <= 9007199254740991d ? l.doubleValue() : l.toString();
    if (v instanceof UInt u)
      return Long.compareUnsigned(u.bits(), 9007199254740991L) <= 0
          ? (double) u.bits()
          : u.toString();
    if (v instanceof Bytes b) return Base64.getEncoder().encodeToString(b.toByteArray());
    if (v instanceof Instant t) return t.toString();
    if (v instanceof Duration d) return Functions.durationString(d);
    if (v instanceof List<?> l) {
      var r = new ArrayList<Object>();
      l.forEach(x -> r.add(json(x)));
      return List.copyOf(r);
    }
    if (v instanceof Map<?, ?> m) {
      var r = new LinkedHashMap<String, Object>();
      for (var e : m.entrySet()) {
        if (!(e.getKey() instanceof String k)) throw Values.overload("JSON object key");
        r.put(k, json(e.getValue()));
      }
      return Collections.unmodifiableMap(r);
    }
    if (v instanceof Boolean || v instanceof Double || v instanceof String || v == CelNull.INSTANCE)
      return v;
    throw Values.overload("JSON value");
  }

  private final class Adapter implements ObjectType {
    final String name;
    final Map<String, Field> schema;
    final Map<String, CelType> fields;

    Adapter(String name, Map<String, Field> schema) {
      this.name = name;
      this.schema = Objects.requireNonNull(schema, "unregistered schema " + name);
      var types = new LinkedHashMap<String, CelType>();
      schema.forEach((n, f) -> types.put(n, fieldType(f)));
      fields = Map.copyOf(types);
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Map<String, CelType> fields() {
      return fields;
    }

    @Override
    public Object construct(Map<String, Object> fields) {
      var normalized = new LinkedHashMap<String, Object>();
      for (var e : fields.entrySet()) {
        Field f = schema.get(e.getKey());
        if (f == null)
          throw Values.error(CelException.Code.NO_SUCH_FIELD, "no such field: " + e.getKey());
        Object v = normalize(f, e.getValue());
        if (v != CelNull.INSTANCE || f.message.equals("google.protobuf.Value"))
          normalized.put(e.getKey(), v);
      }
      return new Message(name, normalized, new Bytes(new byte[0]));
    }

    @Override
    public Object get(Object value, String field) {
      Message m = (Message) value;
      Field f = schema.get(field);
      if (f == null) throw Values.error(CelException.Code.NO_SUCH_FIELD, "no such field: " + field);
      return m.fields.getOrDefault(field, defaultValue(f));
    }

    @Override
    public boolean has(Object value, String field) {
      Message m = (Message) value;
      Field f = schema.get(field);
      if (!m.fields.containsKey(field)) return false;
      return f.presence || !Values.equal(m.fields.get(field), defaultValue(f));
    }

    @Override
    public boolean accepts(Object value) {
      return value instanceof Message m && m.typeName.equals(name);
    }

    @Override
    public Object decode(Bytes bytes) {
      return ProtoSchema.this.decode(name, bytes.toByteArray());
    }

    @Override
    public boolean equal(Object a, Object b) {
      Message x = (Message) a, y = (Message) b;
      if (!x.unknownFields.equals(y.unknownFields)) return false;
      for (String field : schema.keySet()) {
        Field f = schema.get(field);
        if (f.presence && has(x, field) != has(y, field)) return false;
        if (!messageEqual(get(x, field), get(y, field))) return false;
      }
      return true;
    }
  }

  public boolean messageEqual(Object a, Object b) {
    if (a instanceof Message x && b instanceof Message y) {
      if (!x.typeName.equals(y.typeName)) return false;
      if (x.fields.isEmpty() && y.fields.isEmpty()) return x.unknownFields.equals(y.unknownFields);
      return new Adapter(x.typeName, schemas.get(x.typeName)).equal(x, y);
    }
    return Values.equal(a, b);
  }

  private Object decode(String name, Wire wire, int depth) {
    if (depth > 100)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "protobuf depth limit exceeded");
    Map<String, Field> schema = schemas.get(name);
    if (schema == null)
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "unregistered protobuf type: " + name);
    var fields = new LinkedHashMap<String, Object>();
    var unknown = new java.io.ByteArrayOutputStream();
    while (wire.remaining()) {
      int begin = wire.position;
      long tag = wire.varint();
      int number = (int) (tag >>> 3), kind = (int) (tag & 7);
      if (number <= 0)
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid protobuf field number");
      String key = null;
      Field f = null;
      for (var entry : schema.entrySet())
        if (entry.getValue().number == number) {
          key = entry.getKey();
          f = entry.getValue();
          break;
        }
      if (f == null) {
        wire.skip(kind);
        unknown.writeBytes(Arrays.copyOfRange(wire.bytes, begin, wire.position));
        continue;
      }
      if (f.repeated && !f.map && kind == 2 && f.scalar != 9 && f.scalar != 11 && f.scalar != 12) {
        Wire packed = new Wire(wire.chunk());
        var list = new ArrayList<Object>((List<?>) fields.getOrDefault(key, List.of()));
        while (packed.remaining()) list.add(read(f, packed, depth));
        fields.put(key, list);
        continue;
      }
      Object value = read(f, wire, depth);
      if (f.map) {
        Message entry = (Message) value;
        Adapter adapter = new Adapter(f.message, schemas.get(f.message));
        @SuppressWarnings("unchecked")
        var map = new LinkedHashMap<>((Map<Object, Object>) fields.getOrDefault(key, Map.of()));
        map.put(adapter.get(entry, "key"), adapter.get(entry, "value"));
        fields.put(key, map);
      } else if (f.repeated) {
        var list = new ArrayList<Object>((List<?>) fields.getOrDefault(key, List.of()));
        list.add(value);
        fields.put(key, list);
      } else fields.put(key, value);
    }
    if (name.startsWith("google.protobuf.")) {
      Environment env = Cel.newBuilder().build();
      ObjectType type = env.objectTypes.get(name);
      if (type != null && !name.equals("google.protobuf.Any")) return type.construct(fields);
    }
    Message normalized = (Message) new Adapter(name, schema).construct(fields);
    return new Message(name, normalized.fields, new Bytes(unknown.toByteArray()));
  }

  private Object read(Field f, Wire w, int depth) {
    return switch (f.scalar) {
      case 1 -> Double.longBitsToDouble(w.fixed(8));
      case 2 -> (double) Float.intBitsToFloat((int) w.fixed(4));
      case 3 -> w.varint();
      case 4 -> new UInt(w.varint());
      case 5, 14 -> (long) (int) w.varint();
      case 6 -> new UInt(w.fixed(8));
      case 7 -> new UInt(w.fixed(4));
      case 8 -> w.varint() != 0;
      case 9 -> new String(w.chunk(), StandardCharsets.UTF_8);
      case 11 -> decode(f.message, new Wire(w.chunk()), depth + 1);
      case 12 -> new Bytes(w.chunk());
      case 13 -> new UInt(w.varint() & 0xffffffffL);
      case 15 -> (long) (int) w.fixed(4);
      case 16 -> w.fixed(8);
      case 17 -> {
        long n = w.varint();
        yield (long) (int) ((n >>> 1) ^ -(n & 1));
      }
      case 18 -> {
        long n = w.varint();
        yield (n >>> 1) ^ -(n & 1);
      }
      default ->
          throw Values.error(CelException.Code.INVALID_ARGUMENT, "unsupported protobuf scalar");
    };
  }

  private static final class Wire {
    final byte[] bytes;
    int position;

    Wire(byte[] bytes) {
      this.bytes = bytes;
    }

    boolean remaining() {
      return position < bytes.length;
    }

    int next() {
      if (!remaining())
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "truncated protobuf");
      return bytes[position++] & 255;
    }

    long varint() {
      long n = 0;
      for (int shift = 0; shift < 64; shift += 7) {
        int b = next();
        if (shift == 63 && b > 1)
          throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid protobuf varint");
        n |= (long) (b & 127) << shift;
        if (b < 128) return n;
      }
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid protobuf varint");
    }

    long fixed(int size) {
      long n = 0;
      for (int i = 0; i < size; i++) n |= (long) next() << (i * 8);
      return n;
    }

    byte[] chunk() {
      long n = varint();
      if (n < 0 || n > bytes.length - position)
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid protobuf length");
      byte[] r = Arrays.copyOfRange(bytes, position, position + (int) n);
      position += (int) n;
      return r;
    }

    void skip(int kind) {
      switch (kind) {
        case 0 -> varint();
        case 1 -> fixed(8);
        case 2 -> chunk();
        case 5 -> fixed(4);
        default ->
            throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid protobuf wire type");
      }
    }
  }
}
