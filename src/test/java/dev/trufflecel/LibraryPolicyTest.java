package dev.trufflecel;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class LibraryPolicyTest {
  private static void disabled(Environment env, String source) {
    for (boolean checked : List.of(true, false)) {
      CelException e =
          assertThrows(
              source,
              CelException.class,
              () -> {
                if (checked) env.compile(source);
                else env.parse(source);
              });
      assertEquals(source, CelException.Code.UNDECLARED, e.code());
      assertTrue(e.offset() >= 0);
    }
  }

  @Test
  public void legacyListHelperNamesRetainTheirImplementations() {
    for (String name : List.of("first", "last")) {
      var env =
          Cel.newBuilder()
              .function(name, List.of(CelType.list(CelType.STRING)), CelType.STRING, a -> "host")
              .build();
      assertEquals("host", env.compile(name + "(['x'])").evaluate());
      assertEquals("host", env.compile("['x']." + name + "()").evaluate());
    }
    assertEquals(
        CelException.Code.TYPE,
        assertThrows(CelException.class, () -> Cel.compile("regex.replace('a','a','b',true)"))
            .code());
  }

  @Test
  public void restrictedLibrariesExposeTheirAdvertisedOperations() {
    var env =
        Cel.newBuilder()
            .libraries(
                Map.of("lists", 2, "strings", 0, "comprehensions", 0, "bindings", 0, "optional", 2))
            .build();
    assertEquals(List.of(0L, 1L), env.compile("lists.range(2)").evaluate());
    assertEquals("\"x\"", env.compile("strings.quote('x')").evaluate());
    assertEquals(Map.of(0L, 1L), env.compile("[1].transformMapEntry(i,v,{i:v})").evaluate());
    assertEquals(1L, env.compile("cel.bind(x,1,x)").evaluate());
    var canonical = CanonicalAst.parse(env, "[1].transformMapEntry(i,v,{i:v})");
    assertEquals(Map.of(0L, 1L), CanonicalAst.program(env, canonical).evaluate());
    disabled(Cel.newBuilder().libraries(Map.of()).build(), "proto.getExt({}, missing)");
    var protos = Cel.newBuilder().libraries(Map.of("protos", 0)).build();
    assertEquals(
        CelException.Code.NO_OVERLOAD,
        assertThrows(CelException.class, () -> protos.parse("proto.getExt({}, missing)").evaluate())
            .code());
    disabled(Cel.newBuilder().allowedFunctions(Set.of()).build(), "proto.hasExt({}, missing)");
  }

  @Test
  public void newlyAddedFunctionsRespectVersions() {
    disabled(Cel.newBuilder().libraries(Map.of("math", 1)).build(), "math.sqrt(4.0)");
    assertEquals(
        2.0,
        Cel.newBuilder().libraries(Map.of("math", 2)).build().compile("math.sqrt(4.0)").evaluate());
    disabled(Cel.newBuilder().libraries(Map.of("optional", 1)).build(), "[1].first()");
    assertEquals(
        OptionalValue.of(1L),
        Cel.newBuilder()
            .libraries(Map.of("optional", 2))
            .build()
            .compile("[1].first()")
            .evaluate());
    disabled(Cel.newBuilder().libraries(Map.of()).build(), "regex.replace('a', 'a', 'b')");
    assertEquals(
        "b",
        Cel.newBuilder()
            .libraries(Map.of("regex", 0))
            .build()
            .compile("regex.replace('a', 'a', 'b')")
            .evaluate());
  }

  @Test
  public void emptyLibrariesKeepCoreButRemoveExtensions() {
    var env = Cel.newBuilder().libraries(Map.of()).build();
    assertEquals(3L, env.compile("1 + 2").evaluate());
    for (String source :
        List.of(
            "json.parse('{}')",
            "yaml.parse('x')",
            "ip('127.0.0.1')",
            "math.abs(1)",
            "'x'.trim()",
            "[1].slice(0, 1)",
            "sets.contains([], [])",
            "base64.encode(b'a')",
            "optional.of(1)",
            "cel.bind(x, 1, x)",
            "[1].transformList(i, x, x)",
            "[1].all(i, x, true)")) disabled(env, source);
    assertEquals(true, env.compile("[1].all(x, x == 1)").evaluate());
    disabled(env, "false && json.parse('{}').hasValue()");
  }

  @Test
  public void versionsAndFunctionListsAreImmutableAndIntersect() {
    var libraries = new HashMap<>(Map.of("math", 0));
    var names = new HashSet<>(Set.of("math.greatest"));
    var builder = Cel.newBuilder().libraries(libraries).allowedFunctions(names);
    libraries.put("math", 2);
    names.add("math.abs");
    var env = builder.build();
    assertEquals(2L, env.compile("math.greatest(1,2)").evaluate());
    disabled(env, "math.least(1,2)");
    disabled(env, "math.abs(1)");
    var math0 = Cel.newBuilder().libraries(Map.of("math", 0)).build();
    disabled(math0, "math.ceil(1.2)");
    assertEquals(
        2.0,
        Cel.newBuilder().libraries(Map.of("math", 1)).build().compile("math.ceil(1.2)").evaluate());
    var lists0 = Cel.newBuilder().libraries(Map.of("lists", 0)).build();
    assertEquals(List.of(1L), lists0.compile("[1,2].slice(0,1)").evaluate());
    disabled(lists0, "[[1]].flatten()");
    disabled(Cel.newBuilder().libraries(Map.of("lists", 1)).build(), "[1].sort()");
    assertEquals(
        List.of(1L),
        Cel.newBuilder()
            .libraries(Map.of("lists", 1))
            .build()
            .compile("[[1]].flatten()")
            .evaluate());
    disabled(
        Cel.newBuilder().libraries(Map.of("optional", 0)).build(),
        "optional.of(1).optFlatMap(x, optional.of(x))");
    for (var bad : List.of(Map.of("unknown", 0), Map.of("math", -1), Map.of("strings", 1)))
      assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().libraries(bad));
  }

  @Test
  public void dynamicReceiverCannotBypassLibraryRestriction() {
    var strings =
        Cel.newBuilder().libraries(Map.of("strings", 0)).variable("x", CelType.DYN).build();
    var program = strings.compile("x.reverse()");
    assertEquals("ba", program.evaluate(Map.of("x", "ab")));
    assertEquals(
        CelException.Code.UNDECLARED,
        assertThrows(CelException.class, () -> program.evaluate(Map.of("x", List.of(1L)))).code());
    var lists = Cel.newBuilder().libraries(Map.of("lists", 2)).variable("x", CelType.DYN).build();
    assertEquals(
        List.of(2L, 1L), lists.compile("x.reverse()").evaluate(Map.of("x", List.of(1L, 2L))));
    disabled(strings, "[1].reverse()");
  }

  @Test
  public void declaredVariablesAndLocalsWinOverNamespaces() {
    var env =
        Cel.newBuilder()
            .variable("math", CelType.INT)
            .function(
                "abs",
                "receiver_abs",
                true,
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                args -> (Long) args.get(0) + (Long) args.get(1))
            .build();
    assertEquals(7L, env.compile("math.abs(2)").evaluate(Map.of("math", 5L)));
    assertEquals(
        List.of(7L), env.compile("[5].map(math, math.abs(2))").evaluate(Map.of("math", 99L)));
    var constant =
        Cel.newBuilder()
            .constant("math", 5L)
            .function(
                "abs",
                "receiver_abs",
                true,
                List.of(CelType.INT, CelType.INT),
                CelType.INT,
                a -> 7L)
            .build();
    assertEquals(7L, constant.compile("math.abs(2)").evaluate());
  }

  @Test
  public void functionRestrictionsApplyToHostCallsAndCanonicalImports() {
    var env =
        Cel.newBuilder()
            .allowedFunctions(Set.of("+", "size"))
            .function("host", List.of(), CelType.INT, a -> 1L)
            .build();
    assertEquals(2L, env.compile("size('ab')").evaluate());
    disabled(env, "host()");
    disabled(env, "[1].map(x,x)");
    var ast = CanonicalAst.parse(Cel.newBuilder().build(), "json.parse('{}')");
    assertThrows(
        CelException.class,
        () -> CanonicalAst.program(Cel.newBuilder().libraries(Map.of()).build(), ast));
  }
}
