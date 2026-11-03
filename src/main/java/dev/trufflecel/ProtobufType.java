package dev.trufflecel;

import com.google.protobuf.*;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.google.protobuf.Descriptors.*;
import dev.elide.truffledata.*;
import dev.elide.truffledata.protobuf.ProtobufAdapter;
import java.util.*;

/** Descriptor-compiled CEL binding backed by published TruffleData protobuf snapshots. */
public final class ProtobufType implements ObjectType {
  private static final Map<String, ObjectType> WELL_KNOWN = Cel.newBuilder().build().objectTypes;
  private final Descriptor descriptor;
  private final ProtobufAdapter adapter;
  private final Map<String, CelType> fields;
  private final Map<Descriptor, ProtobufType> graph;

  /**
   * A native snapshot plus the immutable protobuf needed for wire round trips and exact equality.
   */
  public static final class Value {
    private final Message message;
    private final AbstractRow row;

    private Value(Message message, AbstractRow row) {
      this.message = message;
      this.row = row;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Value v && message.equals(v.message);
    }

    @Override
    public int hashCode() {
      return message.hashCode();
    }

    public Message message() {
      return message;
    }

    public AbstractRow row() {
      return row;
    }
  }

  public ProtobufType(Descriptor descriptor, Descriptor... additionalTypes) {
    this(descriptor, new IdentityHashMap<>());
    for (Descriptor additional : additionalTypes)
      if (!graph.containsKey(additional)) new ProtobufType(additional, graph);
  }

  private ProtobufType(Descriptor descriptor, Map<Descriptor, ProtobufType> graph) {
    this.descriptor = descriptor;
    this.graph = graph;
    graph.put(descriptor, this);
    adapter = new ProtobufAdapter(descriptor, Limits.DEFAULT, 100);
    var types = new LinkedHashMap<String, CelType>();
    for (FieldDescriptor f : descriptor.getFields()) types.put(f.getName(), type(f));
    fields = Map.copyOf(types);
    for (FieldDescriptor f : descriptor.getFields())
      if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
          && !graph.containsKey(f.getMessageType())) new ProtobufType(f.getMessageType(), graph);
  }

  public Environment.Builder register(Environment.Builder builder) {
    for (ProtobufType type : graph.values()) {
      if (!WELL_KNOWN.containsKey(type.name())) builder.objectType(type);
      var enums = new HashSet<>(type.descriptor.getEnumTypes());
      for (FieldDescriptor f : type.descriptor.getFields())
        if (f.getJavaType() == FieldDescriptor.JavaType.ENUM) enums.add(f.getEnumType());
      for (EnumDescriptor e : enums)
        for (EnumValueDescriptor v : e.getValues())
          builder.constant(v.getFullName(), (long) v.getNumber());
    }
    return builder;
  }

  public Value snapshot(Message message) {
    if (message.getDescriptorForType() != descriptor)
      throw Values.error(
          CelException.Code.INVALID_ARGUMENT, "Protobuf descriptor differs from adapter");
    try {
      return new Value(message, adapter.snapshot(message));
    } catch (ContractViolation e) {
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, e.getMessage());
    }
  }

  /** Ingest and apply CEL's automatic protobuf well-known type mapping. */
  public Object input(Message message) {
    return canonical(snapshot(message));
  }

  private Object canonical(Value value) {
    ObjectType builtin = WELL_KNOWN.get(name());
    if (builtin == null) return value;
    var values = new LinkedHashMap<String, Object>();
    for (FieldDescriptor f : descriptor.getFields())
      if (has(value, f.getName())) values.put(f.getName(), get(value, f.getName()));
    Object result = builtin.construct(values);
    if (result instanceof WellKnownTypes.Any any) {
      String type = any.typeUrl().substring(any.typeUrl().lastIndexOf('/') + 1);
      for (ProtobufType candidate : graph.values())
        if (candidate.name().equals(type))
          return candidate.canonical((Value) candidate.decode(any.bytes()));
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "Any type is not registered: " + type);
    }
    return result;
  }

  public String name() {
    return descriptor.getFullName();
  }

  public Map<String, CelType> fields() {
    return fields;
  }

  public boolean accepts(Object value) {
    return value instanceof Value v && v.message().getDescriptorForType() == descriptor;
  }

  public boolean isZero(Object value) {
    Message message = ((Value) value).message();
    return message.equals(message.getDefaultInstanceForType());
  }

  public boolean equal(Object a, Object b) {
    return ((Value) a).message().equals(((Value) b).message());
  }

  private FieldDescriptor field(String name) {
    FieldDescriptor f = descriptor.findFieldByName(name);
    if (f == null) throw Values.error(CelException.Code.NO_SUCH_KEY, name);
    return f;
  }

  public boolean has(Object value, String name) {
    FieldDescriptor f = field(name);
    Message message = ((Value) value).message();
    return f.isRepeated() ? message.getRepeatedFieldCount(f) != 0 : message.hasField(f);
  }

  public Object get(Object value, String name) {
    Value v = (Value) value;
    FieldDescriptor f = field(name);
    Object nativeValue = DataLibrary.factory().getUncached().read(v.row(), f.getIndex());
    if (nativeValue == Missing.INSTANCE) {
      if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
          && (f.getMessageType().getFullName().endsWith("Value")
              || f.getMessageType().getFullName().equals("google.protobuf.Any"))
          && WELL_KNOWN.containsKey(f.getMessageType().getFullName())) return CelNull.INSTANCE;
      return scalar(v.message().getField(f), f, null);
    }
    if (f.isMapField()) {
      var result = new LinkedHashMap<Object, Object>();
      FieldDescriptor key = f.getMessageType().findFieldByNumber(1);
      FieldDescriptor val = f.getMessageType().findFieldByNumber(2);
      for (int i = 0; i < v.message().getRepeatedFieldCount(f); i++) {
        Message entry = (Message) v.message().getRepeatedField(f, i);
        AbstractRow row = (AbstractRow) element(nativeValue, i);
        ProtobufType entryType = graph.get(f.getMessageType());
        Value item = new Value(entry, row);
        result.put(entryType.get(item, key.getName()), entryType.get(item, val.getName()));
      }
      return Collections.unmodifiableMap(result);
    }
    if (f.isRepeated()) {
      var result = new ArrayList<Object>();
      for (int i = 0; i < v.message().getRepeatedFieldCount(f); i++)
        result.add(scalar(v.message().getRepeatedField(f, i), f, element(nativeValue, i)));
      return List.copyOf(result);
    }
    return scalar(v.message().getField(f), f, nativeValue);
  }

  private static Object element(Object array, int i) {
    try {
      return ((DataArray) array).readArrayElement(i);
    } catch (InvalidArrayIndexException e) {
      throw new AssertionError(e);
    }
  }

  private Object scalar(Object proto, FieldDescriptor f, Object nativeValue) {
    return switch (f.getType()) {
      case UINT32, FIXED32 -> new UInt(Integer.toUnsignedLong((Integer) proto));
      case UINT64, FIXED64 -> new UInt((Long) proto);
      case BYTES -> new Bytes(((ByteString) proto).toByteArray());
      case ENUM ->
          f.getEnumType().getFullName().equals("google.protobuf.NullValue")
              ? CelNull.INSTANCE
              : (long) ((EnumValueDescriptor) proto).getNumber();
      case MESSAGE, GROUP -> {
        ProtobufType nested = graph.get(f.getMessageType());
        yield nested.canonical(
            nativeValue == null
                ? nested.snapshot((Message) proto)
                : new Value((Message) proto, (AbstractRow) nativeValue));
      }
      default -> Values.in(nativeValue == null ? proto : nativeValue);
    };
  }

  public Object construct(Map<String, Object> values) {
    try {
      var builder = DynamicMessage.newBuilder(descriptor);
      var oneofs = new HashSet<OneofDescriptor>();
      for (var entry : values.entrySet()) {
        FieldDescriptor f = field(entry.getKey());
        Object value = entry.getValue();
        if (value == CelNull.INSTANCE && f.getJavaType() == FieldDescriptor.JavaType.MESSAGE)
          continue;
        if (f.getContainingOneof() != null && !oneofs.add(f.getContainingOneof()))
          throw new IllegalArgumentException("Multiple oneof fields");
        if (f.isMapField()) {
          for (var item : ((Map<?, ?>) value).entrySet()) {
            Descriptor d = f.getMessageType();
            builder.addRepeatedField(
                f,
                DynamicMessage.newBuilder(d)
                    .setField(
                        d.findFieldByNumber(1), toProto(item.getKey(), d.findFieldByNumber(1)))
                    .setField(
                        d.findFieldByNumber(2), toProto(item.getValue(), d.findFieldByNumber(2)))
                    .build());
          }
        } else if (f.isRepeated()) {
          for (Object item : (List<?>) value) builder.addRepeatedField(f, toProto(item, f));
        } else builder.setField(f, toProto(value, f));
      }
      return snapshot(builder.build());
    } catch (IllegalArgumentException
        | IllegalStateException
        | ArithmeticException
        | ClassCastException e) {
      throw Values.error(
          CelException.Code.INVALID_ARGUMENT, "Invalid protobuf construction: " + e.getMessage());
    }
  }

  private Object toProto(Object value, FieldDescriptor f) {
    return switch (f.getJavaType()) {
      case INT ->
          f.getType() == FieldDescriptor.Type.UINT32 || f.getType() == FieldDescriptor.Type.FIXED32
              ? unsigned32((UInt) value)
              : Math.toIntExact((Long) value);
      case LONG ->
          f.getType() == FieldDescriptor.Type.UINT64 || f.getType() == FieldDescriptor.Type.FIXED64
              ? ((UInt) value).bits()
              : (Long) value;
      case FLOAT -> ((Double) value).floatValue();
      case DOUBLE, BOOLEAN, STRING -> value;
      case BYTE_STRING -> ByteString.copyFrom(((Bytes) value).toByteArray());
      case ENUM ->
          f.getEnumType()
              .findValueByNumberCreatingIfUnknown(
                  value == CelNull.INSTANCE
                          && f.getEnumType().getFullName().equals("google.protobuf.NullValue")
                      ? 0
                      : Math.toIntExact((Long) value));
      case MESSAGE -> messageValue(value, f.getMessageType());
    };
  }

  private Message messageValue(Object value, Descriptor descriptor) {
    String name = descriptor.getFullName();
    if (value instanceof Value v && v.message().getDescriptorForType() == descriptor)
      return v.message();
    Map<String, Object> fields;
    switch (name) {
      case "google.protobuf.Timestamp" -> {
        var time = (java.time.Instant) value;
        fields = Map.of("seconds", time.getEpochSecond(), "nanos", (long) time.getNano());
      }
      case "google.protobuf.Duration" -> {
        var time = (java.time.Duration) value;
        long seconds = time.getSeconds();
        long nanos = time.getNano();
        if (seconds < 0 && nanos != 0) {
          seconds++;
          nanos -= 1_000_000_000;
        }
        fields = Map.of("seconds", seconds, "nanos", nanos);
      }
      case "google.protobuf.Struct" -> fields = Map.of("fields", value);
      case "google.protobuf.ListValue" -> fields = Map.of("values", value);
      case "google.protobuf.Value" -> {
        String field =
            value == CelNull.INSTANCE
                ? "null_value"
                : value instanceof Boolean
                    ? "bool_value"
                    : value instanceof String
                        ? "string_value"
                        : value instanceof List<?>
                            ? "list_value"
                            : value instanceof Map<?, ?> ? "struct_value" : "number_value";
        Object mapped = value == CelNull.INSTANCE ? 0L : value;
        if (field.equals("number_value"))
          mapped =
              value instanceof UInt u
                  ? u.toBigInteger().doubleValue()
                  : ((Number) value).doubleValue();
        fields = Map.of(field, mapped);
      }
      case "google.protobuf.Any" -> {
        Message message = ((Value) value).message();
        fields =
            Map.of(
                "type_url",
                "type.googleapis.com/" + message.getDescriptorForType().getFullName(),
                "value",
                new Bytes(message.toByteArray()));
      }
      case "google.protobuf.Empty" -> fields = Map.of();
      case "google.protobuf.FieldMask" ->
          fields = Map.of("paths", List.of(((String) value).split(",")));
      default -> {
        if (!WELL_KNOWN.containsKey(name))
          throw new IllegalArgumentException("Expected protobuf " + name);
        fields = Map.of("value", value);
      }
    }
    return ((Value) graph.get(descriptor).construct(fields)).message();
  }

  private static int unsigned32(UInt value) {
    if (Long.compareUnsigned(value.bits(), 0xffffffffL) > 0)
      throw new IllegalArgumentException("uint32 overflow");
    return (int) value.bits();
  }

  public Object decode(Bytes bytes) {
    try {
      return snapshot(DynamicMessage.parseFrom(descriptor, bytes.toByteArray()));
    } catch (InvalidProtocolBufferException e) {
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "Invalid protobuf bytes");
    }
  }

  private static CelType type(FieldDescriptor f) {
    if (f.isMapField())
      return CelType.map(
          type(f.getMessageType().findFieldByNumber(1)),
          type(f.getMessageType().findFieldByNumber(2)));
    CelType scalar =
        switch (f.getType()) {
          case UINT32, UINT64, FIXED32, FIXED64 -> CelType.UINT;
          case INT32, INT64, SINT32, SINT64, SFIXED32, SFIXED64 -> CelType.INT;
          case ENUM ->
              f.getEnumType().getFullName().equals("google.protobuf.NullValue")
                  ? CelType.NULL
                  : CelType.INT;
          case FLOAT, DOUBLE -> CelType.DOUBLE;
          case BOOL -> CelType.BOOL;
          case STRING -> CelType.STRING;
          case BYTES -> CelType.BYTES;
          case MESSAGE, GROUP -> WellKnownTypes.mapped(f.getMessageType().getFullName());
        };
    return f.isRepeated() ? CelType.list(scalar) : scalar;
  }
}
