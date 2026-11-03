package dev.trufflecel;

import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.math.BigInteger;
import org.junit.Test;
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
  public void errorAndUnknownTruthTables() {
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
}
