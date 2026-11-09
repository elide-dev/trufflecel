package dev.trufflecel;

import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The same entry point is exercised by Jazzer and deterministic corpus regressions. */
public final class FuzzTarget {
  private static final Environment ENVIRONMENT =
      Cel.newBuilder()
          .inferVariables(true)
          .maxSourceLength(4096)
          .maxAstNodes(2048)
          .maxDepth(64)
          .evaluationBudget(10000)
          .build();

  public static void fuzzerTestOneInput(byte[] input) {
    if (input.length == 0 || input.length > 4097) return;
    byte[] payload = Arrays.copyOfRange(input, 1, input.length);
    String text = new String(payload, StandardCharsets.UTF_8);
    try {
      switch (Byte.toUnsignedInt(input[0]) % 5) {
        case 0 -> ENVIRONMENT.parse(text);
        case 1 -> ENVIRONMENT.prepare(text);
        case 2 -> {
          var ast =
              CanonicalAst.prototype("cel.expr.ParsedExpr")
                  .newBuilderForType()
                  .mergeFrom(payload)
                  .build();
          CanonicalAst.program(ENVIRONMENT, ast);
        }
        case 3 ->
            EncoderFunctions.apply(
                "json.parse", new Object[] {text}, new EvalContext(ENVIRONMENT, Map.of()));
        case 4 ->
            EncoderFunctions.apply(
                "yaml.parse", new Object[] {text}, new EvalContext(ENVIRONMENT, Map.of()));
        default -> throw new AssertionError("selector");
      }
    } catch (CelException | InvalidProtocolBufferException expected) {
      // Invalid CEL/data and configured resource exhaustion are normal inputs, not findings.
    } catch (IllegalArgumentException malformedAst) {
      // The AST import API explicitly rejects unsupported or inconsistent protobuf shapes.
      if ((Byte.toUnsignedInt(input[0]) % 5) != 2) throw malformedAst;
    }
  }

  static List<byte[]> seeds() {
    var inputs = new ArrayList<byte[]>();
    for (String expression :
        List.of(
            "1 + 2",
            "[1,2,3].map(x, x * 2)",
            "cel.bind(x, 1 / 0, true)",
            "has({'a':1}.a)",
            "[?optional.of(1)]",
            "x ? 1 : 2",
            "1 +",
            "!".repeat(1024) + "true")) {
      for (int kind : new int[] {0, 1})
        inputs.add(input(kind, expression.getBytes(StandardCharsets.UTF_8)));
    }
    for (String expression : List.of("1", "x + 1", "[1].all(x, x == 1)", "{'a': 2}"))
      inputs.add(input(2, CanonicalAst.parse(ENVIRONMENT, expression).toByteArray()));
    for (String text :
        List.of(
            "{\"a\":[1,true,null]}", "a: [1, 2, 3]", "a: &a [*a]", "a: !!java/object {}", "{bad"))
      for (int kind : new int[] {3, 4})
        inputs.add(input(kind, text.getBytes(StandardCharsets.UTF_8)));
    return inputs;
  }

  private static byte[] input(int kind, byte[] payload) {
    var result = new byte[payload.length + 1];
    result[0] = (byte) kind;
    System.arraycopy(payload, 0, result, 1, payload.length);
    return result;
  }

  public static void main(String[] args) throws Exception {
    List<byte[]> seeds = seeds();
    if (args.length == 1) {
      Path directory = Path.of(args[0]);
      Files.createDirectories(directory);
      for (int i = 0; i < seeds.size(); i++)
        Files.write(directory.resolve("seed-" + i), seeds.get(i));
      return;
    }
    if (args.length != 0) throw new IllegalArgumentException("optional corpus directory");
    fuzzerTestOneInput(new byte[0]);
    fuzzerTestOneInput(new byte[4098]);
    var random = new Random(20261108);
    for (byte[] seed : seeds) {
      fuzzerTestOneInput(seed);
      for (int i = 0; i < 40; i++) {
        byte[] mutated = seed.clone();
        mutated[random.nextInt(mutated.length)] ^= (byte) (1 + random.nextInt(255));
        fuzzerTestOneInput(mutated);
      }
    }
    System.out.println("Fuzz seed and mutation regressions passed: " + seeds.size() + " seeds");
  }
}
