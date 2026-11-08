package dev.trufflecel;

import com.google.gson.Gson;
import com.google.protobuf.*;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedRuntimeOptions;
import com.sun.management.ThreadMXBean;
import dev.elide.truffledata.protobuf.*;
import dev.trufflecel.bench.proto.PolicySchema.Request;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;
import jdk.jfr.Recording;

/** Allocation counts and sampled attribution, deliberately separate from throughput timing. */
public final class IngestionProfile {
  private static volatile Object sink;
  private static final Gson JSON = new Gson();

  private static final class Fixture implements AutoCloseable {
    final String name;
    final Message[] messages = new Message[4];
    final byte[][] wire = new byte[4][];
    final Bytes[] bytes = new Bytes[4];
    final ProtobufType adapter;
    final ProtoCompiler.Source bound;
    final Engines cel, stock;
    final Program program;
    final dev.cel.runtime.CelRuntime.Program stockProgram;
    final List<String> stages;

    Fixture(String name) throws Exception {
      this.name = name;
      if (name.equals("messageset")) {
        var descriptor =
            FileDescriptor.buildFrom(
                    FileDescriptorProto.newBuilder()
                        .setName("ingestion-profile.proto")
                        .setPackage("ingestion")
                        .setSyntax("proto2")
                        .addMessageType(
                            DescriptorProto.newBuilder()
                                .setName("Envelope")
                                .setOptions(
                                    MessageOptions.newBuilder().setMessageSetWireFormat(true))
                                .addExtensionRange(
                                    DescriptorProto.ExtensionRange.newBuilder()
                                        .setStart(4)
                                        .setEnd(536870912)))
                        .build(),
                    new FileDescriptor[0])
                .getMessageTypes()
                .getFirst();
        adapter = new ProtobufType(descriptor);
        for (int i = 0; i < 4; i++)
          messages[i] =
              DynamicMessage.newBuilder(descriptor)
                  .setUnknownFields(
                      UnknownFieldSet.newBuilder()
                          .addField(
                              100,
                              UnknownFieldSet.Field.newBuilder()
                                  .addLengthDelimited(
                                      ByteString.copyFrom(new byte[] {8, (byte) (i + 1)}))
                                  .build())
                          .build())
                  .build();
        // MessageSet requires CEL wire normalization; direct-plan/bound comparisons are ineligible.
        bound = null;
        cel = stock = null;
        program = null;
        stockProgram = null;
        stages = List.of("copy-wire", "serialize", "decode-cel", "snapshot-cel", "decode-stock");
      } else {
        if (!Set.of("ordinary", "unknown64k").contains(name))
          throw new IllegalArgumentException(name);
        adapter = new ProtobufType(Request.getDescriptor());
        bound = ProtoCompiler.bind(adapter.plan(), Request.getDescriptor());
        byte[] payload = new byte[65536];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        for (int i = 0; i < 4; i++) {
          Message original = (Message) Workloads.Raw.INPUTS.get(i).get("request");
          messages[i] =
              name.equals("ordinary")
                  ? original
                  : original.toBuilder()
                      .setUnknownFields(
                          UnknownFieldSet.newBuilder()
                              .addField(
                                  100,
                                  UnknownFieldSet.Field.newBuilder()
                                      .addLengthDelimited(ByteString.copyFrom(payload))
                                      .build())
                              .build())
                      .build();
        }
        cel = new Engines("truffle-interpreter", true, false, adapter);
        stock = new Engines("cel-java-optimized");
        program = (Program) cel.compile(Workloads.source("proto-policy"));
        stockProgram =
            (dev.cel.runtime.CelRuntime.Program) stock.compile(Workloads.source("proto-policy"));
        var target = (OptimizedCallTarget) program.callTarget();
        if (target.getOptionValue(OptimizedRuntimeOptions.Compilation))
          throw new AssertionError("Allocation diagnostic requires guest compilation disabled");
        stages =
            List.of(
                "copy-wire",
                "serialize",
                "decode-cel",
                "decode-native",
                "decode-stock",
                "snapshot-cel",
                "snapshot-bound",
                "decode-evaluate-cel",
                "decode-evaluate-stock");
      }
      for (int i = 0; i < 4; i++) {
        wire[i] = messages[i].toByteArray();
        bytes[i] = new Bytes(wire[i]);
      }
      for (String stage : stages) for (int i = 0; i < 4; i++) verify(stage, i, run(stage, i));
    }

    Object run(String stage, int index) throws Exception {
      int i = index & 3;
      return switch (stage) {
        case "copy-wire" -> new Bytes(wire[i]);
        case "serialize" -> messages[i].toByteArray();
        case "decode-cel" -> adapter.decode(bytes[i]);
        case "decode-native" -> adapter.plan().decode(wire[i]);
        case "decode-stock" -> messages[i].getParserForType().parseFrom(wire[i]);
        case "snapshot-cel" -> adapter.snapshot(messages[i]);
        case "snapshot-bound" -> bound.snapshot(messages[i]);
        case "decode-evaluate-cel", "decode-evaluate-stock" -> {
          // Preserve all 69 representative inputs and include the fresh activation map in both
          // paths.
          var input = new LinkedHashMap<>(Workloads.Raw.INPUTS.get(i));
          if (stage.equals("decode-evaluate-cel")) {
            input.put("request", adapter.decode(new Bytes(wire[i])));
            yield program.evaluate(input);
          }
          input.put("request", messages[i].getParserForType().parseFrom(wire[i]));
          yield stockProgram.eval(input);
        }
        default -> throw new IllegalArgumentException(stage);
      };
    }

    void verify(String stage, int index, Object value) throws Exception {
      int i = index & 3;
      if (stage.startsWith("decode-evaluate")) {
        if (!Workloads.expected("proto-policy", i).equals(value)) throw new AssertionError(stage);
        return;
      }
      if (value instanceof Bytes b) value = b.toByteArray();
      if (value instanceof byte[] b) {
        if (!Arrays.equals(wire[i], b)) throw new AssertionError(stage);
        return;
      }
      if (value instanceof ProtobufType.Value v) value = v.message();
      else if (value instanceof ProtoRow row)
        value = messages[i].getParserForType().parseFrom(adapter.plan().encode(row));
      if (!messages[i].equals(value)) throw new AssertionError(name + "/" + stage + "/" + i);
    }

    @Override
    public void close() {
      if (cel != null) {
        if (((OptimizedCallTarget) program.callTarget()).isValid())
          throw new AssertionError("Unexpected guest compilation");
        stock.close();
        cel.close();
      }
    }
  }

  static void smoke() throws Exception {
    for (String name : List.of("ordinary", "unknown64k", "messageset"))
      try (var fixture = new Fixture(name)) {}
  }

  private static void metadata(Fixture fixture) {
    System.out.println(
        JSON.toJson(
            Map.of(
                "fixture",
                fixture.name,
                "stages",
                fixture.stages,
                "wire_lengths",
                Arrays.stream(fixture.wire).mapToInt(b -> b.length).toArray(),
                "java",
                System.getProperty("java.runtime.version"),
                "vm",
                System.getProperty("java.vm.name"),
                "vm_flags",
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                "gc",
                ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .map(b -> b.getName())
                    .toList(),
                "guest_compilation",
                false)));
  }

  private static ThreadMXBean allocationCounter() {
    var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemorySupported())
      throw new IllegalStateException("allocation counter required");
    bean.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    sink = new byte[4096];
    if (bean.getThreadAllocatedBytes(thread) - before < 4096)
      throw new AssertionError("Allocation counter failed its independent probe");
    return bean;
  }

  // A separate entry calls only this path; JFR stays outside its native reachable graph.
  static void allocate(String name) throws Exception {
    try (var fixture = new Fixture(name)) {
      metadata(fixture);
      var bean = allocationCounter();
      long thread = Thread.currentThread().threadId();
      int iterations = fixture.name.equals("unknown64k") ? 500 : 10000;
      for (String stage : fixture.stages) {
        for (int i = 0; i < iterations * 5; i++) sink = fixture.run(stage, i);
        for (int sample = 0; sample < 7; sample++) {
          long start = bean.getThreadAllocatedBytes(thread);
          for (int i = 0; i < iterations; i++) sink = fixture.run(stage, i);
          long allocated = bean.getThreadAllocatedBytes(thread) - start;
          fixture.verify(stage, iterations - 1, sink);
          System.out.println(
              JSON.toJson(
                  Map.of(
                      "stage",
                      stage,
                      "sample",
                      sample,
                      "operations",
                      iterations,
                      "allocated_bytes",
                      allocated,
                      "bytes_per_operation",
                      (double) allocated / iterations)));
        }
      }
    }
  }

  private static void profile(String name, String stage, Path output) throws Exception {
    try (var fixture = new Fixture(name)) {
      metadata(fixture);
      if (!fixture.stages.contains(stage)) throw new IllegalArgumentException(stage);
      var bean = allocationCounter();
      long thread = Thread.currentThread().threadId();
      int iterations = fixture.name.equals("unknown64k") ? 500 : 10000;
      for (int i = 0; i < iterations * 5; i++) sink = fixture.run(stage, i);
      try (var recording = new Recording()) {
        recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "1000/s");
        recording.setMaxSize(32 * 1024 * 1024);
        recording.start();
        long start = bean.getThreadAllocatedBytes(thread);
        long end = System.nanoTime() + 5_000_000_000L;
        int count = 0;
        do {
          for (int i = 0; i < 256; i++) sink = fixture.run(stage, count++);
        } while (System.nanoTime() < end);
        long allocated = bean.getThreadAllocatedBytes(thread) - start;
        recording.stop();
        recording.dump(output);
        System.out.println(
            JSON.toJson(Map.of("stage", stage, "operations", count, "allocated_bytes", allocated)));
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("smoke")) smoke();
    else if (args.length == 2 && args[0].equals("allocate")) allocate(args[1]);
    else if (args.length == 4 && args[0].equals("profile"))
      profile(args[1], args[2], Path.of(args[3]));
    else throw new IllegalArgumentException("allocate FIXTURE | profile FIXTURE STAGE OUTPUT.jfr");
  }
}
