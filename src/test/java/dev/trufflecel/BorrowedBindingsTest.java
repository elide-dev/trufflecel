package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class BorrowedBindingsTest {
  @Test
  public void preparedAndSeparatelyCompiledProgramsKeepIndependentConversionCaches()
      throws Exception {
    var environment =
        Cel.newBuilder()
            .variable("left", CelType.list(CelType.INT))
            .variable("right", CelType.list(CelType.INT))
            .variable("unused", CelType.DYN)
            .build();
    var prepared = environment.prepare("left[0] + right[0] + left[0]");
    var programs =
        List.of(
            prepared.instantiate(),
            prepared.instantiate(),
            environment.compile("right[0] + left[0] + left[0]"));
    try (var executor = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Callable<Object>>();
      for (int i = 0; i < 100; i++) {
        int n = i;
        Program program = programs.get(i % programs.size());
        calls.add(
            () -> {
              int[] traversals = {0};
              Iterable<Integer> left =
                  () -> {
                    assertEquals("one conversion per evaluation", 0, traversals[0]++);
                    return List.of(n).iterator();
                  };
              var input = Map.of("left", left, "right", List.of(n + 1));
              return n % 2 == 0
                  ? program.evaluate(input)
                  : program.evaluate(input, EvaluationOptions.newBuilder().budget(100).build());
            });
      }
      var answers = executor.invokeAll(calls);
      for (int i = 0; i < answers.size(); i++) assertEquals(3L * i + 1, answers.get(i).get());
    }
    var inferred = Cel.newBuilder().inferVariables(true).variable("base", CelType.INT).build();
    var first = inferred.prepare("base + x[0]");
    var second = inferred.prepare("base + y[0]");
    assertEquals(3L, first.instantiate().evaluate(Map.of("base", 1, "x", List.of(2))));
    assertEquals(6L, second.instantiate().evaluate(Map.of("base", 2, "y", List.of(4))));
    assertEquals(Set.of("base", "x"), first.instantiate().variables());
    assertEquals(Set.of("base", "y"), second.instantiate().variables());
    assertEquals(Set.of("base"), inferred.compile("base").variables());
  }

  @Test
  public void evaluationDoesNotEnumerateBindingsAndConversionsAreEvaluationLocal() {
    int[] reads = {0};
    int[] value = {2};
    Map<String, Object> source =
        new AbstractMap<>() {
          public int size() {
            return 2;
          }

          public Object get(Object key) {
            assertEquals("x", key);
            reads[0]++;
            return value[0];
          }

          public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("Borrowed evaluation must not enumerate unused bindings");
          }
        };
    Program program = Cel.compile("x + x");
    assertEquals(4L, program.evaluate(source));
    assertEquals(1, reads[0]);
    value[0] = 3;
    assertEquals(6L, program.evaluate(source));
    assertEquals(2, reads[0]);
  }

  @Test
  public void eachReadBindingIsNormalizedOnceIncludingOneShotIterables() {
    int[] traversals = {0};
    Iterable<Integer> items =
        () -> {
          assertEquals(0, traversals[0]++);
          return List.of(1, 2).iterator();
        };
    assertEquals(3L, Cel.compile("xs[0] + xs[1]").evaluate(Map.of("xs", items)));
    assertEquals(1, traversals[0]);
    var input = new HashMap<String, Object>();
    input.put("x", null);
    input.put("other", 1);
    assertTrue(Cel.compile("x == null && x == null").test(input));
    var missing = new BorrowedBindings(input, Map.of("missing", 0, "x", 1));
    assertNull(missing.get("missing"));
    assertNull(missing.get("missing"));
    assertEquals(CelNull.INSTANCE, missing.get("x"));
    assertEquals(CelNull.INSTANCE, missing.get("x"));
  }

  @Test
  public void boundsApplyOnReadAndShortCircuitingAvoidsUnusedInputs() {
    var cycle = new ArrayList<Object>();
    cycle.add(cycle);
    for (Object invalid : List.of(cycle, Collections.nCopies(1_000_001, 1L))) {
      var inputs = new HashMap<String, Object>();
      inputs.put("bad", invalid);
      inputs.put("enabled", false);
      Program program = Cel.compile("enabled && size(bad) > 0");
      assertFalse(program.test(inputs));
      inputs.put("enabled", true);
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(CelException.class, () -> program.evaluate(inputs)).code());
      inputs.put("bad", List.of(1));
      assertTrue(program.test(inputs));
    }
  }

  @Test
  public void visibleBindingsIncludeUndeclaredInputsWithoutChangingTheSource() {
    var source = new LinkedHashMap<String, Object>();
    source.put("x", 1);
    source.put("extra", List.of(2));
    source.put(null, null);
    var bindings = new BorrowedBindings(source, Map.of("x", 0));
    var context = new EvalContext(Cel.newBuilder().build(), bindings);
    assertEquals(1L, context.getRoot("x"));
    var visible = context.visibleBindings();
    assertEquals(Arrays.asList("x", "extra", null), new ArrayList<>(visible.keySet()));
    assertEquals(List.of(2L), visible.get("extra"));
    assertEquals(CelNull.INSTANCE, visible.get(null));
    assertEquals(1L, context.fork().getRoot("x"));
    assertEquals(Integer.valueOf(1), source.get("x"));
  }

  @Test
  public void reentrantCallsKeepIndependentBorrowedConversions() {
    var recursive = new java.util.concurrent.atomic.AtomicReference<Program>();
    var environment =
        Cel.newBuilder()
            .inferVariables(true)
            .function(
                "inner",
                List.of(),
                CelType.INT,
                ignored ->
                    recursive.get().evaluate(Map.of("reenter", false, "xs", List.of(10, 20))))
            .build();
    Program program = environment.compile("(reenter ? inner() : 0) + xs[0] + xs[1]");
    recursive.set(program);
    assertEquals(33L, program.evaluate(Map.of("reenter", true, "xs", List.of(1, 2))));
    assertEquals(7L, program.evaluate(Map.of("reenter", false, "xs", List.of(3, 4))));
  }

  @Test
  public void returnedValuesOwnStorageAndConcurrentEvaluationsHaveIndependentCaches()
      throws Exception {
    byte[] bytes = {1};
    var list = new ArrayList<Object>(List.of(bytes));
    Program read = Cel.compile("xs");
    var input = Map.of("xs", list);
    var result = (List<?>) read.evaluate(input);
    bytes[0] = 2;
    assertArrayEquals(new byte[] {1}, (byte[]) result.get(0));
    ((byte[]) result.get(0))[0] = 9;
    assertArrayEquals(new byte[] {2}, (byte[]) ((List<?>) read.evaluate(input)).get(0));
    Program sum = Cel.compile("xs[0] + xs[1]");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Callable<Object>>();
      for (int i = 0; i < 100; i++) {
        int n = i;
        calls.add(() -> sum.evaluate(Map.of("xs", List.of(n, n + 1))));
      }
      var answers = executor.invokeAll(calls);
      for (int i = 0; i < answers.size(); i++) assertEquals(2L * i + 1, answers.get(i).get());
    }
  }
}
