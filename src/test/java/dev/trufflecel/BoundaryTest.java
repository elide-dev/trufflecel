package dev.trufflecel;

import static org.junit.Assert.*;
import org.junit.Test;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import com.oracle.truffle.api.interop.*;

/** Boundary contracts shared by lowered execution, generic calls, and host adapters. */
public class BoundaryTest {
  private final Environment env = Cel.newBuilder().build();

  private Object call(String name, Object... arguments) {
    return Functions.apply(name, arguments, env);
  }

  @Test
  public void genericNumericDispatchMatchesScalarExecution() {
    for (long x : new long[] {-7, 0, 7})
      for (long y : new long[] {-2, 2}) {
        assertEquals(x < y, call("<", x, y));
        assertEquals(x <= y, call("<=", x, y));
        assertEquals(x > y, call(">", x, y));
        assertEquals(x >= y, call(">=", x, y));
        assertEquals(x + y, call("+", x, y));
        assertEquals(x - y, call("-", x, y));
        assertEquals(x * y, call("*", x, y));
        assertEquals(x / y, call("/", x, y));
        assertEquals(x % y, call("%", x, y));
      }
    for (String op : List.of("/", "%"))
      assertEquals(
          CelException.Code.DIVIDE_BY_ZERO,
          assertThrows(CelException.class, () -> call(op, 1L, 0L)).code());
    for (Object[] args :
        List.of(
            new Object[] {"+", Long.MAX_VALUE, 1L},
            new Object[] {"-", Long.MIN_VALUE, 1L},
            new Object[] {"*", Long.MAX_VALUE, 2L},
            new Object[] {"/", Long.MIN_VALUE, -1L}))
      assertEquals(
          CelException.Code.OVERFLOW,
          assertThrows(CelException.class, () -> call((String) args[0], args[1], args[2])).code());
    assertEquals(0L, call("%", Long.MIN_VALUE, -1L));
    for (String name : List.of("contains", "startsWith", "endsWith")) {
      assertEquals(true, call(name, "abc", "abc"));
      assertEquals(false, call(name, "abc", "xyz"));
    }
    assertThrows(CelException.class, () -> Functions.stringPredicate("missing", "a", "b"));
    assertThrows(CelException.class, () -> call("optional.none", 1L, 2L, 3L));
  }

  @Test
  public void genericConversionsAndMixedComparisons() {
    for (double invalid :
        new double[] {
          Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0x1p63, -0x1p63
        }) assertThrows(CelException.class, () -> call("int", invalid));
    for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 0x1p64})
      assertThrows(CelException.class, () -> call("uint", invalid));
    assertEquals("false", call("string", false));
    assertEquals("7", call("string", 7L));
    assertEquals("7", call("string", new UInt(7)));
    assertEquals(1, Functions.compare(1L, Double.NEGATIVE_INFINITY));
    assertEquals(-1, Functions.compare(1L, Double.POSITIVE_INFINITY));
    assertThrows(CelException.class, () -> Functions.compare(1L, "1"));
    assertThrows(CelException.class, () -> Functions.compare(new Bytes(new byte[0]), 1L));
    assertThrows(CelException.class, () -> Functions.compare(Instant.EPOCH, 1L));
    assertThrows(CelException.class, () -> Functions.compare(Duration.ZERO, 1L));
    assertEquals(Duration.ZERO, call("duration", "0"));
    assertEquals(Duration.ofSeconds(1), call("duration", "+1s"));
    assertEquals(Duration.ofMillis(-1500), call("duration", "-1.5s"));
    for (String invalid : List.of("", "+", "x1s", "1sx", "1sX2s"))
      assertThrows(CelException.class, () -> call("duration", invalid));
    for (Object value :
        List.of(
            false, "", new Bytes(new byte[0]), List.of(), Map.of(), Duration.ZERO, Instant.EPOCH))
      assertEquals(OptionalValue.none(), call("optional.ofNonZeroValue", value));
    for (Object value : List.of(true, "x", new Bytes(new byte[] {1}), List.of(1L), Map.of("x", 1L)))
      assertEquals(OptionalValue.of(value), call("optional.ofNonZeroValue", value));
  }

  @Test
  public void typeJoinsAndNullableWrappers() {
    var wrapped = CelType.wrapper(CelType.INT);
    assertTrue(wrapped.accepts(CelType.NULL));
    assertTrue(wrapped.accepts(CelType.INT));
    assertFalse(wrapped.accepts(CelType.STRING));
    assertTrue(CelType.INT.accepts(wrapped));
    assertFalse(CelType.STRING.accepts(wrapped));
    assertTrue(CelType.INT.accepts(CelType.parameter("T")));
    assertEquals(
        CelType.list(CelType.INT), CelType.join(CelType.of("list"), CelType.list(CelType.INT)));
    assertEquals(
        CelType.list(CelType.INT), CelType.join(CelType.list(CelType.INT), CelType.of("list")));
    for (CelType type :
        List.of(
            CelType.list(CelType.INT),
            CelType.map(CelType.STRING, CelType.INT),
            CelType.type(CelType.INT))) {
      assertEquals(CelType.DYN, CelType.join(CelType.NULL, type));
      assertEquals(CelType.DYN, CelType.join(type, CelType.NULL));
    }
    var opaque = new CelType("opaque", List.of(CelType.INT));
    assertEquals(opaque, CelType.join(CelType.NULL, opaque));
    assertEquals(CelType.TIMESTAMP, Environment.builtinType("google.protobuf.Timestamp"));
    assertEquals(CelType.DURATION, Environment.builtinType("google.protobuf.Duration"));
  }

  @Test
  public void activationAndDebugScopeContracts() throws Exception {
    var context = new EvalContext(env, Map.of("x", 1L));
    context.push("x", 2L);
    context.push("y", 3L);
    assertEquals(2L, context.getLocal(0));
    assertEquals(1L, context.getRoot(".x"));
    assertThrows(CelException.class, () -> context.getRoot("missing"));
    assertThrows(CelException.class, () -> context.charge(-1));
    var scope = new CelNode.DebugScope(context.visibleBindings());
    var interop = InteropLibrary.getUncached();
    assertEquals("CEL activation", interop.toDisplayString(scope));
    assertFalse(interop.isMemberReadable(scope, "missing"));
    assertThrows(UnknownIdentifierException.class, () -> interop.readMember(scope, "missing"));
    var node = new ExprNode(ExprNode.Kind.CONSTANT, "", 1L, 0);
    assertNull(node.getSourceSection());
    assertFalse(node.hasScope(null));
    assertThrows(UnsupportedMessageException.class, () -> node.getScope(null, true));
  }

  @Test
  public void networkValidationAndClassification() {
    for (int width : new int[] {0, 31, 33, 129})
      assertThrows(
          IllegalArgumentException.class, () -> new NetworkValue.Address(BigInteger.ZERO, width));
    assertThrows(
        IllegalArgumentException.class, () -> new NetworkValue.Address(BigInteger.valueOf(-1), 32));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NetworkValue.Address(BigInteger.ONE.shiftLeft(32), 32));
    for (String text :
        List.of("::g", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8"))
      assertThrows(IllegalArgumentException.class, () -> NetworkValue.parse(text));
    for (String text :
        List.of(
            "0.0.0.0",
            "127.0.0.1",
            "169.254.1.1",
            "224.0.0.1",
            "255.255.255.255",
            "::",
            "::1",
            "fe80::1",
            "ff02::1")) assertFalse(text, NetworkValue.parse(text).global());
    assertTrue(NetworkValue.parse("8.8.8.8").global());
    assertTrue(NetworkValue.parse("2001:db8::1").global());
    assertThrows(CelException.class, () -> NetworkValue.apply("missing", new Object[] {"x"}));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NetworkValue.Prefix(NetworkValue.parse("::1"), -1));
    assertEquals(
        false,
        NetworkValue.apply(
            "containsCIDR",
            new Object[] {NetworkValue.prefix("10.0.0.0/24"), NetworkValue.prefix("10.0.0.0/16")}));
  }

  @Test
  public void extensionResourceLimitsAndFormattingBoundaries() {
    var generous =
        Cel.newBuilder().variable("x", CelType.STRING).evaluationBudget(20_000_000).build();
    String large = "x".repeat(1_000_001);
    for (String expression : List.of("'a'.replace('a', x)", "'%s'.format([x])"))
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(
                  CelException.class,
                  () -> generous.compile(expression).evaluate(Map.of("x", large)))
              .code());
    Object nested = 1L;
    for (int i = 0; i < 103; i++) nested = List.of(nested);
    Object deeplyNested = nested;
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    ExtensionFunctions.apply(
                        "flatten",
                        new Object[] {deeplyNested, 200L},
                        new EvalContext(generous, Map.of())))
            .code());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () ->
                    ExtensionFunctions.apply(
                        "flatten",
                        new Object[] {Collections.nCopies(1_000_001, 1L)},
                        new EvalContext(generous, Map.of())))
            .code());
    assertThrows(
        CelException.class,
        () ->
            ExtensionFunctions.apply(
                "missing", new Object[] {"x"}, new EvalContext(env, Map.of())));
    assertEquals("0", Cel.compile("'%b'.format([false])").evaluate());
    assertEquals("1e+20", Cel.compile("'%.0e'.format([1e20])").evaluate());
    assertEquals("x", Cel.compile("s.trim()").evaluate(Map.of("s", "\t\r\n x \t")));
    assertEquals("", Cel.compile("s.trim()").evaluate(Map.of("s", " \t")));
    assertEquals(List.of("a,b"), Cel.compile("'a,b'.split(',',1)").evaluate());
    assertEquals(List.of("a", "b"), Cel.compile("'a,b'.split(',',2)").evaluate());
    assertEquals(
        Double.POSITIVE_INFINITY,
        Cel.compile("math.greatest(x, 1.0)").evaluate(Map.of("x", Double.POSITIVE_INFINITY)));
  }

  @Test
  public void constructorOnlyWellKnownTypesDoNotExposeHostFields() {
    for (ObjectType type : env.objectTypes.values()) {
      assertFalse(type.accepts(1L));
      assertFalse(type.has(1L, "value"));
      assertThrows(CelException.class, () -> type.get(1L, "value"));
    }
    assertThrows(CelException.class, () -> WellKnownTypes.wrapperValue("Int32Value", "x"));
    assertEquals(
        (long) Integer.MIN_VALUE,
        WellKnownTypes.wrapperValue("Int32Value", (long) Integer.MIN_VALUE));
    assertThrows(
        CelException.class,
        () -> WellKnownTypes.wrapperValue("Int32Value", (long) Integer.MIN_VALUE - 1));
    assertEquals(
        new UInt(0xffffffffL), WellKnownTypes.wrapperValue("UInt32Value", new UInt(0xffffffffL)));
  }

  @Test
  public void runtimeFallbacksRejectMismatchedOperands() {
    for (Object[] arguments :
        List.of(
            new Object[] {"+", new Bytes(new byte[0]), 1L},
            new Object[] {"+", List.of(), 1L},
            new Object[] {"+", Instant.EPOCH, 1L},
            new Object[] {"+", Duration.ZERO, 1L},
            new Object[] {"-", Instant.EPOCH, 1L},
            new Object[] {"-", Duration.ZERO, 1L},
            new Object[] {"matches", "a", 1L},
            new Object[] {"getHours", Instant.EPOCH, 1L}))
      assertThrows(
          CelException.class, () -> call((String) arguments[0], arguments[1], arguments[2]));
    var custom =
        Cel.newBuilder()
            .function(
                "pair",
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                a -> (long) a.get(0) + (long) a.get(1))
            .build();
    assertEquals(3L, Functions.apply("pair", new Object[] {1L, 2L}, custom));
    assertEquals(1L, call("/", Long.MIN_VALUE, Long.MIN_VALUE));
    assertEquals("1.0e16", call("string", 1e16));
    assertEquals(-1L, call("getSeconds", Duration.ofSeconds(-1)));
    assertFalse(Values.equal(OptionalValue.of(1L), 1L));
    assertFalse(Values.equal(Double.NaN, 1L));
    assertFalse(Values.equal(CelType.INT, 1L));
    assertFalse(Values.equal(CelType.INT, CelType.STRING));
    Iterable<Long> many = () -> Collections.nCopies(1_000_001, 1L).iterator();
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> Values.in(many)).code());
    assertThrows(
        CelException.class, () -> HostCollections.mergeEntries(new HashMap<>(), Map.of(1.5, 1L)));
    assertEquals(
        false,
        NetworkValue.apply(
            "containsCIDR",
            new Object[] {NetworkValue.prefix("10.0.0.0/8"), NetworkValue.prefix("11.0.0.0/16")}));
  }

  @Test
  public void unknownMathNamesFailWithoutHostDispatch() {
    assertThrows(
        CelException.class,
        () ->
            ExtensionFunctions.apply(
                "math.missing", new Object[] {1.0}, new EvalContext(env, Map.of())));
    assertEquals("0.000000", Cel.compile("'%f'.format([0.0])").evaluate());
    assertThrows(CelException.class, () -> Cel.compile("'%.123'.format([1.0])").evaluate());
    for (String text : List.of("\b", "\u000e", "\u200b"))
      assertEquals(text, Cel.compile("x.trim()").evaluate(Map.of("x", text)));
  }

  @Test
  public void inferredConstantsAndEmptyObjectPresence() {
    ObjectType type =
        new ObjectType() {
          public String name() {
            return "Record";
          }

          public Map<String, CelType> fields() {
            return Map.of("value", CelType.INT);
          }

          public Object construct(Map<String, Object> fields) {
            return fields;
          }

          public Object get(Object value, String name) {
            return ((Map<?, ?>) value).get(name);
          }

          public boolean has(Object value, String name) {
            return ((Map<?, ?>) value).containsKey(name);
          }

          public boolean accepts(Object value) {
            return value instanceof Map;
          }
        };
    assertTrue(type.isZero(Map.of()));
    assertFalse(type.isZero(Map.of("value", 0L)));
    var inferred =
        Cel.newBuilder().inferVariables(true).constant("answer", 42L).objectType(type).build();
    assertEquals(42L, inferred.compile("answer").evaluate());
    assertEquals(CelType.of("Record"), inferred.compile("Record").evaluate());
    assertEquals(
        OptionalValue.none(), inferred.compile("optional.ofNonZeroValue(Record{})").evaluate());
  }

  @Test
  public void fractionalNegativeDurationsTruncateTowardZero() {
    assertEquals(0L, call("getSeconds", Duration.ofNanos(-1)));
    assertThrows(CelException.class, () -> call("matches", 1L, "a"));
    var strict =
        Cel.newBuilder()
            .variable("i", CelType.INT)
            .variable("d", CelType.DOUBLE)
            .variable("x", CelType.DYN)
            .build();
    for (String source : List.of("-i", "-d", "-x", "null == 1", "1 == null"))
      assertNotNull(strict.compile(source));
    assertThrows(CelException.class, () -> strict.compile("int == 1"));
    assertThrows(CelException.class, () -> strict.compile("optional.missing()"));
    assertEquals(
        List.of(Instant.ofEpochSecond(1)),
        Cel.compile("[1].map(x, google.protobuf.Timestamp{seconds:x})").evaluate());
  }
}
