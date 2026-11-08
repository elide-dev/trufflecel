package dev.trufflecel;

import static dev.trufflecel.CanonicalAst.*;
import static org.junit.Assert.*;

import com.google.protobuf.Message;
import java.util.*;
import org.junit.Test;

public final class AstInterchangeTest {
  private static final Environment ENV = Cel.newBuilder().inferVariables(true).build();

  @Test
  public void cseExportPreservesImportedAndGeneratedSourceLocations() {
    Environment env =
        Cel.newBuilder().variable("x", CelType.INT).commonSubexpressions(true).build();
    String source = "// 😀\n(10 / x) +\n(10 / x)";
    Message parsed = CanonicalAst.parse(env, source);
    Message oldInfo = child(parsed, "source_info");
    oldInfo =
        oldInfo.toBuilder()
            .setField(oldInfo.getDescriptorForType().findFieldByName("location"), "unicode.cel")
            .build();
    parsed =
        parsed.toBuilder()
            .setField(parsed.getDescriptorForType().findFieldByName("source_info"), oldInfo)
            .build();
    for (Message input : List.of(parsed, toV1Alpha1(parsed))) {
      Message checked = check(env, input);
      Message info = child(toCelExpr(checked), "source_info");
      assertEquals("unicode.cel", field(info, "location"));
      assertEquals(field(oldInfo, "line_offsets"), field(info, "line_offsets"));
      assertEquals(field(oldInfo, "macro_calls"), field(info, "macro_calls"));
      List<Message> before = messages(oldInfo, "positions"), after = messages(info, "positions");
      assertTrue(after.containsAll(before));
      assertTrue(after.size() > before.size());
      CelException failure =
          assertThrows(CelException.class, () -> program(env, checked).evaluate(Map.of("x", 0)));
      assertEquals(CelException.Code.DIVIDE_BY_ZERO, failure.code());
      assertEquals(
          source.codePointCount(0, source.indexOf('/', source.indexOf('\n'))), failure.offset());
      assertEquals(2, failure.line());
      assertEquals(5, failure.column());
      assertEquals(CelException.OffsetEncoding.CODE_POINT, failure.offsetEncoding());
      assertTrue(failure.format(source), failure.format(source).endsWith("(10 / x) +\n    ^"));
      assertFalse(
          before.stream().anyMatch(entry -> field(entry, "key").equals(failure.expressionId())));
      assertTrue(
          after.stream()
              .anyMatch(
                  entry ->
                      field(entry, "key").equals(failure.expressionId())
                          && field(entry, "value").equals(failure.offset())));
    }
    Message absent =
        parsed.toBuilder()
            .setField(
                parsed.getDescriptorForType().findFieldByName("source_info"),
                oldInfo.toBuilder()
                    .clearField(oldInfo.getDescriptorForType().findFieldByName("positions"))
                    .build())
            .build();
    assertTrue(messages(child(check(env, absent), "source_info"), "positions").isEmpty());
    var negative = new LinkedHashMap<Long, Integer>();
    for (Message entry : messages(oldInfo, "positions"))
      negative.put((Long) field(entry, "key"), -1);
    Message unknown =
        parsed.toBuilder()
            .setField(
                parsed.getDescriptorForType().findFieldByName("source_info"),
                message("cel.expr.SourceInfo", "positions", negative))
            .build();
    assertEquals(child(unknown, "source_info"), child(check(env, unknown), "source_info"));
  }

  @Test
  public void canonicalUnicodeLocationsSurviveLoweringFoldingAndTraceRestoration() {
    Environment env = Cel.newBuilder().variable("x", CelType.INT).build();
    for (String source :
        List.of("// 😀\nx + 1 + 2", "'😀' == '😀' ? x + 1 + 2 : 0", "// 😀\n1 / 0")) {
      int index = source.contains("x +") ? source.indexOf('+') : source.lastIndexOf('/');
      Message parsed = CanonicalAst.parse(env, source);
      Message checked = check(env, parsed);
      for (Message input : List.of(parsed, checked, toV1Alpha1(parsed), toV1Alpha1(checked))) {
        Program program = program(env, input);
        assertCanonicalLocation(
            source,
            index,
            assertThrows(CelException.class, () -> program.evaluate(Map.of("x", Long.MAX_VALUE))));
        var events = new ArrayList<TraceEvent>();
        assertCanonicalLocation(
            source,
            index,
            assertThrows(
                CelException.class,
                () -> program.trace(events::add).evaluate(Map.of("x", Long.MAX_VALUE))));
        assertFalse(events.isEmpty());
        assertTrue(
            events.stream()
                .filter(event -> event.error() != null)
                .allMatch(
                    event ->
                        event.error().offsetEncoding() == CelException.OffsetEncoding.CODE_POINT));
        if (source.contains("x +")) {
          assertCanonicalLocation(
              source,
              source.lastIndexOf('+'),
              assertThrows(
                  CelException.class, () -> program.evaluate(Map.of("x", Long.MAX_VALUE - 1))));
          assertCanonicalLocation(
              source,
              index,
              assertThrows(CelException.class, () -> program.evaluate(Map.of("x", "wrong"))));
        }
      }
      CelException ordinary =
          assertThrows(
              CelException.class, () -> env.compile(source).evaluate(Map.of("x", Long.MAX_VALUE)));
      assertEquals(CelException.OffsetEncoding.UTF16, ordinary.offsetEncoding());
      assertEquals(index, ordinary.offset());
    }
    String regex = "// 😀\nx.matches('[')";
    Environment strings = Cel.newBuilder().variable("x", CelType.STRING).build();
    Program matches = program(strings, check(strings, CanonicalAst.parse(strings, regex)));
    assertCanonicalLocation(
        regex,
        regex.indexOf("x.matches"),
        assertThrows(CelException.class, () -> matches.evaluate(Map.of("x", "text"))));
    String budgetSource = "// 😀\nx + 1 + 2";
    Message ast = CanonicalAst.parse(env, budgetSource);
    Environment bounded = env.toBuilder().evaluationBudget(1).build();
    CelException budget =
        assertThrows(CelException.class, () -> program(bounded, ast).evaluate(Map.of("x", 1)));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, budget.code());
    assertCanonicalLocation(budgetSource, budgetSource.lastIndexOf('+'), budget);
    Message absent =
        ast.toBuilder()
            .clearField(ast.getDescriptorForType().findFieldByName("source_info"))
            .build();
    CelException missing =
        assertThrows(
            CelException.class, () -> program(env, absent).evaluate(Map.of("x", Long.MAX_VALUE)));
    assertEquals(-1, missing.offset());
    assertEquals(CelException.OffsetEncoding.CODE_POINT, missing.offsetEncoding());
    assertEquals(missing.getMessage(), missing.format(budgetSource));
  }

  @Test
  public void canonicalCheckerAndValidatorFindingsUseImportedPositions() {
    Environment env = Cel.newBuilder().build();
    String source = "// 😀\n['😀', missing, true + 1]";
    Message parsed = CanonicalAst.parse(env, source);
    for (Message input : List.of(parsed, toV1Alpha1(parsed))) {
      CelException failure = assertThrows(CelException.class, () -> check(env, input));
      assertEquals(2, failure.issues().size());
      assertCanonicalLocation(source, source.indexOf("missing"), failure);
      assertEquals(
          CelException.OffsetEncoding.CODE_POINT, failure.issues().get(1).offsetEncoding());
      assertEquals(source.codePointCount(0, source.indexOf('+')), failure.issues().get(1).offset());
    }
    Message absent =
        parsed.toBuilder()
            .clearField(parsed.getDescriptorForType().findFieldByName("source_info"))
            .build();
    CelException unlocated = assertThrows(CelException.class, () -> check(env, absent));
    assertEquals(-1, unlocated.offset());
    assertEquals(CelException.OffsetEncoding.CODE_POINT, unlocated.offsetEncoding());
    String division = "// 😀\n1 / 0";
    Message constant = CanonicalAst.parse(env, division);
    Environment strict = env.toBuilder().validation(Environment.Validation.STRICT).build();
    assertCanonicalLocation(
        division,
        division.lastIndexOf('/'),
        assertThrows(CelException.class, () -> check(strict, constant)));
    String disabled = "// 😀\n'😀'.size()";
    Message call = CanonicalAst.parse(env, disabled);
    assertCanonicalLocation(
        disabled,
        disabled.indexOf('\''),
        assertThrows(
            CelException.class,
            () -> check(env.toBuilder().allowedFunctions(Set.of()).build(), call)));
    String result = "// 😀\n'😀'";
    Message literal = CanonicalAst.parse(env, result);
    assertCanonicalLocation(
        result,
        result.indexOf('\''),
        assertThrows(
            CelException.class,
            () -> check(env.toBuilder().expectedResultType(CelType.INT).build(), literal)));
  }

  private static void assertCanonicalLocation(String source, int javaIndex, CelException error) {
    assertEquals(CelException.OffsetEncoding.CODE_POINT, error.offsetEncoding());
    assertEquals(source.codePointCount(0, javaIndex), error.offset());
    int start = source.lastIndexOf('\n', javaIndex - 1) + 1;
    int end = source.indexOf('\n', javaIndex);
    if (end < 0) end = source.length();
    int column = source.codePointCount(start, javaIndex);
    assertEquals(column + 1, error.column());
    String expected = source.substring(start, end) + "\n" + " ".repeat(column) + "^";
    assertTrue(error.format(source), error.issues().getFirst().format(source).endsWith(expected));
  }

  @Test
  public void macroSourceAndLegacySchemasRoundTrip() {
    for (String source :
        List.of(
            "[1,2].all(v,v>0)",
            "[1,2].map(v,v+1).exists(x,x>2)",
            "has({'a':1}.a)",
            "cel.bind(x,2,x+1)",
            "{'a':1}.transformList(k,v,k)")) {
      Message parsed = CanonicalAst.parse(ENV, source);
      assertFalse(source, messages(child(parsed, "source_info"), "macro_calls").isEmpty());
      String unparsed = CanonicalAst.unparse(ENV, parsed);
      assertEquals(
          source + " => " + unparsed,
          ENV.compile(source).evaluate(),
          ENV.compile(unparsed).evaluate());
      Message legacy = toV1Alpha1(parsed);
      assertEquals(
          "google.api.expr.v1alpha1.ParsedExpr", legacy.getDescriptorForType().getFullName());
      assertArrayEquals(parsed.toByteArray(), legacy.toByteArray());
      assertSame(legacy, toV1Alpha1(legacy));
      assertSame(parsed, toCelExpr(parsed));
      assertEquals(parsed, toCelExpr(legacy));
      assertEquals(ENV.compile(source).evaluate(), program(ENV, legacy).evaluate());
      Message checked = check(ENV, legacy);
      assertEquals(
          "google.api.expr.v1alpha1.CheckedExpr", checked.getDescriptorForType().getFullName());
      assertEquals(ENV.compile(source).evaluate(), program(ENV, checked).evaluate());
      assertEquals(
          ENV.compile(source).evaluate(),
          ENV.compile(CanonicalAst.unparse(ENV, checked)).evaluate());
    }
    assertThrows(
        IllegalArgumentException.class, () -> toV1Alpha1(prototype("google.protobuf.Duration")));
    assertThrows(IllegalArgumentException.class, () -> ast(ENV, prototype("cel.expr.Expr")));
    assertThrows(
        IllegalArgumentException.class, () -> prototype("google.api.expr.v1alpha1.Missing"));
    assertEquals(
        CelType.list(CelType.INT),
        CanonicalAst.type(toV1Alpha1(CanonicalAst.type(CelType.list(CelType.INT)))));
  }

  @Test
  public void editedTreesExportAndCustomMacrosRetainSource() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalAst.parsed(Ast.of(Ast.Node.literal(CelType.list(CelType.INT)))));
    Environment env =
        Cel.newBuilder()
            .inferVariables(true)
            .macro(
                Macro.global(
                    "twice",
                    1,
                    (ctx, target, args) -> Ast.Node.call("*", args.get(0), Ast.Node.literal(2))))
            .build();
    Message parsed = CanonicalAst.parse(env, "twice(twice(x))");
    assertEquals("twice(twice(x))", CanonicalAst.unparse(env, parsed));
    assertEquals(12L, program(env, parsed).evaluate(Map.of("x", 3)));
    Ast editable = ast(env, parsed);
    Ast edited =
        editable
            .editor()
            .transform(n -> n.kind() == Ast.Kind.IDENT ? Ast.Node.literal(4) : n)
            .freeze();
    assertEquals(16L, program(env, CanonicalAst.parsed(edited)).evaluate());
    assertEquals(16L, env.compile(edited.unparse()).evaluate());
    assertFalse(edited.unparse().contains("twice"));
    Message rebuilt = CanonicalAst.parsed(Ast.parse(env, "[1,2].map(x,twice(x))"));
    assertEquals(List.of(2L, 4L), program(env, rebuilt).evaluate());
    assertTrue(CanonicalAst.unparse(env, rebuilt).contains("twice"));
  }

  @Test
  public void macroPlaceholdersAreBoundedAndNonSemantic() {
    Message parsed = CanonicalAst.parse(ENV, "[1].all(x,true)");
    long rootId = (Long) field(child(parsed, "expr"), "id");
    Message original = child(messages(child(parsed, "source_info"), "macro_calls").get(0), "value");
    Message zeroRoot =
        original.toBuilder()
            .setField(original.getDescriptorForType().findFieldByName("id"), 0L)
            .build();
    Message withZero = source(parsed, Map.of(rootId, zeroRoot));
    assertEquals(true, ENV.compile(CanonicalAst.unparse(ENV, withZero)).evaluate());
    Message pointer = message("cel.expr.Expr", "id", 1234L);
    Message withPointer = source(parsed, Map.of(rootId, pointer, 1234L, original));
    assertEquals(true, ENV.compile(CanonicalAst.unparse(ENV, withPointer)).evaluate());
    Message dangling = source(parsed, Map.of(rootId, pointer));
    assertThrows(IllegalArgumentException.class, () -> CanonicalAst.unparse(ENV, dangling));
    assertEquals(true, program(ENV, dangling).evaluate());
    Message cycle = source(parsed, Map.of(rootId, pointer, 1234L, pointer));
    assertThrows(IllegalArgumentException.class, () -> CanonicalAst.unparse(ENV, cycle));
    Message noncall =
        source(
            parsed,
            Map.of(
                rootId,
                message(
                    "cel.expr.Expr",
                    "id",
                    1L,
                    "const_expr",
                    message("cel.expr.Constant", "bool_value", false))));
    assertThrows(IllegalArgumentException.class, () -> CanonicalAst.unparse(ENV, noncall));
    assertEquals(true, program(ENV, noncall).evaluate());
    Message stripped =
        parsed.toBuilder()
            .clearField(parsed.getDescriptorForType().findFieldByName("source_info"))
            .build();
    assertThrows(IllegalArgumentException.class, () -> CanonicalAst.unparse(ENV, stripped));
    assertEquals(true, program(ENV, stripped).evaluate());
    Message deep = original;
    for (int i = 0; i < 20; i++)
      deep =
          message(
              "cel.expr.Expr",
              "id",
              1L,
              "call_expr",
              message("cel.expr.Expr.Call", "function", "f", "args", List.of(deep)));
    Message excessive = source(parsed, Map.of(rootId, deep));
    Environment limited = Cel.newBuilder().inferVariables(true).maxDepth(10).build();
    assertThrows(CelException.class, () -> CanonicalAst.unparse(limited, excessive));
    assertEquals(true, program(limited, excessive).evaluate());
    Message many =
        message(
            "cel.expr.Expr",
            "id",
            1L,
            "call_expr",
            message(
                "cel.expr.Expr.Call", "function", "f", "args", Collections.nCopies(30, original)));
    Message excessiveNodes = source(parsed, Map.of(rootId, many));
    Environment small = Cel.newBuilder().inferVariables(true).maxAstNodes(25).build();
    assertThrows(CelException.class, () -> CanonicalAst.unparse(small, excessiveNodes));
    assertEquals(true, program(small, excessiveNodes).evaluate());
  }

  private static Message source(Message parsed, Map<Long, Message> macros) {
    return parsed.toBuilder()
        .setField(
            parsed.getDescriptorForType().findFieldByName("source_info"),
            message("cel.expr.SourceInfo", "macro_calls", macros))
        .build();
  }
}
