package dev.trufflecel;

import dev.trufflecel.bench.proto.PolicySchema.Request;
import dev.elide.truffledata.protobuf.ProtoCompiler;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Independent ingestion diagnostics; stage timings must not be summed or subtracted. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Thread)
public class IngestionStages {
  private final Request[] messages = new Request[4];
  private final byte[][] wire = new byte[4][];
  private final Bytes[] bytes = new Bytes[4];
  private ProtobufType adapter;
  private ProtoCompiler.Source bound;
  private int cursor;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    adapter = new ProtobufType(Request.getDescriptor());
    bound = ProtoCompiler.bind(adapter.plan(), Request.getDescriptor());
    for (int i = 0; i < 4; i++) {
      messages[i] = (Request) Workloads.Raw.INPUTS.get(i).get("request");
      wire[i] = messages[i].toByteArray();
      bytes[i] = new Bytes(wire[i]);
      if (!messages[i].equals(Request.parseFrom(adapter.plan().encode(bound.snapshot(messages[i]))))
          || !messages[i].equals(Request.parseFrom(wire[i]))
          || !messages[i].equals(((ProtobufType.Value) adapter.decode(bytes[i])).message())
          || !messages[i].equals(adapter.snapshot(messages[i]).message())
          || !messages[i].equals(
              Request.parseFrom(adapter.plan().encode(adapter.plan().decode(wire[i])))))
        throw new AssertionError("Ingestion changed protobuf content: " + i);
    }
  }

  @Benchmark
  public Object serializeMessage() {
    return messages[cursor++ & 3].toByteArray();
  }

  @Benchmark
  public Object copyWire() {
    return new Bytes(wire[cursor++ & 3]);
  }

  @Benchmark
  public Object decodeStock() throws Exception {
    return Request.parseFrom(wire[cursor++ & 3]);
  }

  @Benchmark
  public Object decodeNative() {
    return adapter.plan().decode(wire[cursor++ & 3]);
  }

  @Benchmark
  public Object decodeCel() {
    return adapter.decode(bytes[cursor++ & 3]);
  }

  @Benchmark
  public Object snapshotBound() {
    return bound.snapshot(messages[cursor++ & 3]);
  }

  @Benchmark
  public Object snapshotCel() {
    return adapter.snapshot(messages[cursor++ & 3]);
  }
}
