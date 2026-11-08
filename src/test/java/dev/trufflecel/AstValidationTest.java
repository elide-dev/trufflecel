package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class AstValidationTest {
  private static final Ast.Node ONE = Ast.Node.literal(1);

  private static Ast.Node node(Ast.Kind kind, Object value, Ast.Node... children) {
    return new Ast.Node(0, kind, "", value, List.of(children), false, -1);
  }

  @Test
  public void editedTreesRejectMalformedShapesBeforeCompilation() {
    for (Ast.Node malformed :
        List.of(
            node(Ast.Kind.CONSTANT, null),
            node(Ast.Kind.CONSTANT, List.of("x")),
            node(Ast.Kind.CONSTANT, 1L, ONE),
            node(Ast.Kind.IDENT, null),
            new Ast.Node(0, Ast.Kind.IDENT, "x", null, List.of(ONE), false, -1),
            node(Ast.Kind.BLOCK_INDEX, 0L, ONE),
            node(Ast.Kind.BLOCK_INDEX, "x"),
            node(Ast.Kind.BLOCK_INDEX, (long) Integer.MAX_VALUE + 1),
            node(Ast.Kind.SELECT, false),
            node(Ast.Kind.SELECT, null, ONE),
            node(Ast.Kind.INDEX, null, ONE, ONE),
            node(Ast.Kind.CONDITIONAL, null),
            node(Ast.Kind.OBJECT, null),
            node(Ast.Kind.OBJECT, List.of("a")),
            node(Ast.Kind.CALL, null))) {
      assertThrows(malformed.toString(), IllegalArgumentException.class, () -> Ast.of(malformed));
      Ast.Editor editor = Ast.of(ONE).editor();
      assertThrows(IllegalArgumentException.class, () -> editor.replace(1, malformed));
      assertEquals(ONE.value(), editor.freeze().root().value());
    }
    for (Object names :
        Arrays.asList(
            null,
            List.of(),
            List.of("a", "b"),
            List.of("", "b", ""),
            List.of("a", "", ""),
            List.of("a", "a", ""),
            List.of("a", "b", "a"),
            List.of("a", "b", "b"))) {
      Ast.Node malformed = node(Ast.Kind.COMPREHENSION, names, ONE, ONE, ONE, ONE, ONE);
      assertThrows(IllegalArgumentException.class, () -> Ast.of(malformed));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> Ast.of(node(Ast.Kind.COMPREHENSION, List.of("a", "b", ""))));
    assertThrows(IllegalArgumentException.class, () -> Ast.Node.literal(Map.of("a", 1)));
    Ast.Node optional = ONE.optional(true);
    assertTrue(optional.optionalEntry());
    assertEquals("[?1]", Ast.of(Ast.Node.list(optional)).unparse());
    Ast.Node deep = ONE;
    for (int i = 0; i < 257; i++) deep = Ast.Node.list(deep);
    Ast.Node excessive = deep;
    Ast.Editor editor = Ast.of(ONE).editor();
    assertThrows(CelException.class, () -> editor.transform(n -> excessive));
    assertEquals(1L, editor.freeze().root().value());
    assertThrows(
        CelException.class,
        () ->
            Ast.of(
                new Ast.Node(
                    0, Ast.Kind.CONSTANT, "", 1L, List.of(), false, -1, false, excessive)));
  }

  private static Ast.Node macro(String name, Object names, Ast.Node... children) {
    return new Ast.Node(0, Ast.Kind.MACRO, name, names, List.of(children), false, -1);
  }

  @Test
  public void macroNodesRequireTheirExactBindingAndChildShapes() {
    for (Ast.Node bad :
        List.of(
            macro("has", null),
            macro("has", null, ONE),
            macro("map", null, ONE, ONE),
            macro("map", List.of(), ONE, ONE),
            macro("map", List.of("a", "b", "c"), ONE, ONE),
            macro("map", List.of(""), ONE, ONE),
            macro("map", List.of("a.b"), ONE, ONE),
            macro("all", List.of("a", "a"), ONE, ONE),
            macro("unknown", List.of("a"), ONE, ONE),
            macro("bind", List.of("a", "b"), ONE, ONE),
            macro("filter", List.of("a"), ONE),
            macro("all", List.of("a"), ONE, ONE, ONE),
            macro("map", List.of("a", "b"), ONE, ONE),
            macro("map", List.of("a"), ONE),
            macro("map", List.of("a"), ONE, ONE, ONE, ONE),
            macro("transformList", List.of("a"), ONE, ONE),
            macro("transformList", List.of("a", "b"), ONE),
            macro("transformList", List.of("a", "b"), ONE, ONE, ONE, ONE)))
      assertThrows(bad.name(), IllegalArgumentException.class, () -> Ast.of(bad));
    for (String source :
        List.of(
            "[1].exists_one(x,true)",
            "[1].existsOne(i,v,true)",
            "[1].filter(x,true)",
            "optional.of(1).optMap(x,x)",
            "optional.of(1).optFlatMap(x,optional.of(x))",
            "[1].sortBy(x,x)",
            "[1].transformList(i,v,v)",
            "[1].transformList(i,v,true,v)",
            "[1].transformMap(i,v,true,v)",
            "[1].transformMapEntry(i,v,true,{i:v})")) {
      Environment env = Cel.newBuilder().build();
      Ast tree = Ast.parse(env, source);
      assertEquals(source, env.compile(source).evaluate(), tree.compile(env).evaluate());
    }
    assertThrows(
        IllegalArgumentException.class, () -> Cel.newBuilder().standardMacros(Set.of("bind")));
  }

  @Test
  public void sourceRenderingQuotesFieldsAndRejectsUnspellableNames() {
    for (String field : List.of("true", "false", "null", "in", "as", "a b")) {
      Ast ast = Ast.of(Ast.Node.select(Ast.Node.map(Ast.Node.literal(field), ONE), field, false));
      assertEquals(1L, Cel.compile(ast.unparse()).evaluate());
    }
    for (String field : List.of("", "`", "\\", "\n", "\r"))
      assertThrows(
          IllegalArgumentException.class,
          () -> Ast.of(Ast.Node.select(ONE, field, false)).unparse());
    for (String name : List.of("@internal", "as", "$iter:a", ".null", "a..b"))
      assertThrows(
          IllegalArgumentException.class, () -> Ast.of(Ast.Node.identifier(name)).unparse());
    assertEquals(".a.b", Ast.of(Ast.Node.identifier(".a.b")).unparse());
    assertEquals("cel.iterVar(1, 2)", Ast.of(Ast.Node.identifier("$iter:1:2")).unparse());
    assertEquals(
        "proto.getExt(x, pkg.ext)",
        Ast.of(
                new Ast.Node(
                    0,
                    Ast.Kind.SELECT,
                    "pkg.ext",
                    false,
                    List.of(Ast.Node.identifier("x")),
                    false,
                    -1,
                    true,
                    null))
            .unparse());
    assertEquals(
        "google.protobuf.Int64Value{?value: optional.of(1)}",
        Ast.of(
                Ast.Node.object(
                    "google.protobuf.Int64Value",
                    Map.of("value", Ast.Node.call("optional.of", ONE).optional(true))))
            .unparse());
    assertEquals("(1)[0]", Ast.of(Ast.Node.index(ONE, Ast.Node.literal(0), false)).unparse());
    assertThrows(IllegalArgumentException.class, () -> Ast.of(Ast.Node.call("+", ONE)).unparse());
    assertThrows(IllegalArgumentException.class, () -> Ast.of(Ast.Node.call("!")).unparse());
  }
}
