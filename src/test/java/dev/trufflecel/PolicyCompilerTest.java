package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PolicyCompilerTest {
  @Test
  public void firstMatchAndConditionalResults() {
    Environment env = Cel.newBuilder().variable("x", CelType.INT).build();
    var complete = PolicyCompiler.compile(env, """
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
    var conditional = PolicyCompiler.compile(env, """
        name: conditional
        rule:
          match:
            - {condition: 'x > 0', output: 'x * 2'}
        """);
    assertEquals(OptionalValue.of(4L), conditional.evaluate(Map.of("x", 2)));
    assertEquals(OptionalValue.none(), conditional.evaluate(Map.of("x", 0)));
  }

  @Test
  public void variablesAreLazyMemoizedAndLexicallyScoped() {
    var calls = new AtomicInteger();
    Environment env = Cel.newBuilder().variable("x", CelType.INT)
        .function("load", List.of(), CelType.INT, args -> { calls.incrementAndGet(); return 4L; }).build();
    var program = PolicyCompiler.compile(env, """
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
    assertEquals(true, PolicyCompiler.compile(strict, "name: lazy\nrule: {variables: [{name: bad, expression: '1/0'}], match: [{output: 'true'}]}").evaluate(Map.of()));
  }

  @Test
  public void aggregatePrunesNestedAbsenceButKeepsExplicitListsAndOptionals() {
    Environment env = Cel.newBuilder().variable("x", CelType.INT).build();
    var aggregate = PolicyCompiler.compile(env, """
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
    assertEquals(List.of(List.of(1L, 2L), List.of(3L)), PolicyCompiler.compile(Cel.newBuilder().build(), """
        name: lists
        rule:
          aggregate:
            - {output: '[1, 2]'}
            - {output: '[3]'}
        """).evaluate(Map.of()));
    assertEquals(List.of(OptionalValue.none()), PolicyCompiler.compile(Cel.newBuilder().build(), "name: optional\nrule: {aggregate: [{output: 'optional.none()'}]}").evaluate(Map.of()));
  }

  @Test
  public void unconditionalNestedRulesFallThroughAndAggregateCanBeAMatchResult() {
    Environment env = Cel.newBuilder().variable("x", CelType.INT).build();
    var program = PolicyCompiler.compile(env, """
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
    var aggregate = PolicyCompiler.compile(env, """
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
    for (String rule : List.of(
        "{}", "{match: [], aggregate: []}", "{match: []}",
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
      assertThrows(rule, CelException.class, () -> PolicyCompiler.compile(env, "name: bad\nrule: " + rule));
    assertThrows(IllegalArgumentException.class, () -> PolicyCompiler.compile(env, "name: ''\nrule: {match: [{output: '1'}]}"));
    assertThrows(CelException.class, () -> PolicyCompiler.compile(env, "name: bad\nimports: [{name: Unknown.Message}]\nrule: {match: [{output: '1'}]}"));
  }

  @Test
  public void declaredInvariantsAreValidatedDataNotImplicitProofs() {
    String yaml = """
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
    assertEquals(List.of("rule.result == true"), PolicyCompiler.invariants(yaml).getFirst().assertions());
    assertTrue(PolicyCompiler.invariants("name: no-properties").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> PolicyCompiler.invariants("verification: {invariants: [{id: a, assert: ['true']}, {id: a, assert: ['true']}]}"));
    assertThrows(IllegalArgumentException.class, () -> PolicyCompiler.invariants("verification: {invariants: [{id: a}]}"));
    assertThrows(IllegalArgumentException.class, () -> new PolicyCompiler.Invariant("", "", List.of(), List.of("true")));
  }
}
