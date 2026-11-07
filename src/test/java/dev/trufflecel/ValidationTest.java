package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.api.source.Source;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * Compile-time validation, constant folding and regular-expression precompilation. Folding is
 * always on; these tests pin its invisibility under the deferred policy (values, categories and
 * budget costs are unchanged) and the every-path rule of strict validation.
 */
public final class ValidationTest {
  private static Environment.Builder strict() {
    return Cel.newBuilder().validation(Environment.Validation.STRICT);
  }

  private static Environment standard(Environment.Validation policy) {
    return Cel.newBuilder()
        .validation(policy)
        .variable("x", CelType.INT)
        .variable("b", CelType.BOOL)
        .variable("s", CelType.STRING)
        .variable("xs", CelType.list(CelType.INT))
        .variable("m", CelType.map(CelType.STRING, CelType.INT))
        .variable("t", CelType.TIMESTAMP)
        .function("same", List.of(CelType.INT), CelType.INT, args -> args.get(0))
        .function(
            "pair", List.of(CelType.INT, CelType.INT), CelType.INT, args -> (Long) args.get(0))
        .build();
  }

  private static CelException rejects(Environment env, String source) {
    return assertThrows(CelException.class, () -> env.compile(source));
  }

  @Test
  public void strictReportsEveryIssueWithPositions() {
    Environment env = standard(Environment.Validation.STRICT);
    CelException e = rejects(env, "[x,\n 1 / 0, 2 % 0]");
    assertEquals(2, e.issues().size());
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, e.code());
    assertEquals(7, e.offset());
    assertEquals(2, e.line());
    assertEquals(4, e.column());
    CelException.Issue second = e.issues().get(1);
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, second.code());
    assertEquals(14, second.offset());
    assertEquals(2, second.line());
    assertEquals(11, second.column());
    assertEquals(
        "division by zero at offset 7 (line 2, column 4); "
            + "division by zero at offset 14 (line 2, column 11)",
        e.getMessage());
    // Parser and checker failures are located as well; the single-issue message is unchanged.
    CelException syntax = rejects(env, "1 +\n+ 2");
    assertEquals(CelException.Code.SYNTAX, syntax.code());
    assertEquals(2, syntax.line());
    assertEquals(3, syntax.column());
    assertTrue(syntax.getMessage(), syntax.getMessage().endsWith("at offset 6 (line 2, column 3)"));
    CelException type = rejects(env, "x + 'a'");
    assertEquals(CelException.Code.TYPE, type.code());
    assertEquals(1, type.line());
    assertEquals(3, type.column());
    assertEquals(1, type.issues().size());
  }

  @Test
  public void strictRejectsFailuresOnEveryPath() {
    Environment env = standard(Environment.Validation.STRICT);
    for (String source :
        List.of(
            "1 / 0",
            "(1 / 0 == 0) ? x : 1",
            "[[1, 2][5], x]",
            "[{'a': 1}.b, x]",
            "xs[1 / 0]",
            "cel.bind(v, 1 / 0, x)",
            "cel.bind(v, x, 1 / 0)",
            "cel.block([x], 1 / 0)",
            "same(1 / 0)",
            "pair(1, 1 / 0)",
            "xs.map(i, i)[1 / 0]",
            "has({'a': 1 / 0}.a) ? x : 1",
            "optional.none().orValue(1 / 0)",
            "(1 / 0 == 0).orValue(true)",
            "[1, 2].all(i, 1 / (i - 1) == 1)",
            "b && 'abc'.matches('(')",
            "b && s.matches('(')",
            "b && timestamp('abc') < t",
            "b || duration('1x') == duration('1s')",
            "[{'a': 1, 'a': 2}, m]",
            "[9223372036854775807 + 1, x]"))
      assertEquals(source, CelException.Code.class, rejects(env, source).code().getClass());
    assertEquals(CelException.Code.INVALID_ARGUMENT, rejects(env, "b && s.matches('(')").code());
    assertEquals(CelException.Code.INVALID_ARGUMENT, rejects(env, "timestamp('abc')").code());
    assertEquals(
        CelException.Code.OVERFLOW,
        rejects(env, "b && duration('99999999999h') == duration('1s')").code());
    assertEquals(CelException.Code.NO_OVERLOAD, rejects(env, "same(dyn(1u))").code());
    assertEquals(CelException.Code.INDEX, rejects(env, "[[1, 2][5], x]").code());
    assertEquals(CelException.Code.OVERFLOW, rejects(env, "[9223372036854775807 + 1, x]").code());
    // An invalid constant pattern inside a constant call is reported once; a failing operand of
    // such a call is a separate finding.
    assertEquals(1, rejects(env, "'abc'.matches('(')").issues().size());
    assertEquals(2, rejects(env, "(1 / 0 == 0 ? 'a' : 'b').matches('(')").issues().size());
    // Strict unchecked compilation of a sound program succeeds; literal validators only apply
    // to one-argument conversions of string literals.
    assertEquals(2L, env.parse("x + 1").evaluate(Map.of("x", 1L)));
    assertEquals(Instant.ofEpochSecond(1), env.compile("timestamp(1)").evaluate());
    assertEquals(
        Instant.EPOCH, env.compile("timestamp(s)").evaluate(Map.of("s", "1970-01-01T00:00:00Z")));
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(CelException.class, () -> env.parse("duration('1s', 2)")).code());
    // The recheck after folding sees the folded value's precise type.
    assertEquals(CelException.Code.TYPE, rejects(env, "xs[dyn('a')]").code());
    assertEquals(CelException.Code.TYPE, rejects(env, "m[dyn(1)]").code());
    // Unchecked compilation still reports every-path failures, including host overload arity.
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(CelException.class, () -> env.parse("same(1, 2)")).code());
  }

  @Test
  public void strictRespectsLazinessAndUnknownPrecedence() {
    Environment env = standard(Environment.Validation.STRICT);
    var lazy = new LinkedHashMap<String, Object>();
    lazy.put("x > 0 && 1 / 0 == 0", false);
    lazy.put("!b || 1 / 0 == 0", true);
    lazy.put("b ? 1 / 0 : x", 0L);
    // A sibling that is not constant keeps a failing operand on a lazy path.
    lazy.put("[1, 2][5] + x > 0 || true", true);
    lazy.put("xs.exists(i, 1 / 0 == 0)", false);
    lazy.put("xs.map(i, 1 / 0).size()", 0L);
    lazy.put("optional.of(x).orValue(1 / 0)", 0L);
    lazy.put("optional.of(x).or(optional.of(1 / 0)).value()", 0L);
    lazy.put("cel.block([1 / 0], x)", 0L);
    lazy.put("[1].all(i, 1 / 0 == 0) || true", true);
    lazy.put("has(dyn(x).a) || true", true);
    lazy.put("b && s.matches('^a$')", false);
    // The sibling may be unknown, in which case the unknown wins over the error.
    lazy.put("x + 1 / 0 == 1", Unknown.of("x"));
    lazy.put("pair(x, 1 / 0)", Unknown.of("x"));
    lazy.put("same(x)", Unknown.of("x"));
    lazy.forEach(
        (source, expected) -> {
          Program program = env.compile(source);
          Object value = expected instanceof Unknown ? Unknown.of("x") : 0L;
          Map<String, Object> bindings =
              Map.of("x", value, "b", false, "s", "", "xs", List.of(), "m", Map.of());
          assertEquals(source, expected, program.evaluate(bindings));
        });
    // Deferred failures keep their evaluation category when the lazy path is taken after all.
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(
                CelException.class,
                () -> env.compile("x > 0 && 1 / 0 == 0").evaluate(Map.of("x", 1L)))
            .code());
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(
                CelException.class, () -> env.compile("x + 1 / 0 == 1").evaluate(Map.of("x", 1L)))
            .code());
  }

  @Test
  public void deferredPolicyKeepsEvaluationSemantics() {
    Environment env = standard(Environment.Validation.DEFERRED);
    assertEquals(false, env.compile("false && (1 / 0 == 0)").evaluate());
    assertEquals(List.of(2L, 4L, 6L), env.compile("[1, 2, 3].map(i, i * 2)").evaluate());
    assertEquals(Map.of("a", List.of(1L, 2L)), env.compile("{'a': [1, 2]}").evaluate(Map.of()));
    assertEquals(
        Instant.parse("2020-01-01T00:00:00Z"),
        env.compile("timestamp('2020-01-01T00:00:00Z')").evaluate());
    assertEquals(Duration.ofSeconds(90), env.compile("duration('1m30s')").evaluate());
    assertEquals(Instant.ofEpochSecond(1), env.compile("timestamp(1)").evaluate());
    assertEquals(
        Instant.EPOCH, env.compile("timestamp(s)").evaluate(Map.of("s", "1970-01-01T00:00:00Z")));
    // Comprehension sizes over variable ranges keep their counting lowering.
    assertEquals(
        5L,
        env.compile(
                "size(xs.filter(i, i > 1)) + size(xs.transformList(i, v, v)) + size(xs.map(i, i))")
            .evaluate(Map.of("xs", List.of(1L, 2L))));
    assertEquals(2L, env.compile("size(xs.sortBy(i, i))").evaluate(Map.of("xs", List.of(2L, 1L))));
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(CelException.class, () -> env.parse("size(x, 1)").evaluate(Map.of("x", 1L)))
            .code());
    assertEquals(3L, env.compile("size(b'abc' + b'')").evaluate());
    assertEquals(1L, env.compile("optional.of(1).value()").evaluate());
    assertEquals(false, env.compile("optional.none().hasValue()").evaluate());
    assertEquals(CelType.INT, env.compile("type(1)").evaluate());
    CelException e =
        assertThrows(CelException.class, () -> env.compile("'abc'.matches('(')").evaluate());
    assertEquals(CelException.Code.INVALID_ARGUMENT, e.code());
    assertEquals("invalid regular expression", e.issues().get(0).message());
    assertTrue(e.expressionId() > 0);
    assertEquals(1, e.line());
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(CelException.class, () -> env.compile("same(dyn(1u))").evaluate()).code());
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(CelException.class, () -> env.parse("dyn(1).matches('a')").evaluate()).code());
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(CelException.class, () -> env.compile("[1 / 0]").evaluate()).code());
    // Prepared templates carry folds; every instantiation and evaluation agrees.
    PreparedProgram prepared = env.prepare("[1, 2, 3].map(i, i * 2).size() + x");
    for (int i = 0; i < 2; i++) {
      Program program = prepared.instantiate();
      assertEquals(4L, program.evaluate(Map.of("x", 1L)));
      assertEquals(5L, program.evaluate(Map.of("x", 2L)));
    }
    // Explicit unknown constants and host functions are never folded.
    Environment unknowns =
        Cel.newBuilder()
            .constant("u", Unknown.of("u"))
            .function("same", List.of(CelType.DYN), CelType.DYN, args -> args.get(0))
            .build();
    assertEquals(Unknown.of("u"), unknowns.compile("u + 1").evaluate());
    assertEquals(Unknown.of("u"), unknowns.compile("same(u)").evaluate());
    int[] calls = new int[1];
    Environment host =
        Cel.newBuilder()
            .function(
                "count",
                List.of(CelType.INT),
                CelType.INT,
                args -> {
                  calls[0]++;
                  return args.get(0);
                })
            .function("size", List.of(CelType.INT), CelType.INT, args -> -1L)
            .build();
    Program counted = host.compile("count(1 + 1)");
    assertEquals(0, calls[0]);
    assertEquals(2L, counted.evaluate());
    assertEquals(2L, counted.evaluate());
    assertEquals(2, calls[0]);
    // A host binding named like a standard, time or extension function keeps that dispatch when
    // unchecked, so its declared signature is not checked statically.
    assertEquals(3L, host.parse("size('abc')").evaluate());
    Environment shadowing =
        Cel.newBuilder()
            .validation(Environment.Validation.STRICT)
            .function("getLabel", List.of(CelType.INT), CelType.INT, args -> args.get(0))
            .function("reverse", List.of(CelType.INT), CelType.INT, args -> args.get(0))
            .build();
    for (String source : List.of("getLabel('a')", "reverse(1)"))
      assertEquals(
          source,
          CelException.Code.NO_OVERLOAD,
          assertThrows(CelException.class, () -> shadowing.parse(source).evaluate()).code());
  }

  @Test
  public void foldedSubtreesChargeTheOriginalBudget() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        for (String source :
            List.of(
                "1 + 2 + 3",
                "(1 + 2) + x + (3 + 4)",
                "[1, 2, 3].map(i, i * 2).size()",
                "size(xs.map(i, [1, 2][0]))",
                "'abc'.matches('^a') && x > 0",
                "s.matches('(') || b",
                "{'a': [1, 2]}.a[1] + x",
                "[1, 2].exists(i, i == 2) && b",
                "cel.bind(v, 1 + 1, v + x)",
                "x + 1 / 0",
                "x > 0 || 1 / 0 == 0",
                "same(dyn(1u)) + x",
                "[1, 2, 3, 4, 5, 6, 7, 8].map(i, i + 1).size() + x")) {
          var folded = new ArrayList<Object>();
          var instrumented = new ArrayList<Object>();
          for (long budget = 1; budget <= 48; budget++) {
            Environment env =
                Cel.newBuilder()
                    .evaluationBudget(budget)
                    .variable("x", CelType.INT)
                    .variable("b", CelType.BOOL)
                    .variable("s", CelType.STRING)
                    .variable("xs", CelType.list(CelType.INT))
                    .function("same", List.of(CelType.INT), CelType.INT, args -> args.get(0))
                    .build();
            Map<String, Object> bindings =
                Map.of("x", 1L, "b", true, "s", "abc", "xs", List.of(1L, 2L));
            folded.add(outcome(env.compile(source), bindings));
            Source truffleSource = Source.newBuilder("cel", source, "budget.cel").build();
            instrumented.add(outcome(env.compile(truffleSource), bindings));
          }
          assertEquals(source, instrumented, folded);
          assertNotEquals(source, folded.get(0), folded.get(folded.size() - 1));
        }
      } finally {
        context.leave();
      }
    }
  }

  private static Object outcome(Program program, Map<String, Object> bindings) {
    try {
      return program.evaluate(bindings);
    } catch (CelException e) {
      return e.code();
    }
  }

  @Test
  public void foldingIsBounded() {
    Environment env = Cel.newBuilder().variable("x", CelType.INT).build();
    // Large results are computed but not embedded; the evaluation still produces them.
    ExprNode large = new Parser("lists.range(5000)", env).parse();
    env.prepare("lists.range(5000)", large, true);
    assertNull(large.fold);
    ExprNode small = new Parser("lists.range(5000).size() + x", env).parse();
    env.prepare("lists.range(5000).size() + x", small, true);
    assertEquals(5000L, small.children[0].fold.value());
    assertEquals(5000L, env.compile("size(lists.range(5000))").evaluate());
    // Plan-time work is bounded by one evaluation budget in total, after which nothing folds.
    String heavy = "[lists.range(600000), lists.range(600000)].size() + (1 + 1)";
    ExprNode tree = new Parser(heavy, env).parse();
    env.prepare(heavy, tree, true);
    assertNull(tree.fold);
    assertNull(tree.children[1].fold);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> env.compile(heavy).evaluate()).code());
    // Exhausting the budget while folding leaves the subtree to evaluation.
    Environment tiny = Cel.newBuilder().evaluationBudget(4).build();
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> tiny.compile("1 + 2 + 3 + 4").evaluate()).code());
    assertEquals(
        10L, Cel.newBuilder().evaluationBudget(7).build().compile("1 + 2 + 3 + 4").evaluate());
  }

  @Test
  public void constantPatternsArePrecompiledAndDynamicPatternsAreCached() throws Exception {
    Environment env = standard(Environment.Validation.DEFERRED);
    Program program = env.compile("s.matches('^a+$')");
    assertEquals(true, program.evaluate(Map.of("s", "aaa")));
    assertEquals(false, program.evaluate(Map.of("s", "ab")));
    assertEquals(Unknown.of("s"), program.evaluate(Map.of("s", Unknown.of("s"))));
    Program invalid = env.compile("s.matches('(')");
    assertEquals(
        CelException.Code.INVALID_ARGUMENT,
        assertThrows(CelException.class, () -> invalid.evaluate(Map.of("s", "a"))).code());
    assertEquals(Unknown.of("s"), invalid.evaluate(Map.of("s", Unknown.of("s"))));
    // Operand failures win over the pattern, as they are evaluated first.
    assertEquals(
        CelException.Code.NO_SUCH_KEY,
        assertThrows(
                CelException.class,
                () -> env.compile("string(m['k']).matches('(')").evaluate(Map.of("m", Map.of())))
            .code());
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(
                CelException.class, () -> env.parse("x.matches('a')").evaluate(Map.of("x", 1L)))
            .code());
    assertEquals(true, env.compile("matches('abc', 'b')").evaluate());
    assertEquals(true, env.compile("(s + '').matches(s + '$')").evaluate(Map.of("s", "ab")));
    for (String source : List.of("s.matches(1)", "matches('a')", "duration('1s', 2)"))
      assertEquals(
          source,
          CelException.Code.NO_OVERLOAD,
          assertThrows(CelException.class, () -> env.parse(source).evaluate(Map.of("s", "a")))
              .code());
    // Dynamic patterns: bounded cache, oversized keys bypass it, syntax failures are categorized.
    Environment dyn =
        Cel.newBuilder().variable("s", CelType.STRING).variable("p", CelType.STRING).build();
    Program dynamicProgram = dyn.compile("s.matches(p)");
    for (int i = 0; i < 300; i++)
      assertEquals(
          true, dynamicProgram.evaluate(Map.of("s", "a".repeat(i), "p", "^a{" + i + "}$")));
    assertTrue(Regex.cacheSize() <= 256);
    int size = Regex.cacheSize();
    assertEquals(
        true, dynamicProgram.evaluate(Map.of("s", "a", "p", "^a" + "b?".repeat(2500) + "$")));
    assertEquals(size, Regex.cacheSize());
    assertEquals(
        CelException.Code.INVALID_ARGUMENT,
        assertThrows(CelException.class, () -> dynamicProgram.evaluate(Map.of("s", "a", "p", "(")))
            .code());
    // Instrumented programs keep the precompiled pattern under their wrappers.
    try (Context context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        Source source = Source.newBuilder("cel", "s.matches('^a+$')", "regex.cel").build();
        assertEquals(true, env.compile(source).evaluate(Map.of("s", "aa")));
        assertEquals(
            true, env.prepare("s.matches('^a+$')").instantiate(source).evaluate(Map.of("s", "aa")));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void durationAndTimeZoneParsingWithoutRegularExpressions() {
    Environment env = standard(Environment.Validation.DEFERRED);
    var durations = new LinkedHashMap<String, Duration>();
    durations.put("1.5s", Duration.ofMillis(1500));
    durations.put(".5s", Duration.ofMillis(500));
    durations.put("1.s", Duration.ofSeconds(1));
    durations.put(
        "1h2m3s4ms5us6ns",
        Duration.ofHours(1).plusMinutes(2).plusSeconds(3).plusMillis(4).plusNanos(5006));
    durations.put("+1µs", Duration.ofNanos(1000));
    durations.put("-1μs", Duration.ofNanos(-1000));
    durations.put("0", Duration.ZERO);
    durations.forEach(
        (text, expected) ->
            assertEquals(text, expected, env.compile("duration(s)").evaluate(Map.of("s", text))));
    for (String text : List.of("", "s", "1", "1.5", ".s", "1x", "1s2", "1ss", "1 s", "+", "1.5.5s"))
      assertEquals(
          text,
          CelException.Code.INVALID_ARGUMENT,
          assertThrows(
                  CelException.class, () -> env.compile("duration(s)").evaluate(Map.of("s", text)))
              .code());
    Program hours = env.compile("t.getHours(s)");
    Instant noon = Instant.parse("2020-01-01T12:00:00Z");
    assertEquals(17L, hours.evaluate(Map.of("t", noon, "s", "05:30")));
    assertEquals(6L, hours.evaluate(Map.of("t", noon, "s", "-05:30")));
    assertEquals(12L, hours.evaluate(Map.of("t", noon, "s", "UTC")));
    for (String zone : List.of("Not/AZone", "ab:cd", "0a:30", "05:x0", "05:3x", "05-30", "5:30"))
      assertEquals(
          zone,
          CelException.Code.INVALID_ARGUMENT,
          assertThrows(CelException.class, () -> hours.evaluate(Map.of("t", noon, "s", zone)))
              .code());
  }

  @Test
  public void homogeneousLiteralValidator() {
    for (Environment.Validation policy : Environment.Validation.values()) {
      Environment env =
          Cel.newBuilder()
              .validation(policy)
              .homogeneousLiterals(true)
              .variable("x", CelType.INT)
              .build();
      CelException list = rejects(env, "[1, 'a']");
      assertEquals(CelException.Code.TYPE, list.code());
      assertEquals(4, list.offset());
      assertEquals(
          "heterogeneous list element: expected int, found string", list.issues().get(0).message());
      CelException map = rejects(env, "{1: 'a', 'b': 2}");
      assertEquals(2, map.issues().size());
      assertEquals(9, map.issues().get(0).offset());
      assertEquals(14, map.issues().get(1).offset());
      assertEquals(CelException.Code.TYPE, rejects(env, "[[1], ['a']]").code());
      assertEquals(CelException.Code.TYPE, rejects(env, "[?optional.of(1), 'a']").code());
      for (String source :
          List.of(
              "[1, dyn('a'), x]",
              "[?optional.of(1), 2]",
              "{'a': 1, 'b': x}",
              "[]",
              "{}",
              "[[1], [2]]",
              "[1.0, 2.0]")) assertNotNull(source, env.compile(source).evaluate(Map.of("x", 1L)));
    }
    assertEquals(List.of(1L, "a"), Cel.compile("[1, 'a']").evaluate());
  }

  @Test
  public void comprehensionNestingValidator() {
    Environment env =
        Cel.newBuilder().maxComprehensionNesting(1).variable("x", CelType.INT).build();
    CelException nested = rejects(env, "[1].all(i, [2].all(j, j > i))");
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, nested.code());
    assertEquals(11, nested.offset());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        rejects(env, "cel.bind(v, 1, [1].all(i, i > v))").code());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        rejects(env, "[1].map(i, [2].filter(j, j > i))").code());
    for (String source :
        List.of(
            "[1].all(i, i > 0) && [2].exists(j, j > 1)",
            "optional.of(1).optMap(v, [1].all(i, i > v)).value()",
            "has({'a': 1}.a)",
            "[x].sortBy(i, i)"))
      assertNotNull(source, env.compile(source).evaluate(Map.of("x", 1L)));
    assertEquals(true, Cel.compile("[1].all(i, [2].all(j, [3].all(k, k > j)))").evaluate());
    // More than eight simultaneously bound variables grow the analysis scope.
    String chain = "a + b + c + d + e + f + g + h + i";
    for (String name : List.of("i", "h", "g", "f", "e", "d", "c", "b", "a"))
      chain = "cel.bind(" + name + ", x, " + chain + ")";
    assertEquals(9L, Cel.compile(chain).evaluate(Map.of("x", 1L)));
    assertEquals(9L, Cel.compile(chain.replace("x,", "1,")).evaluate());
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().maxComprehensionNesting(0));
    assertThrows(NullPointerException.class, () -> Cel.newBuilder().validation(null));
  }

  @Test
  public void issuesAndLocations() {
    assertThrows(IllegalArgumentException.class, () -> new CelException(List.of()));
    assertThrows(
        NullPointerException.class, () -> new CelException.Issue(null, "message", 0, -1, -1));
    var issue = new CelException.Issue(CelException.Code.TYPE, "bad", 4, -1, -1);
    assertEquals("bad at offset 4", issue.toString());
    var located = issue.locate("ab\ncd");
    assertEquals(2, located.line());
    assertEquals(2, located.column());
    assertSame(located, located.locate("ab\ncd"));
    assertEquals("bad at offset 4 (line 2, column 2)", located.toString());
    assertSame(issue, issue.locate("ab"));
    var unknown = new CelException.Issue(CelException.Code.TYPE, "bad", -1, -1, -1);
    assertSame(unknown, unknown.locate("ab"));
    assertEquals(
        "bad", new CelException.Issue(CelException.Code.TYPE, "bad", -1, -1, -1).toString());
    CelException runtime = new CelException(CelException.Code.OVERFLOW, "overflow");
    assertSame(runtime, runtime.locate("1 + 1"));
    assertEquals(-1, runtime.line());
    assertEquals(-1, runtime.column());
    assertEquals(-1, runtime.offset());
    assertEquals(
        List.of(new CelException.Issue(CelException.Code.OVERFLOW, "overflow", -1, -1, -1)),
        runtime.issues());
    CelException end = new CelException(CelException.Code.SYNTAX, "unexpected end", 2).locate("1+");
    assertEquals(1, end.line());
    assertEquals(3, end.column());
  }
}
