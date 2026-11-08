package dev.trufflecel;

import static org.junit.Assert.*;
import com.google.protobuf.*;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import dev.elide.truffledata.*;
import dev.elide.truffledata.protobuf.ProtoRow;
import java.util.*;
import org.junit.Test;

public class ProtobufIntegrationTest {
  @Test
  public void productionCorpusEdgesKeepBoundsAndCelEquality() throws Exception {
    var d = descriptor();
    var limits = new Limits(32, 64, 2048, 1024);
    var type = new ProtobufType(d, List.of(), limits);
    assertEquals(limits, type.plan().limits());
    assertThrows(
        ContractViolation.class, () -> new ProtobufType(d, List.of(), new Limits(1, 1, 1, 0)));
    Object floating = type.construct(Map.of("float", Double.NaN));
    assertFalse(type.equal(floating, floating));
    Object finite = type.construct(Map.of("float", 1.5));
    assertTrue(type.equal(finite, finite));
    assertTrue(type.equal(finite, new ProtobufType(d).construct(Map.of("float", 1.5))));
    assertFalse(type.equal(finite, 1.5));
    assertFalse(type.equal(finite, new ProtobufType(descriptor()).construct(Map.of("float", 1.5))));

    var json = new ProtobufType(ListValue.getDescriptor());
    Object nanList = json.construct(Map.of("values", List.of(Double.NaN)));
    assertFalse(json.equal(nanList, nanList));
    Object finiteList = json.construct(Map.of("values", List.of(1.0, "ok")));
    assertTrue(json.equal(finiteList, finiteList));

    var envelope =
        DescriptorProto.newBuilder()
            .setName("Envelope")
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("payload")
                    .setNumber(1)
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                    .setTypeName(".google.protobuf.Any"));
    var file =
        FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("envelope.proto")
                .setSyntax("proto3")
                .addDependency("google/protobuf/any.proto")
                .addMessageType(envelope)
                .build(),
            new FileDescriptor[] {Any.getDescriptor().getFile()});
    var adapter = new ProtobufType(file.findMessageTypeByName("Envelope"));
    for (Object value :
        List.of(
            java.time.Instant.ofEpochSecond(7),
            java.time.Duration.ofMillis(-1500),
            true,
            7L,
            new UInt(7),
            2.5,
            "text",
            new Bytes(new byte[] {1, 2}),
            List.of(1.0),
            Map.of("x", 1.0),
            CelNull.INSTANCE)) {
      Object message = adapter.construct(Map.of("payload", value));
      assertEquals(value, adapter.get(message, "payload"));
    }
    // JSON Value preserves integers outside binary64's exact range as strings.
    var list =
        (ProtobufType.Value)
            json.construct(
                Map.of("values", List.of(-9007199254740992L, 9007199254740992L, new UInt(-1L))));
    assertEquals(
        List.of("-9007199254740992", "9007199254740992", "18446744073709551615"),
        json.input(list.message()));
  }

  static Descriptor descriptor() throws Exception {
    var message = DescriptorProto.newBuilder().setName("Record");
    var enumeration =
        EnumDescriptorProto.newBuilder()
            .setName("State")
            .addValue(EnumValueDescriptorProto.newBuilder().setName("ZERO").setNumber(0))
            .addValue(EnumValueDescriptorProto.newBuilder().setName("ONE").setNumber(1));
    message.addEnumType(enumeration);
    for (FieldDescriptorProto.Type t : FieldDescriptorProto.Type.values()) {
      if (t == FieldDescriptorProto.Type.TYPE_GROUP) continue;
      var field =
          FieldDescriptorProto.newBuilder()
              .setName(t.name().substring(5).toLowerCase())
              .setNumber(t.getNumber())
              .setType(t)
              .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
      if (t == FieldDescriptorProto.Type.TYPE_ENUM) field.setTypeName(".test.Record.State");
      if (t == FieldDescriptorProto.Type.TYPE_MESSAGE) field.setTypeName(".test.Record");
      message.addField(field);
    }
    message.addField(
        FieldDescriptorProto.newBuilder()
            .setName("items")
            .setNumber(30)
            .setType(FieldDescriptorProto.Type.TYPE_INT64)
            .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED));
    message.addNestedType(
        DescriptorProto.newBuilder()
            .setName("LabelsEntry")
            .setOptions(MessageOptions.newBuilder().setMapEntry(true))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("key")
                    .setNumber(1)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("value")
                    .setNumber(2)
                    .setType(FieldDescriptorProto.Type.TYPE_INT64)));
    message.addField(
        FieldDescriptorProto.newBuilder()
            .setName("labels")
            .setNumber(31)
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setTypeName(".test.Record.LabelsEntry")
            .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED));
    return FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("test")
                .setSyntax("proto3")
                .addMessageType(message)
                .build(),
            new FileDescriptor[] {})
        .findMessageTypeByName("Record");
  }

  private static void zeroMatchesHost(ProtobufType type, Object value) {
    var message = ((ProtobufType.Value) value).message();
    assertEquals(
        message.equals(DynamicMessage.getDefaultInstance(message.getDescriptorForType())),
        type.isZero(value));
  }

  @Test
  public void nativeZeroChecksPreservePresenceUnknownsAndMapEntrySemantics() throws Exception {
    var d = descriptor();
    var type = new ProtobufType(d);
    zeroMatchesHost(type, type.construct(Map.of()));
    for (var fields :
        List.of(
            Map.of("double", 0.0),
            Map.of("double", -0.0),
            Map.of("double", Double.NaN),
            Map.of("float", -0.0),
            Map.of("int64", 0L),
            Map.of("int64", 1L),
            Map.of("string", ""),
            Map.of("bytes", new Bytes(new byte[0])),
            Map.of("items", List.of()),
            Map.of("items", List.of(0L)),
            Map.of("labels", Map.of()),
            Map.of("labels", Map.of("", 0L)),
            Map.of("message", type.construct(Map.of()))))
      zeroMatchesHost(type, type.construct(new HashMap<>(fields)));
    for (byte[] bytes :
        List.of(
            new byte[] {(byte) 0xa0, 6, 0},
            new byte[] {(byte) 0xa2, 6, 0},
            new byte[] {(byte) 0xa3, 6, (byte) 0xa4, 6}))
      zeroMatchesHost(type, type.decode(new Bytes(bytes)));
    var other = new ProtobufType(d);
    assertTrue(type.isZero(other.construct(Map.of())));
    assertFalse(type.isZero(new ProtobufType(descriptor()).construct(Map.of())));
    var entry = new ProtobufType(d.findFieldByName("labels").getMessageType());
    zeroMatchesHost(entry, entry.construct(Map.of()));
    zeroMatchesHost(entry, entry.construct(Map.of("key", "x", "value", 0L)));
    var required =
        FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                    .setName("required_zero.proto")
                    .setSyntax("proto2")
                    .addMessageType(
                        DescriptorProto.newBuilder()
                            .setName("Required")
                            .addField(
                                FieldDescriptorProto.newBuilder()
                                    .setName("number")
                                    .setNumber(1)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REQUIRED)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                    .setDefaultValue("7")))
                    .build(),
                new FileDescriptor[] {})
            .getMessageTypes()
            .get(0);
    var requiredType = new ProtobufType(required);
    zeroMatchesHost(requiredType, requiredType.construct(Map.of("number", 7L)));
  }

  @Test
  public void allScalarMappingsConstructionAndWire() throws Exception {
    Descriptor descriptor = descriptor();
    ProtobufType type = new ProtobufType(descriptor);
    var values = new LinkedHashMap<String, Object>();
    values.put("double", 2.5);
    values.put("float", 1.5);
    values.put("bool", true);
    values.put("string", "hello");
    values.put("bytes", new Bytes(new byte[] {0, -1}));
    values.put("enum", 1L);
    for (String name : List.of("int32", "int64", "sint32", "sint64", "sfixed32", "sfixed64"))
      values.put(name, -7L);
    for (String name : List.of("uint32", "fixed32")) values.put(name, UInt.parse("4294967295"));
    for (String name : List.of("uint64", "fixed64")) values.put(name, new UInt(-1));
    values.put("items", List.of(1L, 2L));
    values.put("labels", Map.of("a", 3L));
    values.put("message", type.construct(Map.of("string", "nested")));
    var value = (ProtobufType.Value) type.construct(values);
    assertTrue(type.accepts(value));
    assertFalse(type.accepts(1L));
    for (var item : values.entrySet()) {
      assertTrue(item.getKey(), type.has(value, item.getKey()));
      if (!item.getKey().equals("message"))
        assertEquals(item.getKey(), item.getValue(), type.get(value, item.getKey()));
    }
    assertTrue(type.equal(value, type.decode(new Bytes(value.message().toByteArray()))));
    Environment env =
        type.register(Cel.newBuilder()).variable("r", CelType.of(type.name())).build();
    assertTrue(
        env.compile(
                "r.uint64 == 18446744073709551615u && r.bytes == b'\\x00\\xff' && r.message.string == 'nested' && r.labels['a'] == 3 && r.items[1] == 2 && r.enum == 1")
            .test(Map.of("r", value)));
    assertTrue(env.compile("test.Record{int32: 7}.int32 == 7").test());
    var empty = type.snapshot(DynamicMessage.getDefaultInstance(descriptor));
    assertFalse(env.compile("optional.ofNonZeroValue(r).hasValue()").test(Map.of("r", empty)));
    assertTrue(env.compile("optional.ofNonZeroValue(r).hasValue()").test(Map.of("r", value)));
    assertFalse(type.has(empty, "int32"));
    assertFalse(type.has(empty, "items"));
    assertEquals(0L, type.get(empty, "int32"));
    assertTrue(type.accepts(type.get(empty, "message")));
    assertThrows(CelException.class, () -> type.get(value, "bad"));
    assertThrows(CelException.class, () -> type.decode(new Bytes(new byte[] {-1})));
    assertThrows(CelException.class, () -> type.construct(Map.of("uint32", new UInt(-1))));
    assertThrows(CelException.class, () -> type.construct(Map.of("int32", Long.MAX_VALUE)));
    assertThrows(CelException.class, () -> type.construct(Map.of("bad", 1L)));
  }

  @Test
  public void nativeRowsPreserveCompactBytesPresenceAndNumericEnums() throws Exception {
    Descriptor descriptor = descriptor();
    var proto =
        DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("uint64"), -1L)
            .setField(
                descriptor.findFieldByName("enum"),
                descriptor.findEnumTypeByName("State").findValueByNumberCreatingIfUnknown(123))
            .setField(descriptor.findFieldByName("bytes"), ByteString.copyFrom(new byte[] {-1}))
            .build();
    ProtobufType type = new ProtobufType(descriptor);
    var value = type.snapshot(proto);
    ProtoRow row = value.row();
    var data = DataLibrary.factory().getUncached();
    assertFalse(row.has(descriptor.findFieldByName("int32").getIndex()));
    assertFalse(proto.hasField(descriptor.findFieldByName("int32")));
    var binary = (DataArray) data.read(row, descriptor.findFieldByName("bytes").getIndex());
    assertTrue(binary.isBytes());
    assertEquals(1, binary.size());
    assertEquals(255L, binary.get(0));
    assertEquals(123L, data.read(row, descriptor.findFieldByName("enum").getIndex()));
    assertSame(type.plan(), row.plan());
    assertEquals(value, type.input(row));
    assertEquals(value, type.decode(value.toBytes()));
    assertEquals(123L, type.get(value, "enum"));
    assertEquals(new Bytes(new byte[] {-1}), type.get(value, "bytes"));
    assertFalse(type.has(value, "int32"));
    assertEquals(new UInt(-1), type.get(value, "uint64"));
  }

  @Test
  public void wellKnownInputMappings() throws Exception {
    assertEquals(42L, new ProtobufType(Int64Value.getDescriptor()).input(Int64Value.of(42)));
    assertEquals(
        0L, new ProtobufType(Int64Value.getDescriptor()).input(Int64Value.getDefaultInstance()));
    assertEquals(
        new UInt(-1), new ProtobufType(UInt64Value.getDescriptor()).input(UInt64Value.of(-1)));
    assertEquals(
        java.time.Instant.ofEpochSecond(42),
        new ProtobufType(Timestamp.getDescriptor())
            .input(Timestamp.newBuilder().setSeconds(42).build()));
    assertEquals(
        java.time.Duration.ofSeconds(2),
        new ProtobufType(Duration.getDescriptor())
            .input(Duration.newBuilder().setSeconds(2).build()));
    var json =
        Struct.newBuilder()
            .putFields("x", com.google.protobuf.Value.newBuilder().setNumberValue(2).build())
            .build();
    assertEquals(Map.of("x", 2.0), new ProtobufType(Struct.getDescriptor()).input(json));
    assertEquals(
        List.of("a"),
        new ProtobufType(ListValue.getDescriptor())
            .input(
                ListValue.newBuilder()
                    .addValues(com.google.protobuf.Value.newBuilder().setStringValue("a"))
                    .build()));
    assertEquals(
        CelNull.INSTANCE,
        new ProtobufType(com.google.protobuf.Value.getDescriptor())
            .input(com.google.protobuf.Value.getDefaultInstance()));
    var any = Any.pack(Int64Value.of(7));
    assertEquals(7L, new ProtobufType(Any.getDescriptor(), Int64Value.getDescriptor()).input(any));
    assertEquals(7L, new ProtobufType(Any.getDescriptor()).input(any));
    var standardAny = new ProtobufType(Any.getDescriptor());
    assertEquals(-7L, standardAny.input(Any.pack(Int32Value.of(-7))));
    assertEquals(new UInt(4294967295L), standardAny.input(Any.pack(UInt32Value.of(-1))));
    assertEquals(1.5, standardAny.input(Any.pack(FloatValue.of(1.5f))));
    assertThrows(
        CelException.class,
        () ->
            new ProtobufType(Any.getDescriptor())
                .input(
                    Any.newBuilder().setTypeUrl("type.googleapis.com/unregistered.Type").build()));
    var type = new ProtobufType(descriptor());
    assertTrue(
        type.input(((ProtobufType.Value) type.construct(Map.of())).message())
            instanceof ProtobufType.Value);
  }

  @Test
  public void wellKnownFieldConstructionAndDefaults() throws Exception {
    FileDescriptor[] imports = {
      Timestamp.getDescriptor().getFile(),
      Duration.getDescriptor().getFile(),
      Struct.getDescriptor().getFile(),
      Int64Value.getDescriptor().getFile(),
      Any.getDescriptor().getFile(),
      Empty.getDescriptor().getFile(),
      FieldMask.getDescriptor().getFile()
    };
    var file =
        FileDescriptorProto.newBuilder()
            .setName("holder.proto")
            .setPackage("test")
            .setSyntax("proto3");
    for (var dependency : imports) file.addDependency(dependency.getName());
    var message = DescriptorProto.newBuilder().setName("Holder");
    String[] names = {
      "Timestamp",
      "Duration",
      "Struct",
      "ListValue",
      "Value",
      "Int64Value",
      "Any",
      "Empty",
      "FieldMask"
    };
    for (int i = 0; i < names.length; i++)
      message.addField(
          FieldDescriptorProto.newBuilder()
              .setName("f" + i)
              .setNumber(i + 1)
              .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
              .setTypeName(".google.protobuf." + names[i]));
    Descriptor d =
        FileDescriptor.buildFrom(file.addMessageType(message).build(), imports)
            .findMessageTypeByName("Holder");
    var type = new ProtobufType(d);
    Object nested = type.construct(Map.of());
    var values = new LinkedHashMap<String, Object>();
    values.put("f0", java.time.Instant.ofEpochSecond(2, 3));
    values.put("f1", java.time.Duration.ofNanos(-1));
    for (var duration : List.of(java.time.Duration.ofSeconds(-1), java.time.Duration.ofSeconds(1)))
      assertEquals(duration, type.get(type.construct(Map.of("f1", duration)), "f1"));
    values.put("f2", Map.of("null", CelNull.INSTANCE, "n", 3L));
    values.put("f3", List.of(true, "x", new UInt(2), List.of(4L), Map.of("a", false)));
    values.put("f4", 3.5);
    values.put("f5", 42L);
    values.put("f6", nested);
    values.put("f7", Map.of());
    values.put("f8", "a,b");
    var value = type.construct(values);
    assertEquals(values.get("f0"), type.get(value, "f0"));
    assertEquals(values.get("f1"), type.get(value, "f1"));
    assertEquals(Map.of("null", CelNull.INSTANCE, "n", 3.0), type.get(value, "f2"));
    assertEquals(List.of(true, "x", 2.0, List.of(4.0), Map.of("a", false)), type.get(value, "f3"));
    for (String f : List.of("f4", "f5", "f7", "f8"))
      assertEquals(values.get(f), type.get(value, f));
    assertEquals(nested, type.get(value, "f6"));
    assertEquals(nested.hashCode(), type.get(value, "f6").hashCode());
    assertFalse(nested.equals(1L));
    assertEquals(CelNull.INSTANCE, type.get(nested, "f5"));
    assertEquals(CelNull.INSTANCE, type.get(nested, "f6"));
    assertEquals(java.time.Instant.EPOCH, type.get(nested, "f0"));
    assertFalse(type.has(type.construct(Map.of("f5", CelNull.INSTANCE)), "f5"));
    var env = type.register(Cel.newBuilder()).variable("v", CelType.of(type.name())).build();
    assertTrue(
        env.compile("v.f5 == 42 && v.f0 == timestamp('1970-01-01T00:00:02.000000003Z')")
            .test(Map.of("v", value)));
  }

  @Test
  public void explicitDefaultsOneofsAndIngestionLimits() throws Exception {
    var m =
        DescriptorProto.newBuilder()
            .setName("Presence")
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("number")
                    .setNumber(1)
                    .setType(FieldDescriptorProto.Type.TYPE_INT32)
                    .setDefaultValue("7"))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("a")
                    .setNumber(2)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                    .setOneofIndex(0))
            .addField(
                FieldDescriptorProto.newBuilder()
                    .setName("b")
                    .setNumber(3)
                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                    .setOneofIndex(0));
    Descriptor d =
        FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                    .setName("presence.proto")
                    .setSyntax("proto2")
                    .addMessageType(m)
                    .build(),
                new FileDescriptor[] {})
            .findMessageTypeByName("Presence");
    var type = new ProtobufType(d);
    Object empty = type.construct(Map.of());
    assertEquals(7L, type.get(empty, "number"));
    assertFalse(type.has(empty, "number"));
    zeroMatchesHost(type, empty);
    zeroMatchesHost(type, type.construct(Map.of("number", 7L)));
    zeroMatchesHost(type, type.construct(Map.of("a", "")));
    assertTrue(type.has(type.construct(Map.of("number", 7L)), "number"));
    assertThrows(CelException.class, () -> type.construct(Map.of("a", "x", "b", "y")));
    assertThrows(CelException.class, () -> type.construct(Map.of("number", "bad")));
    assertThrows(CelException.class, () -> type.snapshot(Int64Value.of(7)));
    var bytes = new ProtobufType(BytesValue.getDescriptor());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () -> bytes.input(BytesValue.of(ByteString.copyFrom(new byte[262145]))))
            .code());
    var nullType = new ProtobufType(com.google.protobuf.Value.getDescriptor());
    assertEquals(
        CelNull.INSTANCE,
        nullType.input(
            com.google.protobuf.Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()));
  }

  @Test
  public void uncheckedConstructionStillEnforcesSignedness() throws Exception {
    var type = new ProtobufType(descriptor());
    var env = type.register(Cel.newBuilder()).build();
    for (String field : List.of("uint32", "uint64", "fixed32", "fixed64"))
      assertThrows(
          field, CelException.class, () -> env.parse("test.Record{" + field + ": 1}").evaluate());
    for (String field : List.of("int32", "int64", "sint32", "sint64", "sfixed32", "sfixed64"))
      assertThrows(
          field, CelException.class, () -> env.parse("test.Record{" + field + ": 1u}").evaluate());
  }

  @Test
  public void nativeConstructionWireAndRowInputs() throws Exception {
    Descriptor d = descriptor();
    var type = new ProtobufType(d);
    ProtoRow row =
        type.plan()
            .newBuilder()
            .set(d.findFieldByName("string").getIndex(), "native")
            .set(d.findFieldByName("enum").getIndex(), 456L)
            .build();
    var value = (ProtobufType.Value) type.input(row);
    assertSame(row, value.row());
    var env = type.register(Cel.newBuilder()).variable("r", CelType.of(type.name())).build();
    assertTrue(
        env.compile("r.string == 'native' && r.enum == 456 && !has(r.int32)")
            .test(Map.of("r", type.decode(value.toBytes()))));
    var other = new ProtobufType(d);
    assertThrows(CelException.class, () -> type.input(other.plan().newBuilder().build()));
    var nested = (ProtobufType.Value) other.construct(Map.of("string", "remapped"));
    var parent = type.construct(Map.of("message", nested));
    assertEquals(nested, type.get(parent, "message"));
    assertFalse(value.equals(new ProtobufType(descriptor()).construct(Map.of("string", "native"))));
    var wrapper = new ProtobufType(Int64Value.getDescriptor());
    assertEquals(42L, wrapper.input(wrapper.plan().decode(new byte[] {8, 42})));
  }

  @Test
  public void nativeCodecPreservesUnknownFieldsAndMapSemantics() throws Exception {
    Descriptor d = descriptor();
    var type = new ProtobufType(d);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(77).build())
            .build();
    var message = DynamicMessage.newBuilder(d).setUnknownFields(unknown).build();
    var value = (ProtobufType.Value) type.decode(new Bytes(message.toByteArray()));
    assertEquals(unknown, value.message().getUnknownFields());
    assertArrayEquals(message.toByteArray(), value.toBytes().toByteArray());
    assertEquals(type.snapshot(message), value);
    assertFalse(type.isZero(value));
    assertFalse(type.equal(value, type.construct(Map.of())));
    Map<String, Long> forward = new LinkedHashMap<>();
    forward.put("a", 1L);
    forward.put("b", 2L);
    Map<String, Long> reverse = new LinkedHashMap<>();
    reverse.put("b", 2L);
    reverse.put("a", 1L);
    assertEquals(
        type.construct(Map.of("labels", forward)), type.construct(Map.of("labels", reverse)));
    Descriptor entry = d.findFieldByName("labels").getMessageType();
    var duplicate = DynamicMessage.newBuilder(d);
    for (long n : new long[] {1, 2})
      duplicate.addRepeatedField(
          d.findFieldByName("labels"),
          DynamicMessage.newBuilder(entry)
              .setField(entry.findFieldByNumber(1), "key")
              .setField(entry.findFieldByNumber(2), n)
              .build());
    var decoded = type.decode(new Bytes(duplicate.build().toByteArray()));
    assertEquals(Map.of("key", 2L), type.get(decoded, "labels"));
    for (byte[] wire : new byte[][] {{0}, {18, 127}, {(byte) 0xff}})
      assertEquals(
          CelException.Code.INVALID_ARGUMENT,
          assertThrows(CelException.class, () -> type.decode(new Bytes(wire))).code());
  }

  @Test
  public void nullEnumsRepeatedValuesAndWrongMessageInputs() throws Exception {
    var valueType = new ProtobufType(com.google.protobuf.Value.getDescriptor());
    var nullValue =
        (ProtobufType.Value) valueType.construct(Map.of("null_value", CelNull.INSTANCE));
    assertEquals(
        com.google.protobuf.Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build(),
        nullValue.message());
    var lists = new ProtobufType(ListValue.getDescriptor());
    var list = (ProtobufType.Value) lists.construct(Map.of("values", List.of(CelNull.INSTANCE)));
    assertEquals(
        ListValue.newBuilder()
            .addValues(com.google.protobuf.Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
            .build(),
        list.message());
    var descriptor = descriptor();
    var type = new ProtobufType(descriptor, descriptor);
    assertEquals(
        CelException.Code.INVALID_ARGUMENT,
        assertThrows(CelException.class, () -> type.construct(Map.of("message", 1L))).code());
  }

  @Test
  public void customValueSuffixIsNotAWellKnownType() throws Exception {
    var descriptor =
        FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                    .setName("value.proto")
                    .setPackage("test")
                    .setSyntax("proto3")
                    .addMessageType(
                        DescriptorProto.newBuilder()
                            .setName("CustomValue")
                            .addField(
                                FieldDescriptorProto.newBuilder()
                                    .setName("child")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".test.CustomValue")))
                    .build(),
                new FileDescriptor[] {})
            .findMessageTypeByName("CustomValue");
    var type = new ProtobufType(descriptor);
    Object empty = type.construct(Map.of());
    assertTrue(type.accepts(type.get(empty, "child")));
    var ordinaryEnum = new ProtobufType(descriptor());
    assertThrows(
        CelException.class, () -> ordinaryEnum.construct(Map.of("enum", CelNull.INSTANCE)));
  }

  @Test
  public void nativeRegisteredExtensionsRoundTripAndDefaults() throws Exception {
    Descriptor record = descriptor();
    var file =
        FileDescriptorProto.newBuilder()
            .setName("extensions.proto")
            .setPackage("ext")
            .setSyntax("proto2")
            .addDependency(record.getFile().getName())
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Base")
                    .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("regular")
                            .setNumber(1)
                            .setType(FieldDescriptorProto.Type.TYPE_INT32)
                            .setOneofIndex(0))
                    .addEnumType(
                        EnumDescriptorProto.newBuilder()
                            .setName("Closed")
                            .addValue(
                                EnumValueDescriptorProto.newBuilder().setName("ZERO").setNumber(0))
                            .addValue(
                                EnumValueDescriptorProto.newBuilder().setName("ONE").setNumber(1)))
                    .addExtensionRange(
                        DescriptorProto.ExtensionRange.newBuilder().setStart(100).setEnd(1000)));
    int number = 100;
    for (FieldDescriptor original : record.getFields()) {
      if (original.isMapField()) continue;
      for (boolean repeated : List.of(false, true)) {
        var field =
            original.toProto().toBuilder()
                .setName(original.getName() + (repeated ? "_many" : "_one"))
                .setNumber(number++)
                .setExtendee(".ext.Base")
                .setLabel(
                    repeated
                        ? FieldDescriptorProto.Label.LABEL_REPEATED
                        : FieldDescriptorProto.Label.LABEL_OPTIONAL);
        if (original.getJavaType() == FieldDescriptor.JavaType.MESSAGE)
          field.setTypeName(".test.Record");
        if (original.getJavaType() == FieldDescriptor.JavaType.ENUM)
          field.setTypeName(".test.Record.State");
        if (!repeated && original.getJavaType() == FieldDescriptor.JavaType.STRING)
          field.setDefaultValue("fallback");
        file.addExtension(field);
      }
    }
    file.addExtension(
        FieldDescriptorProto.newBuilder()
            .setName("closed")
            .setNumber(number)
            .setExtendee(".ext.Base")
            .setType(FieldDescriptorProto.Type.TYPE_ENUM)
            .setTypeName(".ext.Base.Closed"));
    FileDescriptor descriptor =
        FileDescriptor.buildFrom(file.build(), new FileDescriptor[] {record.getFile()});
    Descriptor base = descriptor.findMessageTypeByName("Base");
    List<FieldDescriptor> extensions = descriptor.getExtensions();
    ProtobufType type = new ProtobufType(base, extensions);
    var env =
        type.register(Cel.newBuilder().container("ext"))
            .variable("m", CelType.of(type.name()))
            .build();
    var message = DynamicMessage.newBuilder(base);
    for (FieldDescriptor field : extensions) {
      Object scalar =
          switch (field.getJavaType()) {
            case INT -> 7;
            case LONG -> 9L;
            case FLOAT -> 1.5f;
            case DOUBLE -> 2.5;
            case BOOLEAN -> true;
            case STRING -> "present";
            case BYTE_STRING -> ByteString.copyFromUtf8("hello");
            case ENUM -> field.getEnumType().findValueByNumber(1);
            case MESSAGE ->
                DynamicMessage.newBuilder(record)
                    .setField(record.findFieldByName("int64"), 12L)
                    .build();
          };
      message.setField(field, field.isRepeated() ? List.of(scalar) : scalar);
    }
    ProtobufType.Value value = type.snapshot(message.build());
    assertEquals(message.build(), value.message());
    var decoded = (ProtobufType.Value) type.decode(value.toBytes());
    assertEquals(value, decoded);
    var constructed = new LinkedHashMap<String, Object>();
    for (FieldDescriptor field : extensions) {
      String name = field.getFullName();
      assertTrue(type.has(decoded, name));
      constructed.put(name, type.get(decoded, name));
      assertTrue(env.compile("proto.hasExt(m, " + field.getName() + ")").test(Map.of("m", value)));
      assertEquals(
          type.get(value, name),
          env.compile("proto.getExt(m, ." + name + ")").evaluateInternal(Map.of("m", value)));
    }
    assertEquals(value, type.construct(constructed));
    assertThrows(
        CelException.class,
        () ->
            env.compile("proto.getExt(dyn(optional.of(m)), ext.string_one)")
                .evaluate(Map.of("m", value)));
    assertEquals(
        "[ext.string_one]",
        type.plan()
            .field(type.plan().ordinal(descriptor.findExtensionByName("string_one").getNumber()))
            .jsonName());
    var empty = type.construct(Map.of());
    zeroMatchesHost(type, empty);
    zeroMatchesHost(type, value);
    zeroMatchesHost(type, type.construct(Map.of("ext.string_one", "fallback")));
    assertFalse(env.compile("proto.hasExt(m, string_one)").test(Map.of("m", empty)));
    assertEquals(
        "fallback", env.compile("proto.getExt(m, string_one)").evaluate(Map.of("m", empty)));
    assertEquals(List.of(), type.get(empty, "ext.int32_many"));
    assertTrue(type.get(empty, "ext.message_one") instanceof ProtobufType.Value);
    assertFalse(env.compile("proto.hasExt(dyn(m), ext.string_one)").test(Map.of("m", empty)));
    assertThrows(CelException.class, () -> env.compile("proto.getExt(m, ext.nope)"));
    assertThrows(
        CelException.class,
        () -> env.parse("proto.getExt(m, ext.nope)").evaluate(Map.of("m", value)));
    assertThrows(
        CelException.class,
        () -> env.compile("proto.getExt(dyn(m), ext.nope)").evaluate(Map.of("m", value)));
    assertThrows(CelException.class, () -> env.compile("proto.getExt(1, ext.string_one)"));
    assertThrows(CelException.class, () -> env.parse("proto.getExt(1, ext.string_one)").evaluate());
    assertThrows(
        CelException.class, () -> env.parse("proto.hasExt({}, ext.string_one)").evaluate());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtobufType(base, List.of(record.getFields().get(0))));
    assertThrows(IllegalArgumentException.class, () -> new ProtobufType(record, extensions));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProtobufType(base, List.of(extensions.get(0), extensions.get(0))));
    assertTrue(env.compile(".proto.hasExt(m, .ext.string_one)").test(Map.of("m", value)));
    var ordinary =
        new ObjectType() {
          public String name() {
            return "Ordinary";
          }

          public Map<String, CelType> fields() {
            return Map.of();
          }

          public Object construct(Map<String, Object> f) {
            return new Object();
          }

          public Object get(Object v, String f) {
            return 0L;
          }

          public boolean has(Object v, String f) {
            return false;
          }

          public boolean accepts(Object v) {
            return false;
          }
        };
    assertTrue(ordinary.extensions().isEmpty());
    var many = FileDescriptorProto.newBuilder().setName("many.proto");
    for (int i = 0; i < 256; i++)
      many.addMessageType(DescriptorProto.newBuilder().setName("Message" + i));
    var roots =
        FileDescriptor.buildFrom(many.build(), new FileDescriptor[] {})
            .getMessageTypes()
            .toArray(Descriptor[]::new);
    assertThrows(ContractViolation.class, () -> new ProtobufType(base, extensions, roots));
  }

  @Test
  public void editionsPresenceAndWireRoundTrip() throws Exception {
    var file =
        FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("edition.proto")
                .setPackage("edition")
                .setSyntax("editions")
                .setEdition(Edition.EDITION_2023)
                .addMessageType(
                    DescriptorProto.newBuilder()
                        .setName("Record")
                        .addField(
                            FieldDescriptorProto.newBuilder()
                                .setName("explicit_value")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_INT64))
                        .addField(
                            FieldDescriptorProto.newBuilder()
                                .setName("implicit_value")
                                .setNumber(2)
                                .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                .setOptions(
                                    FieldOptions.newBuilder()
                                        .setFeatures(
                                            FeatureSet.newBuilder()
                                                .setFieldPresence(
                                                    FeatureSet.FieldPresence.IMPLICIT)))))
                .build(),
            new FileDescriptor[] {});
    var d = file.getMessageTypes().get(0);
    var type = new ProtobufType(d);
    var env = type.register(Cel.newBuilder()).variable("m", CelType.of(type.name())).build();
    var message = DynamicMessage.newBuilder(d).setField(d.findFieldByNumber(1), 0L).build();
    var input = type.snapshot(message);
    zeroMatchesHost(type, input);
    zeroMatchesHost(type, type.construct(Map.of()));
    zeroMatchesHost(type, type.construct(Map.of("implicit_value", 0L)));
    assertEquals(message, input.message());
    assertEquals(message, ((ProtobufType.Value) type.decode(input.toBytes())).message());
    assertTrue(
        env.compile("has(m.explicit_value) && !has(m.implicit_value)").test(Map.of("m", input)));
    assertEquals(
        message,
        ((ProtobufType.Value) env.compile("edition.Record{explicit_value: 0}").evaluate())
            .message());
  }

  @Test
  public void messageSetExtensionsUseNativeStorage() throws Exception {
    var file =
        FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("messageset.proto")
                .setPackage("ms")
                .setSyntax("proto2")
                .addMessageType(
                    DescriptorProto.newBuilder()
                        .setName("Set")
                        .setOptions(MessageOptions.newBuilder().setMessageSetWireFormat(true))
                        .addExtensionRange(
                            DescriptorProto.ExtensionRange.newBuilder()
                                .setStart(4)
                                .setEnd(536870912)))
                .addMessageType(
                    DescriptorProto.newBuilder()
                        .setName("Payload")
                        .addField(
                            FieldDescriptorProto.newBuilder()
                                .setName("name")
                                .setNumber(1)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)))
                .addExtension(
                    FieldDescriptorProto.newBuilder()
                        .setName("payload")
                        .setNumber(100)
                        .setExtendee(".ms.Set")
                        .setTypeName(".ms.Payload")
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build(),
            new FileDescriptor[] {});
    var root = file.getMessageTypes().get(0);
    var payload = file.getMessageTypes().get(1);
    var extension = file.getExtensions().get(0);
    var type = new ProtobufType(root, List.of(extension));
    var message =
        DynamicMessage.newBuilder(root)
            .setField(
                extension,
                DynamicMessage.newBuilder(payload)
                    .setField(payload.getFields().get(0), "native")
                    .build())
            .build();
    var value = type.snapshot(message);
    assertEquals(message, value.message());
    assertEquals(
        message, ((ProtobufType.Value) type.decode(new Bytes(message.toByteArray()))).message());
    var env = type.register(Cel.newBuilder()).variable("m", CelType.of(type.name())).build();
    assertTrue(
        env.compile("proto.hasExt(m, ms.payload) && proto.getExt(m, ms.payload).name == 'native'")
            .test(Map.of("m", value)));
    for (byte[] invalid :
        List.of(
            new byte[] {11, 16, 100, 26, 0},
            new byte[] {11, 20},
            new byte[] {11, 26, 0, 12},
            new byte[] {11, 16, -1, -1, -1, -1, 7, 12},
            new byte[] {11, 16, -1, -1, -1, -1, 15, 12},
            new byte[] {12}))
      assertThrows(CelException.class, () -> type.decode(new Bytes(invalid)));
    assertTrue(
        type.decode(new Bytes(new byte[] {11, 16, 100, 32, 1, 26, 0, 12}))
            instanceof ProtobufType.Value);
    assertTrue(
        type.decode(new Bytes(new byte[] {11, 26, 0, 16, 100, 12})) instanceof ProtobufType.Value);
    assertTrue(type.decode(new Bytes(new byte[] {-94, 6, 0})) instanceof ProtobufType.Value);
    Object nativeUnknownGroup = type.input(type.plan().decode(new byte[] {11, 12}));
    zeroMatchesHost(type, type.construct(Map.of()));
    zeroMatchesHost(type, value);
    zeroMatchesHost(type, nativeUnknownGroup);
    assertTrue(((ProtobufType.Value) nativeUnknownGroup).toBytes().size() > 0);
    assertThrows(CelException.class, () -> type.decode(new Bytes(new byte[8 * 1024 * 1024 + 1])));
    var plain = new ProtobufType(root);
    assertEquals(
        DynamicMessage.parseFrom(root, message.toByteArray()), plain.snapshot(message).message());
    assertArrayEquals(message.toByteArray(), plain.snapshot(message).toBytes().toByteArray());
    // MessageSet can be reachable only through a registered extension. The ordinary parent must
    // still normalize it, while an unregistered extension remains opaque unknown wire data.
    var outerFile =
        FileDescriptor.buildFrom(
            FileDescriptorProto.newBuilder()
                .setName("outer.proto")
                .setPackage("outer")
                .setSyntax("proto2")
                .addDependency("messageset.proto")
                .addMessageType(
                    DescriptorProto.newBuilder()
                        .setName("Root")
                        .addField(
                            FieldDescriptorProto.newBuilder()
                                .setName("child")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".outer.Root"))
                        .addField(
                            FieldDescriptorProto.newBuilder()
                                .setName("group")
                                .setNumber(2)
                                .setType(FieldDescriptorProto.Type.TYPE_GROUP)
                                .setTypeName(".outer.Root.Group"))
                        .addField(
                            FieldDescriptorProto.newBuilder()
                                .setName("scalar")
                                .setNumber(3)
                                .setType(FieldDescriptorProto.Type.TYPE_INT32))
                        .addNestedType(
                            DescriptorProto.newBuilder()
                                .setName("Group")
                                .addField(
                                    FieldDescriptorProto.newBuilder()
                                        .setName("set")
                                        .setNumber(1)
                                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                        .setTypeName(".ms.Set")))
                        .addExtensionRange(
                            DescriptorProto.ExtensionRange.newBuilder().setStart(100).setEnd(200)))
                .addExtension(
                    FieldDescriptorProto.newBuilder()
                        .setName("set")
                        .setNumber(100)
                        .setExtendee(".outer.Root")
                        .setTypeName(".ms.Set")
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build(),
            new FileDescriptor[] {file});
    var outer = outerFile.getMessageTypes().get(0);
    var outerExtension = outerFile.getExtensions().get(0);
    var outerType = new ProtobufType(outer, List.of(outerExtension, extension));
    var outerMessage = DynamicMessage.newBuilder(outer).setField(outerExtension, message).build();
    assertEquals(outerMessage, outerType.snapshot(outerMessage).message());
    assertEquals(
        outerMessage,
        ((ProtobufType.Value) outerType.decode(new Bytes(outerMessage.toByteArray()))).message());
    var group = outer.getNestedTypes().get(0);
    var grouped =
        DynamicMessage.newBuilder(outer)
            .setField(
                outer.findFieldByNumber(2),
                DynamicMessage.newBuilder(group)
                    .setField(group.findFieldByNumber(1), message)
                    .build())
            .setField(outer.findFieldByNumber(3), 7)
            .build();
    assertEquals(grouped, outerType.snapshot(grouped).message());
    assertEquals(
        grouped,
        ((ProtobufType.Value) outerType.decode(new Bytes(grouped.toByteArray()))).message());
    // Unknown groups, including a group carrying a scalar field number, remain opaque.
    for (byte[] unknown : List.of(new byte[] {27, 28}, new byte[] {35, 36}))
      assertArrayEquals(
          unknown,
          ((ProtobufType.Value) outerType.decode(new Bytes(unknown))).toBytes().toByteArray());
    byte[] recursive = new byte[0];
    for (int depth = 0; depth < 102; depth++) {
      var buffer = new java.io.ByteArrayOutputStream();
      var output = CodedOutputStream.newInstance(buffer);
      output.writeByteArray(1, recursive);
      output.flush();
      recursive = buffer.toByteArray();
    }
    Bytes tooDeep = new Bytes(recursive);
    assertThrows(CelException.class, () -> outerType.decode(tooDeep));
    var opaqueType = new ProtobufType(outer);
    assertEquals(
        DynamicMessage.parseFrom(outer, outerMessage.toByteArray()),
        opaqueType.snapshot(outerMessage).message());
  }

  @Test
  public void groupsWithScalarFieldNumbersRemainUnknown() {
    var type = new ProtobufType(StringValue.getDescriptor());
    var value = (ProtobufType.Value) type.decode(new Bytes(new byte[] {11, 12}));
    assertArrayEquals(new byte[] {11, 12}, value.toBytes().toByteArray());
    assertEquals("", type.get(value, "value"));
  }

  @Test
  public void nativeWireRecursionRemainsBounded() throws Exception {
    var node =
        FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                    .setName("recursive.proto")
                    .addMessageType(
                        DescriptorProto.newBuilder()
                            .setName("Node")
                            .addField(
                                FieldDescriptorProto.newBuilder()
                                    .setName("child")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".Node")))
                    .build(),
                new FileDescriptor[] {})
            .getMessageTypes()
            .get(0);
    var type = new ProtobufType(node);
    byte[] wire = new byte[0];
    for (int depth = 0; depth < 102; depth++) {
      var buffer = new java.io.ByteArrayOutputStream();
      var output = CodedOutputStream.newInstance(buffer);
      output.writeByteArray(1, wire);
      output.flush();
      wire = buffer.toByteArray();
    }
    Bytes bytes = new Bytes(wire);
    assertThrows(CelException.class, () -> type.decode(bytes));
  }

  @Test
  public void editionsPackedFieldsClosedEnumsAndDelimitedMessages() throws Exception {
    for (Edition edition : List.of(Edition.EDITION_2023, Edition.EDITION_2024)) {
      var file =
          FileDescriptor.buildFrom(
              FileDescriptorProto.newBuilder()
                  .setName("features.proto")
                  .setSyntax("editions")
                  .setEdition(edition)
                  .setPackage("features")
                  .addEnumType(
                      EnumDescriptorProto.newBuilder()
                          .setName("Enum")
                          .setOptions(
                              EnumOptions.newBuilder()
                                  .setFeatures(
                                      FeatureSet.newBuilder()
                                          .setEnumType(FeatureSet.EnumType.CLOSED)))
                          .addValue(
                              EnumValueDescriptorProto.newBuilder().setName("ZERO").setNumber(0)))
                  .addMessageType(
                      DescriptorProto.newBuilder()
                          .setName("Inner")
                          .addField(
                              FieldDescriptorProto.newBuilder()
                                  .setName("name")
                                  .setNumber(1)
                                  .setType(FieldDescriptorProto.Type.TYPE_STRING)))
                  .addMessageType(
                      DescriptorProto.newBuilder()
                          .setName("Outer")
                          .addField(
                              FieldDescriptorProto.newBuilder()
                                  .setName("inner")
                                  .setNumber(1)
                                  .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                  .setTypeName(".features.Inner")
                                  .setOptions(
                                      FieldOptions.newBuilder()
                                          .setFeatures(
                                              FeatureSet.newBuilder()
                                                  .setMessageEncoding(
                                                      FeatureSet.MessageEncoding.DELIMITED))))
                          .addField(
                              FieldDescriptorProto.newBuilder()
                                  .setName("numbers")
                                  .setNumber(2)
                                  .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                  .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                          .addField(
                              FieldDescriptorProto.newBuilder()
                                  .setName("enumeration")
                                  .setNumber(3)
                                  .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                                  .setTypeName(".features.Enum")))
                  .build(),
              new FileDescriptor[] {});
      var inner = file.getMessageTypes().get(0);
      var outer = file.getMessageTypes().get(1);
      var type = new ProtobufType(outer);
      Message message =
          DynamicMessage.newBuilder(outer)
              .setField(
                  outer.findFieldByNumber(1),
                  DynamicMessage.newBuilder(inner)
                      .setField(inner.getFields().get(0), "value")
                      .build())
              .setField(outer.findFieldByNumber(2), List.of(1L, 2L, 3L))
              .build();
      ProtobufType.Value value = type.snapshot(message);
      zeroMatchesHost(type, type.construct(Map.of()));
      zeroMatchesHost(type, type.construct(Map.of("enumeration", 0L)));
      zeroMatchesHost(type, type.decode(new Bytes(new byte[] {24, 99})));
      assertEquals(message, value.message());
      assertEquals(
          message, ((ProtobufType.Value) type.decode(new Bytes(message.toByteArray()))).message());
      var env = type.register(Cel.newBuilder()).variable("m", CelType.of(type.name())).build();
      assertTrue(
          env.compile("m.inner.name == 'value' && m.numbers == [1,2,3] && m.enumeration == 0")
              .test(Map.of("m", value)));
    }
  }

  @Test
  public void constructionRangeFailuresKeepTheOverflowCategory() throws Exception {
    var type = new ProtobufType(descriptor());
    for (Map<String, Object> fields :
        List.<Map<String, Object>>of(
            Map.of("int32", 2147483648L),
            Map.of("sint32", -2147483649L),
            Map.of("uint32", new UInt(4294967296L)),
            Map.of("fixed32", new UInt(4294967296L))))
      assertEquals(
          fields.toString(),
          CelException.Code.OVERFLOW,
          assertThrows(CelException.class, () -> type.construct(fields)).code());
  }
}
