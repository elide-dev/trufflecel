package dev.trufflecel.bench;

import dev.trufflecel.*;
import dev.elide.truffledata.*;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.DynamicMessage;
import java.util.*;
import java.util.function.LongSupplier;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.shadowed.org.json.JSONObject;

/**
 * Deterministic native-AOT workloads; setup/warmup/assertions stay outside measured regions.
 * Historical literal fixtures retain their original expressions and names. The -input variants
 * exercise runtime inputs independently of constant folding and establish separate baselines.
 */
public final class SimulationBenchmark {
  private static volatile long sink;

  private record Case(String name, LongSupplier operation, long expected) {}

  private static List<Case> cases() throws Exception {
    var activation = Map.<String, Object>of("x", 42L);
    var scalar = Cel.compile("x * 2 + 1");
    var logic = Cel.compile("x == 42 || (1 / 0 == 0)");
    var list = Cel.compile("xs.filter(x,x % 2 == 0).map(x,x * x).size()");
    var listInput = Map.<String, Object>of("xs", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L));
    var folded = Cel.compile("[1,2,3,4,5,6,7,8].filter(x,x % 2 == 0).map(x,x * x).size()");
    var regex = Cel.compile("s.matches('^[a-z]+@[a-z]+[.]com$')");
    var regexInput = Map.<String, Object>of("s", "person@example.com");
    var schema = Schema.of(Field.requiredLong("id"));
    var row = Batch.copyOf(schema, List.<Object[]>of(new Object[] {42L}), Limits.DEFAULT).row(0);
    var rowType = new DataType("Row", schema);
    var rowProgram =
        Cel.newBuilder()
            .objectType(rowType)
            .variable("r", CelType.of("Row"))
            .build()
            .compile("r.id * 2");
    var rowInput = Map.<String, Object>of("r", row);
    var descriptor =
        FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                    .setName("bench.proto")
                    .setSyntax("proto3")
                    .addMessageType(
                        DescriptorProto.newBuilder()
                            .setName("Record")
                            .addField(
                                FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_UINT64)))
                    .build(),
                new FileDescriptor[] {})
            .findMessageTypeByName("Record");
    var type = new ProtobufType(descriptor);
    var message =
        DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("id"), 42L)
            .build();
    var protoProgram =
        type.register(Cel.newBuilder())
            .variable("p", CelType.of("Record"))
            .build()
            .compile("p.id == 42u");
    var protoInput =
        Map.<String, Object>of("p", type.input(type.plan().decode(message.toByteArray())));
    var jsonProgram = Cel.compile("j.id == 42 && j.tags.exists(t,t == 'admin')");
    var jsonInput =
        Map.<String, Object>of(
            "j", JsonValues.from(new JSONObject("{\"id\":42,\"tags\":[\"user\",\"admin\"]}")));
    var optional = Cel.compile("m.?payload.value.orValue(0)");
    var optionalInput = Map.<String, Object>of("m", Map.of("payload", Map.of("value", 42L)));
    var transform = Cel.compile("xs.transformList(i,v,i+v).size()");
    var transformInput = Map.<String, Object>of("xs", List.of(1L, 2L, 3L, 4L));
    var format = Cel.compile("'%.2f %s'.format([d, s]).size()");
    var formatInput = Map.<String, Object>of("d", 3.5, "s", "CEL");
    var sort = Cel.compile("xs.sortBy(v,-v)[0]");
    var sortInput = Map.<String, Object>of("xs", List.of(4L, 2L, 3L, 1L));
    var extrema = Cel.compile("math.greatest(xs)");
    var extremaInput = Map.<String, Object>of("xs", List.of(3L, 1L, 4L, 2L));
    var network = Cel.compile("cidr(net).containsIP(ip)");
    var networkInput = Map.<String, Object>of("net", "192.168.0.0/24", "ip", "192.168.0.42");
    var transformLiteral = Cel.compile("[1,2,3,4].transformList(i,v,i+v).size()");
    var formatLiteral = Cel.compile("'%.2f %s'.format([3.5,'CEL']).size()");
    var sortLiteral = Cel.compile("[4,2,3,1].sortBy(v,-v)[0]");
    var extremaLiteral = Cel.compile("math.greatest([3,1,4,2])");
    var networkLiteral = Cel.compile("cidr('192.168.0.0/24').containsIP('192.168.0.42')");
    var listLiteral = Cel.compile("[1,2,3,4,5,6,7,8].filter(x,x % 2 == 0).map(x,x * x).size()");
    return List.of(
        new Case("comprehension", () -> (Long) listLiteral.evaluate(), 4),
        new Case("network-cidr", () -> networkLiteral.test() ? 1 : 0, 1),
        new Case("math-extrema", () -> (Long) extremaLiteral.evaluate(), 4),
        new Case("list-sort", () -> (Long) sortLiteral.evaluate(), 4),
        new Case("string-format", () -> (Long) formatLiteral.evaluate(), 8),
        new Case("two-variable-transform", () -> (Long) transformLiteral.evaluate(), 4),
        new Case("optional-chain", () -> (Long) optional.evaluate(optionalInput), 42),
        new Case(
            "two-variable-transform-input", () -> (Long) transform.evaluate(transformInput), 4),
        new Case("string-format-input", () -> (Long) format.evaluate(formatInput), 8),
        new Case("list-sort-input", () -> (Long) sort.evaluate(sortInput), 4),
        new Case("math-extrema-input", () -> (Long) extrema.evaluate(extremaInput), 4),
        new Case("network-cidr-input", () -> network.test(networkInput) ? 1 : 0, 1),
        new Case(
            "compile-evaluate", () -> (Long) Cel.compile("x * 2 + 1").evaluate(activation), 85),
        new Case("evaluate-scalar", () -> (Long) scalar.evaluate(activation), 85),
        new Case("short-circuit", () -> logic.test(activation) ? 1 : 0, 1),
        new Case("comprehension-input", () -> (Long) list.evaluate(listInput), 4),
        new Case("folded-constant", () -> (Long) folded.evaluate(), 4),
        new Case("regex", () -> regex.test(regexInput) ? 1 : 0, 1),
        new Case("native-row", () -> (Long) rowProgram.evaluate(rowInput), 84),
        new Case("protobuf", () -> protoProgram.test(protoInput) ? 1 : 0, 1),
        new Case("json", () -> jsonProgram.test(jsonInput) ? 1 : 0, 1));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected --smoke or case name");
    var cases = cases();
    if (args[0].equals("--smoke")) {
      for (Case c : cases)
        if (c.operation().getAsLong() != c.expected()) throw new AssertionError(c.name());
      return;
    }
    Case c = cases.stream().filter(item -> item.name().equals(args[0])).findFirst().orElseThrow();
    for (int i = 0; i < 16; i++) sink = c.operation().getAsLong();
    Runnable body =
        () -> {
          long sum = 0;
          for (int i = 0; i < 256; i++) sum += c.operation().getAsLong();
          sink = sum;
        };
    String uri = "benchmarks/cases.json::native-aot/" + c.name() + "[iterations=256]";
    if (ImageInfo.inImageCode()) NativeHooks.measure(uri, body);
    else body.run();
    if (sink != 256 * c.expected()) throw new AssertionError("Checksum mismatch: " + c.name());
    System.out.println(
        "{\"case\":\"" + c.name() + "\",\"iterations\":256,\"checksum\":" + sink + "}");
  }
}
