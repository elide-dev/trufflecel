package dev.trufflecel;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class EnvironmentControlsTest {
  @Test
  public void regexLimitsApplyToConstantsDynamicPatternsAndCachedPatterns() {
    var small = Cel.newBuilder().maxRegexProgramSize(4).variable("p", CelType.STRING).build();
    assertEquals(true, small.compile("'a'.matches('a')").evaluate());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> small.compile("'abc'.matches('a{20}')")).code());
    var large = Cel.newBuilder().variable("p", CelType.STRING).build();
    assertEquals(false, large.compile("'abc'.matches(p)").evaluate(Map.of("p", "a{20}")));
    for (String expression :
        List.of(
            "'abc'.matches(p)",
            "regex.extract('abc',p)",
            "regex.extractAll('abc',p)",
            "regex.replace('abc',p,'x')"))
      assertEquals(
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(
                  CelException.class,
                  () -> small.compile(expression).evaluate(Map.of("p", "a{20}")))
              .code());
    assertEquals(
        "abc", small.compile("regex.replace('abc',p,'x',0)").evaluate(Map.of("p", "a{20}")));
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().maxRegexProgramSize(0));
  }

  @Test
  public void aliasesResolveBeforeContainersAndPreserveAbsoluteNames() {
    var builder =
        Cel.newBuilder()
            .container("other")
            .variable("example.value", CelType.INT)
            .variable("other.v", CelType.INT)
            .alias("v", "example.value")
            .alias("m", "math")
            .constant("example.limit", 5L)
            .abbreviations("example.limit")
            .function(
                "example.doubleIt", List.of(CelType.INT), CelType.INT, a -> (Long) a.get(0) * 2)
            .alias("api", "example");
    var env = builder.build();
    var input = Map.<String, Object>of("example.value", -3L, "other.v", 9L);
    assertEquals(true, env.compile("m.abs(v) < limit").evaluate(input));
    assertEquals(-6L, env.compile("api.doubleIt(v)").evaluate(input));
    assertEquals(-3L, env.compile("api.value").evaluate(input));
    assertEquals(9L, env.compile(".other.v").evaluate(input));
    assertThrows(CelException.class, () -> env.compile("api.missing()"));
    builder.alias("later", "example.value");
    assertThrows(CelException.class, () -> env.compile("later"));
    for (String alias : List.of("int", "a.b", "9x", ""))
      assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().alias(alias, "p.x"));
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().alias("x", "p..x"));
    assertThrows(
        IllegalArgumentException.class, () -> Cel.newBuilder().abbreviations("a.x", "b.x"));
  }

  @Test
  public void expectedTypeConstrainsSourceTemplatesAndCanonicalImports() {
    var env = Cel.newBuilder().expectedResultType(CelType.BOOL).build();
    assertEquals(true, env.prepare("1 < 2").instantiate().evaluate());
    assertEquals(
        CelException.Code.TYPE, assertThrows(CelException.class, () -> env.compile("1")).code());
    assertThrows(CelException.class, () -> env.parse("'x'"));
    var ast = CanonicalAst.parse(Cel.newBuilder().build(), "[1]");
    assertThrows(CelException.class, () -> CanonicalAst.program(env, ast));
    assertEquals(
        1L, Cel.newBuilder().expectedResultType(CelType.DYN).build().compile("1").evaluate());
    assertThrows(NullPointerException.class, () -> Cel.newBuilder().expectedResultType(null));
  }

  @Test
  public void flatExpressionsAndImportedTreesRespectNodeBudgets() {
    var env = Cel.newBuilder().maxAstNodes(3).build();
    assertEquals(3L, env.compile("1+2").evaluate());
    for (String source : List.of("[1,2,3]", "1+2+3", "{'x':1,'y':2}", "-x+1+2"))
      assertEquals(
          source,
          CelException.Code.RESOURCE_EXHAUSTED,
          assertThrows(CelException.class, () -> env.parse(source)).code());
    var ast = CanonicalAst.parse(Cel.newBuilder().build(), "[1,2,3]");
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> CanonicalAst.program(env, ast)).code());
    assertEquals(
        List.of(1L, 2L, 3L),
        CanonicalAst.program(Cel.newBuilder().maxAstNodes(4).build(), ast).evaluate());
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().maxAstNodes(0));
    ExprNode tree = new Parser("[1,2,3]", Cel.newBuilder().build()).parse();
    assertThrows(CelException.class, () -> env.prepare("[1,2,3]", tree, true));
  }
}
