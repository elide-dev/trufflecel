package dev.trufflecel;

import static dev.trufflecel.CanonicalAst.*;
import static org.junit.Assert.*;

import com.google.protobuf.Message;
import java.util.*;
import org.junit.Test;

public final class AstInterchangeTest {
  private static final Environment ENV = Cel.newBuilder().inferVariables(true).build();

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
