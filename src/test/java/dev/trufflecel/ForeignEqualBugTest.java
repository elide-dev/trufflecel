package dev.trufflecel;

import static org.junit.Assert.*;
import com.google.protobuf.*;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import java.util.*;
import org.junit.Test;

/**
 * Regression test for cross-adapter protobuf message equality. Two {@link ProtobufType} adapters
 * over the same {@link Descriptor} but with different extension registration sets produce values
 * whose bytes are byte-identical for the same logical message yet, before the fix, re-parsed
 * through each side's own {@link ExtensionRegistry} during {@code equal()} / {@code Value.equals}:
 * a registered extension parsed as a real field on one side and as an {@link UnknownFieldSet} entry
 * on the other, so {@link DynamicMessage#equals} reported them unequal.
 *
 * <p>The fix re-parses both sides under a single registry (the left side's producing adapter's),
 * mirroring the producing-adapter delegation already used by {@code has()} / {@code get()}.
 */
public class ForeignEqualBugTest {
  @Test
  public void foreignSiblingValuesWithDifferentExtensionSetsCompareEqual() throws Exception {
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    // Adapter A registers ext_x; adapter B registers no extensions.
    ProtobufType adapterA = new ProtobufType(base, List.of(extX));
    ProtobufType adapterB = new ProtobufType(base, List.of());
    // The same logical message: ext_x=7. Adapter A snapshots it as a registered extension;
    // adapter B snapshots it as a preserved unknown field.
    Message source = DynamicMessage.newBuilder(base).setField(extX, 7).build();
    ProtobufType.Value valueA = adapterA.snapshot(source);
    ProtobufType.Value valueB = adapterB.snapshot(source);
    // Byte content is identical: the snapshot stores wire field numbers, not field descriptors.
    assertArrayEquals(valueA.toBytes().toByteArray(), valueB.toBytes().toByteArray());

    // Both directions of equal() must succeed; equality is symmetric.
    assertTrue(adapterA.equal(valueA, valueB));
    assertTrue(adapterB.equal(valueB, valueA));
    // Value.equals (the `in` path via Values.equal) must agree with ==.
    assertTrue(valueA.equals(valueB));
    assertTrue(valueB.equals(valueA));
    // The hash contract holds: byte-identical wires hash identically regardless of registry.
    assertEquals(valueA.hashCode(), valueB.hashCode());
  }

  @Test
  public void foreignSiblingValuesCompareUnequalWhenContentDiffers() throws Exception {
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    ProtobufType adapterA = new ProtobufType(base, List.of(extX));
    ProtobufType adapterB = new ProtobufType(base, List.of());
    ProtobufType.Value valueA =
        adapterA.snapshot(DynamicMessage.newBuilder(base).setField(extX, 7).build());
    ProtobufType.Value valueB =
        adapterB.snapshot(DynamicMessage.newBuilder(base).setField(extX, 8).build());

    assertFalse(adapterA.equal(valueA, valueB));
    assertFalse(adapterB.equal(valueB, valueA));
    assertFalse(valueA.equals(valueB));
    assertFalse(valueB.equals(valueA));
  }

  @Test
  public void celEqualityAgreesWithForeignSiblingValuesInRegisteredEnv() throws Exception {
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    ProtobufType adapterA = new ProtobufType(base, List.of(extX));
    ProtobufType adapterB = new ProtobufType(base, List.of());
    Message source = DynamicMessage.newBuilder(base).setField(extX, 7).build();
    ProtobufType.Value valueA = adapterA.snapshot(source);
    ProtobufType.Value valueB = adapterB.snapshot(source);

    // Register adapter A; it accepts valueB by descriptor identity so its equal() runs the
    // cross-adapter path. CEL == must be true and != must be false.
    Environment env =
        adapterA
            .register(Cel.newBuilder().container("ext"))
            .variable("m", CelType.of(adapterA.name()))
            .variable("m2", CelType.of(adapterA.name()))
            .build();
    assertTrue(env.compile("m == m2").test(Map.of("m", valueA, "m2", valueB)));
    assertFalse(env.compile("m != m2").test(Map.of("m", valueA, "m2", valueB)));
    // Cross-check: when only adapter B is registered, it must also accept both values by
    // descriptor and answer symmetrically.
    Environment envB =
        adapterB
            .register(Cel.newBuilder().container("ext"))
            .variable("m", CelType.of(adapterB.name()))
            .variable("m2", CelType.of(adapterB.name()))
            .build();
    assertTrue(envB.compile("m == m2").test(Map.of("m", valueA, "m2", valueB)));
    assertFalse(envB.compile("m != m2").test(Map.of("m", valueA, "m2", valueB)));
  }

  @Test
  public void celInOperatorAgreesWithForeignSiblingValues() throws Exception {
    // The `in` operator dispatches through Values.equal -> Value.equals, which must use the
    // same single-registry comparison as ProtobufType.equal.
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    ProtobufType adapterA = new ProtobufType(base, List.of(extX));
    ProtobufType adapterB = new ProtobufType(base, List.of());
    Message source = DynamicMessage.newBuilder(base).setField(extX, 7).build();
    ProtobufType.Value valueA = adapterA.snapshot(source);
    ProtobufType.Value valueB = adapterB.snapshot(source);

    Environment env =
        adapterA
            .register(Cel.newBuilder().container("ext"))
            .variable("m", CelType.of(adapterA.name()))
            .variable("m2", CelType.of(adapterA.name()))
            .variable("c", CelType.of(adapterA.name()))
            .build();
    // m2 (foreign) must be found in [m] because Value.equals re-parses under one registry.
    assertTrue(env.compile("m2 in [m]").test(Map.of("m", valueA, "m2", valueB)));
    assertTrue(env.compile("m in [m2]").test(Map.of("m", valueA, "m2", valueB)));
    // A list with a wire-distinct message must not contain either equal-pair entry.
    ProtobufType.Value valueC =
        adapterA.snapshot(DynamicMessage.newBuilder(base).setField(extX, 9).build());
    assertFalse(env.compile("m in [c]").test(Map.of("m", valueA, "c", valueC)));
    assertFalse(env.compile("m2 in [c]").test(Map.of("m2", valueB, "c", valueC)));
  }

  @Test
  public void foreignSiblingRawExportsDifferButEqualUsesSingleRegistry() throws Exception {
    // Pins the bug's mechanism: byte-identical wires export to non-equal DynamicMessages under
    // their own adapters' registries (the bug's cause), so the fix must re-parse one side under
    // the other's registry rather than comparing raw exports. A future "optimization" that
    // shortcuts to message().equals(other.message()) would re-introduce the bug.
    Descriptor base = baseDescriptor();
    FieldDescriptor extX = base.getFile().findExtensionByName("ext_x");
    ProtobufType adapterA = new ProtobufType(base, List.of(extX));
    ProtobufType adapterB = new ProtobufType(base, List.of());
    Message source = DynamicMessage.newBuilder(base).setField(extX, 7).build();
    ProtobufType.Value valueA = adapterA.snapshot(source);
    ProtobufType.Value valueB = adapterB.snapshot(source);

    assertArrayEquals(valueA.toBytes().toByteArray(), valueB.toBytes().toByteArray());
    assertNotEquals(valueA.message(), valueB.message());
    assertTrue(adapterA.equal(valueA, valueB));
  }

  private static Descriptor baseDescriptor() throws Exception {
    var file =
        FileDescriptorProto.newBuilder()
            .setName("foreign_equal.proto")
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
