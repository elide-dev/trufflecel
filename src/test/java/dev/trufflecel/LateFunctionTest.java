package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class LateFunctionTest {
  @Test
  public void builtinFallbackIsPlannedEvenWhenDynamicArgumentsCanMatchLateFunctions() {
    var env =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .lateFunction("size", "size_int", false, List.of(CelType.INT), CelType.INT)
            .build();
    var program = env.compile("size(x)");
    var options =
        EvaluationOptions.newBuilder()
            .function("size_int", args -> (Long) args.getFirst() + 100)
            .build();
    assertEquals(3L, program.evaluate(Map.of("x", "abc"), options));
    assertEquals(3L, program.evaluate(Map.of("x", "abc")));
    assertEquals(101L, program.evaluate(Map.of("x", 1L), options));
  }

  @Test
  public void returnedErrorsAndLegacyDeclarationsRetainTheirContract() {
    var binding =
        new Environment.FunctionBinding(List.of(CelType.INT), CelType.INT, args -> args.getFirst());
    var overload = new Environment.FunctionOverload("legacy", false, binding);
    assertFalse(overload.nonStrict());
    assertFalse(overload.lateBound());
    var env =
        Cel.newBuilder()
            .nonStrictFunction(
                "pass",
                "pass_int",
                false,
                List.of(CelType.INT),
                CelType.INT,
                args -> args.getFirst())
            .build();
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(CelException.class, () -> env.compile("pass(1/0)").evaluate()).code());
    var late =
        Cel.newBuilder()
            .lateFunction("late", "late", false, List.of(), CelType.INT)
            .build()
            .compile("late()");
    assertEquals(
        CelException.Code.UNDECLARED,
        late.evaluateWithDetails(Map.of(), EvaluationOptions.newBuilder().build()).error().code());
  }

  @Test
  public void implementationsArePerCallAndOverloadIdsAreChecked() throws Exception {
    var env =
        Cel.newBuilder()
            .lateFunction("f", "f_int", false, List.of(CelType.INT), CelType.INT)
            .lateFunction("f", "f_string", false, List.of(CelType.STRING), CelType.STRING)
            .build();
    var program = env.compile("f(2)");
    assertEquals(
        CelException.Code.UNDECLARED, assertThrows(CelException.class, program::evaluate).code());
    var options =
        EvaluationOptions.newBuilder().function("f_int", a -> (long) a.get(0) + 3).build();
    assertEquals(5L, program.evaluate(Map.of(), options));
    assertEquals(5L, program.evaluate(Activation.snapshot(Map.of()), options));
    assertThrows(CelException.class, program::evaluate);
    assertEquals(
        "ok",
        env.compile("f('a')")
            .evaluate(
                Map.of(), EvaluationOptions.newBuilder().function("f_string", a -> "ok").build()));
    assertThrows(CelException.class, () -> env.compile("f(true)"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            program.evaluate(
                Map.of(), EvaluationOptions.newBuilder().function("unknown_id", a -> 1L).build()));
    assertThrows(
        IllegalArgumentException.class, () -> EvaluationOptions.newBuilder().function("", a -> 1L));
    assertThrows(
        NullPointerException.class, () -> EvaluationOptions.newBuilder().function("f_int", null));
    try (var pool = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Callable<Object>>();
      for (long i = 0; i < 100; i++) {
        long result = i;
        jobs.add(() ->
            program.evaluate(
                Map.of(), EvaluationOptions.newBuilder().function("f_int", a -> result).build()));
      }
      var results = pool.invokeAll(jobs);
      for (int i = 0; i < results.size(); i++) assertEquals((long) i, results.get(i).get());
    }
  }

  @Test
  public void nonStrictCallbacksReceiveErrorsAndUnknownsButNotBudgetFailures() {
    var env =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .nonStrictFunction(
                "recover",
                "recover_int",
                false,
                List.of(CelType.INT),
                CelType.STRING,
                args ->
                    args.get(0) instanceof CelException
                        ? "error"
                        : args.get(0) instanceof Unknown ? "unknown" : "value")
            .lateFunction("late", "late_int", false, List.of(CelType.INT), CelType.STRING, true)
            .build();
    assertEquals("error", env.compile("recover(1 / 0)").evaluate());
    var strict =
        Cel.newBuilder()
            .validation(Environment.Validation.STRICT)
            .nonStrictFunction(
                "recover", "recover", false, List.of(CelType.INT), CelType.INT, a -> 7L)
            .build();
    assertEquals(7L, strict.compile("recover(1 / 0)").evaluate());
    assertEquals("unknown", env.compile("recover(x)").evaluate(Map.of("x", Unknown.of("x"))));
    assertEquals("value", env.compile("recover(2)").evaluate());
    assertEquals(
        "handled",
        env.compile("late(1 / 0)")
            .evaluate(
                Map.of(),
                EvaluationOptions.newBuilder().function("late_int", args -> "handled").build()));
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    env.compile("recover(2)")
                        .evaluate(Map.of(), EvaluationOptions.newBuilder().budget(1).build()))
            .code());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            env.compile("recover(2)")
                .evaluate(
                    Map.of(),
                    EvaluationOptions.newBuilder()
                        .function("recover_int", args -> "overridden")
                        .build()));
  }

  @Test
  public void unresolvedOverloadsDoNotGuessAndReceiverDeclarationsWork() {
    var env =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .nonStrictFunction("f", "int", false, List.of(CelType.INT), CelType.INT, a -> 1L)
            .nonStrictFunction("f", "str", false, List.of(CelType.STRING), CelType.INT, a -> 2L)
            .lateFunction("twice", "receiver", true, List.of(CelType.INT), CelType.INT)
            .build();
    assertEquals(Unknown.of("x"), env.compile("f(x)").evaluate(Map.of("x", Unknown.of("x"))));
    assertEquals(
        CelException.Code.DIVIDE_BY_ZERO,
        assertThrows(CelException.class, () -> env.compile("f(1 / 0)").evaluate()).code());
    assertEquals(
        4L,
        env.compile("2.twice()")
            .evaluate(
                Map.of(),
                EvaluationOptions.newBuilder()
                    .function("receiver", a -> (long) a.get(0) * 2)
                    .build()));
    assertThrows(CelException.class, () -> env.compile("twice(2)"));
    assertEquals(
        CelException.Code.INVALID_ARGUMENT,
        assertThrows(
                CelException.class,
                () ->
                    env.compile("2.twice()")
                        .evaluate(
                            Map.of(),
                            EvaluationOptions.newBuilder()
                                .function(
                                    "receiver",
                                    a -> {
                                      throw new IllegalArgumentException("bad");
                                    })
                                .build()))
            .code());
  }
}
