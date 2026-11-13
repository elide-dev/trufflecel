package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PolicyCompilerTest {
  @Test
  public void variableMetadataIsTypedAndInitializersAreRequired() {
    var environment = Cel.newBuilder().build();
    assertEquals(
        1L,
        PolicyCompiler.compile(
                environment,
                "name: documented\nrule: {variables: [{name: a, description: value, expression: '1'}], match: [{output: 'variables.a'}]}")
            .evaluate());
    for (String variable :
        List.of("{name: a, description: 1, expression: '1'}", "{name: a, description: missing}")) {
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  PolicyCompiler.compile(
                      environment,
                      "name: malformed\nrule: {variables: ["
                          + variable
                          + "], match: [{output: '1'}]}"));
      assertTrue(failure.getMessage().contains("string"));
    }
  }

  @Test
  public void staticRegexConditionsRemainValidatedAfterNodeSpecialization() {
    for (String condition : List.of("'a'.matches('z')", "'a'.matches('a')")) {
      String fallback = condition.contains("'z'") ? "" : ", {output: '2'}";
      String yaml =
          "name: regex\nrule:\n  match:\n    - condition: >\n        "
              + condition
              + "\n      output: '1'\n";
      if (!fallback.isEmpty()) yaml += "    - output: '2'\n";
      String invalid = yaml;
      assertThrows(
          CelException.class, () -> PolicyCompiler.compile(Cel.newBuilder().build(), invalid));
    }
  }

  @Test
  public void conditionAnalysisIsBoundedAndNeverInvokesHostFunctions() {
    var calls = new AtomicInteger();
    var env =
        Cel.newBuilder()
            .function(
                "allow",
                List.of(),
                CelType.BOOL,
                args -> {
                  calls.incrementAndGet();
                  return true;
                })
            .build();
    var policy =
        PolicyCompiler.compile(
            env, "name: host\nrule: {match: [{condition: 'allow()', output: '1'}]}");
    assertEquals(0, calls.get());
    assertEquals(1L, policy.evaluate(Map.of()));
    assertEquals(1, calls.get());
    for (String condition : List.of("1/0 > 0", "dyn(1)")) {
      var deferred =
          PolicyCompiler.compile(
              Cel.newBuilder().build(),
              "name: deferred\nrule: {match: [{condition: '" + condition + "', output: '1'}]}");
      assertThrows(CelException.class, () -> deferred.evaluate(Map.of()));
    }
    var tiny = Cel.newBuilder().evaluationBudget(1).build();
    var bounded =
        PolicyCompiler.compile(
            tiny,
            "name: bounded\nrule: {aggregate: [{condition: '1+1 == 2', output: '1'}, {condition: '2+2 == 4', output: '2'}]}");
    assertThrows(CelException.class, () -> bounded.evaluate(Map.of()));
    assertThrows(
        CelException.class,
        () ->
            PolicyCompiler.compile(
                tiny,
                "name: bounded\nrule: {aggregate: [{condition: '1+1 == 2', output: '1'}, {condition: 'false', output: '2'}]}"));
  }

  @Test
  public void policyLimitsAndImportedEnumsAreExplicit() {
    var enums =
        Cel.newBuilder().constant("example.Color.RED", 1L).constant("unrelated.BLUE", 2L).build();
    assertEquals(
        1L,
        PolicyCompiler.compile(
                enums,
                "name: enums\nimports: [{name: example.Color}]\nrule: {match: [{output: Color.RED}]}")
            .evaluate(Map.of()));
    var oversized =
        assertThrows(
            CelException.class,
            () ->
                PolicyCompiler.compile(
                    Cel.newBuilder().maxSourceLength(1).build(),
                    "name: size\nrule: {match: [{output: '1+1'}]}"));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, oversized.code());
    var deep =
        assertThrows(
            CelException.class,
            () ->
                PolicyCompiler.compile(
                    Cel.newBuilder().maxDepth(1).build(),
                    "name: depth\nrule: {match: [{rule: {match: [{rule: {match: [{output: '1'}]}}]}}]}"));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, deep.code());
    assertThrows(
        CelException.class,
        () -> PolicyCompiler.compile(enums, "name: empty\nrule:\n  match:\n    - output: >"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PolicyCompiler.compile(
                enums,
                "name: types\nrule: {variables: [{name: a, description: 1, expression: '1'}], match: [{output: '1'}]}"));
    assertThrows(
        CelException.class,
        () ->
            PolicyCompiler.compile(
                enums, "name: falsity\nrule: {match: [{condition: '1 == 2', output: '1'}]}"));
    assertThrows(
        CelException.class,
        () ->
            PolicyCompiler.compile(
                enums,
                "name: unreachable\nrule: {match: [{condition: '1 == 1', output: '1'}, {output: '2'}]}"));
    assertEquals(
        "😀",
        PolicyCompiler.compile(
                Cel.newBuilder().maxSourceLength(3).build(),
                "name: unicode\nrule: {match: [{output: \"'😀'\"}]}")
            .evaluate(Map.of()));
    assertThrows(
        CelException.class,
        () ->
            PolicyCompiler.compile(
                Cel.newBuilder().maxSourceLength(2).build(),
                "name: unicode\nrule: {match: [{output: \"'😀'\"}]}"));
  }

  @Test
  public void customComprehensionsAndMacroLocalsDoNotCapturePolicyVariables() {
    var env =
        Cel.newBuilder()
            .macro(
                Macro.global(
                    "scoped",
                    0,
                    (c, t, a) ->
                        Ast.Node.comprehension(
                            "variables",
                            "item",
                            "",
                            Ast.Node.list(),
                            Ast.Node.map(Ast.Node.literal("n"), Ast.Node.literal(7)),
                            Ast.Node.literal(false),
                            Ast.Node.identifier("variables"),
                            Ast.Node.select(Ast.Node.identifier("variables"), "n", false))))
            .build();
    assertEquals(
        7L,
        PolicyCompiler.compile(
                env,
                "name: scopes\nrule: {variables: [{name: n, expression: '99'}], match: [{output: 'scoped()'}]}")
            .evaluate(Map.of()));
    assertEquals(
        8L,
        PolicyCompiler.compile(
                env,
                "name: scopes\nrule: {variables: [{name: n, expression: '99'}], match: [{output: \"[{'n':8}].map(variables, variables.n)[0]\"}]}")
            .evaluate(Map.of()));
  }

  @Test
  public void blockScalarExpressionsPreserveCommentsWhitespaceAndPolicyLocations() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    String policy =
        """
        name: multiline
        rule:
          match:
            - output: >
                x + // folding this newline would remove the next operand
                  2
        """;
    assertEquals(5L, PolicyCompiler.compile(env, policy).evaluate(Map.of("x", 3)));
    for (String style : List.of("|", ">")) {
      String failure = "name: '😀'\nrule:\n  match:\n    - output: " + style + "\n        x / 0\n";
      var program = PolicyCompiler.compile(env, failure);
      var error = assertThrows(CelException.class, () -> program.evaluate(Map.of("x", 1)));
      assertEquals(CelException.Code.DIVIDE_BY_ZERO, error.code());
      assertEquals(failure.indexOf('/'), error.offset());
      assertEquals(5, error.line());
      assertEquals(11, error.column());
      assertTrue(error.expressionId() > 0);
      String invalid = failure.replace("x / 0", "undeclared");
      var compile = assertThrows(CelException.class, () -> PolicyCompiler.compile(env, invalid));
      assertEquals(5, compile.line());
      assertEquals(9, compile.column());
      assertEquals(invalid.indexOf("undeclared"), compile.offset());
      assertEquals(CelException.OffsetEncoding.UTF16, compile.offsetEncoding());
      assertTrue(
          compile.format(invalid),
          compile.format(invalid).endsWith("        undeclared\n        ^"));
    }
  }

  @Test
  public void generatedHelpersDoNotExpandUserFunctionPermissions() {
    var env = Cel.newBuilder().allowedFunctions(Set.of("+")).libraries(Map.of()).build();
    assertEquals(
        3L,
        PolicyCompiler.compile(env, "name: limited\nrule: {match: [{output: '1 + 2'}]}")
            .evaluate(Map.of()));
    assertThrows(
        CelException.class,
        () ->
            PolicyCompiler.compile(
                env, "name: limited\nrule: {match: [{output: 'optional.of(1)'}]}"));
  }

  @Test
  public void firstMatchAndConditionalResults() {
    Environment env = Cel.newBuilder().variable("x", CelType.INT).build();
    var complete =
        PolicyCompiler.compile(
            env,
            """
            name: first
            description: first matching output
            rule:
              id: root
              description: choices
              match:
                - {condition: 'x > 0', output: "'positive'", explanation: "'reason'"}
                - {output: "'other'"}
            """);
    assertEquals("positive", complete.evaluate(Map.of("x", 1)));
    assertEquals("other", complete.evaluate(Map.of("x", 0)));
    var conditional =
        PolicyCompiler.compile(
            env,
            """
            name: conditional
            rule:
              match:
                - {condition: 'x > 0', output: 'x * 2'}
            """);
    assertEquals(4L, conditional.evaluate(Map.of("x", 2)));
    assertEquals(OptionalValue.none(), conditional.evaluate(Map.of("x", 0)));
  }

  @Test
  public void variablesAreLazyMemoizedAndLexicallyScoped() {
    var calls = new AtomicInteger();
    Environment env =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .function(
                "load",
                List.of(),
                CelType.INT,
                args -> {
                  calls.incrementAndGet();
                  return 4L;
                })
            .build();
    var program =
        PolicyCompiler.compile(
            env,
            """
            name: variables
            rule:
              variables:
                - {name: unused, expression: '1 / 0'}
                - {name: a, expression: 'load()'}
                - {name: b, expression: 'variables.a * 2'}
              match:
                - condition: 'x > 0'
                  rule:
                    variables:
                      - {name: a, expression: 'variables.b + 1'}
                    match:
                      - {output: 'variables.a + variables.b'}
                - {output: '0'}
            """);
    assertEquals(0L, program.evaluate(Map.of("x", 0)));
    assertEquals(0, calls.get());
    assertEquals(17L, program.evaluate(Map.of("x", 1)));
    assertEquals(1, calls.get());
    assertEquals(17L, program.evaluate(Map.of("x", 1)));
    assertEquals(2, calls.get());
    var strict = Cel.newBuilder().validation(Environment.Validation.STRICT).build();
    assertEquals(
        true,
        PolicyCompiler.compile(
                strict,
                "name: lazy\nrule: {variables: [{name: bad, expression: '1/0'}], match: [{output: 'true'}]}")
            .evaluate(Map.of()));
  }

  @Test
  public void aggregatePrunesNestedAbsenceButKeepsExplicitListsAndOptionals() {
    Environment env = Cel.newBuilder().variable("x", CelType.INT).build();
    var aggregate =
        PolicyCompiler.compile(
            env,
            """
            name: tags
            rule:
              aggregate:
                - {condition: 'x > 0', output: "'positive'"}
                - rule:
                    match:
                      - {condition: 'x > 10', output: "'large'"}
                - {output: "'always'"}
            """);
    assertEquals(List.of("always"), aggregate.evaluate(Map.of("x", 0)));
    assertEquals(List.of("positive", "large", "always"), aggregate.evaluate(Map.of("x", 20)));
    assertEquals(
        List.of(List.of(1L, 2L), List.of(3L)),
        PolicyCompiler.compile(
                Cel.newBuilder().build(),
                """
                name: lists
                rule:
                  aggregate:
                    - {output: '[1, 2]'}
                    - {output: '[3]'}
                """)
            .evaluate(Map.of()));
    assertEquals(
        List.of(OptionalValue.none()),
        PolicyCompiler.compile(
                Cel.newBuilder().build(),
                "name: optional\nrule: {aggregate: [{output: 'optional.none()'}]}")
            .evaluate(Map.of()));
  }

  @Test
  public void unconditionalNestedRulesFallThroughAndAggregateCanBeAMatchResult() {
    Environment env = Cel.newBuilder().variable("x", CelType.INT).build();
    var program =
        PolicyCompiler.compile(
            env,
            """
            name: fallthrough
            rule:
              match:
                - rule:
                    match:
                      - {condition: 'x > 0', output: "'inner'"}
                - {output: "'fallback'"}
            """);
    assertEquals("inner", program.evaluate(Map.of("x", 1)));
    assertEquals("fallback", program.evaluate(Map.of("x", 0)));
    var aggregate =
        PolicyCompiler.compile(
            env,
            """
            name: nested-aggregate
            rule:
              match:
                - condition: 'x > 0'
                  rule:
                    aggregate:
                      - {output: 'x'}
                - {output: '[]'}
            """);
    assertEquals(List.of(2L), aggregate.evaluate(Map.of("x", 2)));
    assertEquals(List.of(), aggregate.evaluate(Map.of("x", 0)));
  }

  @Test
  public void scopeRecoveryAndTypeConstraintsRejectInvalidPolicies() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    for (String rule :
        List.of(
            "{}",
            "{match: [], aggregate: []}",
            "{match: []}",
            "{match: [{output: '1', rule: {match: [{output: '1'}]}}]}",
            "{match: [{condition: 'false', output: '1'}]}",
            "{match: [{condition: '1', output: '1'}]}",
            "{match: [{output: '1'}, {output: '2'}]}",
            "{match: [{condition: 'x > 0', output: '1'}, {output: \"'string'\"}]} ",
            "{match: [{output: 'variables.missing'}]}",
            "{variables: [{name: a, expression: 'variables.a'}], match: [{output: '1'}]}",
            "{variables: [{name: a, expression: 'variables.b'}, {name: b, expression: '1'}], match: [{output: '1'}]}",
            "{variables: [{name: a, expression: '1'}, {name: a, expression: '2'}], match: [{output: '1'}]}",
            "{variables: [{name: 'a.b', expression: '1'}], match: [{output: '1'}]}",
            "{match: [{output: '1', explanation: '2'}]}",
            "{aggregate: [{rule: {match: [{rule: {aggregate: [{output: '1'}]}}]}}]}",
            "{match: [{condition: 'x>0', rule: {id: duplicate, match: [{output: '1'}]}}, {rule: {id: duplicate, match: [{output: '1'}]}}]}",
            "{match: [{output: '1 + '}]}",
            "{match: [{output: 'undefined'}]}"))
      assertThrows(
          rule, CelException.class, () -> PolicyCompiler.compile(env, "name: bad\nrule: " + rule));
    assertThrows(
        IllegalArgumentException.class,
        () -> PolicyCompiler.compile(env, "name: ''\nrule: {match: [{output: '1'}]}"));
    assertThrows(
        CelException.class,
        () ->
            PolicyCompiler.compile(
                env,
                "name: bad\nimports: [{name: Unknown.Message}]\nrule: {match: [{output: '1'}]}"));
  }

  @Test
  public void hasOverPolicyVariableReportsCleanDiagnosticInsteadOfCrashing() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    // Variant A: foldable condition previously crashed at compile time with
    // ArrayIndexOutOfBoundsException during optimizer constant folding.
    var foldable =
        assertThrows(
            CelException.class,
            () ->
                PolicyCompiler.compile(
                    env,
                    "name: repro\nrule: {variables: [{name: v, expression: '1 + 1'}],"
                        + " match: [{condition: 'has(variables.v)', output: \"'yes'\"},"
                        + " {output: \"'no'\"}]}"));
    assertTrue(foldable.getMessage().contains("has requires a field selection"));
    // Variant B: non-foldable condition previously crashed at evaluate time.
    var deferred =
        assertThrows(
            CelException.class,
            () ->
                PolicyCompiler.compile(
                    env,
                    "name: deferred\nrule: {variables: [{name: v, expression: '1'}],"
                        + " match: [{condition: 'x > 0 && has(variables.v)', output: \"'yes'\"},"
                        + " {output: \"'no'\"}]}"));
    assertTrue(deferred.getMessage().contains("has requires a field selection"));
    // The diagnostic is located, not a raw unlocated RuntimeException.
    assertTrue(foldable.offset() >= 0);
    assertTrue(deferred.offset() >= 0);
    // has over a nested select on a policy variable keeps the SELECT shape and works.
    assertEquals(
        "yes",
        PolicyCompiler.compile(
                env,
                "name: nested\nrule: {variables: [{name: m, expression: \"{'a': 1}\"}],"
                    + " match: [{condition: 'has(variables.m.a)', output: \"'yes'\"},"
                    + " {output: \"'no'\"}]}")
            .evaluate(Map.of()));
    // has over a declared map variable is unaffected (regression guard).
    var mapEnv = Cel.newBuilder().variable("x", CelType.map(CelType.STRING, CelType.INT)).build();
    assertEquals(
        true,
        PolicyCompiler.compile(
                mapEnv,
                "name: declared\nrule: {match: [{condition: 'has(x.a)', output: 'true'},"
                    + " {output: 'false'}]}")
            .evaluate(Map.of("x", Map.of("a", 1))));
    assertEquals(
        false,
        PolicyCompiler.compile(
                mapEnv,
                "name: absent\nrule: {match: [{condition: 'has(x.b)', output: 'true'},"
                    + " {output: 'false'}]}")
            .evaluate(Map.of("x", Map.of("a", 1))));
  }

  @Test
  public void declaredInvariantsAreValidatedDataNotImplicitProofs() {
    String yaml =
        """
        name: properties
        rule: {match: [{output: 'true'}]}
        verification:
          invariants:
            - id: boolean
              description: output contract
              assume: ['true']
              assert: ['rule.result == true']
        """;
    assertEquals(true, PolicyCompiler.compile(Cel.newBuilder().build(), yaml).evaluate(Map.of()));
    assertEquals(
        List.of("rule.result == true"), PolicyCompiler.invariants(yaml).getFirst().assertions());
    assertTrue(PolicyCompiler.invariants("name: no-properties").isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PolicyCompiler.invariants(
                "verification: {invariants: [{id: a, assert: ['true']}, {id: a, assert: ['true']}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> PolicyCompiler.invariants("verification: {invariants: [{id: a}]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PolicyCompiler.Invariant("", "", List.of(), List.of("true")));
  }
}
