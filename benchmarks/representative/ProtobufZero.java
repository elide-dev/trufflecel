package dev.trufflecel;

import com.google.protobuf.DynamicMessage;
import dev.trufflecel.bench.proto.PolicySchema.Request;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Native zero checks and their former export implementation on identical immutable inputs. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Thread)
public class ProtobufZero {
  @Param({"empty", "populated", "unknown"})
  public String input;

  private ProtobufType adapter;
  private ProtobufType.Value value;

  @Setup(Level.Trial)
  public void setup() {
    adapter = new ProtobufType(Request.getDescriptor());
    value =
        switch (input) {
          case "empty" -> adapter.snapshot(Request.getDefaultInstance());
          case "populated" ->
              adapter.snapshot((Request) Workloads.Raw.INPUTS.get(0).get("request"));
          case "unknown" ->
              (ProtobufType.Value) adapter.decode(new Bytes(new byte[] {(byte) 0xa0, 6, 0}));
          default -> throw new IllegalArgumentException(input);
        };
    if (nativeZero() != input.equals("empty") || exportedZero() != nativeZero())
      throw new AssertionError("Zero check changed semantics: " + input);
  }

  @Benchmark
  public boolean nativeZero() {
    return adapter.isZero(value);
  }

  @Benchmark
  public boolean exportedZero() {
    return value.message().equals(DynamicMessage.getDefaultInstance(Request.getDescriptor()));
  }
}
