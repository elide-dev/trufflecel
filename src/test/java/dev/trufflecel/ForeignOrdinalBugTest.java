package dev.trufflecel;

import static org.junit.Assert.*;
import com.google.protobuf.*;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import java.util.*;
import org.junit.Test;

/**
 * Regression test for cross-adapter extension presence reads. Two {@link ProtobufType} chains over
 * the same {@link Descriptor} but with differently-ordered extension registration produce different
 * per-adapter ordinal layouts. A foreign {@link ProtobufType.Value} consumed by the wrong adapter
 * must delegate presence checks back to the producing adapter, mirroring {@code get()}'s guard.
 */
public class ForeignOrdinalBugTest {
  @Test
  public void foreignAdapterHasDelegatesToProducingOrdinals() throws Exception {
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    FieldDescriptor extY = base.getFile().findExtensionByName("ext_y");
    FieldDescriptor extZ = base.getFile().findExtensionByName("ext_z");
    // Adapter A registers extensions in order [ext_x, ext_y, ext_z].
    ProtobufType adapterA = new ProtobufType(base, List.of(extX, extY, extZ));
    // Adapter B registers the same extensions with ext_x/ext_y swapped.
    ProtobufType adapterB = new ProtobufType(base, List.of(extY, extX, extZ));
    // A value with only ext_x set, produced by adapter A.
    ProtobufType.Value valueA =
        adapterA.snapshot(DynamicMessage.newBuilder(base).setField(extX, 7).build());

    // Adapter B accepts the foreign value by descriptor identity.
    assertTrue(adapterB.accepts(valueA));
    // Control: the producing adapter answers correctly.
    assertTrue(adapterA.has(valueA, "ext.ext_x"));
    assertFalse(adapterA.has(valueA, "ext.ext_y"));
    // The foreign adapter must delegate has() to the producing adapter's ordinals.
    assertTrue(adapterB.has(valueA, "ext.ext_x"));
    assertFalse(adapterB.has(valueA, "ext.ext_y"));
    // get() already delegates; has() and get() must agree on the same value.
    assertEquals(7L, adapterB.get(valueA, "ext.ext_x"));
    assertEquals(0L, adapterB.get(valueA, "ext.ext_y"));
  }

  @Test
  public void foreignAdapterHasAgreesWithGetForAllExtensions() throws Exception {
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    FieldDescriptor extY = base.getFile().findExtensionByName("ext_y");
    FieldDescriptor extZ = base.getFile().findExtensionByName("ext_z");
    ProtobufType adapterA = new ProtobufType(base, List.of(extX, extY, extZ));
    ProtobufType adapterB = new ProtobufType(base, List.of(extY, extX, extZ));
    // Build a value with ext_x and ext_z set, ext_y unset.
    ProtobufType.Value valueA =
        adapterA.snapshot(
            DynamicMessage.newBuilder(base).setField(extX, 7).setField(extZ, 9).build());
    for (FieldDescriptor ext : List.of(extX, extY, extZ)) {
      String name = ext.getFullName();
      boolean aHas = adapterA.has(valueA, name);
      boolean bHas = adapterB.has(valueA, name);
      // The two adapters must agree on presence for the foreign value.
      assertEquals("presence mismatch for " + name, aHas, bHas);
      // has() must agree with get(): a set extension returns a non-default value.
      Object viaGet = adapterB.get(valueA, name);
      if (bHas) {
        assertFalse("ext_y should not be reported present", name.equals("ext.ext_y"));
      }
    }
    assertTrue(adapterB.has(valueA, "ext.ext_x"));
    assertFalse(adapterB.has(valueA, "ext.ext_y"));
    assertTrue(adapterB.has(valueA, "ext.ext_z"));
  }

  @Test
  public void celProtoHasExtDelegatesThroughForeignAdapter() throws Exception {
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    FieldDescriptor extY = base.getFile().findExtensionByName("ext_y");
    FieldDescriptor extZ = base.getFile().findExtensionByName("ext_z");
    ProtobufType adapterA = new ProtobufType(base, List.of(extX, extY, extZ));
    ProtobufType adapterB = new ProtobufType(base, List.of(extY, extX, extZ));
    ProtobufType.Value valueA =
        adapterA.snapshot(DynamicMessage.newBuilder(base).setField(extX, 7).build());
    // Register adapter B in the CEL environment; it accepts valueA by descriptor identity.
    Environment env =
        adapterB
            .register(Cel.newBuilder().container("ext"))
            .variable("m", CelType.of(adapterB.name()))
            .build();
    // proto.hasExt must reflect the producing adapter's ordinals, not B's.
    assertTrue(env.compile("proto.hasExt(m, ext.ext_x)").test(Map.of("m", valueA)));
    assertFalse(env.compile("proto.hasExt(m, ext.ext_y)").test(Map.of("m", valueA)));
    // proto.getExt already delegates correctly; has/get must be consistent.
    assertEquals(7L, env.compile("proto.getExt(m, ext.ext_x)").evaluate(Map.of("m", valueA)));
    assertEquals(0L, env.compile("proto.getExt(m, ext.ext_y)").evaluate(Map.of("m", valueA)));
  }

  @Test
  public void ownAdapterHasIsUnchangedByDelegationGuard() throws Exception {
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    FieldDescriptor extY = base.getFile().findExtensionByName("ext_y");
    FieldDescriptor extZ = base.getFile().findExtensionByName("ext_z");
    ProtobufType adapter = new ProtobufType(base, List.of(extX, extY, extZ));
    ProtobufType.Value set =
        adapter.snapshot(
            DynamicMessage.newBuilder(base).setField(extX, 7).setField(extZ, 9).build());
    ProtobufType.Value empty = adapter.snapshot(DynamicMessage.newBuilder(base).build());
    assertTrue(adapter.has(set, "ext.ext_x"));
    assertFalse(adapter.has(set, "ext.ext_y"));
    assertTrue(adapter.has(set, "ext.ext_z"));
    assertFalse(adapter.has(empty, "ext.ext_x"));
    assertFalse(adapter.has(empty, "ext.ext_y"));
    assertFalse(adapter.has(empty, "ext.ext_z"));
    assertEquals(7L, adapter.get(set, "ext.ext_x"));
    assertEquals(0L, adapter.get(set, "ext.ext_y"));
    assertEquals(9L, adapter.get(set, "ext.ext_z"));
  }

  private static Descriptor baseDescriptor() throws Exception {
    var file =
        FileDescriptorProto.newBuilder()
            .setName("foreign_ordinal.proto")
            .setPackage("ext")
            .setSyntax("proto2")
            .addMessageType(
                DescriptorProto.newBuilder()
                    .setName("Base")
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("a")
                            .setNumber(1)
                            .setType(FieldDescriptorProto.Type.TYPE_INT32))
                    .addField(
                        FieldDescriptorProto.newBuilder()
                            .setName("b")
                            .setNumber(2)
                            .setType(FieldDescriptorProto.Type.TYPE_INT32))
                    .addExtensionRange(
                        DescriptorProto.ExtensionRange.newBuilder().setStart(100).setEnd(1000)));
    String[] names = {"ext_x", "ext_y", "ext_z"};
    for (int i = 0; i < names.length; i++)
      file.addExtension(
          FieldDescriptorProto.newBuilder()
              .setName(names[i])
              .setNumber(100 + i)
              .setExtendee(".ext.Base")
              .setType(FieldDescriptorProto.Type.TYPE_INT32)
              .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
    return FileDescriptor.buildFrom(file.build(), new FileDescriptor[] {})
        .findMessageTypeByName("Base");
  }
}
