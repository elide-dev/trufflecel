package dev.trufflecel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CheckerTest {
  @Test
  public void invalidTypesAreRejected() {
    for (String expr :
        List.of(
            "1 && true",
            "false || 'x'",
            "!1",
            "-true",
            "1 + 'x'",
            "1.0 % 2.0",
            "1 + 1u",
            "1 < 'x'",
            "[1] < [2]",
            "true ? 1 : missing",
            "1 ? 2 : 3",
            "[1]['a']",
            "1[0]",
            "{'a':1}[false]",
            "{1.0: 2}",
            "1.field",
            "1 in 2",
            "size(1)",
            "'x'.contains(1)",
            "int(true)",
            "uint(false)",
            "double([])",
            "bytes(1)",
            "timestamp(false)",
            "duration(1)",
            "bool(1)",
            "string([])",
            "size()",
            "int()",
            "dyn()",
            "type()",
            "unknown()",
            "1.all(x, true)",
            "[1].all(x, x)",
            "[1].map(x, 1, 2)",
            "1.getFullYear()",
            "optional.none(1)",
            "optional.of()")) {
      assertThrows(expr, CelException.class, () -> Cel.newBuilder().build().compile(expr));
    }
  }

  @Test
  public void dynamicEvaluationReportsMissingOverloads() {
    var env = Cel.newBuilder().build();
    for (String expr :
        List.of(
            "1 && true",
            "false || 1",
            "!1",
            "-true",
            "1 ? 2 : 3",
            "1[0]",
            "1.field",
            "1 in 2",
            "size(1)",
            "bytes(1)",
            "timestamp(false)",
            "duration(1)",
            "bool(1)",
            "string([])",
            "1.all(x,true)",
            "[1].all(x,x)",
            "[1][0.5]",
            "[1][double('NaN')]",
            "{1:1, 1:2}",
            "{1.0:1}",
            "unknown(1,2,3)",
            "'x'.contains(1)",
            "'x'.startsWith(1)",
            "'x'.endsWith(1)",
            "'x'.matches(1)",
            "int(true)",
            "uint(true)",
            "double(true)",
            "true + true",
            "1.getHours()",
            "duration('1s').getDate()")) {
      assertThrows(expr, CelException.class, () -> env.parse(expr).evaluate());
    }
    assertThrows(
        CelException.class, () -> Cel.newBuilder().macros(false).build().compile("has({'a':1}.a)"));
  }

  @Test
  public void namespacesConstantsAndGenerics() {
    var env =
        Cel.newBuilder()
            .container("a.b")
            .variable("a.x", CelType.INT)
            .variable("x", CelType.STRING)
            .constant("a.answer", 42)
            .build();
    assertEquals(42L, env.compile("answer").evaluate());
    assertEquals(1L, env.compile("x").evaluate(Map.of("a.x", 1)));
    assertEquals("root", env.compile(".x").evaluate(Map.of("x", "root")));
    assertEquals(1L, env.compile(".a.x").evaluate(Map.of("a.x", 1)));
    var generic =
        Cel.newBuilder()
            .function(
                "first",
                List.of(CelType.list(CelType.parameter("T"))),
                CelType.parameter("T"),
                args -> ((List<?>) args.get(0)).get(0))
            .build();
    assertEquals(CelType.STRING, generic.compile("first(['x'])").resultType());
    assertEquals("x", generic.compile("first(['x'])").evaluate());
    assertEquals(CelType.DYN, generic.compile("first(dyn([1]))").resultType());
    assertThrows(CelException.class, () -> generic.compile("first(1)"));
    assertThrows(CelException.class, () -> generic.compile("first()"));
    assertEquals(CelType.list(CelType.DYN), Cel.compile("[]").resultType());
    assertEquals(CelType.map(CelType.DYN, CelType.DYN), Cel.compile("{}").resultType());
    assertEquals(CelType.list(CelType.DYN), Cel.compile("[1,'x']").resultType());
    assertFalse(
        new CelType("tuple", List.of(CelType.INT))
            .accepts(new CelType("tuple", List.of(CelType.INT, CelType.INT))));
  }
}
