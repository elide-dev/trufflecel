package dev.trufflecel;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class FunctionOverloadTest {
  @Test
  public void arithmeticLoweringPreservesCustomCallAtChainBase() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var env =
        Cel.newBuilder()
            .function(
                "value",
                "custom_value",
                false,
                List.of(),
                CelType.INT,
                args -> (long) calls.incrementAndGet())
            .build();
    var program = env.compile("value() + 1 + 2");
    assertEquals(4L, program.evaluate());
    assertEquals(5L, program.evaluate());
    assertEquals(2, calls.get());
  }

  @Test
  public void signaturesDispatchStaticallyAndDynamicallyWithoutOverwriting() {
    var builder =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .function("convert", List.of(CelType.INT), CelType.STRING, a -> "int:" + a.get(0))
            .function("convert", List.of(CelType.STRING), CelType.STRING, a -> "str:" + a.get(0));
    var env = builder.build();
    assertEquals("int:1", env.compile("convert(1)").evaluate());
    assertEquals("str:a", env.compile("convert('a')").evaluate());
    var dynamic = env.compile("convert(x)");
    assertEquals("int:2", dynamic.evaluate(Map.of("x", 2L)));
    assertEquals("str:b", dynamic.evaluate(Map.of("x", "b")));
    assertEquals(CelType.STRING, dynamic.resultType());
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(CelException.class, () -> dynamic.evaluate(Map.of("x", true))).code());
    assertThrows(CelException.class, () -> env.compile("convert(true)"));
    assertEquals(Unknown.of("u"), dynamic.evaluate(Map.of("x", Unknown.of("u"))));
    builder.function("convert", List.of(CelType.BOOL), CelType.STRING, a -> "bool");
    assertThrows(CelException.class, () -> env.compile("convert(true)"));
    assertEquals("bool", builder.build().compile("convert(true)").evaluate());
  }

  @Test
  public void explicitStylesAndNamespacesRemainDistinct() {
    var env =
        Cel.newBuilder()
            .container("app")
            .function("app.f", "global_int", false, List.of(CelType.INT), CelType.INT, a -> 10L)
            .function("f", "receiver_int", true, List.of(CelType.INT), CelType.INT, a -> 20L)
            .function("g", "global_g", false, List.of(CelType.INT), CelType.INT, a -> 30L)
            .function("g", "receiver_g", true, List.of(CelType.INT), CelType.INT, a -> 40L)
            .build();
    assertEquals(10L, env.compile("f(1)").evaluate());
    assertEquals(10L, env.compile("app.f(1)").evaluate());
    assertEquals(20L, env.compile("1.f()").evaluate());
    assertEquals(30L, env.compile("g(1)").evaluate());
    assertEquals(40L, env.compile("1.g()").evaluate());
    assertThrows(CelException.class, () -> env.compile("f()"));
    var onlyGlobal =
        Cel.newBuilder()
            .function("f", "global", false, List.of(CelType.INT), CelType.INT, a -> 1L)
            .build();
    assertThrows(CelException.class, () -> onlyGlobal.compile("1.f()"));
    assertThrows(CelException.class, () -> onlyGlobal.parse("1.f()").evaluate());
  }

  @Test
  public void rejectsAmbiguousOrDuplicateRegistrations() {
    var builder =
        Cel.newBuilder().function("f", "f_int", false, List.of(CelType.INT), CelType.INT, a -> 1L);
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.function("f", "f_dyn", false, List.of(CelType.DYN), CelType.INT, a -> 2L));
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.function("g", "f_int", false, List.of(), CelType.INT, a -> 2L));
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.function("f", "", false, List.of(), CelType.INT, a -> 2L));
    assertThrows(
        IllegalArgumentException.class,
        () -> builder.function("f", "receiver_empty", true, List.of(), CelType.INT, a -> 2L));
    builder.function("f", "f_zero", false, List.of(), CelType.INT, a -> 3L);
    assertEquals(3L, builder.build().compile("f()").evaluate());
    var lists =
        Cel.newBuilder()
            .function("f", "ints", false, List.of(CelType.list(CelType.INT)), CelType.INT, a -> 1L);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            lists.function(
                "f",
                "strings",
                false,
                List.of(CelType.list(CelType.STRING)),
                CelType.INT,
                a -> 2L));
  }

  @Test
  public void standardFunctionsKeepUnmatchedBuiltInSignatures() {
    var env =
        Cel.newBuilder()
            .function(
                "size",
                "size_int",
                false,
                List.of(CelType.INT),
                CelType.INT,
                a -> (Long) a.get(0) + 1)
            .function(
                "contains",
                "contains_int",
                true,
                List.of(CelType.INT, CelType.INT),
                CelType.BOOL,
                a -> a.get(0).equals(a.get(1)))
            .build();
    assertEquals(5L, env.compile("size(4)").evaluate());
    assertEquals(3L, env.compile("size('abc')").evaluate());
    assertEquals(true, env.compile("4.contains(4)").evaluate());
    assertEquals(true, env.compile("'abc'.contains('b')").evaluate());
    assertEquals(false, env.parse("4.contains(5)").evaluate());
  }

  @Test
  public void genericResultsAndCanonicalIdsSurviveInterchange() {
    var t = CelType.parameter("T");
    var env =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .function("same", "same_T", false, List.of(t), t, a -> a.get(0))
            .function("pick", "pick_int", false, List.of(CelType.INT), CelType.INT, a -> a.get(0))
            .function(
                "pick",
                "pick_string",
                false,
                List.of(CelType.STRING),
                CelType.STRING,
                a -> a.get(0))
            .build();
    assertEquals(CelType.INT, env.compile("same(1)").resultType());
    assertEquals(CelType.DYN, env.compile("pick(x)").resultType());
    var checked = CanonicalAst.check(env, CanonicalAst.parse(env, "pick(x)"));
    String text = checked.toString();
    assertTrue(text.contains("pick_int"));
    assertTrue(text.contains("pick_string"));
    assertEquals("a", CanonicalAst.program(env, checked).evaluate(Map.of("x", "a")));
    assertEquals(3L, env.prepare("same(3)").instantiate().evaluate());
  }

  @Test
  public void repeatedTypeParametersRejectInconsistentBindings() {
    var t = CelType.parameter("T");
    var env =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .function("same2", "same2_T", false, List.of(t, t), t, a -> a.get(0))
            .function("same", "same_T", false, List.of(t), t, a -> a.get(0))
            .build();
    // Consistent bindings deduce the concrete type.
    assertEquals(CelType.INT, env.compile("same2(1, 2)").resultType());
    assertEquals(CelType.STRING, env.compile("same2('a', 'b')").resultType());
    assertEquals(1L, env.compile("same2(1, 2)").evaluate());
    // A leading dyn argument keeps the binding general.
    assertEquals(CelType.DYN, env.compile("same2(dyn(1), 2)").resultType());
    assertEquals(CelType.DYN, env.compile("same2(2, dyn(1))").resultType());
    assertEquals(CelType.DYN, env.compile("same2(x, x)").resultType());
    // Single-position control still works.
    assertEquals(CelType.INT, env.compile("same(1)").resultType());
    assertEquals(CelType.DYN, env.compile("same(x)").resultType());
    // Inconsistent non-numeric bindings are rejected at check time.
    assertThrows(CelException.class, () -> env.compile("same2(1, 'x')"));
    assertThrows(CelException.class, () -> env.compile("same2(true, 'x')"));
    assertThrows(CelException.class, () -> env.compile("same2(1, true)"));
    // Inconsistent bindings inside parameterized types are also rejected.
    assertThrows(CelException.class, () -> env.compile("same2([1], ['x'])"));
    assertThrows(CelException.class, () -> env.compile("same2([1], [true])"));
    // Numeric type mixing generalizes to dyn (CEL heterogeneous numerics).
    assertEquals(CelType.DYN, env.compile("same2(1, 1u)").resultType());
    assertEquals(CelType.DYN, env.compile("same2(1, 1.0)").resultType());
    assertEquals(CelType.DYN, env.compile("same2(1u, 1.0)").resultType());
  }

  @Test
  public void customObjectsOperatorsAndCallbackFailures() {
    record Box(long value) {}
    ObjectType type =
        new ObjectType() {
          public String name() {
            return "Box";
          }

          public Map<String, CelType> fields() {
            return Map.of("value", CelType.INT);
          }

          public Object construct(Map<String, Object> fields) {
            return new Box((Long) fields.get("value"));
          }

          public Object get(Object value, String field) {
            return ((Box) value).value();
          }

          public boolean has(Object value, String field) {
            return field.equals("value");
          }

          public boolean accepts(Object value) {
            return value instanceof Box;
          }
        };
    var env =
        Cel.newBuilder()
            .objectType(type)
            .variable("x", CelType.of("Box"))
            .function(
                "size",
                "box_size",
                false,
                List.of(CelType.of("Box")),
                CelType.INT,
                a -> ((Box) a.get(0)).value())
            .function(
                "+",
                "box_plus_int",
                false,
                List.of(CelType.of("Box"), CelType.INT),
                CelType.INT,
                a -> ((Box) a.get(0)).value() + (Long) a.get(1))
            .build();
    assertEquals(9L, env.compile("size(x)").evaluate(Map.of("x", new Box(9))));
    assertEquals(12L, env.compile("x + 1 + 2").evaluate(Map.of("x", new Box(9))));
    assertEquals(3L, env.compile("1 + 2").evaluate());
    var unknownObject = new Object();
    assertThrows(
        CelException.class, () -> env.parse("size(x)").evaluate(Map.of("x", unknownObject)));
    for (RuntimeException failure :
        List.of(
            new ArithmeticException(),
            new IllegalArgumentException(),
            new IndexOutOfBoundsException(),
            new ClassCastException(),
            new java.time.DateTimeException("invalid date"),
            new CelException(CelException.Code.INDEX, "index"))) {
      var failing =
          Cel.newBuilder()
              .function(
                  "f",
                  "f",
                  false,
                  List.of(),
                  CelType.INT,
                  a -> {
                    throw failure;
                  })
              .build();
      var error = assertThrows(CelException.class, () -> failing.compile("f()").evaluate());
      assertEquals(
          failure instanceof CelException e
              ? e.code()
              : failure instanceof ArithmeticException
                  ? CelException.Code.OVERFLOW
                  : CelException.Code.INVALID_ARGUMENT,
          error.code());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Cel.newBuilder()
                .function(
                    "&&",
                    "and",
                    false,
                    List.of(CelType.BOOL, CelType.BOOL),
                    CelType.BOOL,
                    a -> true));
  }

  @Test
  public void nullableAndLegacySignaturesCannotHideAmbiguity() {
    for (var pair :
        List.of(
            List.of(CelType.NULL, CelType.wrapper(CelType.INT)),
            List.of(CelType.wrapper(CelType.INT), CelType.NULL),
            List.of(CelType.DYN, CelType.INT))) {
      var b =
          Cel.newBuilder().function("f", "one", false, List.of(pair.get(0)), CelType.INT, a -> 1L);
      assertThrows(
          IllegalArgumentException.class,
          () -> b.function("f", "two", false, List.of(pair.get(1)), CelType.INT, a -> 2L));
    }
    var legacy = Cel.newBuilder().function("f", List.of(CelType.INT), CelType.INT, a -> 1L);
    assertThrows(
        IllegalArgumentException.class,
        () -> legacy.function("f", "receiver", true, List.of(CelType.INT), CelType.INT, a -> 2L));
  }

  @Test
  public void overloadDispatchPrecedesSpecializedOperatorsAndRegex() {
    var env =
        Cel.newBuilder()
            .function(
                "<",
                "less_strings",
                false,
                List.of(CelType.STRING, CelType.STRING),
                CelType.BOOL,
                a -> false)
            .function(
                "matches",
                "matches_ints",
                true,
                List.of(CelType.INT, CelType.INT),
                CelType.BOOL,
                a -> true)
            .function("reverse", "reverse_int", true, List.of(CelType.INT), CelType.INT, a -> -1L)
            .function(
                "getFullYear", "year_int", true, List.of(CelType.INT), CelType.INT, a -> 2000L)
            .build();
    assertEquals(false, env.compile("'a' < 'b'").evaluate());
    assertEquals(true, env.compile("1 < 2").evaluate());
    assertEquals(true, env.compile("1.matches(2)").evaluate());
    assertEquals(true, env.compile("'abc'.matches('a.*')").evaluate());
    assertEquals(-1L, env.compile("1.reverse()").evaluate());
    assertEquals("cba", env.compile("'abc'.reverse()").evaluate());
    assertEquals(2000L, env.compile("1.getFullYear()").evaluate());
    assertEquals(1970L, env.compile("timestamp(0).getFullYear()").evaluate());
  }
}
