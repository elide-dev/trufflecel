package dev.trufflecel;

import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.math.BigInteger;
import org.junit.Test;
import org.graalvm.polyglot.Context;
import com.oracle.truffle.api.source.Source;
import static org.junit.Assert.*;

public class RuntimeTest {
  @Test
  public void elideContract() {
    var p = Cel.compile("user.age >= 18 && 'admin' in user.roles");
    assertEquals(Set.of("user"), p.variables());
    assertTrue(p.test(Map.of("user", Map.of("age", 30, "roles", List.of("admin")))));
    assertFalse(p.test(Map.of("user", Map.of("age", 12, "roles", List.of("admin")))));
    assertEquals(7L, Cel.compile("1 + 2 * 3").evaluate());
    assertEquals("1 + 2 * 3", Cel.compile("1 + 2 * 3").source());
    assertNull(Cel.compile("null").evaluate());
    assertArrayEquals(
        new byte[] {1, 2}, (byte[]) Cel.compile("b").evaluate(Map.of("b", new byte[] {1, 2})));
    assertTrue(
        Cel.compile("xs.all(x, type(x) == int)")
            .test(Map.of("xs", List.of(1, (short) 2, (byte) 3))));
    assertEquals(1.5, Cel.compile("x").evaluate(Map.of("x", 1.5f)));
    assertEquals(List.of(1L, 2L), Cel.compile("x").evaluate(Map.of("x", new Object[] {1, 2})));
    assertEquals(
        Arrays.asList(null, Map.of("k", List.of(2L))),
        Cel.compile("[null, {'k': [2]}]").evaluate());
    assertThrows(CelException.class, () -> Cel.compile("1").test());
    assertThrows(CelException.class, () -> Cel.compile("missing", Set.of("allowed")));
    assertThrows(CelException.class, () -> Cel.compile("missing").evaluate());
  }

  @Test
  public void nestedScopesAndConcurrentReuse() throws Exception {
    var p = Cel.compile("x == 1 && [2].exists(x, x == 2) && ys.map(y, y + x).size() == 1");
    assertEquals(Set.of("x", "ys"), p.variables());
    try (var executor = Executors.newFixedThreadPool(8)) {
      var calls = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 200; i++) {
        final int n = i;
        calls.add(() -> p.test(Map.of("x", 1, "ys", List.of(n))));
      }
      for (var result : executor.invokeAll(calls)) assertTrue(result.get());
    }
    assertTrue(Cel.compile("[1,2].all(x, [3,4].all(x, x > 2))").test());
  }

  @Test
  public void localSlotsRespectRangesShadowingAndPreparedSources() throws Exception {
    var environment = Cel.newBuilder().variable("x", CelType.INT).build();
    String expression =
        "[1,2].map(x, [x,x+1].transformList(i,x, optional.of(x).optMap(y,y+i+.x).value()))";
    var prepared = environment.prepare(expression);
    try (Context context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        Source source = Source.newBuilder("cel", expression, "locals.cel").build();
        for (Program program :
            List.of(
                prepared.instantiate(),
                prepared.instantiate(source),
                environment.compile(source))) {
          assertEquals(
              List.of(List.of(11L, 13L), List.of(12L, 14L)), program.evaluate(Map.of("x", 10L)));
          assertEquals(
              List.of(List.of(21L, 23L), List.of(22L, 24L)), program.evaluate(Map.of("x", 20L)));
        }
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void reentrantEvaluationsKeepIndependentBindingsAndBudgets() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        for (boolean observable : new boolean[] {false, true}) {
          Program[] current = new Program[1];
          var environment =
              Cel.newBuilder()
                  .variable("x", CelType.INT)
                  .evaluationBudget(12)
                  .function(
                      "recur",
                      List.of(CelType.INT),
                      CelType.INT,
                      args -> current[0].evaluate(Map.of("x", args.get(0))))
                  .build();
          String expression = "x == 0 ? 0 : recur(x - 1) + x";
          current[0] =
              observable
                  ? environment.compile(
                      Source.newBuilder("cel", expression, "reentrant.cel").build())
                  : environment.compile(expression);
          assertEquals(55L, current[0].evaluate(Map.of("x", 10L)));
          assertEquals(6L, current[0].evaluate(Map.of("x", 3L)));
          String excessive = "x == 0 ? 0 : recur(x - 1) + x + x + x";
          var limited =
              observable
                  ? environment.compile(Source.newBuilder("cel", excessive, "budget.cel").build())
                  : environment.compile(excessive);
          assertEquals(
              CelException.Code.RESOURCE_EXHAUSTED,
              assertThrows(CelException.class, () -> limited.evaluate(Map.of("x", 1L))).code());
          assertEquals(3L, current[0].evaluate(Map.of("x", 2L)));
        }
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void hostEntryRetainsTruffleFramesAcrossReentryAndFailures() {
    Program[] programs = new Program[2];
    var innerEnvironment =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .function(
                "inspect",
                List.of(),
                CelType.INT,
                args -> {
                  var frames = new ArrayList<com.oracle.truffle.api.CallTarget>();
                  com.oracle.truffle.api.Truffle.getRuntime()
                      .iterateFrames(
                          frame -> {
                            var target = frame.getCallTarget();
                            if (target == programs[0].callTarget()
                                || target == programs[1].callTarget()) frames.add(target);
                            return null;
                          });
                  assertEquals(List.of(programs[0].callTarget(), programs[1].callTarget()), frames);
                  return 10L;
                })
            .build();
    programs[0] = innerEnvironment.compile("inspect() / x");
    programs[1] =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .function(
                "nested",
                List.of(CelType.INT),
                CelType.INT,
                args -> programs[0].evaluate(Map.of("x", args.get(0))))
            .build()
            .compile("nested(x)");
    for (long x : new long[] {2, 0, 5}) {
      if (x == 0)
        assertEquals(
            CelException.Code.DIVIDE_BY_ZERO,
            assertThrows(CelException.class, () -> programs[1].evaluate(Map.of("x", x))).code());
      else assertEquals(10L / x, programs[1].evaluate(Map.of("x", x)));
      assertNull(
          com.oracle.truffle.api.Truffle.getRuntime()
              .iterateFrames(
                  frame ->
                      frame.getCallTarget() == programs[0].callTarget()
                              || frame.getCallTarget() == programs[1].callTarget()
                          ? frame
                          : null));
    }
  }

  @Test
  public void frameLocalsSurviveReentrantEvaluationAndNestedRanges() {
    Program[] current = new Program[1];
    var environment =
        Cel.newBuilder()
            .variable("n", CelType.INT)
            .evaluationBudget(20)
            .function(
                "recur",
                List.of(CelType.INT),
                CelType.INT,
                args -> current[0].evaluate(Map.of("n", args.get(0))))
            .build();
    var prepared = environment.prepare("n == 0 ? 0 : [n].map(x, recur(x - 1) + x)[0]");
    current[0] = prepared.instantiate();
    assertEquals(55L, current[0].evaluate(Map.of("n", 10L)));
    assertEquals(6L, current[0].evaluate(Map.of("n", 3L)));
    assertEquals(
        List.of(List.of(2L, 3L), List.of(4L, 5L)),
        Cel.compile("[1,2].map(x, [x,x+1].map(y,y+x))").evaluate());
    assertEquals(
        List.of(List.of(10L, 20L), List.of(10L, 20L)),
        Cel.compile("[1,2].map(x, [10,20].map(x,x))").evaluate());
  }

  @Test
  public void deepScopesRestoreShadowedAndAbsoluteBindings() {
    String nested = "x == 6 && .x == 99";
    for (int i = 6; i >= 1; i--) nested = "[" + i + "].all(x, " + nested + ") && x == " + (i - 1);
    var p = Cel.compile("[0].all(x, " + nested + ") && x == 99");
    assertTrue(p.test(Map.of("x", 99L)));
    assertFalse(p.test(Map.of("x", 98L)));
    assertTrue(Cel.compile("[null].all(x, x == null)").test());
    assertNull(Cel.compile("x").evaluate(Collections.singletonMap("x", null)));
    assertThrows(CelException.class, () -> Cel.compile(".x").evaluate());
  }

  @Test
  public void numericEqualityPreservesSpecialValuesAndMixedPrecision() {
    var equal = Cel.compile("x == y");
    for (Object[] pair :
        List.of(
            new Object[] {Long.MIN_VALUE, Long.MIN_VALUE},
            new Object[] {new UInt(-1L), new UInt(-1L)},
            new Object[] {-0.0, 0.0},
            new Object[] {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
            new Object[] {42L, new UInt(42L)}))
      assertTrue(equal.test(Map.of("x", pair[0], "y", pair[1])));
    for (Object[] pair :
        List.of(
            new Object[] {Long.MAX_VALUE, Long.MAX_VALUE - 1},
            new Object[] {new UInt(-1L), new UInt(-2L)},
            new Object[] {Double.NaN, Double.NaN},
            new Object[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
            new Object[] {9007199254740993L, 9007199254740992.0},
            new Object[] {-1L, new UInt(-1L)}))
      assertFalse(equal.test(Map.of("x", pair[0], "y", pair[1])));
  }

  @Test
  public void customEqualityCanOverridePrimitivesAndWellKnownConstructors() {
    var builder = Cel.newBuilder().variable("a", CelType.INT).variable("b", CelType.INT);
    var standard = builder.build();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    builder.objectType(
        new ObjectType() {
          @Override
          public String name() {
            return "google.protobuf.Int64Value";
          }

          @Override
          public Map<String, CelType> fields() {
            return Map.of("value", CelType.INT);
          }

          @Override
          public Object construct(Map<String, Object> fields) {
            return fields.getOrDefault("value", 0L);
          }

          @Override
          public Object get(Object value, String field) {
            return value;
          }

          @Override
          public boolean has(Object value, String field) {
            return field.equals("value");
          }

          @Override
          public boolean accepts(Object value) {
            return value instanceof Long;
          }

          @Override
          public boolean equal(Object a, Object b) {
            calls.incrementAndGet();
            return (Long) a % 10 == (Long) b % 10;
          }
        });
    var custom = builder.build();
    var input = Map.of("a", 1001L, "b", 2001L);
    assertFalse(standard.compile("a == b").test(input));
    assertTrue(standard.compile("a != b").test(input));
    assertTrue(custom.compile("a == b").test(input));
    assertFalse(custom.compile("a != b").test(input));
    assertFalse(custom.compile("a == b").test(Map.of("a", 1001L, "b", 1002L)));
    assertTrue(custom.compile("a != b").test(Map.of("a", 1001L, "b", 1002L)));
    assertTrue(custom.compile("google.protobuf.Int64Value{value: 11} == 21").test());
    assertEquals(5, calls.get());
    assertFalse(standard.compile("google.protobuf.Int64Value{value: 11} == 21").test());
    assertEquals(5, calls.get());
  }

  @Test
  public void inferredDeclarationsDoNotMutateSharedCompilerConfiguration() {
    var builder = Cel.newBuilder().variable("base", CelType.INT).inferVariables(true);
    var environment = builder.build();
    builder.variable("later", CelType.STRING);
    var declared = environment.prepare("base + 1").instantiate();
    var first = environment.compile("base + first");
    var second = environment.prepare("base + second").instantiate();
    assertEquals(Set.of("base"), declared.variables());
    assertEquals(Set.of("base", "first"), first.variables());
    assertEquals(Set.of("base", "second"), second.variables());
    assertEquals(Set.of("base"), environment.compile("base").variables());
    assertEquals(3L, first.evaluate(Map.of("base", 1L, "first", 2L)));
    assertEquals(4L, second.evaluate(Map.of("base", 1L, "second", 3L)));
    assertThrows(UnsupportedOperationException.class, () -> declared.variables().add("extra"));
  }

  @Test
  public void singleEntryInputsWorkWithLargerDeclarationEnvironments() {
    var environment =
        Cel.newBuilder()
            .variable("unused", CelType.DYN)
            .variable("x", CelType.DYN)
            .variable("y", CelType.DYN)
            .build();
    for (Program program :
        List.of(environment.compile("x"), environment.prepare("x").instantiate())) {
      assertEquals(Set.of("unused", "x", "y"), program.variables());
      assertEquals(3L, program.evaluate(Map.of("x", 3)));
      assertNull(program.evaluate(Collections.singletonMap("x", null)));
      assertEquals(
          CelException.Code.UNDECLARED,
          assertThrows(CelException.class, () -> program.evaluate(Map.of("y", 3))).code());
    }
    assertEquals(4L, environment.compile(".x + 1").evaluate(Map.of("x", 3)));
    // The first syntactic identifier can be local; the input must still bind the global y.
    assertEquals(
        List.of(4L, 5L), environment.compile("[1,2].map(x, x + y)").evaluate(Map.of("y", 3)));
  }

  @Test
  public void explicitSingletonSnapshotsIsolateMutationDuringEvaluation() {
    var values = new ArrayList<>(List.of(1L));
    var input = new HashMap<String, Object>();
    input.put("xs", values);
    var env =
        Cel.newBuilder()
            .variable("xs", CelType.list(CelType.INT))
            .function(
                "mutate",
                List.of(),
                CelType.BOOL,
                ignored -> {
                  values.set(0, 2L);
                  input.put("xs", List.of(3L));
                  return true;
                })
            .build();
    var program = env.compile("mutate() && xs[0] == 1");
    assertTrue(program.test(Activation.snapshot(input)));
    assertFalse(program.test(Activation.snapshot(input)));
    assertEquals(Set.of("one"), Cel.compile("one").variables());
    assertEquals(Set.of("two"), Cel.compile("two").variables());
  }

  @Test
  public void borrowedBindingsDoNotTraverseUnmatchedInputs() {
    var environment =
        Cel.newBuilder()
            .variable("a", CelType.DYN)
            .variable("b", CelType.DYN)
            .variable("c", CelType.DYN)
            .variable("d", CelType.DYN)
            .build();
    var recursive = new ArrayList<Object>();
    recursive.add(recursive);
    for (Program program :
        List.of(environment.compile("a + b"), environment.prepare("a + b").instantiate())) {
      assertEquals(3L, program.evaluate(Map.of("a", 1L, "b", 2L)));
      assertEquals(3L, program.evaluate(Map.of("a", 1, "b", 2)));
      assertEquals(3L, program.evaluate(Map.of("a", 1L, "b", 2L, "extra", true)));
      for (String key : List.of("c", "extra")) {
        var inputs = Map.of("a", 1L, "b", 2L, key, recursive);
        assertEquals(3L, program.evaluate(inputs));
        assertEquals(
            CelException.Code.RESOURCE_EXHAUSTED,
            assertThrows(CelException.class, () -> Activation.snapshot(inputs)).code());
      }
      assertEquals(
          CelException.Code.UNDECLARED,
          assertThrows(CelException.class, () -> program.evaluate(Map.of("a", 1L, "extra", 2L)))
              .code());
    }
  }

  @Test
  public void scalarActivationSnapshotsSurviveMutationDuringEvaluation() {
    var input = new HashMap<String, Object>();
    input.put("a", 1L);
    input.put("b", 2L);
    var environment =
        Cel.newBuilder()
            .inferVariables(true)
            .function(
                "mutate",
                List.of(),
                CelType.BOOL,
                ignored -> {
                  input.put("a", 11L);
                  input.put("b", 12L);
                  return true;
                })
            .build();
    var program = environment.compile("mutate() && a == 1 && b == 2");
    assertTrue(program.test(Activation.snapshot(input)));
    assertFalse(program.test(Activation.snapshot(input)));
    assertTrue(
        environment
            .compile("a == 1.5 && b == 'x' && enabled")
            .test(Map.of("a", 1.5, "b", "x", "enabled", true)));
    assertEquals(
        15L,
        environment
            .compile("a + b + c + d + e")
            .evaluate(Map.of("a", 1L, "b", 2L, "c", 3L, "d", 4L, "e", 5L)));
  }

  @Test
  public void activationsPreserveMissingBindingsNullsAndSnapshots() {
    var values = new ArrayList<>(List.of(1L));
    var env =
        Cel.newBuilder()
            .inferVariables(true)
            .function(
                "mutate",
                List.of(),
                CelType.BOOL,
                ignored -> {
                  values.set(0, 2L);
                  return true;
                })
            .build();
    var prepared = env.prepare("mutate() && xs[0] == expected");
    var first = prepared.instantiate();
    var second = prepared.instantiate();
    assertTrue(first.test(Activation.snapshot(Map.of("xs", values, "expected", 1L))));
    assertTrue(second.test(Activation.snapshot(Map.of("xs", values, "expected", 2L))));
    assertFalse(first.test(Activation.snapshot(Map.of("xs", values, "expected", 1L))));
    var input = new LinkedHashMap<String, Object>();
    input.put("a", null);
    input.put("b", 1L);
    var nullCheck = Cel.compile("a == null && b == 1");
    assertTrue(nullCheck.test(input));
    input.put("unused", 4L);
    assertTrue(nullCheck.test(input));
    input.remove("b");
    assertEquals(
        CelException.Code.UNDECLARED,
        assertThrows(CelException.class, () -> nullCheck.evaluate(input)).code());
    assertEquals(
        CelException.Code.UNDECLARED,
        assertThrows(CelException.class, () -> Cel.compile("a + b").evaluate(Map.of("a", 1L)))
            .code());
    assertTrue(
        Cel.compile("[1].all(x, x == 1 && .x == y) && x == y").test(Map.of("x", 9L, "y", 9L)));
    assertEquals(
        10L, Cel.compile("a + b + c + d").evaluate(Map.of("a", 1, "b", 2, "c", 3, "d", 4)));
  }

  @Test
  public void normalizedListSnapshotsAreIndependentImmutableAndBounded() {
    byte[] bytes = {1, 2};
    var nested = new ArrayList<>(List.of(3));
    var input = new ArrayList<Object>(Arrays.asList(null, 1, 2.5f, bytes, nested));
    var snapshot = (List<?>) Values.in(input);
    input.clear();
    bytes[0] = 9;
    nested.set(0, 4);
    assertEquals(CelNull.INSTANCE, snapshot.get(0));
    assertEquals(1L, snapshot.get(1));
    Object[] copy = snapshot.toArray();
    copy[1] = 99L;
    assertEquals(1L, snapshot.get(1));
    assertEquals(2.5, snapshot.get(2));
    assertArrayEquals(new byte[] {1, 2}, (byte[]) Values.out(snapshot.get(3)));
    assertEquals(List.of(3L), snapshot.get(4));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(0));
    assertThrows(IndexOutOfBoundsException.class, () -> snapshot.get(-1));
    assertThrows(IndexOutOfBoundsException.class, () -> snapshot.get(5));
    assertEquals(List.of(), Values.in(List.of()));
    Iterable<Integer> iterable = () -> List.of(1, 2).iterator();
    assertEquals(List.of(1L, 2L), Values.in(iterable));
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> Values.in(Collections.nCopies(1_000_001, 1L)))
            .code());
  }

  @Test
  public void comprehensionRangesKeepSnapshotsAcrossHostMutation() {
    var input = new ArrayList<>(List.of(1L, 2L, 3L));
    var environment =
        Cel.newBuilder()
            .variable("xs", CelType.list(CelType.INT))
            .function(
                "edit",
                List.of(CelType.INT),
                CelType.INT,
                args -> {
                  input.clear();
                  return args.get(0);
                })
            .build();
    var program = environment.compile("xs.map(x, edit(x))");
    assertEquals(List.of(1L, 2L, 3L), program.evaluate(Map.of("xs", input)));
    assertTrue(input.isEmpty());
    input.addAll(List.of(4L, 5L));
    assertEquals(List.of(4L, 5L), program.evaluate(Map.of("xs", input)));
    assertTrue(input.isEmpty());
    assertEquals(List.of(), program.evaluate(Map.of("xs", input)));
  }

  @Test
  public void arithmeticLoweringPreservesSourceResultsErrorsAndBudgets() {
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        for (String expression :
            List.of(
                "x * 2 + 1",
                "(x - 4) * 3 + 7",
                "x * 2 - 1",
                "x + 2 - 3",
                "1 + 2 + 3",
                "(x * 2 + 1) + (y * 3 + 2)",
                "1 / x * 2 + 1",
                "missing * 2 + 1")) {
          var source = Source.newBuilder("cel", expression, "arithmetic.cel").build();
          for (int budget = 1; budget <= 16; budget++) {
            var template =
                Cel.newBuilder()
                    .inferVariables(true)
                    .evaluationBudget(budget)
                    .build()
                    .prepare(expression);
            var lowered = template.instantiate();
            var original = template.instantiate(source);
            for (Object value :
                Arrays.asList(
                    Long.MIN_VALUE,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE / 2 + 1,
                    -1L,
                    0L,
                    42L,
                    3.5,
                    "text",
                    new UInt(1),
                    null,
                    Unknown.of("x"))) {
              var input = new HashMap<String, Object>();
              input.put("x", value);
              input.put("y", 3L);
              assertEquals(
                  expression + " budget=" + budget + " x=" + value,
                  evaluationOutcome(original, input),
                  evaluationOutcome(lowered, input));
            }
          }
        }
      } finally {
        context.leave();
      }
    }
  }

  private static Object evaluationOutcome(Program program, Map<String, ?> input) {
    try {
      return program.evaluate(input);
    } catch (CelException e) {
      return List.of(e.code(), e.getMessage());
    }
  }

  @Test
  public void comprehensionSizePreservesVisitsErrorsUnknownsAndBudgets() {
    var calls = new ArrayList<Object>();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        for (String expression :
            List.of(
                "xs.map(x, observe(x)).size()",
                "size(xs.filter(x, observe(x) > 0))",
                "xs.map(x, observe(x) > 0, 12 / x).size()",
                "xs.filter(x, observe(x) > 0).map(x, observe(12 / x)).size()",
                "xs.map(x, [x,x].map(y, observe(y)).size()).size()",
                "xs.transformList(i, x, observe(i + x)).size()",
                "xs.transformList(i, x, observe(x) > 0, 12 / x).size()",
                "{'a':1,'b':2}.map(k, observe(k)).size()",
                "{'a':1,'b':2}.filter(k, observe(k) == 'a').size()",
                "{'a':1,'b':2}.transformList(k, v, observe(v)).size()",
                "xs.map(x, x / 0).size()",
                "xs.filter(x, dyn(x)).size()")) {
          for (int budget = 1; budget <= 64; budget++) {
            var template =
                Cel.newBuilder()
                    .inferVariables(true)
                    .evaluationBudget(budget)
                    .function(
                        "observe",
                        List.of(CelType.DYN),
                        CelType.DYN,
                        args -> {
                          calls.add(args.get(0));
                          return args.get(0);
                        })
                    .build()
                    .prepare(expression);
            var lowered = template.instantiate();
            var original =
                template.instantiate(Source.newBuilder("cel", expression, "count.cel").build());
            for (Object xs :
                List.of(
                    List.of(),
                    List.of(0L, 2L, -1L),
                    List.of(2L, 0L),
                    List.of(Unknown.of("a"), Unknown.of("b")),
                    List.of(Unknown.of("a"), 0L),
                    List.of(true, false),
                    Unknown.of("xs"),
                    7L)) {
              var input = Map.of("xs", xs);
              calls.clear();
              Object expected = evaluationOutcome(original, input);
              var expectedCalls = List.copyOf(calls);
              calls.clear();
              String diagnostic = expression + " budget=" + budget + " xs=" + xs;
              assertEquals(diagnostic, expected, evaluationOutcome(lowered, input));
              assertEquals(diagnostic, expectedCalls, calls);
            }
          }
        }
      } finally {
        context.leave();
      }
    }
    var custom =
        Cel.newBuilder()
            .container("custom")
            .function("custom.size", List.of(CelType.list(CelType.INT)), CelType.INT, args -> 99L)
            .build();
    assertEquals(99L, custom.compile("size([1,2].map(x, x))").evaluate());
    assertEquals(99L, custom.compile("custom.size([1,2].map(x, x))").evaluate());
  }

  @Test
  public void arithmeticLoweringDoesNotMoveHostCallsAcrossBudgetFailures() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        for (int budget = 1; budget <= 10; budget++) {
          String expression = "step() * 2 + 1";
          var template =
              Cel.newBuilder()
                  .evaluationBudget(budget)
                  .function(
                      "step", List.of(), CelType.INT, ignored -> (long) calls.incrementAndGet())
                  .build()
                  .prepare(expression);
          var lowered = template.instantiate();
          var original =
              template.instantiate(Source.newBuilder("cel", expression, "host-call.cel").build());
          calls.set(0);
          Object expected = evaluationOutcome(original, Map.of());
          int expectedCalls = calls.get();
          calls.set(0);
          assertEquals(expected, evaluationOutcome(lowered, Map.of()));
          assertEquals(expectedCalls, calls.get());
        }
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void arithmeticAgainstIndependentIntegerOracle() {
    var random = new Random(0xCE1);
    for (String op : List.of("+", "-", "*", "/", "%")) {
      var p =
          Cel.newBuilder()
              .variable("a", CelType.INT)
              .variable("b", CelType.INT)
              .build()
              .compile("a " + op + " b");
      for (int i = 0; i < 250; i++) {
        long a = random.nextLong(), b = random.nextLong();
        var x = BigInteger.valueOf(a);
        var y = BigInteger.valueOf(b);
        BigInteger expected =
            switch (op) {
              case "+" -> x.add(y);
              case "-" -> x.subtract(y);
              case "*" -> x.multiply(y);
              case "/" -> x.divide(y);
              default -> x.remainder(y);
            };
        if (expected.bitLength() > 63)
          assertThrows(CelException.class, () -> p.evaluate(Map.of("a", a, "b", b)));
        else assertEquals(expected.longValueExact(), p.evaluate(Map.of("a", a, "b", b)));
      }
    }
  }

  @Test
  public void budgetsArePerEvaluationAndNotSuppressible() {
    var p =
        Cel.newBuilder()
            .evaluationBudget(25)
            .variable("xs", CelType.list(CelType.INT))
            .build()
            .compile("xs.all(x, x > 0)");
    assertTrue(p.test(Map.of("xs", List.of(1, 2))));
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> p.evaluate(Map.of("xs", Collections.nCopies(50, 1))))
            .code());
    assertTrue(p.test(Map.of("xs", List.of(3))));
    var q = Cel.newBuilder().evaluationBudget(5).build().compile("[1,2,3].all(x, x > 0) || true");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED, assertThrows(CelException.class, q::evaluate).code());
    var cycle = new ArrayList<Object>();
    cycle.add(cycle);
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> Cel.compile("x").evaluate(Map.of("x", cycle)))
            .code());
  }

  @Test
  public void stringPredicatesPreserveUnknownsErrorsAndBudgets() {
    for (String name : List.of("contains", "startsWith", "endsWith")) {
      var environment =
          Cel.newBuilder().variable("x", CelType.DYN).variable("y", CelType.DYN).build();
      String expression = "x." + name + "(y)";
      Program program = environment.compile(expression);
      assertTrue(program.test(Map.of("x", "λ🙂", "y", "")));
      assertFalse(program.test(Map.of("x", "λ🙂", "y", "missing")));
      assertEquals(
          new Unknown(Set.of("x", "y")),
          program.evaluate(Map.of("x", Unknown.of("x"), "y", Unknown.of("y"))));
      assertEquals(
          Unknown.of("x"),
          environment.compile("x." + name + "(dyn(1 / 0))").evaluate(Map.of("x", Unknown.of("x"))));
      assertEquals(
          Unknown.of("y"),
          environment.compile("dyn(1 / 0)." + name + "(y)").evaluate(Map.of("y", Unknown.of("y"))));
      assertEquals(
          CelException.Code.NO_OVERLOAD,
          assertThrows(CelException.class, () -> program.evaluate(Map.of("x", 1L, "y", 2L)))
              .code());
      var custom =
          Cel.newBuilder()
              .container("custom")
              .function(
                  "custom." + name,
                  List.of(CelType.INT, CelType.INT),
                  CelType.INT,
                  args -> (Long) args.get(0) + (Long) args.get(1))
              .build();
      assertEquals(3L, custom.compile(name + "(1,2)").evaluate());
      assertEquals(3L, custom.compile("custom." + name + "(1,2)").evaluate());
      var limited =
          Cel.newBuilder().inferVariables(true).evaluationBudget(2).build().compile(expression);
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(
                  CelException.class,
                  () -> limited.evaluate(Map.of("x", Unknown.of("x"), "y", Unknown.of("y"))))
              .code());
    }
  }

  @Test
  public void errorAndUnknownTruthTables() {
    assertEquals(Unknown.of("x"), Cel.compile("(1/0) + x").evaluate(Map.of("x", Unknown.of("x"))));
    for (int budget : new int[] {1, 2}) {
      var limited =
          Cel.newBuilder().inferVariables(true).evaluationBudget(budget).build().compile("x + y");
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(
                  CelException.class,
                  () -> limited.evaluate(Map.of("x", Unknown.of("x"), "y", Unknown.of("y"))))
              .code());
    }

    assertFalse(Cel.compile("(1/0 == 0) && false").test());
    assertTrue(Cel.compile("(1/0 == 0) || true").test());
    assertEquals(2L, Cel.compile("false ? 1/0 : 2").evaluate());
    assertEquals(Unknown.of("x"), Cel.compile("x + (1/0)").evaluate(Map.of("x", Unknown.of("x"))));
    assertEquals(
        new Unknown(Set.of("x", "y")),
        Cel.compile("x + y").evaluate(Map.of("x", Unknown.of("x"), "y", Unknown.of("y"))));
    for (String expr :
        List.of(
            "x",
            "x.foo",
            "x[0]",
            "[1][x]",
            "[x]",
            "{x: 1}",
            "{'a': x}",
            "x ? 1 : 2",
            "x.all(v, true)",
            "[1].all(v, x)",
            "has(x.f)",
            "x && true",
            "false || x")) {
      assertEquals(expr, Unknown.of("x"), Cel.compile(expr).evaluate(Map.of("x", Unknown.of("x"))));
    }
    assertFalse(Cel.compile("x && false").test(Map.of("x", Unknown.of("x"))));
    assertTrue(Cel.compile("x || true").test(Map.of("x", Unknown.of("x"))));
    assertTrue(Cel.compile("[0,1].exists(x, 1/x == 1)").test());
    assertFalse(Cel.compile("[0,1].all(x, 1/x == 0)").test());
    for (String expr :
        List.of(
            "1/0",
            "1%0",
            "1u/0u",
            "1u%0u",
            "[0].all(x, 1/x == 1)",
            "[0].exists(x, 1/x == 1)",
            "[0].exists_one(x, 1/x == 1)",
            "[0].map(x, 1/x)"))
      assertThrows(expr, CelException.class, () -> Cel.compile(expr).evaluate());
  }

  @Test
  public void explicitObjectAdapterAndFunctionBindings() {
    record Item(long id, boolean present) {}
    ObjectType adapter =
        new ObjectType() {
          public String name() {
            return "app.Item";
          }

          public Map<String, CelType> fields() {
            return Map.of("id", CelType.INT);
          }

          public Object construct(Map<String, Object> fields) {
            return new Item((long) fields.getOrDefault("id", 0L), fields.containsKey("id"));
          }

          public Object get(Object value, String field) {
            return ((Item) value).id;
          }

          public boolean has(Object value, String field) {
            return ((Item) value).present;
          }

          public boolean accepts(Object value) {
            return value instanceof Item;
          }
        };
    var env =
        Cel.newBuilder()
            .objectType(adapter)
            .container("app")
            .variable("item", CelType.of("app.Item"))
            .function(
                "app.twice", List.of(CelType.INT), CelType.INT, args -> (long) args.get(0) * 2)
            .function("app.none", List.of(), CelType.BOOL, args -> true)
            .function(
                "app.sum",
                List.of(CelType.INT, CelType.INT, CelType.INT),
                CelType.INT,
                args -> (long) args.get(0) + (long) args.get(1) + (long) args.get(2))
            .function(
                "app.plus",
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                args -> (long) args.get(0) + (long) args.get(1))
            .build();
    assertTrue(
        env.compile("has(item.id) && twice(item.id) == 8").test(Map.of("item", new Item(4, true))));
    assertFalse(env.compile("has(Item{}.id)").test());
    assertEquals(6L, env.compile("app.sum(1, 2, 3)").evaluate());
    assertEquals(3L, env.compile("app.plus(1, 2)").evaluate());
    assertTrue(env.compile("none()").test());
    assertEquals(CelType.of("app.Item"), env.compile("type(Item{})").evaluate());
    assertEquals(9L, env.compile("Item{id: 9}.id").evaluate());
    assertThrows(CelException.class, () -> env.compile("Item{missing: 1}"));
    assertThrows(CelException.class, () -> env.compile("Item{id: 'x'}"));
    assertThrows(
        CelException.class,
        () -> env.parse("item.missing").evaluate(Map.of("item", new Item(1, false))));
    assertThrows(CelException.class, () -> env.compile("twice('a')"));
    assertThrows(CelException.class, () -> adapter.decode(new Bytes(new byte[0])));
    assertTrue(adapter.equal(new Item(1, true), new Item(1, true)));
  }

  @Test
  public void conversionAndTimeBoundaries() {
    assertTrue(Cel.compile("x", Set.of("x")).test(Map.of("x", true)));
    assertEquals("42", Cel.compile("string(42.0)").evaluate());
    assertEquals(Double.POSITIVE_INFINITY, Cel.compile("double('Infinity')").evaluate());
    assertEquals(Double.POSITIVE_INFINITY, Cel.compile("double('+Infinity')").evaluate());
    assertEquals(Double.NEGATIVE_INFINITY, Cel.compile("double('-Infinity')").evaluate());
    assertTrue(Cel.compile("double('Infinity') > 1 && double('-Infinity') < 1").test());
    assertTrue(Cel.compile("double('Infinity') >= double('Infinity')").test());
    assertEquals(Duration.ofMinutes(90), Cel.compile("duration('1h30m')").evaluate());
    assertEquals(Duration.ofNanos(1_001_000), Cel.compile("duration('1ms1us')").evaluate());
    assertEquals(Duration.ofNanos(2_000), Cel.compile("duration('1µs1μs')").evaluate());
    assertThrows(
        CelException.class,
        () -> Cel.newBuilder().build().parse("timestamp(0).getUnknown()").evaluate());
    assertThrows(
        CelException.class,
        () -> Cel.compile("google.protobuf.Value{bool_value:true, string_value:'x'}").evaluate());
    assertThrows(
        CelException.class,
        () -> Cel.newBuilder().variable("x", CelType.INT).build().compile(".x").evaluate());
    assertEquals(CelType.DYN, Cel.compile("type(x)").evaluate(Map.of("x", new Object())));
  }

  @Test
  public void valueObjectsAreImmutableAndPrecise() {
    byte[] data = {1, 2};
    Bytes b = new Bytes(data);
    data[0] = 4;
    b.toByteArray()[0] = 9;
    assertEquals(new Bytes(new byte[] {1, 2}), b);
    assertEquals(b.hashCode(), new Bytes(new byte[] {1, 2}).hashCode());
    assertFalse(b.equals("x"));
    assertEquals(2, b.size());
    assertTrue(b.compareTo(new Bytes(new byte[] {(byte) 255})) < 0);
    assertEquals(
        new BigInteger("18446744073709551615"), UInt.parse("18446744073709551615").toBigInteger());
    assertTrue(new UInt(-1).compareTo(new UInt(1)) > 0);
    assertEquals("map[string, int]", CelType.map(CelType.STRING, CelType.INT).toString());
    assertEquals("int", CelType.INT.toString());
    assertTrue(CelType.INT.isNumeric());
    assertFalse(CelType.STRING.isNumeric());
    assertEquals(OptionalValue.none(), Cel.compile("optional.none()").evaluate());
    assertEquals(OptionalValue.of(1L), Cel.compile("optional.of(1)").evaluate());
    assertEquals(OptionalValue.none(), Cel.compile("optional.ofNonZeroValue(0)").evaluate());
    assertEquals(OptionalValue.of(1L), Cel.compile("optional.ofNonZeroValue(1)").evaluate());
  }

  @org.junit.Test
  public void preparedExpressionsInstantiateIndependentPrograms() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    var prepared = env.prepare("[x, x + 1].map(v, v * 2)[1]");
    var first = prepared.instantiate();
    var second = prepared.instantiate();
    assertEquals(8L, first.evaluate(Map.of("x", 3L)));
    assertEquals(22L, second.evaluate(Map.of("x", 10L)));
    assertEquals(4L, first.evaluate(Map.of("x", 1L)));
    assertEquals(-2L, prepared.instantiate().evaluate(Map.of("x", -2L)));
    assertEquals(CelType.INT, first.resultType());
    assertEquals(Set.of("x"), first.variables());
    assertThrows(CelException.class, () -> env.prepare("x + true"));
  }

  @Test
  public void unknownsMergeAcrossGenericCallsAndOptionalEntries() {
    var x = Unknown.of("x");
    var y = Unknown.of("y");
    var both = new Unknown(Set.of("x", "y"));
    for (String source :
        List.of(
            "'%s%s'.format([x,y])",
            "[x,y]", "{x:1,y:2}", "{'a':x,'b':y}", "[1,2].all(i, i == 1 ? x : y)"))
      assertEquals(source, both, Cel.compile(source).evaluate(Map.of("x", x, "y", y)));
    for (String source :
        List.of(
            "optional.none().or(x)",
            "[?x]",
            "{?'a':x}",
            "google.protobuf.Int64Value{?value:x}",
            "google.protobuf.Int64Value{value:x}"))
      assertEquals(source, x, Cel.compile(source).evaluate(Map.of("x", x)));
    for (String source : List.of("1 != 2", "1 != 1", "1 == 1", "1 == 2"))
      assertEquals(source.contains("!=") != source.endsWith("1"), Cel.compile(source).evaluate());
    assertEquals(
        CelException.Code.OVERFLOW,
        assertThrows(
                CelException.class,
                () -> Cel.compile("x / y").evaluate(Map.of("x", Long.MIN_VALUE, "y", -1L)))
            .code());
    assertEquals(
        1L, Cel.compile("x / y").evaluate(Map.of("x", Long.MIN_VALUE, "y", Long.MIN_VALUE)));
    assertThrows(CelException.class, () -> Cel.compile("[1].filter(i, dyn(1))").evaluate());
    assertThrows(
        CelException.class,
        () -> Cel.compile("[1].transformMapEntry(i,v,{dyn(1.0):v})").evaluate());
    assertEquals(
        OptionalValue.of(1L), Cel.compile("optional.none().or(optional.of(1))").evaluate());
  }

  @Test
  public void unknownsInMultiArgumentCallsAndFilteredMappers() {
    var x = Unknown.of("x");
    var y = Unknown.of("y");
    var both = new Unknown(Set.of("x", "y"));
    for (String source :
        List.of("x.matches(y)", "x && y", "x || y", "[1,2].map(i,true,i == 1 ? x : y)"))
      assertEquals(source, both, Cel.compile(source).evaluate(Map.of("x", x, "y", y)));
    assertThrows(CelException.class, () -> Cel.compile("[1].map(i,dyn(1),i)").evaluate());
    assertEquals(List.of(1L), Cel.compile("[1].map(int,int)").evaluate());
    assertEquals(1L, Cel.compile("size([1].transformList(i,v,v))").evaluate());
    assertEquals(0L, Cel.compile("size([1].filter(i,false))").evaluate());
    assertEquals(2L, Cel.compile("size([1,2].sortBy(i,-i))").evaluate());
    var unchecked = Cel.newBuilder().build();
    assertThrows(CelException.class, () -> unchecked.parse("size()").evaluate());
    assertThrows(CelException.class, () -> unchecked.parse("optional.none().or()").evaluate());
  }

  @Test
  public void extensionCallsInsideLocalsAndInvalidUncheckedDispatch() {
    assertEquals(List.of(1L), Cel.compile("[-1].map(x, math.abs(x))").evaluate());
    assertThrows(CelException.class, () -> Cel.compile("optional.missing()").evaluate());
    assertThrows(
        CelException.class, () -> Cel.newBuilder().build().parse("contains('x')").evaluate());
  }

  @Test
  public void invalidRegularExpressionsAreInvalidArguments() {
    var env = Cel.newBuilder().variable("s", CelType.STRING).build();
    for (String source : List.of("s.matches('(')", "'abc'.matches(s)"))
      assertEquals(
          source,
          CelException.Code.INVALID_ARGUMENT,
          assertThrows(CelException.class, () -> env.compile(source).evaluate(Map.of("s", "(")))
              .code());
  }

  @Test
  public void evaluationErrorsStayOutsideCompilerSlowPathHierarchies() {
    // The Truffle partial evaluator compiles a handler for any of these types as a deoptimization
    // that recurs on every throw, so an error that is a CEL value must not belong to them (#29).
    for (Class<?> slowPath :
        List.of(
            IllegalArgumentException.class,
            IllegalStateException.class,
            ArithmeticException.class,
            IndexOutOfBoundsException.class,
            ClassCastException.class))
      assertFalse(slowPath.getName(), slowPath.isAssignableFrom(CelException.class));
    assertTrue(RuntimeException.class.isAssignableFrom(CelException.class));
  }

  @Test
  public void explicitOverflowChecksMatchExactArithmetic() {
    long[] samples = {
      Long.MIN_VALUE,
      Long.MIN_VALUE + 1,
      -3037000500L,
      -3037000499L,
      -2,
      -1,
      0,
      1,
      2,
      3037000499L,
      3037000500L,
      Long.MAX_VALUE - 1,
      Long.MAX_VALUE
    };
    for (long x : samples) {
      assertArithmetic(x + " neg", () -> Math.negateExact(x), () -> Functions.negate(x));
      for (long y : samples) {
        assertArithmetic(x + " + " + y, () -> Math.addExact(x, y), () -> Functions.add(x, y));
        assertArithmetic(
            x + " - " + y, () -> Math.subtractExact(x, y), () -> Functions.subtract(x, y));
        assertArithmetic(
            x + " * " + y, () -> Math.multiplyExact(x, y), () -> Functions.multiply(x, y));
        assertArithmetic(x + " / " + y, () -> Math.divideExact(x, y), () -> Functions.divide(x, y));
        assertArithmetic(x + " % " + y, () -> x % y, () -> Functions.remainder(x, y));
      }
    }
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(CelException.class, () -> Functions.divide(1, 0)).code());
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(CelException.class, () -> Functions.remainder(1, 0)).code());
  }

  private static void assertArithmetic(
      String operation,
      java.util.function.LongSupplier exact,
      java.util.function.LongSupplier checked) {
    long reference;
    try {
      reference = exact.getAsLong();
    } catch (ArithmeticException e) {
      CelException error = assertThrows(operation, CelException.class, checked::getAsLong);
      assertEquals(
          operation,
          operation.endsWith(" 0") ? CelException.Code.DIVIDE_BY_ZERO : CelException.Code.OVERFLOW,
          error.code());
      return;
    }
    assertEquals(operation, reference, checked.getAsLong());
  }

  @Test
  public void uncheckedHostDispatchMatchesDeclaredOverloads() {
    var env =
        Cel.newBuilder()
            .function("same", List.of(CelType.INT), CelType.INT, args -> args.get(0))
            .build();
    assertEquals(1L, env.parse("same(1)").evaluate());
    for (String source : List.of("same('a')", "same(1, 2)", "same(dyn(1u))", "same()"))
      assertEquals(
          source,
          CelException.Code.NO_OVERLOAD,
          assertThrows(CelException.class, () -> env.parse(source).evaluate()).code());
  }
}
