package dev.trufflecel;

import static org.junit.Assert.*;

import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.util.*;
import org.junit.Test;

/**
 * Regression coverage for out-of-range protobuf {@code google.protobuf.Timestamp} {@code seconds}.
 * Every path that crosses {@link WellKnownTypes#time(long, long, boolean)} must translate an
 * out-of-range {@code seconds} value into a {@link CelException} with code {@code OVERFLOW}, never
 * a raw {@code ArithmeticException} or {@code java.time.DateTimeException} that escapes {@link
 * Program#evaluate(Map)}.
 */
public final class TimestampOverflowTest {
  private static final long MIN_SECONDS = -62135596800L;
  private static final long MAX_SECONDS = 253402300799L;

  private static final long[] OUT_OF_RANGE_SECONDS = {
    MAX_SECONDS + 1, MIN_SECONDS - 1, 1_000_000_000_000L, Long.MAX_VALUE
  };

  private static Object outcome(Runnable evaluation) {
    try {
      evaluation.run();
      return null;
    } catch (CelException e) {
      return e.code();
    }
  }

  private static Timestamp ts(long seconds, int nanos) {
    return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static Timestamp ts(long seconds) {
    return Timestamp.newBuilder().setSeconds(seconds).build();
  }

  @Test
  public void ingestRejectsOutOfRangeSeconds() {
    var type = new ProtobufType(Timestamp.getDescriptor());
    for (long seconds : OUT_OF_RANGE_SECONDS) {
      assertEquals(
          "seconds=" + seconds,
          CelException.Code.OVERFLOW,
          assertThrows(CelException.class, () -> type.input(ts(seconds))).code());
    }
  }

  @Test
  public void ingestAcceptsBoundarySeconds() {
    var type = new ProtobufType(Timestamp.getDescriptor());
    assertEquals(java.time.Instant.ofEpochSecond(MIN_SECONDS, 0), type.input(ts(MIN_SECONDS)));
    assertEquals(
        java.time.Instant.ofEpochSecond(MAX_SECONDS, 999_999_999),
        type.input(ts(MAX_SECONDS, 999_999_999)));
  }

  @Test
  public void mappedReadRejectsOutOfRangeSeconds() throws Exception {
    Descriptor descriptor = mappedDescriptor();
    var type = new ProtobufType(descriptor);
    var env = type.register(Cel.newBuilder()).variable("r", CelType.of(type.name())).build();
    var reference =
        type.register(Cel.newBuilder())
            .objectType(generic(type))
            .variable("r", CelType.of(type.name()))
            .build();
    var descriptorFields = descriptor.getFields();
    int timestampIndex = 0;
    for (long seconds : OUT_OF_RANGE_SECONDS) {
      var nested = type.plan().nested(timestampIndex);
      var builder = nested.newBuilder();
      builder.set(0, seconds);
      Object value =
          type.input(type.plan().newBuilder().set(timestampIndex, builder.build()).build());
      String source = "r." + descriptorFields.get(timestampIndex).getName();
      assertEquals(
          "seconds=" + seconds,
          CelException.Code.OVERFLOW,
          outcome(() -> env.compile(source).evaluate(Map.of("r", value))));
      assertEquals(
          "seconds=" + seconds,
          outcome(() -> reference.compile(source).evaluate(Map.of("r", value))),
          outcome(() -> env.compile(source).evaluate(Map.of("r", value))));
    }
  }

  @Test
  public void mappedReadAcceptsValidTimestamp() throws Exception {
    Descriptor descriptor = mappedDescriptor();
    var type = new ProtobufType(descriptor);
    var env = type.register(Cel.newBuilder()).variable("r", CelType.of(type.name())).build();
    var nested = type.plan().nested(0);
    var builder = nested.newBuilder();
    builder.set(0, 100L);
    builder.set(1, 5);
    Object value = type.input(type.plan().newBuilder().set(0, builder.build()).build());
    assertEquals(
        java.time.Instant.ofEpochSecond(100, 5),
        env.compile("r.created").evaluate(Map.of("r", value)));
  }

  @Test
  public void durationReadRejectsOutOfRangeSeconds() throws Exception {
    Descriptor descriptor = durationDescriptor();
    var type = new ProtobufType(descriptor);
    var env = type.register(Cel.newBuilder()).variable("r", CelType.of(type.name())).build();
    var descriptorFields = descriptor.getFields();
    int durationIndex = 0;
    for (long seconds : new long[] {315576000001L, -315576000001L}) {
      var nested = type.plan().nested(durationIndex);
      var builder = nested.newBuilder();
      builder.set(0, seconds);
      Object value =
          type.input(type.plan().newBuilder().set(durationIndex, builder.build()).build());
      String source = "r." + descriptorFields.get(durationIndex).getName();
      assertEquals(
          "seconds=" + seconds,
          CelException.Code.OVERFLOW,
          outcome(() -> env.compile(source).evaluate(Map.of("r", value))));
    }
  }

  @Test
  public void rawExceptionDoesNotEscapeEvaluate() throws Exception {
    Descriptor descriptor = mappedDescriptor();
    var type = new ProtobufType(descriptor);
    var env = type.register(Cel.newBuilder()).variable("r", CelType.of(type.name())).build();
    var nested = type.plan().nested(0);
    var builder = nested.newBuilder();
    builder.set(0, 1_000_000_000_000L);
    Object value = type.input(type.plan().newBuilder().set(0, builder.build()).build());
    CelException e =
        assertThrows(
            CelException.class, () -> env.compile("r.created").evaluate(Map.of("r", value)));
    assertEquals(CelException.Code.OVERFLOW, e.code());
  }

  private static Descriptor mappedDescriptor() throws Exception {
    var root = DescriptorProto.newBuilder().setName("Record");
    root.addField(
        FieldDescriptorProto.newBuilder()
            .setName("created")
            .setNumber(1)
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setTypeName(".google.protobuf.Timestamp")
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
    FileDescriptor[] dependencies = {Timestamp.getDescriptor().getFile()};
    var file =
        FileDescriptorProto.newBuilder()
            .setName("timestamp_overflow.proto")
            .setSyntax("proto3")
            .addMessageType(root);
    for (FileDescriptor d : dependencies) file.addDependency(d.getName());
    return FileDescriptor.buildFrom(file.build(), dependencies).findMessageTypeByName("Record");
  }

  private static Descriptor durationDescriptor() throws Exception {
    var root = DescriptorProto.newBuilder().setName("Record");
    root.addField(
        FieldDescriptorProto.newBuilder()
            .setName("elapsed")
            .setNumber(1)
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setTypeName(".google.protobuf.Duration")
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
    FileDescriptor[] dependencies = {Duration.getDescriptor().getFile()};
    var file =
        FileDescriptorProto.newBuilder()
            .setName("duration_overflow.proto")
            .setSyntax("proto3")
            .addMessageType(root);
    for (FileDescriptor d : dependencies) file.addDependency(d.getName());
    return FileDescriptor.buildFrom(file.build(), dependencies).findMessageTypeByName("Record");
  }

  private static ObjectType generic(ProtobufType type) {
    return new ObjectType() {
      public String name() {
        return type.name();
      }

      public Map<String, CelType> fields() {
        return type.fields();
      }

      public Object construct(Map<String, Object> values) {
        return type.construct(values);
      }

      public Object get(Object value, String field) {
        return type.get(value, field);
      }

      public boolean has(Object value, String field) {
        return type.has(value, field);
      }

      public boolean accepts(Object value) {
        return type.accepts(value);
      }
    };
  }
}
