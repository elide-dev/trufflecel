package dev.trufflecel;

import static org.junit.Assert.*;
import static dev.trufflecel.CanonicalAst.*;

import com.google.protobuf.*;
import java.util.*;
import org.junit.Test;

public final class CanonicalAstTest {
  @Test
  public void canonicalRoundTripsCoverLanguageFormsAndMacros() {
    Environment env = Cel.newBuilder().inferVariables(true).build();
    for (String source :
        List.of(
            "true",
            "null",
            "1",
            "1u",
            "1.5",
            "b'abc'",
            "'text'",
            "type(1)",
            "x + 1",
            "x - 2 * 3 / 1 % 4",
            "x < 3 && !(x >= 5) || x == 2",
            "x != 4 ? x : 0",
            "[1, 2, 3][1]",
            "{'a': 1}.a",
            "has({'a': 1}.a)",
            "[?optional.of(1), ?optional.none()]",
            "{?'a': optional.of(1)}",
            "{'a': 1}.?a",
            "{'a': 1}[?'a']",
            "'abc'.startsWith('a')",
            "[1,2,3].all(v, v > 0)",
            "[1,2,3].exists(v, v == 2)",
            "[1,2,3].exists_one(v, v == 2)",
            "[1,2,3].filter(v, v > 1)",
            "[1,2,3].map(v, v * 2)",
            "[1,2,3].map(v, v > 1, v * 2)",
            "[1,2,3].all(i, v, i < v)",
            "{'a':1}.transformList(k,v,k)",
            "{'a':1}.transformMap(k,v,v+1)",
            "{'a':1}.transformMapEntry(k,v,{k:v+1})",
            "cel.bind(v, 2, v+x)",
            "cel.block([x+1, cel.index(0)*2], cel.index(1))",
            "google.protobuf.Int64Value{value: 2}")) {
      Message parsed = CanonicalAst.parse(env, source);
      assertEquals(source, parsed, decode("cel.expr.ParsedExpr", new Bytes(parsed.toByteArray())));
      Message checked = CanonicalAst.check(env, parsed);
      Object expected = env.compile(source).evaluateInternal(Map.of("x", 2L));
      assertEquals(
          source + " parsed", expected, program(env, parsed).evaluateInternal(Map.of("x", 2L)));
      assertEquals(
          source + " checked", expected, program(env, checked).evaluateInternal(Map.of("x", 2L)));
      assertFalse(((List<?>) field(checked, "type_map")).isEmpty());
    }
  }

  @Test
  public void typeInterchangeAndInvalidInputs() {
    for (CelType type :
        List.of(
            CelType.DYN,
            CelType.BOOL,
            CelType.INT,
            CelType.UINT,
            CelType.DOUBLE,
            CelType.STRING,
            CelType.BYTES,
            CelType.NULL,
            CelType.TIMESTAMP,
            CelType.DURATION,
            CelType.of("google.protobuf.Any"),
            CelType.list(CelType.INT),
            CelType.map(CelType.STRING, CelType.INT),
            CelType.TYPE,
            CelType.type(CelType.INT),
            CelType.wrapper(CelType.INT),
            CelType.parameter("T"),
            CelType.of("example.Message"),
            new CelType("optional_type", List.of(CelType.STRING)))) {
      assertEquals(type.toString(), type, CanonicalAst.type(CanonicalAst.type(type)));
    }
    Environment env = Cel.newBuilder().build();
    assertThrows(IllegalArgumentException.class, () -> prototype("missing"));
    assertThrows(
        CelException.class, () -> decode("cel.expr.ParsedExpr", new Bytes(new byte[] {-1})));
    assertThrows(IllegalArgumentException.class, () -> program(env, prototype("cel.expr.Expr")));
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalAst.check(env, prototype("cel.expr.Expr")));
    assertThrows(
        IllegalArgumentException.class, () -> program(env, prototype("cel.expr.ParsedExpr")));
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalAst.type(prototype("cel.expr.Type")));
  }

  @Test
  public void importedLoopsPreserveErrorUnknownAndBudgetSemantics() {
    Environment env = Cel.newBuilder().inferVariables(true).build();
    for (String source :
        List.of("[0, 1].all(v, 1/v > 0 && false)", "[0, 1].exists(v, 1/v > 0 || true)")) {
      assertEquals(
          env.compile(source).evaluate(), program(env, CanonicalAst.parse(env, source)).evaluate());
    }
    var unknown = Unknown.of("x");
    Message ast = CanonicalAst.parse(env, "[1,2].all(v, x > v)");
    assertEquals(unknown, program(env, ast).evaluate(Map.of("x", unknown)));
    Environment limited = Cel.newBuilder().inferVariables(true).evaluationBudget(8).build();
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> program(limited, ast).evaluate(Map.of("x", 5L)))
            .code());
  }

  private static long id = 1000;

  private static Message expr(String kind, Message value) {
    return message("cel.expr.Expr", "id", id++, kind, value);
  }

  private static Message lit(Object value) {
    return expr(
        "const_expr",
        message(
            "cel.expr.Constant",
            value instanceof Boolean
                ? "bool_value"
                : value instanceof String ? "string_value" : "int64_value",
            value));
  }

  private static Message call(String name, Message... args) {
    return expr(
        "call_expr", message("cel.expr.Expr.Call", "function", name, "args", List.of(args)));
  }

  private static Message ast(Message expr) {
    return message("cel.expr.ParsedExpr", "expr", expr);
  }

  private static void invalid(Message expr) {
    assertThrows(
        IllegalArgumentException.class,
        () -> program(Cel.newBuilder().inferVariables(true).build(), ast(expr)));
  }

  private static Message loop(Message range, Message condition) {
    return expr(
        "comprehension_expr",
        message(
            "cel.expr.Expr.Comprehension",
            "iter_var",
            "i",
            "accu_var",
            "a",
            "iter_range",
            range,
            "accu_init",
            lit(0L),
            "loop_condition",
            condition,
            "loop_step",
            lit(1L),
            "result",
            lit(2L)));
  }

  @Test
  public void rejectMalformedUntrustedTreesAndEnforceLimits() {
    invalid(message("cel.expr.Expr", "id", 1L));
    Message zero = lit(0L);
    invalid(call("_+_", zero, zero));
    for (long bad : List.of(0L, -1L))
      invalid(
          message(
              "cel.expr.Expr",
              "id",
              bad,
              "const_expr",
              message("cel.expr.Constant", "int64_value", 0L)));
    invalid(expr("const_expr", prototype("cel.expr.Constant")));
    for (List<Integer> indices : List.of(List.of(-1), List.of(1), List.of(0, 0)))
      invalid(
          expr(
              "list_expr",
              message(
                  "cel.expr.Expr.CreateList",
                  "elements",
                  List.of(lit(1L)),
                  "optional_indices",
                  indices)));
    Message key = lit("a");
    for (long entryId : List.of(0L, -1L, (Long) field(key, "id"))) {
      Message entry =
          message(
              "cel.expr.Expr.CreateStruct.Entry", "id", entryId, "map_key", key, "value", lit(1L));
      invalid(
          expr("struct_expr", message("cel.expr.Expr.CreateStruct", "entries", List.of(entry))));
    }
    invalid(
        expr(
            "struct_expr",
            message(
                "cel.expr.Expr.CreateStruct",
                "entries",
                List.of(
                    message(
                        "cel.expr.Expr.CreateStruct.Entry",
                        "id",
                        id++,
                        "field_key",
                        "a",
                        "value",
                        lit(1L))))));
    invalid(
        expr(
            "struct_expr",
            message(
                "cel.expr.Expr.CreateStruct",
                "message_name",
                "M",
                "entries",
                List.of(
                    message(
                        "cel.expr.Expr.CreateStruct.Entry",
                        "id",
                        id++,
                        "map_key",
                        lit("a"),
                        "value",
                        lit(1L))))));
    for (String[] names :
        List.of(
            new String[] {"", "i", ""},
            new String[] {"a", "", ""},
            new String[] {"a", "a", ""},
            new String[] {"a", "i", "a"},
            new String[] {"a", "i", "i"})) {
      Message body =
          (Message)
              field(
                  loop(expr("list_expr", prototype("cel.expr.Expr.CreateList")), lit(false)),
                  "comprehension_expr");
      var builder = body.toBuilder();
      for (int i = 0; i < 3; i++)
        builder.setField(
            body.getDescriptorForType()
                .findFieldByName(List.of("accu_var", "iter_var", "iter_var2").get(i)),
            names[i]);
      invalid(expr("comprehension_expr", builder.build()));
    }
    invalid(call("_?_:_", lit(true)));
    invalid(call("_?._", lit(1L), lit(1L)));
    invalid(call("_?._", lit(1L), expr("ident_expr", message("cel.expr.Expr.Ident", "name", "x"))));
    invalid(call("cel.block", lit(1L), lit(2L)));
    for (Message index :
        List.of(
            lit(-1L),
            lit(2147483648L),
            lit("a"),
            expr("ident_expr", message("cel.expr.Expr.Ident", "name", "x"))))
      invalid(call("cel.index", index));
    for (String field : List.of("primitive", "well_known"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              CanonicalAst.type(
                  message(
                      "cel.expr.Type",
                      field,
                      field.equals("primitive")
                          ? "PRIMITIVE_TYPE_UNSPECIFIED"
                          : "WELL_KNOWN_TYPE_UNSPECIFIED")));
    Environment tiny = Cel.newBuilder().maxSourceLength(1).build();
    assertThrows(CelException.class, () -> CanonicalAst.parse(tiny, "12"));
    Message tree = ast(call("_+_", lit(1L), lit(2L)));
    assertThrows(CelException.class, () -> program(tiny, tree));
    assertThrows(CelException.class, () -> program(Cel.newBuilder().maxDepth(1).build(), tree));
    Bytes large = new Bytes(new byte[8 * 1024 * 1024 + 1]);
    assertThrows(CelException.class, () -> decode("cel.expr.ParsedExpr", large));
    Message huge = ast(lit("x".repeat(8 * 1024 * 1024)));
    assertThrows(CelException.class, () -> program(Cel.newBuilder().build(), huge));
  }

  @Test
  public void generalLoopConditionsAndLegacyConstants() {
    Environment env = Cel.newBuilder().inferVariables(true).build();
    Message range =
        expr("list_expr", message("cel.expr.Expr.CreateList", "elements", List.of(lit(1L))));
    Message dynamic = loop(range, expr("ident_expr", message("cel.expr.Expr.Ident", "name", "x")));
    assertEquals(
        Unknown.of("x"), program(env, ast(dynamic)).evaluate(Map.of("x", Unknown.of("x"))));
    assertThrows(CelException.class, () -> program(env, ast(dynamic)).evaluate(Map.of("x", 1L)));
    Message badRange = ast(loop(lit(1L), lit(true)));
    assertThrows(CelException.class, () -> CanonicalAst.check(env, badRange));
    assertThrows(CelException.class, () -> program(env, badRange).evaluate());
    Message badHelper = ast(call("@not_strictly_false", lit(1L), lit(2L)));
    assertThrows(CelException.class, () -> CanonicalAst.check(env, badHelper));
    for (String source : List.of("[0,1].all(v, 1/v > 0)", "[0,1].exists(v, 1/v < 0)"))
      assertThrows(
          CelException.class, () -> program(env, CanonicalAst.parse(env, source)).evaluate());
    Message helper = ast(call("@not_strictly_false", call("_+_", lit(1L), lit(2L))));
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () -> program(Cel.newBuilder().evaluationBudget(1).build(), helper).evaluate())
            .code());
    for (String kind : List.of("duration_value", "timestamp_value")) {
      Message value =
          message(
              kind.equals("duration_value")
                  ? "google.protobuf.Duration"
                  : "google.protobuf.Timestamp",
              "seconds",
              1L,
              "nanos",
              2);
      Object result =
          program(env, ast(expr("const_expr", message("cel.expr.Constant", kind, value))))
              .evaluate();
      assertEquals(
          kind.equals("duration_value")
              ? java.time.Duration.ofSeconds(1, 2)
              : java.time.Instant.ofEpochSecond(1, 2),
          result);
    }
  }

  @Test
  public void constantExportsAndInternalHelperValidation() {
    for (Object value :
        List.of(
            java.time.Duration.ofSeconds(-1),
            java.time.Duration.ofSeconds(1),
            java.time.Duration.ofSeconds(1, 1),
            java.time.Duration.ofSeconds(-1, 1),
            java.time.Instant.ofEpochSecond(1))) {
      Environment env = Cel.newBuilder().constant("v", value).build();
      Message checked = CanonicalAst.check(env, CanonicalAst.parse(env, "v"));
      assertEquals(value, program(env, checked).evaluate());
    }
    Environment env = Cel.newBuilder().constant("v", new Object()).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalAst.check(env, CanonicalAst.parse(env, "v")));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalAst.parse(Cel.newBuilder().build(), "has(xs[0])"));
    Environment dynamic = Cel.newBuilder().inferVariables(true).build();
    Message ast = CanonicalAst.parse(dynamic, "xs.all(v, true)");
    assertEquals(Unknown.of("xs"), program(dynamic, ast).evaluate(Map.of("xs", Unknown.of("xs"))));
    EvalContext context = new EvalContext(dynamic, Map.of());
    for (List<CelType> types :
        List.of(
            List.of(CelType.INT),
            List.of(CelType.list(CelType.INT), CelType.list(CelType.of("bad")))))
      assertThrows(
          CelException.class,
          () -> ExtensionFunctions.check("@sortByAssociatedKeys", types, true, 0));
    for (List<CelType> types :
        List.of(
            List.<CelType>of(),
            List.of(CelType.INT, CelType.INT, CelType.INT),
            List.of(CelType.map(CelType.INT, CelType.INT), CelType.INT)))
      assertThrows(
          CelException.class, () -> ExtensionFunctions.check("cel.@mapInsert", types, true, 0));
    assertThrows(
        CelException.class,
        () ->
            ExtensionFunctions.apply(
                "@sortByAssociatedKeys", new Object[] {List.of(1L), List.of()}, context));
  }

  @Test
  public void canonicalReferencesMetadataAndEntryValidation() {
    var env = Cel.newBuilder().variable("ns.x", CelType.INT).build();
    Message x = expr("ident_expr", message("cel.expr.Expr.Ident", "name", "x"));
    long xId = (Long) field(x, "id");
    Message checked =
        message(
            "cel.expr.CheckedExpr",
            "expr",
            x,
            "reference_map",
            Map.of(xId, message("cel.expr.Reference", "name", "ns.x")));
    assertEquals(42L, program(env, checked).evaluate(Map.of("ns.x", 42L)));
    Message constant =
        message("cel.expr.Reference", "value", message("cel.expr.Constant", "int64_value", 7L));
    checked = message("cel.expr.CheckedExpr", "expr", x, "reference_map", Map.of(xId, constant));
    assertEquals(7L, program(env, checked).evaluate());
    Message emptyRef =
        message(
            "cel.expr.CheckedExpr",
            "expr",
            x,
            "reference_map",
            Map.of(xId, prototype("cel.expr.Reference")));
    assertThrows(CelException.class, () -> program(env, emptyRef));
    Message select =
        expr(
            "select_expr",
            message(
                "cel.expr.Expr.Select",
                "operand",
                expr("ident_expr", message("cel.expr.Expr.Ident", "name", "ns")),
                "field",
                "x"));
    assertEquals(
        42L,
        program(
                env,
                message(
                    "cel.expr.CheckedExpr",
                    "expr",
                    select,
                    "reference_map",
                    Map.of(
                        (Long) field(select, "id"), message("cel.expr.Reference", "name", "ns.x"))))
            .evaluate(Map.of("ns.x", 42L)));
    Message longTree = ast(call("_+_", call("_+_", lit(1L), lit(2L)), lit(3L)));
    assertThrows(CelException.class, () -> program(Cel.newBuilder().maxDepth(1).build(), longTree));
    long entryId = id++;
    Message entry1 =
        message(
            "cel.expr.Expr.CreateStruct.Entry",
            "id",
            entryId,
            "map_key",
            lit("a"),
            "value",
            lit(1L));
    Message entry2 =
        message(
            "cel.expr.Expr.CreateStruct.Entry",
            "id",
            entryId,
            "map_key",
            lit("b"),
            "value",
            lit(2L));
    invalid(
        expr(
            "struct_expr",
            message("cel.expr.Expr.CreateStruct", "entries", List.of(entry1, entry2))));
    Message positionsEntry = message("cel.expr.SourceInfo.PositionsEntry", "key", 1L, "value", 2);
    assertEquals(
        1,
        ((List<?>)
                field(
                    message("cel.expr.SourceInfo", "positions", List.of(positionsEntry)),
                    "positions"))
            .size());
    var qualified = Cel.newBuilder().variable("a.b", CelType.INT).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalAst.check(qualified, CanonicalAst.parse(qualified, "has(a.b)")));
  }
}
