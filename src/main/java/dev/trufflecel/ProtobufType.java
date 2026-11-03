package dev.trufflecel;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import dev.elide.truffledata.*;
import dev.elide.truffledata.protobuf.*;
import java.util.*;

/** Descriptor-compiled CEL binding backed by published TruffleData protobuf snapshots. */
public final class ProtobufType implements ObjectType {
  private static final Map<String, ObjectType> WELL_KNOWN = Cel.newBuilder().build().objectTypes;
  private final Descriptor descriptor;
  private final ProtoPlan plan;
  private final ProtoCompiler.Source source;
  private final Map<String, CelType> fields;
  private final Map<Descriptor, ProtobufType> graph;

  /** A lossless native protobuf value; no protobuf Message is retained. */
  public static final class Value {
    private final ProtobufType type;
    private final ProtoRow row;

    private Value(ProtobufType type, ProtoRow row) {
      this.type = type;
      this.row = row;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Value v
          && type.descriptor == v.type.descriptor
          && message().equals(v.message());
    }

    @Override
    public int hashCode() {
      return message().hashCode();
    }

    /** Explicit host export. Binary ingestion and CEL field access use the native row directly. */
    public Message message() {
      try {
        return DynamicMessage.parseFrom(type.descriptor, toBytes().toByteArray());
      } catch (InvalidProtocolBufferException e) {
        throw new IllegalStateException("Invalid native protobuf row", e);
      }
    }

    public Bytes toBytes() {
      return new Bytes(type.plan.encode(row));
    }

    public ProtoRow row() {
      return row;
    }
  }

  public ProtobufType(Descriptor descriptor, Descriptor... additionalTypes) {
    this(descriptor, new IdentityHashMap<>(), model(descriptor, additionalTypes));
    for (Descriptor additional : additionalTypes)
      if (!graph.containsKey(additional)) new ProtobufType(additional, graph, model);
  }

  private final ProtoPlan.Model model;

  private static ProtoPlan.Model model(Descriptor descriptor, Descriptor[] additionalTypes) {
    var roots = new ArrayList<Descriptor>();
    roots.add(descriptor);
    roots.addAll(Arrays.asList(additionalTypes));
    return new ProtoPlan.Model(
        null, ProtoCompiler.definitions(roots), Limits.DEFAULT, 100, 8 * 1024 * 1024);
  }

  private ProtobufType(
      Descriptor descriptor, Map<Descriptor, ProtobufType> graph, ProtoPlan.Model model) {
    this.descriptor = descriptor;
    this.graph = graph;
    this.model = model;
    graph.put(descriptor, this);
    plan = model.type(descriptor.getFullName());
    source = ProtoCompiler.bind(plan, descriptor);
    var types = new LinkedHashMap<String, CelType>();
    for (FieldDescriptor f : descriptor.getFields()) types.put(f.getName(), type(f));
    fields = Map.copyOf(types);
    for (FieldDescriptor f : descriptor.getFields())
      if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
          && !graph.containsKey(f.getMessageType()))
        new ProtobufType(f.getMessageType(), graph, model);
  }

  /** The shared native plan for direct binary decoding and row construction. */
  public ProtoPlan plan() {
    return plan;
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
      return new Value(this, source.snapshot(message));
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
    return value instanceof Value v && v.type.descriptor == descriptor;
  }

  public boolean isZero(Object value) {
    Value v = (Value) value;
    return v.message().equals(DynamicMessage.getDefaultInstance(descriptor));
  }

  public boolean equal(Object a, Object b) {
    return a.equals(b);
  }

  private FieldDescriptor field(String name) {
    FieldDescriptor f = descriptor.findFieldByName(name);
    if (f == null) throw Values.error(CelException.Code.NO_SUCH_KEY, name);
    return f;
  }

  public boolean has(Object value, String name) {
    FieldDescriptor f = field(name);
    return ((Value) value).row().has(f.getIndex());
  }

  /** Ingest a row produced by this type's plan and apply CEL well-known mappings. */
  public Object input(ProtoRow row) {
    if (row.plan() != plan)
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "Protobuf plan differs from adapter");
    return canonical(new Value(this, row));
  }

  public Object get(Object value, String name) {
    Value v = (Value) value;
    FieldDescriptor f = field(name);
    Object nativeValue = DataLibrary.factory().getUncached().read(v.row(), f.getIndex());
    if (nativeValue == Missing.INSTANCE) {
      String nestedName = f.getMessageType().getFullName();
      if ((nestedName.endsWith("Value") || nestedName.equals("google.protobuf.Any"))
          && WELL_KNOWN.containsKey(nestedName)) return CelNull.INSTANCE;
      ProtobufType nested = graph.get(f.getMessageType());
      return nested.canonical(new Value(nested, nested.plan.newBuilder().build()));
    }
    if (f.isMapField()) {
      var result = new LinkedHashMap<Object, Object>();
      FieldDescriptor key = f.getMessageType().findFieldByNumber(1);
      FieldDescriptor val = f.getMessageType().findFieldByNumber(2);
      DataArray entries = (DataArray) nativeValue;
      ProtobufType entryType = graph.get(f.getMessageType());
      for (int i = 0; i < entries.size(); i++) {
        Value item = new Value(entryType, (ProtoRow) entries.get(i));
        result.put(entryType.get(item, key.getName()), entryType.get(item, val.getName()));
      }
      return Collections.unmodifiableMap(result);
    }
    if (f.isRepeated()) {
      var result = new ArrayList<Object>();
      DataArray entries = (DataArray) nativeValue;
      for (int i = 0; i < entries.size(); i++) result.add(scalar(entries.get(i), f));
      return List.copyOf(result);
    }
    return scalar(nativeValue, f);
  }

  private Object scalar(Object value, FieldDescriptor f) {
    return switch (f.getType()) {
      case UINT32, FIXED32 -> new UInt((Long) value);
      case UINT64, FIXED64 ->
          new UInt(((ExactNumber) value).decimal().toBigIntegerExact().longValue());
      case BYTES -> {
        DataArray array = (DataArray) value;
        byte[] bytes = new byte[(int) array.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (long) (Long) array.get(i);
        yield new Bytes(bytes);
      }
      case ENUM ->
          f.getEnumType().getFullName().equals("google.protobuf.NullValue")
              ? CelNull.INSTANCE
              : value;
      case MESSAGE, GROUP -> {
        ProtobufType nested = graph.get(f.getMessageType());
        yield nested.canonical(new Value(nested, (ProtoRow) value));
      }
      default -> Values.in(value);
    };
  }

  public Object construct(Map<String, Object> values) {
    try {
      var builder = plan.newBuilder();
      var oneofs = new HashSet<OneofDescriptor>();
      for (var entry : values.entrySet()) {
        FieldDescriptor f = field(entry.getKey());
        Object value = entry.getValue();
        if (value == CelNull.INSTANCE && f.getJavaType() == FieldDescriptor.JavaType.MESSAGE)
          continue;
        if (f.getContainingOneof() != null && !oneofs.add(f.getContainingOneof()))
          throw new IllegalArgumentException("Multiple oneof fields");
        if (f.isMapField()) {
          var entries = new ArrayList<Object>();
          for (var item : ((Map<?, ?>) value).entrySet()) {
            Descriptor d = f.getMessageType();
            ProtobufType entryType = graph.get(d);
            entries.add(
                ((Value)
                        entryType.construct(
                            Map.of(
                                d.findFieldByNumber(1).getName(), item.getKey(),
                                d.findFieldByNumber(2).getName(), item.getValue())))
                    .row());
          }
          builder.set(f.getIndex(), new DataArray(entries.toArray()));
        } else if (f.isRepeated()) {
          var entries = new ArrayList<Object>();
          for (Object item : (List<?>) value) entries.add(toProto(item, f));
          builder.set(f.getIndex(), new DataArray(entries.toArray()));
        } else builder.set(f.getIndex(), toProto(value, f));
      }
      return new Value(this, builder.build());
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
              ? Integer.toUnsignedLong(unsigned32((UInt) value))
              : (long) Math.toIntExact((Long) value);
      case LONG ->
          f.getType() == FieldDescriptor.Type.UINT64 || f.getType() == FieldDescriptor.Type.FIXED64
              ? new ExactNumber(new java.math.BigDecimal(((UInt) value).toBigInteger()))
              : (Long) value;
      case FLOAT -> (double) ((Double) value).floatValue();
      case DOUBLE -> (Double) value;
      case BOOLEAN -> (Boolean) value;
      case STRING -> (String) value;
      case BYTE_STRING -> DataArray.copyOfBytes(((Bytes) value).toByteArray());
      case ENUM ->
          (long)
              Math.toIntExact(
                  value == CelNull.INSTANCE
                          && f.getEnumType().getFullName().equals("google.protobuf.NullValue")
                      ? 0L
                      : (Long) value);
      case MESSAGE -> messageValue(value, f.getMessageType());
    };
  }

  private ProtoRow messageValue(Object value, Descriptor descriptor) {
    String name = descriptor.getFullName();
    ProtobufType nested = graph.get(descriptor);
    if (value instanceof Value v && v.type.descriptor == descriptor)
      return v.row().plan() == nested.plan
          ? v.row()
          : nested.plan.decode(v.toBytes().toByteArray());
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
        Value message = (Value) value;
        fields =
            Map.of(
                "type_url",
                "type.googleapis.com/" + message.type.name(),
                "value",
                message.toBytes());
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
    return ((Value) nested.construct(fields)).row();
  }

  private static int unsigned32(UInt value) {
    if (Long.compareUnsigned(value.bits(), 0xffffffffL) > 0)
      throw new IllegalArgumentException("uint32 overflow");
    return (int) value.bits();
  }

  public Object decode(Bytes bytes) {
    try {
      return new Value(this, plan.decode(bytes.toByteArray()));
    } catch (ContractViolation e) {
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
