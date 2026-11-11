package dev.trufflecel;

import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.List;
import java.util.Map;

/** Shared expressions, declarations, activations and expected results for both implementations. */
final class Fixtures {
  static final String[] NAMES = {"scalar", "policy", "comprehension"};
  static final String[] SOURCES = {
    "x * 2 + 1",
    "enabled && email.endsWith('@example.com') && x >= 18",
    "xs.filter(v, v % 2 == 0).map(v, v * v).size()"
  };

  static final class Truffle {
    static final Environment COMPILER =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .variable("enabled", CelType.BOOL)
            .variable("email", CelType.STRING)
            .variable("xs", CelType.list(CelType.INT))
            .build();
  }

  static final class Java {
    static final CelCompiler COMPILER =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setStandardMacros(CelStandardMacro.values())
            .addVar("x", SimpleType.INT)
            .addVar("enabled", SimpleType.BOOL)
            .addVar("email", SimpleType.STRING)
            .addVar("xs", ListType.create(SimpleType.INT))
            .build();
    static final CelRuntime RUNTIME = CelRuntimeFactory.plannerRuntimeBuilder().build();
  }

  static final List<Map<String, Object>> INPUTS =
      List.of(
          Map.of(
              "x", 42L, "enabled", true, "email", "a@example.com", "xs", List.of(1L, 2L, 3L, 4L)),
          Map.of("x", 7L, "enabled", true, "email", "b@other.com", "xs", List.of(2L, 4L, 6L)),
          Map.of("x", -1L, "enabled", false, "email", "c@example.com", "xs", List.of(1L, 3L)),
          Map.of("x", 21L, "enabled", true, "email", "d@example.com", "xs", List.of(8L)));
  static final Object[][] EXPECTED = {
    {85L, 15L, -1L, 43L}, {true, false, false, true}, {2L, 3L, 0L, 1L}
  };

  static List<Map<String, Object>> inputs(int workload) {
    return INPUTS.stream()
        .map(input ->
            switch (workload) {
              case 0 -> Map.of("x", input.get("x"));
              case 1 ->
                  Map.of(
                      "x",
                      input.get("x"),
                      "enabled",
                      input.get("enabled"),
                      "email",
                      input.get("email"));
              default -> Map.of("xs", input.get("xs"));
            })
        .toList();
  }
}
