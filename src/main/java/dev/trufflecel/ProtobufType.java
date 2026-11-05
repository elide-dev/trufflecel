package dev.trufflecel;

import com.google.protobuf.*;
import com.google.protobuf.Descriptors.*;
import dev.elide.truffledata.*;
import dev.elide.truffledata.protobuf.*;
import java.util.*;

/** Descriptor-compiled CEL binding backed by published TruffleData protobuf snapshots. */
public final class ProtobufType implements ObjectType, FieldPlan.Provider {
  private static final Map<String, ObjectType> WELL_KNOWN = Cel.newBuilder().build().objectTypes;

  private final Descriptor descriptor;
  private final ProtoPlan plan;
  private final Map<String, CelType> fields;
  private final Map<Descriptor, ProtobufType> graph;
  private final Map<String, FieldDescriptor> descriptors;
  private final Map<FieldDescriptor, Integer> ordinals;
  private final List<FieldDescriptor> extensions;
  private final Map<String, CelType> extensionTypes;
  private final ExtensionRegistry registry;

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
        return DynamicMessage.parseFrom(type.descriptor, toBytes().toByteArray(), type.registry);
      } catch (InvalidProtocolBufferException e) {
        throw new IllegalStateException("Invalid native protobuf row", e);
      }
    }

    public Bytes toBytes() {
      return new Bytes(type.wire(type.plan.encode(row), false, 0));
    }

    public ProtoRow row() {
      return row;
    }
  }

  public ProtobufType(Descriptor descriptor, Descriptor... additionalTypes) {
    this(descriptor, List.of(), additionalTypes);
  }

  /** Register extensions explicitly; native rows store their values by wire field number. */
  public ProtobufType(
      Descriptor descriptor, List<FieldDescriptor> extensions, Descriptor... additionalTypes) {
    this(descriptor, extensions, Limits.DEFAULT, additionalTypes);
  }

  /** Explicit bounded structural limits for trusted schemas larger than the default. */
  public ProtobufType(
      Descriptor descriptor,
      List<FieldDescriptor> extensions,
      Limits limits,
      Descriptor... additionalTypes) {
    this(descriptor, extensions, limits, expandedTypes(descriptor, additionalTypes, extensions));
  }

  private ProtobufType(
      Descriptor descriptor,
      List<FieldDescriptor> extensions,
      Limits limits,
      List<Descriptor> additionalTypes) {
    this(
        descriptor,
        new IdentityHashMap<>(),
        model(
            descriptor,
            additionalTypes.toArray(Descriptor[]::new),
            extensions,
            Objects.requireNonNull(limits)),
        List.copyOf(extensions));
    for (Descriptor additional : additionalTypes)
      if (!graph.containsKey(additional))
        new ProtobufType(additional, graph, model, this.extensions);
  }

  private static List<Descriptor> expandedTypes(
      Descriptor root, Descriptor[] additional, List<FieldDescriptor> extensions) {
    var pending = new ArrayDeque<Descriptor>();
    pending.add(root);
    pending.addAll(Arrays.asList(additional));
    for (FieldDescriptor e : extensions)
      if (e.getJavaType() == FieldDescriptor.JavaType.MESSAGE) pending.add(e.getMessageType());
    var names = new HashSet<String>();
    while (!pending.isEmpty()) {
      Descriptor d = pending.remove();
      if (!names.add(d.getFullName())) continue;
      for (FieldDescriptor f : d.getFields())
        if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) pending.add(f.getMessageType());
    }
    var result = new ArrayList<>(Arrays.asList(additional));
    if (names.contains("google.protobuf.Any"))
      for (Descriptor d :
          List.of(
              BoolValue.getDescriptor(),
              Int32Value.getDescriptor(),
              Int64Value.getDescriptor(),
              UInt32Value.getDescriptor(),
              UInt64Value.getDescriptor(),
              FloatValue.getDescriptor(),
              DoubleValue.getDescriptor(),
              StringValue.getDescriptor(),
              BytesValue.getDescriptor(),
              Timestamp.getDescriptor(),
              Duration.getDescriptor(),
              ListValue.getDescriptor(),
              Struct.getDescriptor(),
              com.google.protobuf.Value.getDescriptor()))
        if (!names.contains(d.getFullName())) result.add(d);
    return List.copyOf(result);
  }

  private final ProtoPlan.Model model;

  private static ProtoPlan.Model model(
      Descriptor descriptor,
      Descriptor[] additionalTypes,
      List<FieldDescriptor> extensions,
      Limits limits) {
    var roots = new ArrayList<Descriptor>();
    roots.add(descriptor);
    roots.addAll(Arrays.asList(additionalTypes));
    return new ProtoPlan.Model(
        null, extensionDefinitions(roots, extensions, limits), limits, 100, 8 * 1024 * 1024);
  }

  private static List<ProtoPlan.Type> extensionDefinitions(
      List<Descriptor> roots, List<FieldDescriptor> extensions, Limits limits) {
    for (FieldDescriptor extension : extensions)
      if (!extension.isExtension())
        throw new IllegalArgumentException("Not an extension: " + extension.getFullName());
    var pending = new ArrayDeque<>(roots);
    var types = new LinkedHashMap<String, ProtoPlan.Type>();
    var used = new HashSet<FieldDescriptor>();
    while (!pending.isEmpty()) {
      Descriptor descriptor = pending.remove();
      if (types.containsKey(descriptor.getFullName())) continue;
      var fields = new ArrayList<>(descriptor.getFields());
      for (FieldDescriptor extension : extensions)
        if (extension.getContainingType() == descriptor) {
          fields.add(extension);
          used.add(extension);
        }
      limits.check(1, fields.size());
      if (types.size() >= limits.maxColumns())
        throw new ContractViolation("Protobuf descriptor graph budget exceeded");
      var plans = new ArrayList<ProtoPlan.FieldPlan>();
      for (FieldDescriptor field : fields) {
        var kind = ProtoPlan.Kind.valueOf(field.getType().name());
        ProtoPlan.EnumType enumeration = null;
        if (field.getJavaType() == FieldDescriptor.JavaType.ENUM) {
          var numbers = new LinkedHashMap<String, Integer>();
          for (EnumValueDescriptor value : field.getEnumType().getValues())
            numbers.put(value.getName(), value.getNumber());
          enumeration =
              new ProtoPlan.EnumType(
                  field.getEnumType().getFullName(), !field.getEnumType().isClosed(), numbers);
        }
        boolean message = field.getJavaType() == FieldDescriptor.JavaType.MESSAGE;
        if (message) pending.add(field.getMessageType());
        Object defaultValue;
        if (message) defaultValue = Missing.INSTANCE;
        else {
          Object value =
              field.isRepeated()
                  ? switch (field.getType()) {
                    case INT64, UINT64, SINT64, FIXED64, SFIXED64 -> 0L;
                    case FLOAT -> 0.0f;
                    case DOUBLE -> 0.0;
                    case BOOL -> false;
                    case STRING -> "";
                    case BYTES -> ByteString.EMPTY;
                    case ENUM -> field.getEnumType().getValues().get(0);
                    default -> 0;
                  }
                  : field.getDefaultValue();
          defaultValue =
              switch (field.getType()) {
                case BYTES -> DataArray.copyOfBytes(((ByteString) value).toByteArray());
                case ENUM -> (long) ((EnumValueDescriptor) value).getNumber();
                case UINT32, FIXED32 -> Integer.toUnsignedLong((Integer) value);
                case UINT64, FIXED64 ->
                    new ExactNumber(
                        new java.math.BigDecimal(new UInt((Long) value).toBigInteger()));
                case INT32, SINT32, SFIXED32 -> ((Integer) value).longValue();
                case FLOAT -> ((Float) value).doubleValue();
                default -> value;
              };
        }
        String name = field.isExtension() ? field.getFullName() : field.getName();
        plans.add(
            new ProtoPlan.FieldPlan(
                name,
                field.getNumber(),
                kind,
                field.isRepeated(),
                field.isMapField(),
                field.hasPresence(),
                field.isRequired(),
                field.isPacked(),
                field.getContainingOneof() == null ? -1 : field.getContainingOneof().getIndex(),
                message ? field.getMessageType().getFullName() : "",
                enumeration,
                defaultValue,
                field.isExtension() ? "[" + name + "]" : field.getJsonName()));
      }
      types.put(
          descriptor.getFullName(),
          new ProtoPlan.Type(
              descriptor.getFullName(), descriptor.getOptions().getMapEntry(), plans));
    }
    if (used.size() != extensions.size())
      throw new IllegalArgumentException("Duplicate or unreachable extension");
    return List.copyOf(types.values());
  }

  private ProtobufType(
      Descriptor descriptor,
      Map<Descriptor, ProtobufType> graph,
      ProtoPlan.Model model,
      List<FieldDescriptor> extensions) {
    this.descriptor = descriptor;
    this.graph = graph;
    this.model = model;
    this.extensions = extensions;
    registry = ExtensionRegistry.newInstance();
    for (FieldDescriptor extension : extensions) {
      if (extension.getJavaType() == FieldDescriptor.JavaType.MESSAGE)
        registry.add(extension, DynamicMessage.getDefaultInstance(extension.getMessageType()));
      else registry.add(extension);
    }
    graph.put(descriptor, this);
    plan = model.type(descriptor.getFullName());
    var types = new LinkedHashMap<String, CelType>();
    var all = new ArrayList<>(descriptor.getFields());
    for (FieldDescriptor extension : extensions)
      if (extension.getContainingType() == descriptor) all.add(extension);
    var names = new LinkedHashMap<String, FieldDescriptor>();
    var indices = new IdentityHashMap<FieldDescriptor, Integer>();
    for (FieldDescriptor f : all) {
      String name = f.isExtension() ? f.getFullName() : f.getName();
      types.put(name, type(f));
      names.put(name, f);
      indices.put(f, indices.size());
    }
    descriptors = Map.copyOf(names);
    ordinals = Map.copyOf(indices);
    fields = Map.copyOf(types);
    var registered = new LinkedHashMap<String, CelType>();
    for (FieldDescriptor field : all)
      if (field.isExtension()) registered.put(field.getFullName(), type(field));
    extensionTypes = Map.copyOf(registered);
    for (FieldDescriptor f : all)
      if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
          && !graph.containsKey(f.getMessageType()))
        new ProtobufType(f.getMessageType(), graph, model, extensions);
  }

  /** The shared native plan for direct binary decoding and row construction. */
  public ProtoPlan plan() {
    return plan;
  }

  public Environment.Builder register(Environment.Builder builder) {
    for (ProtobufType type : graph.values()) {
      if (!WELL_KNOWN.containsKey(type.name())) builder.objectType(type);
      var enums = new HashSet<>(type.descriptor.getEnumTypes());
      for (FieldDescriptor f : type.descriptors.values())
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
      return new Value(this, plan.decode(wire(message.toByteArray(), true, 0)));
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

  public Map<String, CelType> extensions() {
    return extensionTypes;
  }

  public boolean accepts(Object value) {
    return value instanceof Value v && v.type.descriptor == descriptor;
  }

  @Override
  public FieldPlan prepareField(String name, ObjectType[] runtimeTypes) {
    // Custom adapters may overlap protobuf values or have observable accepts/get callbacks.
    for (ObjectType type : runtimeTypes) if (!(type instanceof ProtobufType)) return null;
    FieldDescriptor field = descriptors.get(name);
    if (field == null || field.isExtension() || field.isMapField()) return null;
    boolean scalar =
        switch (field.getType()) {
          case INT32, INT64, SINT32, SINT64, SFIXED32, SFIXED64, FLOAT, DOUBLE, BOOL, STRING ->
              true;
          default -> false;
        };
    if (!scalar) return null;
    return new ScalarField(this, ordinals.get(field), field.isRepeated());
  }

  private record ScalarField(ProtobufType type, int ordinal, boolean repeated)
      implements FieldPlan {
    public ReadNode instantiate() {
      return new ScalarRead(this);
    }
  }

  private static final class ScalarRead extends FieldPlan.ReadNode {
    private final ScalarField field;
    @Child private DataLibrary data = DataLibrary.factory().createDispatched(2);

    ScalarRead(ScalarField field) {
      this.field = field;
    }

    boolean accepts(Object value) {
      // Value construction guarantees this adapter's exact plan; descriptor identity is weaker.
      return value instanceof Value v && v.type == field.type;
    }

    Object read(Object value) {
      Object result = data.read(((Value) value).row, field.ordinal);
      return field.repeated ? Values.scalarRange((DataArray) result) : result;
    }

    boolean has(Object value) {
      return ((Value) value).row.has(field.ordinal);
    }
  }

  public boolean isZero(Object value) {
    Value v = (Value) value;
    return v.message().equals(DynamicMessage.getDefaultInstance(descriptor));
  }

  public boolean equal(Object a, Object b) {
    Value left = (Value) a;
    if (!(b instanceof Value right) || left.type.descriptor != right.type.descriptor) return false;
    Message message = left.message();
    return message.equals(right.message()) && reflexive(message);
  }

  // protobuf-java considers NaNs equal; CEL preserves IEEE equality inside messages.
  private static boolean reflexive(Object value) {
    if (value instanceof Double d) return !d.isNaN();
    if (value instanceof Float f) return !f.isNaN();
    if (value instanceof Message m) {
      for (Object field : m.getAllFields().values()) if (!reflexive(field)) return false;
    } else if (value instanceof List<?> list) {
      for (Object item : list) if (!reflexive(item)) return false;
    }
    return true;
  }

  private FieldDescriptor field(String name) {
    FieldDescriptor f = descriptors.get(name);
    if (f == null) throw Values.error(CelException.Code.NO_SUCH_KEY, name);
    return f;
  }

  public boolean has(Object value, String name) {
    FieldDescriptor f = field(name);
    return ((Value) value).row().has(ordinals.get(f));
  }

  /** Ingest a row produced by this type's plan and apply CEL well-known mappings. */
  public Object input(ProtoRow row) {
    if (row.plan() != plan)
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "Protobuf plan differs from adapter");
    return canonical(new Value(this, row));
  }

  public Object get(Object value, String name) {
    Value v = (Value) value;
    if (v.type != this) return v.type.get(v, name);
    FieldDescriptor f = field(name);
    Object nativeValue = DataLibrary.factory().getUncached().read(v.row(), ordinals.get(f));
    if (nativeValue == Missing.INSTANCE) {
      String nestedName = f.getMessageType().getFullName();
      if ((nestedName.endsWith("Value") && !nestedName.equals("google.protobuf.ListValue")
              || nestedName.equals("google.protobuf.Any"))
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
        if (value == CelNull.INSTANCE && !f.isRepeated() && nullableMessage(f)) continue;
        if (f.getContainingOneof() != null && !oneofs.add(f.getContainingOneof()))
          throw new IllegalArgumentException("Multiple oneof fields");
        if (f.isMapField()) {
          var entries = new ArrayList<Object>();
          for (var item : ((Map<?, ?>) value).entrySet()) {
            Descriptor d = f.getMessageType();
            if (item.getValue() == CelNull.INSTANCE && nullableMessage(d.findFieldByNumber(2)))
              continue;
            ProtobufType entryType = graph.get(d);
            entries.add(
                ((Value)
                        entryType.construct(
                            Map.of(
                                d.findFieldByNumber(1).getName(), item.getKey(),
                                d.findFieldByNumber(2).getName(), item.getValue())))
                    .row());
          }
          builder.set(ordinals.get(f), new DataArray(entries.toArray()));
        } else if (f.isRepeated()) {
          var entries = new ArrayList<Object>();
          for (Object item : (List<?>) value)
            if (item != CelNull.INSTANCE || !nullableMessage(f)) entries.add(toProto(item, f));
          builder.set(ordinals.get(f), new DataArray(entries.toArray()));
        } else builder.set(ordinals.get(f), toProto(value, f));
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

  private static boolean nullableMessage(FieldDescriptor f) {
    return f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
        && !Set.of(
                "google.protobuf.Value",
                "google.protobuf.Any",
                "google.protobuf.Struct",
                "google.protobuf.ListValue")
            .contains(f.getMessageType().getFullName());
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
          : nested.plan.decode(nested.wire(v.toBytes().toByteArray(), true, 0));
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
        if (value instanceof Bytes bytes)
          value = Base64.getEncoder().encodeToString(bytes.toByteArray());
        else if (value instanceof java.time.Instant time) value = time.toString();
        else if (value instanceof java.time.Duration time) value = Functions.durationString(time);
        else if (value instanceof Long number
            && (number < -9007199254740991L || number > 9007199254740991L))
          value = number.toString();
        else if (value instanceof UInt number
            && Long.compareUnsigned(number.bits(), 9007199254740991L) > 0)
          value = number.toString();
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
        Value message;
        if (value instanceof Value v) message = v;
        else {
          String payload =
              value instanceof Long
                  ? "Int64Value"
                  : value instanceof UInt
                      ? "UInt64Value"
                      : value instanceof Double
                          ? "DoubleValue"
                          : value instanceof Boolean
                              ? "BoolValue"
                              : value instanceof String
                                  ? "StringValue"
                                  : value instanceof Bytes
                                      ? "BytesValue"
                                      : value instanceof java.time.Instant
                                          ? "Timestamp"
                                          : value instanceof java.time.Duration
                                              ? "Duration"
                                              : value instanceof List<?>
                                                  ? "ListValue"
                                                  : value instanceof Map<?, ?> ? "Struct" : "Value";
          ProtobufType payloadType =
              graph.values().stream()
                  .filter(t -> t.name().equals("google.protobuf." + payload))
                  .findFirst()
                  .orElseThrow();
          message = new Value(payloadType, payloadType.messageValue(value, payloadType.descriptor));
        }
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
      return new Value(this, plan.decode(wire(bytes.toByteArray(), true, 0)));
    } catch (ContractViolation e) {
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "Invalid protobuf bytes");
    }
  }

  // MessageSet is normalized to ordinary length-delimited extension fields for native storage.
  // Recurse through registered messages so nested MessageSets use the same bounded codec.
  private byte[] wire(byte[] bytes, boolean ingest, int depth) {
    if (depth > 100 || bytes.length > 8 * 1024 * 1024)
      throw new ContractViolation("Protobuf wire budget exceeded");
    try {
      var input = CodedInputStream.newInstance(bytes);
      var buffer = new java.io.ByteArrayOutputStream();
      var output = CodedOutputStream.newInstance(buffer);
      boolean messageSet = descriptor.getOptions().getMessageSetWireFormat();
      int tag;
      while ((tag = input.readTag()) != 0) {
        int number = tag >>> 3;
        int kind = tag & 7;
        if (messageSet && ingest && tag == 11) {
          int typeId = 0;
          ByteString payload = ByteString.EMPTY;
          int itemTag;
          while ((itemTag = input.readTag()) != 12) {
            if (itemTag == 0) throw new java.io.IOException("Unterminated MessageSet item");
            if (itemTag == 16) typeId = input.readUInt32();
            else if (itemTag == 26) payload = input.readBytes();
            else {
              // The pinned reader rejects end-group tags outside readGroup.
              boolean unused = input.skipField(itemTag);
            }
          }
          if (typeId <= 0 || typeId > 536870911)
            throw new java.io.IOException("Invalid MessageSet type id");
          output.writeByteArray(typeId, nestedWire(typeId, payload.toByteArray(), true, depth));
        } else if (kind == 2) {
          byte[] payload = nestedWire(number, input.readByteArray(), ingest, depth);
          if (messageSet && !ingest) {
            output.writeTag(1, 3);
            output.writeUInt32(2, number);
            output.writeByteArray(3, payload);
            output.writeTag(1, 4);
          } else output.writeByteArray(number, payload);
        } else if (kind == 3 && groupType(number) != null) {
          var group = UnknownFieldSet.newBuilder();
          input.readGroup(number, group, ExtensionRegistryLite.getEmptyRegistry());
          byte[] payload = groupType(number).wire(group.build().toByteArray(), ingest, depth + 1);
          output.writeTag(number, 3);
          output.writeRawBytes(payload);
          output.writeTag(number, 4);
        } else {
          // Preserve unknown scalars and groups, including their wire field numbers.
          var unknown = UnknownFieldSet.newBuilder();
          // End-group tags at this level are rejected by the pinned reader itself.
          boolean unused = unknown.mergeFieldFrom(tag, input);
          unknown.build().writeTo(output);
        }
      }
      output.flush();
      return buffer.toByteArray();
    } catch (java.io.IOException e) {
      throw new ContractViolation("Invalid protobuf wire data: " + e.getMessage());
    }
  }

  private ProtobufType groupType(int number) {
    for (FieldDescriptor field : descriptors.values())
      if (field.getNumber() == number && field.getType() == FieldDescriptor.Type.GROUP)
        return graph.get(field.getMessageType());
    return null;
  }

  private byte[] nestedWire(int number, byte[] bytes, boolean ingest, int depth) {
    for (FieldDescriptor field : descriptors.values())
      if (field.getNumber() == number && field.getType() == FieldDescriptor.Type.MESSAGE)
        return graph.get(field.getMessageType()).wire(bytes, ingest, depth + 1);
    return bytes;
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
          case MESSAGE, GROUP -> {
            String name = f.getMessageType().getFullName();
            CelType mapped = WellKnownTypes.mapped(name);
            yield name.startsWith("google.protobuf.")
                    && name.endsWith("Value")
                    && !Set.of("google.protobuf.Value", "google.protobuf.ListValue").contains(name)
                ? CelType.wrapper(mapped)
                : mapped;
          }
        };
    return f.isRepeated() ? CelType.list(scalar) : scalar;
  }
}
