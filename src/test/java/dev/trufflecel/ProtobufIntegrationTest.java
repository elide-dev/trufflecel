package dev.trufflecel;

import static org.junit.Assert.*;
import com.google.protobuf.*;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import dev.elide.truffledata.*;
import dev.elide.truffledata.protobuf.ProtobufAdapter;
import java.util.*;
import org.junit.Test;

public class ProtobufIntegrationTest {
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
  public void publishedRowsPreserveCompactBytesButNeedCelPresenceAndEnumBridge() throws Exception {
    Descriptor descriptor = descriptor();
    var proto =
        DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("uint64"), -1L)
            .setField(
                descriptor.findFieldByName("enum"),
                descriptor.findEnumTypeByName("State").findValueByNumberCreatingIfUnknown(123))
            .setField(descriptor.findFieldByName("bytes"), ByteString.copyFrom(new byte[] {-1}))
            .build();
    var adapter = new ProtobufAdapter(descriptor, Limits.DEFAULT, 100);
    var row = adapter.snapshot(proto);
    var data = DataLibrary.factory().getUncached();
    assertTrue(data.isPresent(row, descriptor.findFieldByName("int32").getIndex()));
    assertFalse(proto.hasField(descriptor.findFieldByName("int32")));
    var binary = (DataArray) data.read(row, descriptor.findFieldByName("bytes").getIndex());
    assertTrue(binary.isBytes());
    assertEquals(1, binary.size());
    assertEquals(255L, binary.get(0));
    assertTrue(data.read(row, descriptor.findFieldByName("enum").getIndex()) instanceof String);
    ProtobufType type = new ProtobufType(descriptor);
    var value = type.snapshot(proto);
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
    assertThrows(CelException.class, () -> new ProtobufType(Any.getDescriptor()).input(any));
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
}
