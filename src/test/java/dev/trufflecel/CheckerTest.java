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

  @Test
  public void builtinArityAndOperandBoundaries() {
    var env = Cel.newBuilder().variable("x", CelType.DYN).build();
    for (String source :
        List.of(
            "UnknownMessage{}",
            "1.optMap(v,v)",
            "{'x':1}.sortBy(v,v)",
            "optional.none().value(1)",
            "optional.none().or()",
            "optional.none().orValue()",
            "contains('x')",
            "contains(1,'x')",
            "matches('x',1)",
            "getHours()",
            "getHours(timestamp(0),1)",
            "getHours(timestamp(0),'UTC',1)",
            "timestamp(0) - 1",
            "[1] + 1",
            "null < 1",
            "1 == '1'",
            "1.reverse()",
            "1.flatten()",
            "[1].flatten('x')",
            "1.distinct()",
            "[{}].sort()",
            "cidr('10.0.0.0/8').containsIP(1)",
            "cidr('10.0.0.0/8').containsCIDR(1)",
            "math.greatest(true)",
            "math.bitNot(1,2)",
            "math.bitAnd(1)")) {
      assertThrows(source, CelException.class, () -> env.compile(source));
    }
    assertEquals(CelType.list(CelType.DYN), env.compile("[1] + ['x']").resultType());

    for (String source :
        List.of(
            "x.optMap(v,v)",
            "x.sortBy(v,v)",
            "size(x)",
            "int(x)",
            "x == null",
            "null == x",
            "x < 1",
            "1 < x",
            "x.getHours()",
            "duration('1s').getHours()",
            "x.reverse()",
            "x.flatten()",
            "x.sort()",
            "math.greatest(x)",
            "math.bitNot(x)",
            "cidr('10.0.0.0/8').containsIP(ip('10.0.0.1'))",
            "cidr('10.0.0.0/8').containsCIDR(cidr('10.0.0.0/16'))")) {
      assertNotNull(source, env.compile(source).resultType());
    }
    assertEquals(CelType.BOOL, env.compile("type(1) == int").resultType());
    assertEquals(CelType.BOOL, env.compile("int == type(1)").resultType());
  }

  @Test
  public void astValidationRejectsMalformedOperatorArities() {
    var checker = new Checker(Cel.newBuilder().build(), Map.of(), true);
    var one = new ExprNode(ExprNode.Kind.CONSTANT, "", 1L, 0);
    var truth = new ExprNode(ExprNode.Kind.CONSTANT, "", true, 0);
    for (String operator :
        List.of("&&", "||", "==", "!=", "<", "<=", ">", ">=", "in", "+", "-", "*", "/", "%"))
      assertThrows(
          operator,
          CelException.class,
          () -> checker.check(new ExprNode(ExprNode.Kind.CALL, operator, false, 0, one), Map.of()));
    assertThrows(
        CelException.class,
        () ->
            checker.check(new ExprNode(ExprNode.Kind.CALL, "!", false, 0, truth, truth), Map.of()));
    assertThrows(
        CelException.class,
        () -> checker.check(new ExprNode(ExprNode.Kind.CALL, "neg", false, 0), Map.of()));
    assertEquals(
        CelType.type(CelType.INT),
        checker.check(new ExprNode(ExprNode.Kind.CONSTANT, "", CelType.INT, 0), Map.of()));
    var custom =
        Cel.newBuilder()
            .function("f", List.of(CelType.INT), CelType.INT, args -> args.get(0))
            .build();
    assertEquals(CelType.INT, custom.parse("f()").resultType());
  }

  @Test
  public void hasMacroRequiresFieldSelection() {
    var checker = new Checker(Cel.newBuilder().build(), Map.of(), true);
    // A bare IDENT as the has argument is rejected, mirroring the parser's guard.
    var ident = new ExprNode(ExprNode.Kind.IDENT, "x", null, 0);
    var malformed = new ExprNode(ExprNode.Kind.MACRO, "has", null, 0, ident);
    var error = assertThrows(CelException.class, () -> checker.check(malformed, Map.of()));
    assertTrue(error.getMessage().contains("has requires a field selection"));
    // A SELECT argument passes the structural guard and type-checks normally.
    var mapEnv = Cel.newBuilder().variable("x", CelType.map(CelType.STRING, CelType.INT)).build();
    var mapChecker = new Checker(mapEnv, mapEnv.variables, true);
    var select =
        new ExprNode(
            ExprNode.Kind.SELECT, "a", false, 0, new ExprNode(ExprNode.Kind.IDENT, "x", null, 0));
    var valid = new ExprNode(ExprNode.Kind.MACRO, "has", null, 0, select);
    assertEquals(CelType.BOOL, mapChecker.check(valid, Map.of()));
  }

  @Test
  public void nullIsOnlyAssignableToNullableMessageFields() {
    var builder = Cel.newBuilder();
    TestObjects.register(builder);
    var env = builder.build();
    String type = "cel.expr.conformance.proto3.TestAllTypes";
    for (String field :
        List.of(
            "single_int32_wrapper", "single_nested_message", "single_timestamp", "single_value"))
      env.compile(type + "{" + field + ": null}");
    for (String field :
        List.of("single_int32", "single_string", "standalone_enum", "repeated_int32"))
      assertEquals(
          field,
          CelException.Code.TYPE,
          assertThrows(CelException.class, () -> env.compile(type + "{" + field + ": null}"))
              .code());
    assertThrows(CelException.class, () -> env.parse(type + "{single_int32: null}").evaluate());
  }
}
