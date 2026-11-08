package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class EnvironmentYamlTest {
  @Test
  public void sourceLimitsCountCodePointsAcrossFrontendApis() {
    var env = EnvironmentYaml.read("limits: [{name: cel.limit.expression_code_points, value: 3}]");
    String source = "'😀'";
    assertEquals(4, source.length());
    assertEquals("😀", env.compile(source).evaluate());
    assertEquals("😀", env.parse(source).evaluate());
    assertEquals("😀", Ast.parse(env, source).compile(env).evaluate());
    assertTrue(CostEstimator.estimate(env, source).maximum().isPresent());
    for (Environment limited : List.of(env, Cel.newBuilder().maxSourceLength(2).build())) {
      String tooLong = limited == env ? "'ab'" : source;
      for (Runnable action :
          List.<Runnable>of(
              () -> limited.compile(tooLong),
              () -> Ast.parse(limited, tooLong),
              () -> CostEstimator.estimate(limited, tooLong)))
        assertEquals(
            CelException.Code.RESOURCE_EXHAUSTED,
            assertThrows(CelException.class, action::run).code());
    }
  }

  @Test
  public void extendedConfigurationRoundTripsWithoutLosingRestrictions() {
    var env =
        Cel.newBuilder()
            .evaluationBudget(Long.MAX_VALUE)
            .commonSubexpressions(true)
            .standardMacros(Set.of("has", "cel.bind"))
            .variable("raw", CelType.of("list"))
            .variable("opaque", new CelType("custom.Box", List.of(CelType.INT)))
            .build();
    var restored = EnvironmentYaml.read(EnvironmentYaml.write(env));
    assertEquals(env.variables, restored.variables);
    assertEquals(env.standardMacros, restored.standardMacros);
    assertEquals(Long.MAX_VALUE, restored.budget);
    assertTrue(restored.commonSubexpressions);
    assertFalse(
        EnvironmentYaml.read(EnvironmentYaml.write(Cel.newBuilder().macros(false).build())).macros);
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentYaml.write(Cel.newBuilder().validator(context -> List.of()).build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EnvironmentYaml.write(
                Cel.newBuilder()
                    .macro(Macro.global("m", 0, (c, t, a) -> Ast.Node.literal(true)))
                    .build()));
    for (String value : List.of("-1", "false", "'large'"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              EnvironmentYaml.read(
                  "limits: [{name: trufflecel.evaluation_budget, value: " + value + "}]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentYaml.read("context_variable: {type_name: absent.Message}"));
    var limited = EnvironmentYaml.read("stdlib: {disable_macros: true}");
    assertThrows(CelException.class, () -> limited.compile("[1].all(x, true)"));
    assertEquals(2L, limited.compile("cel.bind(x, 1, x+1)").evaluate(Map.of()));
    var dormant = Cel.newBuilder().macros(false).standardMacros(Set.of("has", "cel.bind")).build();
    var dormantCopy = EnvironmentYaml.read(EnvironmentYaml.write(dormant));
    assertFalse(dormantCopy.macros);
    assertEquals(dormant.standardMacros, dormantCopy.standardMacros);
    assertTrue(
        dormantCopy.toBuilder().macros(true).build().compile("has({'x':1}.x)").test(Map.of()));
    for (long budget : List.of(1L, 9007199254740993L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
      String yaml = EnvironmentYaml.write(Cel.newBuilder().evaluationBudget(budget).build());
      assertEquals(budget, EnvironmentYaml.read(yaml).budget);
    }
    CelException limit =
        assertThrows(
            CelException.class,
            () ->
                EnvironmentYaml.write(
                    Cel.newBuilder().variable("a".repeat(600000), CelType.INT).build()));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, limit.code());
  }

  @Test
  public void shorthandAndVerboseTypesRoundTrip() {
    assertEquals(
        CelType.map(CelType.STRING, CelType.list(CelType.INT)),
        TypeSyntax.parse(" map < string, list<int> > "));
    assertEquals(CelType.TIMESTAMP, TypeSyntax.parse("timestamp"));
    assertEquals(CelType.DURATION, TypeSyntax.parse("duration"));
    assertEquals(CelType.TYPE, TypeSyntax.parse("type"));
    assertEquals(CelType.type(CelType.INT), TypeSyntax.parse("type<int>"));
    assertEquals(CelType.wrapper(CelType.BOOL), TypeSyntax.parse("wrapper<bool>"));
    for (String text :
        List.of(
            "int",
            "$T",
            "custom.Record",
            "map",
            "_custom._Record",
            "map<string, optional<list<int>>>"))
      assertEquals(
          TypeSyntax.parse(text), TypeSyntax.parse(TypeSyntax.format(TypeSyntax.parse(text))));
    var env =
        EnvironmentYaml.read(
            """
        name: config
        description: sample
        variables:
          - {name: x, type: 'map<string, list<int>>'}
          - name: y
            type_name: map
            params:
              - {type_name: string}
              - {type_name: list, params: [{type_name: int}]}
        result_type: int
        """);
    assertEquals(
        5L,
        env.compile("x['k'][0] + y['k'][0]")
            .evaluate(Map.of("x", Map.of("k", List.of(2)), "y", Map.of("k", List.of(3)))));
    assertEquals(env.variables, EnvironmentYaml.read(EnvironmentYaml.write(env)).variables);
    assertThrows(CelException.class, () -> env.compile("true"));
  }

  @Test
  public void declarationsUseExplicitPerEvaluationBindings() {
    var env =
        EnvironmentYaml.read(
            """
        functions:
          - name: twice
            description: doubles an integer
            overloads:
              - id: twice_int
                args: [int]
                return: int
          - name: first
            overloads:
              - id: first_generic
                target: {type_name: list, params: [{type_name: T, is_type_param: true}]}
                return: {type_name: T, is_type_param: true}
        """);
    var options =
        EvaluationOptions.newBuilder()
            .function("twice_int", values -> (Long) values.getFirst() * 2)
            .function("first_generic", values -> ((List<?>) values.getFirst()).getFirst())
            .build();
    assertEquals(8L, env.compile("twice([4].first())").evaluate(Map.of(), options));
    assertThrows(CelException.class, () -> env.compile("twice(4)").evaluate(Map.of()));
    var restored = EnvironmentYaml.read(EnvironmentYaml.write(env));
    assertEquals(8L, restored.compile("twice([4].first())").evaluate(Map.of(), options));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EnvironmentYaml.write(
                Cel.newBuilder().function("f", List.of(), CelType.INT, args -> 1L).build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentYaml.write(Cel.newBuilder().constant("x", 1).build()));
  }

  @Test
  public void importsLibrariesAndLimitsRemainEffectiveAfterExport() {
    var env =
        EnvironmentYaml.read(
            """
        container:
          name: pkg
          abbreviations: [other.value]
          aliases: [{alias: alias, qualified_name: other.value}]
        variables: [{name: other.value, type_name: int}]
        extensions: [{name: math, version: latest}]
        features:
          - {name: trufflecel.infer_variables, enabled: false}
          - {name: trufflecel.homogeneous_literals, enabled: true}
          - {name: trufflecel.strict_validation, enabled: true}
          - {name: cel.feature.macro_call_tracking, enabled: true}
        limits:
          - {name: cel.limit.expression_code_points, value: 1000}
          - {name: cel.limit.parse_recursion_depth, value: 64}
          - {name: trufflecel.ast_nodes, value: 1000}
          - {name: trufflecel.regex_program_size, value: 500}
          - {name: trufflecel.comprehension_nesting, value: 2}
          - {name: trufflecel.evaluation_budget, value: 10000}
        """);
    for (Environment value : List.of(env, EnvironmentYaml.read(EnvironmentYaml.write(env)))) {
      assertEquals(6L, value.compile("alias + value").evaluate(Map.of("other.value", 3L)));
      assertEquals(4.0, value.compile("math.sqrt(16.0)").evaluate(Map.of()));
      assertThrows(CelException.class, () -> value.compile("[1, 'two']"));
      assertThrows(CelException.class, () -> value.compile("1 / 0"));
      assertThrows(CelException.class, () -> value.compile("'x'.trim()"));
      assertEquals(1000, value.maxSourceLength);
      assertEquals(1000, value.maxAstNodes);
      assertEquals(500, value.maxRegexProgramSize);
      assertEquals(64, value.maxDepth);
      assertEquals(2, value.comprehensionNesting);
      assertEquals(10000, value.budget);
    }
    assertEquals("pkg", EnvironmentYaml.read("container: pkg").container);
  }

  @Test
  public void standardLibraryRestrictionsRejectCalls() {
    var env =
        EnvironmentYaml.read(
            """
        stdlib:
          disabled: true
          include_functions: [{name: '_+_'}]
          disable_macros: true
        """);
    assertEquals(3L, env.compile("1 + 2").evaluate(Map.of()));
    assertThrows(CelException.class, () -> env.compile("1 - 2"));
    assertThrows(CelException.class, () -> env.compile("[1].exists(x, true)"));
    var restored = EnvironmentYaml.read(EnvironmentYaml.write(env));
    assertEquals(3L, restored.compile("1 + 2").evaluate(Map.of()));
    var selected =
        EnvironmentYaml.read("stdlib: {include_macros: [has, exists], exclude_macros: [exists]}");
    assertTrue(selected.compile("has({'a':1}.a)").test(Map.of()));
    assertThrows(CelException.class, () -> selected.compile("[1].exists(x, true)"));
    var disabled = EnvironmentYaml.read("stdlib: {disabled: true}");
    assertThrows(CelException.class, () -> disabled.compile("1+1"));
    var excluded = EnvironmentYaml.read("stdlib: {exclude_macros: [has]}");
    assertThrows(CelException.class, () -> excluded.compile("has({'x':1}.x)"));
    assertTrue(excluded.compile("[1].all(x,true)").test(Map.of()));
  }

  @Test
  public void canonicalOperatorNamesSelectTheirSourceOperations() {
    var sources =
        Map.ofEntries(
            Map.entry("_-_", "3-2"),
            Map.entry("_*_", "1*1"),
            Map.entry("_/_", "2/2"),
            Map.entry("_%_", "3%2"),
            Map.entry("_==_", "1==1"),
            Map.entry("_!=_", "1!=2"),
            Map.entry("_<_", "1<2"),
            Map.entry("_<=_", "1<=2"),
            Map.entry("_>_", "2>1"),
            Map.entry("_>=_", "2>=1"),
            Map.entry("_&&_", "true&&true"),
            Map.entry("_||_", "true||false"),
            Map.entry("!_", "!false"),
            Map.entry("-_", "-x"),
            Map.entry("_?_:_", "true ? 1 : 2"),
            Map.entry("@in", "1 in [1]"));
    for (var entry : sources.entrySet()) {
      Environment env =
          EnvironmentYaml.read(
              "variables: [{name: x, type: int}]\nstdlib: {include_functions: [{name: '"
                  + entry.getKey()
                  + "'}]}");
      Object result = env.compile(entry.getValue()).evaluate(Map.of("x", -1));
      assertTrue(entry.toString(), Boolean.TRUE.equals(result) || Long.valueOf(1).equals(result));
    }
  }

  @Test
  public void contextFieldsHaveDistinctValidatedDeclarations() {
    ObjectType record =
        new ObjectType() {
          public String name() {
            return "example.Context";
          }

          public Map<String, CelType> fields() {
            return Map.of("value", CelType.INT);
          }

          public Object construct(Map<String, Object> fields) {
            return fields;
          }

          public Object get(Object value, String field) {
            return ((Map<?, ?>) value).get(field);
          }

          public boolean has(Object value, String field) {
            return ((Map<?, ?>) value).containsKey(field);
          }

          public boolean accepts(Object value) {
            return value instanceof Map<?, ?>;
          }
        };
    String yaml =
        "context_variable: {type_name: example.Context}\nvariables: [{name: extra, type: int, description: independent}]";
    var env = EnvironmentYaml.read(yaml, Cel.newBuilder().objectType(record));
    assertEquals(3L, env.compile("value + extra").evaluate(Map.of("value", 1, "extra", 2)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EnvironmentYaml.read(
                yaml.replace("name: extra", "name: value"), Cel.newBuilder().objectType(record)));
  }

  @Test
  public void invalidDataAndUnsupportedSettingsAreRejected() {
    for (String yaml :
        List.of(
            "",
            "[1, 2]",
            "name: [1]",
            "name: a\nname: b",
            "unknown: true",
            "name: !!java/object {}",
            "variables: false",
            "variables: [1]",
            "variables: [{name: x, type: int}, {name: x, type: int}]",
            "variables: [{name: x, type: int, type_name: int}]",
            "variables: [{name: x, type: int, params: []}]",
            "variables: [{name: x, type: int, is_type_param: false}]",
            "variables: [{name: x, description: false, type: int}]",
            "variables: [{name: x, type: {type_name: int, ignored: true}}]",
            "result_type: {type_name: int, ignored: true}",
            "result_type: {type: int, ignored: true}",
            "result_type: {type_name: list, params: [{type_name: int, ignored: true}]}",
            "variables: [{name: x, type_name: T, is_type_param: yes}]",
            "variables: [{name: x, type_name: T, is_type_param: true, params: [{type_name: int}]}]",
            "variables: [{name: x, type_name: 'bad.name', is_type_param: true}]",
            "functions: [{name: f}]",
            "functions: [{name: f, description: false}]",
            "functions: [{name: f, overloads: [{id: x, args: [int], return: int, extra: 1}]}]",
            "extensions: [{name: unknown, version: latest}]",
            "extensions: [{name: math}, {name: math}]",
            "extensions: [{name: math, version: -1}]",
            "extensions: [{name: math, version: 2147483648}]",
            "extensions: [{name: math, version: false}]",
            "extensions: [{name: math, version: 100}]",
            "features: [{name: unknown, enabled: true}]",
            "features: [{name: cel.feature.macro_call_tracking, enabled: false}]",
            "limits: [{name: unknown, value: 2}]",
            "stdlib: {disabled: 'true'}",
            "stdlib: {exclude_macros: [missing]}",
            "container: {aliases: [{alias: a, qualified_name: bad-name}]}"))
      assertThrows(yaml, IllegalArgumentException.class, () -> EnvironmentYaml.read(yaml));
    for (String type :
        List.of(
            "",
            "1bad",
            "a..b",
            "$a.b",
            "int<string>",
            "list<>",
            "list<int",
            "map<string>",
            "int int",
            "map<string,int,int>",
            "list<int>>"))
      assertThrows(type, IllegalArgumentException.class, () -> TypeSyntax.parse(type));
    assertThrows(IllegalArgumentException.class, () -> TypeSyntax.parse("x".repeat(4097)));
    assertThrows(
        IllegalArgumentException.class,
        () -> TypeSyntax.parse("list<".repeat(66) + "int" + ">".repeat(66)));
    CelType type = CelType.INT;
    for (int i = 0; i < 66; i++) type = CelType.list(type);
    CelType deep = type;
    assertThrows(IllegalArgumentException.class, () -> TypeSyntax.format(deep));
    assertThrows(
        IllegalArgumentException.class, () -> TypeSyntax.format(CelType.of("x".repeat(4097))));
    assertThrows(
        IllegalArgumentException.class,
        () -> TypeSyntax.format(new CelType("Box", Collections.nCopies(2000, CelType.INT))));
    assertThrows(
        IllegalArgumentException.class,
        () -> TypeSyntax.format(new CelType("list", List.of(CelType.INT, CelType.INT))));
    assertThrows(IllegalArgumentException.class, () -> EnvironmentYaml.read(" ".repeat(1_000_001)));
  }
}
