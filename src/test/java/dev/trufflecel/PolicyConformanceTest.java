package dev.trufflecel;

import static dev.trufflecel.ConfigurationData.*;
import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Complete pinned CEL Policy corpus; no fixture exclusions or skipped failures. */
@RunWith(Parameterized.class)
public class PolicyConformanceTest {
  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> cases() throws Exception {
    try (var paths = Files.walk(Path.of("third_party/cel-policy/testdata"))) {
      var result =
          paths
              .filter(p -> p.getFileName().toString().equals("policy.yaml"))
              .sorted()
              .map(p -> new Object[] {p.getParent().toString(), p})
              .toList();
      assertEquals("complete pinned policy count", 43, result.size());
      return result;
    }
  }

  private final Path policy;

  public PolicyConformanceTest(String name, Path policy) {
    this.policy = policy;
  }

  @Test
  public void conforms() throws Exception {
    var builder = Cel.newBuilder();
    ProductionCorpus.register(builder);
    Path configFile = policy.resolveSibling("config.yaml");
    Environment env =
        Files.exists(configFile)
            ? EnvironmentYaml.read(Files.readString(configFile), builder)
            : builder.build();
    // The upstream policy runner enables optional types independently of environment extensions.
    var libraries = new LinkedHashMap<>(env.libraryPolicy.libraries);
    libraries.put("optional", 2);
    Environment configured = env.toBuilder().libraries(libraries).build();
    String yaml = Files.readString(policy);
    if (policy.toString().contains("/compile_errors/")) {
      var error = assertThrows(CelException.class, () -> PolicyCompiler.prepare(configured, yaml));
      expectedFailure(policy.getParent().getFileName().toString()).check(error);
      if (error.code() == CelException.Code.SYNTAX) {
        // This fixture's first malformed expression omits the space in "l in resource.labels".
        // Assert the offending token's location, independently of this parser's diagnostic text.
        assertEquals(yaml.indexOf("resource.labels"), error.offset());
      }
      return;
    }
    Program program = PolicyCompiler.compile(configured, fixturePolicy(yaml));
    var expressions =
        env.toBuilder().libraries(LibraryPolicy.LATEST).standardMacros(Macro.STANDARD).build();
    var tests = ConfigurationData.read(Files.readString(policy.resolveSibling("tests.yaml")));
    int count = 0;
    for (Object section : list(tests, tests.containsKey("section") ? "section" : "sections")) {
      for (Object item : list(object(section), "tests")) {
        var test = object(item);
        var input = new LinkedHashMap<String, Object>();
        for (var binding : object(test.getOrDefault("input", Map.of())).entrySet())
          input.put(binding.getKey(), value(expressions, binding.getValue()));
        if (test.containsKey("context_expr")) {
          Object context = expressions.compile(string(test.get("context_expr"))).evaluate(Map.of());
          ObjectType adapter =
              env.objectTypes.values().stream()
                  .filter(a -> a.accepts(context))
                  .findFirst()
                  .orElseThrow();
          adapter.fields().keySet().forEach(field -> input.put(field, adapter.get(context, field)));
        }
        var options = EvaluationOptions.newBuilder();
        for (var overloads : env.overloads.values())
          for (var overload : overloads) {
            if (!overload.lateBound()) continue;
            options.function(
                overload.id(),
                arguments ->
                    switch (overload.id()) {
                      case "locationCode_string" ->
                          arguments.getFirst().equals("10.0.0.1") ? "us" : "cu";
                      case "hasCreditCard" -> ((Map<?, ?>) arguments.getFirst()).containsKey("cc");
                      case "hasEmailOrPhone" ->
                          ((Map<?, ?>) arguments.getFirst()).containsKey("email")
                              || ((Map<?, ?>) arguments.getFirst()).containsKey("phone");
                      default ->
                          throw new AssertionError(
                              "unimplemented fixture host function: " + overload.id());
                    });
          }
        Object expected = value(expressions, test.get("output"));
        Object actual = program.evaluate(input, options.build());
        assertEquals(string(test.get("name")), Values.in(expected), Values.in(actual));
        count++;
      }
    }
    assertTrue("fixture must execute its cases", count > 0);
  }

  /** Semantic expectations come from each upstream fixture, not its engine-specific wording. */
  private enum Failure {
    SYNTAX(CelException.Code.SYNTAX),
    UNDECLARED(CelException.Code.TYPE, "undeclared", "spec"),
    DUPLICATE_VARIABLE(CelException.Code.TYPE, "duplicate", "variable", "want"),
    IMPORT(CelException.Code.TYPE, "import"),
    OUTPUT_TYPES(CelException.Code.TYPE, "incompatible", "output", "types"),
    FALSE_CONDITION(CelException.Code.TYPE, "condition", "false"),
    NESTED_AGGREGATE(CelException.Code.TYPE, "nested", "aggregate"),
    UNREACHABLE(CelException.Code.TYPE, "unreachable");

    private final CelException.Code code;
    private final List<String> concepts;

    Failure(CelException.Code code, String... concepts) {
      this.code = code;
      this.concepts = List.of(concepts);
    }

    void check(CelException error) {
      assertEquals(error.getMessage(), code, error.code());
      String message = error.getMessage().toLowerCase(Locale.ROOT);
      // Nested first-match outputs compose through an optional fallback. Its type checker can
      // reject incompatible outputs before the policy-level output compatibility pass runs.
      if (this == OUTPUT_TYPES
          && message.contains("fallback")
          && message.contains("type")
          && message.contains("mismatch")) return;
      for (String concept : concepts)
        assertTrue(
            "expected " + name() + " (" + concept + "), got: " + error, message.contains(concept));
    }
  }

  private static Failure expectedFailure(String fixture) {
    return switch (fixture) {
      case "syntax" -> Failure.SYNTAX;
      case "undeclared_reference" -> Failure.UNDECLARED;
      case "duplicate_variable" -> Failure.DUPLICATE_VARIABLE;
      case "import" -> Failure.IMPORT;
      case "aggregate_errors",
          "aggregate_list_errors",
          "aggregate_heterogeneous_outputs",
          "aggregate_subrule_heterogeneous_outputs",
          "compose_conflicting_output",
          "compose_conflicting_subrule",
          "incompatible_outputs" ->
          Failure.OUTPUT_TYPES;
      case "aggregate_false_condition" -> Failure.FALSE_CONDITION;
      case "aggregate_nested_mixed_semantics" -> Failure.NESTED_AGGREGATE;
      case "aggregate_unreachable_in_nested_first_match",
          "unreachable",
          "unreachable_under_unconditional_aggregate_subrule" ->
          Failure.UNREACHABLE;
      default -> throw new AssertionError("missing expected failure category for " + fixture);
    };
  }

  private static Object value(Environment environment, Object entry) {
    var value = object(entry);
    if (value.containsKey("expr"))
      return environment.compile(string(value.get("expr"))).evaluate(Map.of());
    if (value.containsKey("value")) return value.get("value");
    throw new AssertionError("unhandled fixture value: " + value);
  }

  /** Mirrors the upstream test-only K8s custom tag handler; no cluster matching is simulated. */
  private static String fixturePolicy(String yaml) {
    var data = ConfigurationData.read(yaml);
    if (!data.containsKey("kind")) return yaml;
    assertEquals("ValidatingAdmissionPolicy", data.get("kind"));
    var spec = object(data.get("spec"));
    var matches = new ArrayList<Object>();
    for (Object entry : list(spec, "validations")) {
      var validation = object(entry);
      matches.add(
          Map.of(
              "condition",
              "!(" + string(validation.get("expression")) + ")",
              "output",
              validation.getOrDefault("messageExpression", "'invalid admission request'")));
    }
    return ConfigurationData.write(
        Map.of(
            "name",
            data.get("name"),
            "rule",
            Map.of("variables", list(spec, "variables"), "match", matches)));
  }
}
