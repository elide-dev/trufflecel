package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Debugger;
import java.util.*;
import java.util.concurrent.*;
import org.graalvm.polyglot.Context;
import org.junit.Test;

public class ActivationTest {
  @Test
  public void snapshotsOwnCollectionsAndReturnedBytes() {
    byte[] bytes = {1, 2};
    var list = new ArrayList<Object>(Arrays.asList(1, null, bytes));
    var nested = new LinkedHashMap<String, Object>();
    nested.put("items", list);
    var input = new HashMap<String, Object>();
    input.put("data", nested);
    input.put("array", new Object[] {2, 3.5f});
    input.put("nothing", null);
    Activation activation = Activation.snapshot(input);
    input.clear();
    nested.clear();
    list.clear();
    bytes[0] = 9;
    assertTrue(
        Cel.compile("data.items[0] == 1 && data.items[1] == null && nothing == null")
            .test(activation));
    assertEquals(List.of(2L, 3.5), Cel.compile("array").evaluate(activation));
    Program read = Cel.compile("data.items[2]");
    byte[] first = (byte[]) read.evaluate(activation);
    assertArrayEquals(new byte[] {1, 2}, first);
    first[0] = 99;
    assertArrayEquals(new byte[] {1, 2}, (byte[]) read.evaluate(activation));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((List<?>) Cel.compile("data.items").evaluate(activation)).clear());
  }

  @Test
  public void singleEmptyMissingNullAndUnknownBindings() {
    assertEquals(3L, Cel.compile("1 + 2").evaluate(Activation.snapshot(Map.of())));
    var input = new HashMap<String, Object>();
    input.put("x", null);
    assertNull(Cel.compile("x").evaluate(Activation.snapshot(input)));
    assertFalse(Cel.compile("x != null").test(Activation.snapshot(input)));
    assertEquals(
        Unknown.of("x"),
        Cel.compile("x + 1").evaluate(Activation.snapshot(Map.of("x", Unknown.of("x")))));
    assertEquals(
        CelException.Code.UNDECLARED,
        assertThrows(
                CelException.class,
                () -> Cel.compile("x").evaluate(Activation.snapshot(Map.of("other", 1))))
            .code());
    assertEquals(
        CelException.Code.TYPE,
        assertThrows(
                CelException.class,
                () -> Cel.compile("x").test(Activation.snapshot(Map.of("x", 1))))
            .code());
    assertThrows(NullPointerException.class, () -> Activation.snapshot(null));
    input.put(null, 1);
    assertThrows(NullPointerException.class, () -> Activation.snapshot(input));
  }

  @Test
  public void preparationValidatesUnusedInputsAndBounds() {
    var cycle = new ArrayList<Object>();
    cycle.add(cycle);
    for (Object invalid : List.of(cycle, Collections.nCopies(1_000_001, 1L))) {
      Map<String, Object> inputs = Map.of("used", 1L, "unused", invalid);
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(CelException.class, () -> Activation.snapshot(inputs)).code());
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(CelException.class, () -> Cel.compile("used").evaluate(inputs)).code());
    }
  }

  @Test
  public void scopesAndBudgetsAreIndependentAcrossProgramsThreadsAndFailures() throws Exception {
    var env =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .variable("xs", CelType.list(CelType.INT))
            .evaluationBudget(35)
            .build();
    var good = Activation.snapshot(Map.of("xs", List.of(1, 2), "x", 10));
    var large = Activation.snapshot(Map.of("xs", Collections.nCopies(100, 1), "x", 10));
    String expression = "xs.map(x, x + .x)";
    try (Context context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        var programs =
            List.of(
                env.compile(expression),
                env.compile(Source.newBuilder("cel", expression, "activation.cel").build()));
        for (Program program : programs) {
          assertEquals(List.of(11L, 12L), program.evaluate(good));
          assertEquals(
              CelException.Code.RESOURCE_EXHAUSTED,
              assertThrows(CelException.class, () -> program.evaluate(large)).code());
          assertEquals(List.of(11L, 12L), program.evaluate(good));
        }
        // Direct programs are reusable without entering a shared Polyglot context on worker
        // threads.
        Program direct = programs.get(0);
        try (var executor = Executors.newFixedThreadPool(4)) {
          var calls = new ArrayList<Callable<Object>>();
          for (int i = 0; i < 200; i++) calls.add(() -> direct.evaluate(good));
          for (var result : executor.invokeAll(calls))
            assertEquals(List.of(11L, 12L), result.get());
        }
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void reentrantEvaluationDoesNotReuseMutableExecutionState() {
    var activation = Activation.snapshot(Map.of("x", 7));
    var inner = Cel.newBuilder().inferVariables(true).evaluationBudget(5).build().compile("x + 1");
    var outer =
        Cel.newBuilder()
            .inferVariables(true)
            .evaluationBudget(10)
            .function("inner", List.of(), CelType.INT, ignored -> inner.evaluate(activation))
            .build()
            .compile("inner() + x");
    for (int i = 0; i < 10; i++) assertEquals(15L, outer.evaluate(activation));
  }

  @Test
  public void debuggerSeesPreparedBindingsAndComprehensionLocals() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        var source =
            Source.newBuilder("cel", "xs.map(x,\n x + offset)", "prepared-debug.cel").build();
        var env =
            Cel.newBuilder()
                .variable("xs", CelType.list(CelType.INT))
                .variable("offset", CelType.INT)
                .build();
        var program = env.compile(source);
        var snapshot = Activation.snapshot(Map.of("xs", List.of(1, 2), "offset", 10));
        var locals = new ArrayList<String>();
        try (var session =
            Debugger.find(context.getEngine())
                .startSession(
                    event -> {
                      var scope = event.getTopStackFrame().getScope();
                      locals.add(scope.getDeclaredValue("x").toDisplayString());
                      assertEquals("10", scope.getDeclaredValue("offset").toDisplayString());
                    })) {
          session.install(Breakpoint.newBuilder(source).lineIs(2).build());
          assertEquals(List.of(11L, 12L), program.evaluate(snapshot));
          assertEquals(List.of("1", "2"), locals);
        }
      } finally {
        context.leave();
      }
    }
  }
}
