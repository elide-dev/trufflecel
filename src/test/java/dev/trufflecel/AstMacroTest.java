package dev.trufflecel;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class AstMacroTest {
  @Test
  public void customMacroFixedAritiesShareANameWithoutAmbiguity() {
    var environment =
        Cel.newBuilder()
            .macro(Macro.global("choose", 1, (context, target, arguments) -> arguments.getFirst()))
            .macro(Macro.global("choose", 2, (context, target, arguments) -> arguments.get(1)))
            .build();
    assertEquals(1L, environment.compile("choose(1)").evaluate());
    assertEquals(2L, environment.compile("choose(1, 2)").evaluate());
    assertThrows(CelException.class, () -> environment.compile("choose()"));
  }

  private static final Environment DYNAMIC = Cel.newBuilder().inferVariables(true).build();

  @Test
  public void sourceRoundTripsAndOwnedEditing() {
    for (String source :
        List.of(
            "true",
            "null",
            "-9223372036854775808",
            "1u",
            "-0.0",
            "b'\\x00\\xff'",
            "'a\\n\\r\\t\\u0001\\\"\\\\'",
            "[?optional.of(1),2]",
            "{?'x':optional.of(1)}",
            "{'x':1}.x",
            "{'x':1}.?x",
            "{'x':1}[?'x']",
            "1 < 2 ? 3 : 4",
            "!(1 == 2)",
            "-'1'.size()",
            "[1,2].all(v,v>0)",
            "[1,2].map(v,v*2)",
            "[1,2].map(v,v>1,v*2)",
            "has({'x':1}.x)",
            "cel.bind(v,2,v+1)",
            "cel.block([1+1],cel.index(0))",
            "google.protobuf.Int64Value{value:2}")) {
      Ast tree = Ast.parse(DYNAMIC, source);
      assertEquals(source, tree.source());
      Object expected = DYNAMIC.compile(source).evaluateInternal(Map.of());
      assertEquals(source, expected, tree.compile(DYNAMIC).evaluateInternal(Map.of()));
      assertEquals(
          source + " => " + tree.unparse(),
          expected,
          DYNAMIC.compile(tree.unparse()).evaluateInternal(Map.of()));
    }
    Ast original = Ast.parse(DYNAMIC, "x + 1");
    Ast.Node constant =
        original.nodes().stream()
            .filter(n -> n.kind() == Ast.Kind.CONSTANT)
            .findFirst()
            .orElseThrow();
    assertEquals(constant, original.find(constant.id()).orElseThrow());
    assertTrue(original.find(999).isEmpty());
    var editor = original.editor();
    Ast edited = editor.replace(constant.id(), Ast.Node.literal(2)).freeze();
    assertEquals(4L, edited.compile(DYNAMIC).evaluate(Map.of("x", 2L)));
    assertEquals(3L, original.compile(DYNAMIC).evaluate(Map.of("x", 2L)));
    assertThrows(IllegalArgumentException.class, () -> editor.replace(999, Ast.Node.literal(0)));
    Ast changed =
        edited
            .editor()
            .transform(n -> n.kind() == Ast.Kind.CONSTANT ? Ast.Node.literal(3) : n)
            .freeze();
    assertEquals(5L, changed.compile(DYNAMIC).evaluate(Map.of("x", 2L)));
    assertThrows(UnsupportedOperationException.class, () -> original.root().children().clear());
    assertThrows(CelException.class, () -> original.compile(Cel.newBuilder().build()));
  }

  @Test
  public void literalFactoriesAndRendering() {
    for (Object value :
        List.of(
            new UInt(-1),
            new Bytes(new byte[] {0, 127, -1}),
            Instant.EPOCH,
            Duration.ofNanos(-1),
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            CelType.INT,
            true,
            2L,
            "\u007f")) {
      Ast tree = Ast.of(Ast.Node.literal(value));
      assertEquals(value, tree.compile(DYNAMIC).evaluateInternal(Map.of()));
      assertEquals(value, DYNAMIC.compile(tree.unparse()).evaluateInternal(Map.of()));
    }
    var fields = new LinkedHashMap<String, Ast.Node>();
    fields.put("value", Ast.Node.literal(7));
    Ast object = Ast.of(Ast.Node.object("google.protobuf.Int64Value", fields));
    assertEquals(7L, object.compile(DYNAMIC).evaluate());
    assertEquals(
        "(\"hello\").size()",
        Ast.of(Ast.Node.receiverCall(Ast.Node.literal("hello"), "size")).unparse());
    assertEquals(
        2L,
        Ast.of(
                Ast.Node.conditional(
                    Ast.Node.literal(true), Ast.Node.literal(2), Ast.Node.literal(3)))
            .compile(DYNAMIC)
            .evaluate());
    assertEquals(
        1L,
        Ast.of(
                Ast.Node.select(
                    Ast.Node.map(Ast.Node.literal("a b"), Ast.Node.literal(1)), "a b", false))
            .compile(DYNAMIC)
            .evaluate());
    assertEquals(
        2L,
        Ast.of(Ast.Node.index(Ast.Node.list(Ast.Node.literal(2)), Ast.Node.literal(0), false))
            .compile(DYNAMIC)
            .evaluate());
    assertThrows(IllegalArgumentException.class, () -> Ast.Node.literal(List.of(1)));
    assertThrows(IllegalArgumentException.class, () -> Ast.Node.literal(new Object()));
  }

  @Test
  public void fixedReceiverVariadicAndQualifiedMacros() {
    var expanded = new AtomicInteger();
    Environment env =
        Cel.newBuilder()
            .inferVariables(true)
            .macro(
                Macro.global(
                    "twice",
                    1,
                    (ctx, target, args) -> {
                      assertNull(target);
                      expanded.incrementAndGet();
                      return Ast.Node.call("*", args.get(0), Ast.Node.literal(2));
                    }))
            .macro(
                Macro.receiver(
                    "twice",
                    0,
                    (ctx, target, args) -> Ast.Node.call("*", target, Ast.Node.literal(2))))
            .macro(
                Macro.globalVarargs(
                    "total",
                    0,
                    (ctx, target, args) -> {
                      Ast.Node result = Ast.Node.literal(0);
                      for (Ast.Node value : args) result = Ast.Node.call("+", result, value);
                      return result;
                    }))
            .macro(Macro.global("total", 1, (ctx, target, args) -> Ast.Node.literal(99)))
            .macro(
                Macro.receiverVarargs(
                    "append",
                    1,
                    (ctx, target, args) ->
                        Ast.Node.call("+", target, Ast.Node.list(args.toArray(Ast.Node[]::new)))))
            .macro(
                Macro.global(
                    "math.double",
                    1,
                    (ctx, target, args) -> Ast.Node.call("*", args.get(0), Ast.Node.literal(2))))
            .build();
    assertEquals(6L, env.compile("twice(3)").evaluate());
    assertEquals(6L, env.compile(".twice(3)").evaluate());
    assertEquals(8L, env.compile("4.twice()").evaluate());
    assertEquals(0L, env.compile("total()").evaluate());
    assertEquals(99L, env.compile("total(1)").evaluate());
    assertEquals(6L, env.compile("total(1,2,3)").evaluate());
    assertEquals(List.of(1L, 2L, 3L), env.compile("[1].append(2,3)").evaluate());
    assertEquals(4L, env.compile("math.double(2)").evaluate());
    assertEquals(4L, env.compile(".math.double(2)").evaluate());
    Ast tree = Ast.parse(env, "twice(twice(x))");
    int calls = expanded.get();
    assertEquals(12L, tree.compile(env).evaluate(Map.of("x", 3)));
    assertEquals(calls, expanded.get());
    assertEquals("twice(twice(x))", tree.unparse());
    Ast edited =
        tree.editor().transform(n -> n.kind() == Ast.Kind.IDENT ? Ast.Node.literal(2) : n).freeze();
    assertEquals(8L, edited.compile(env).evaluate());
    assertFalse(edited.unparse().contains("twice"));
    assertThrows(CelException.class, () -> env.compile("total(1).append()"));
    assertThrows(CelException.class, () -> env.compile("twice()"));
    Macro global = Macro.globalVarargs("ns.pick", 0, (c, t, a) -> Ast.Node.literal("global"));
    Macro receiver = Macro.receiverVarargs("pick", 0, (c, t, a) -> Ast.Node.literal("receiver"));
    for (boolean globalFirst : List.of(true, false)) {
      var builder = Cel.newBuilder();
      builder.macro(globalFirst ? global : receiver).macro(globalFirst ? receiver : global);
      assertEquals("global", builder.build().compile("ns.pick()").evaluate());
    }
    var exact =
        Cel.newBuilder()
            .macro(Macro.global("choose", 0, (c, t, a) -> Ast.Node.literal(1)))
            .macro(Macro.globalVarargs("choose", 0, (c, t, a) -> Ast.Node.literal(2)))
            .build();
    assertEquals(1L, exact.compile("choose()").evaluate());
  }

  @Test
  public void generatedComprehensionsHaveFreshBindingsAndBounds() {
    Macro sum =
        Macro.global(
            "sum",
            1,
            (ctx, target, args) -> {
              String acc = ctx.freshName(), iter = ctx.freshName();
              return Ast.Node.comprehension(
                  acc,
                  iter,
                  "",
                  args.get(0),
                  Ast.Node.literal(0),
                  Ast.Node.literal(true),
                  Ast.Node.call("+", Ast.Node.identifier(acc), Ast.Node.identifier(iter)),
                  Ast.Node.identifier(acc));
            });
    Environment env = Cel.newBuilder().macro(sum).build();
    assertEquals(6L, env.compile("sum([1,2,3])").evaluate());
    assertEquals(6L, env.compile("sum([sum([1,2]),3])").evaluate());
    ExprNode generated = new Parser("1 + sum([2])", env).parse().children[1];
    assertEquals(4, generated.offset);
    assertEquals(4, generated.bindings[0].sourceStart);
    assertEquals("sum([1, 2])", Ast.parse(env, "sum([1,2])").unparse());
    assertThrows(
        CelException.class,
        () -> Cel.newBuilder().macro(sum).maxAstNodes(5).build().compile("sum([1])"));
    assertThrows(
        CelException.class,
        () -> Cel.newBuilder().macro(sum).maxDepth(2).build().compile("sum([1])"));
    var reject =
        Cel.newBuilder()
            .macro(
                Macro.global(
                    "reject",
                    0,
                    (ctx, target, args) -> {
                      throw ctx.error("rejected");
                    }))
            .build();
    CelException error = assertThrows(CelException.class, () -> reject.compile("reject()"));
    assertEquals(CelException.Code.SYNTAX, error.code());
    assertEquals(0, error.offset());
    assertThrows(CelException.class, () -> Cel.compile("cel.bind(.x,1,x)"));
  }

  @Test
  public void macroConfigurationAndMalformedTrees() {
    assertEquals(
        true,
        Cel.newBuilder()
            .standardMacros(Set.of("has"))
            .build()
            .compile("has({'a':1}.a)")
            .evaluate());
    assertThrows(
        CelException.class,
        () -> Cel.newBuilder().standardMacros(Set.of()).build().compile("[1].all(x,true)"));
    assertThrows(
        CelException.class,
        () -> Cel.newBuilder().standardMacros(Set.of()).build().compile("has({'a':1}.a)"));
    assertThrows(
        CelException.class,
        () -> Cel.newBuilder().standardMacros(Set.of()).build().compile("cel.bind(x,1,x)"));
    Macro identity = Macro.global("identity", 1, (ctx, target, args) -> args.get(0));
    assertThrows(
        CelException.class,
        () -> Cel.newBuilder().macro(identity).macros(false).build().compile("identity(1)"));
    assertThrows(
        IllegalArgumentException.class, () -> Cel.newBuilder().macro(identity).macro(identity));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Cel.newBuilder()
                .macro(Macro.globalVarargs("x", 0, (c, t, a) -> Ast.Node.literal(0)))
                .macro(Macro.globalVarargs("x", 1, (c, t, a) -> Ast.Node.literal(0))));
    assertThrows(
        IllegalArgumentException.class, () -> Cel.newBuilder().standardMacros(Set.of("missing")));
    assertThrows(
        IllegalArgumentException.class,
        () -> Macro.global("", 0, (c, t, a) -> Ast.Node.literal(0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Macro.global("x", -1, (c, t, a) -> Ast.Node.literal(0)));
    assertThrows(IllegalArgumentException.class, () -> Ast.Node.identifier(""));
    assertThrows(IllegalArgumentException.class, () -> Ast.of(Ast.Node.map(Ast.Node.literal(1))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Ast.of(new Ast.Node(0, Ast.Kind.INDEX, "", false, List.of(), false, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Ast.of(new Ast.Node(0, Ast.Kind.CALL, "x", true, List.of(), false, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Ast.of(new Ast.Node(0, Ast.Kind.BLOCK, "", null, List.of(), false, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Ast.of(new Ast.Node(0, Ast.Kind.BLOCK_INDEX, "", -1L, List.of(), false, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Ast.of(
                new Ast.Node(
                    1,
                    Ast.Kind.LIST,
                    "",
                    null,
                    List.of(new Ast.Node(1, Ast.Kind.IDENT, "x", null, List.of(), false, 0)),
                    false,
                    0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Ast.Node(-1, Ast.Kind.IDENT, "x", null, List.of(), false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Ast.Node(0, Ast.Kind.OBJECT, "x", List.of(1), List.of(), false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> Ast.of(new Ast.Node(Long.MAX_VALUE, Ast.Kind.IDENT, "x", null, List.of(), false, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Ast.of(
                new Ast.Node(
                    Long.MAX_VALUE - 1,
                    Ast.Kind.LIST,
                    "",
                    null,
                    List.of(Ast.Node.literal(1)),
                    false,
                    0)));
    Ast.Node deep = Ast.Node.literal(0);
    for (int i = 0; i < 257; i++) deep = Ast.Node.list(deep);
    Ast.Node excessive = deep;
    assertThrows(CelException.class, () -> Ast.of(excessive));
    assertThrows(
        CelException.class, () -> Ast.parse(Cel.newBuilder().maxSourceLength(1).build(), "12"));
    assertThrows(CelException.class, () -> Ast.parse(DYNAMIC, "!"));
  }

  @Test
  public void customMacrosCannotShadowEnabledStandardMacros() {
    Macro.Expander noop = (ctx, target, args) -> Ast.Node.literal(0);

    // Collisions with comprehension standard macros (receiver style).
    for (Macro conflicting :
        List.of(
            Macro.receiver("map", 2, noop),
            Macro.receiver("map", 3, noop),
            Macro.receiver("all", 2, noop),
            Macro.receiver("all", 3, noop),
            Macro.receiver("exists", 2, noop),
            Macro.receiver("exists", 3, noop),
            Macro.receiver("existsOne", 2, noop),
            Macro.receiver("existsOne", 3, noop),
            Macro.receiver("exists_one", 2, noop),
            Macro.receiver("filter", 2, noop),
            Macro.receiver("optMap", 2, noop),
            Macro.receiver("optFlatMap", 2, noop),
            Macro.receiver("sortBy", 2, noop),
            Macro.receiver("transformList", 3, noop),
            Macro.receiver("transformList", 4, noop),
            Macro.receiver("transformMap", 3, noop),
            Macro.receiver("transformMap", 4, noop),
            Macro.receiver("transformMapEntry", 3, noop),
            Macro.receiver("transformMapEntry", 4, noop)))
      try {
        Cel.newBuilder().macro(conflicting).build();
        fail("expected IllegalArgumentException for: " + conflicting.name());
      } catch (IllegalArgumentException expected) {
        // expected
      }

    // Collision with the standard has macro (global style).
    assertThrows(
        IllegalArgumentException.class,
        () -> Cel.newBuilder().macro(Macro.global("has", 1, noop)).build());

    // Collisions with cel.* macros: receiver-style with the simple name.
    for (Macro conflicting :
        List.of(
            Macro.receiver("bind", 3, noop),
            Macro.receiver("block", 2, noop),
            Macro.receiver("index", 1, noop),
            Macro.receiver("iterVar", 2, noop)))
      try {
        Cel.newBuilder().macro(conflicting).build();
        fail("expected IllegalArgumentException for cel.* receiver: " + conflicting.name());
      } catch (IllegalArgumentException expected) {
        // expected
      }

    // Collisions with cel.* macros: global-style with the qualified name.
    for (Macro conflicting :
        List.of(
            Macro.global("cel.bind", 3, noop),
            Macro.global("cel.block", 2, noop),
            Macro.global("cel.index", 1, noop),
            Macro.global("cel.iterVar", 2, noop)))
      try {
        Cel.newBuilder().macro(conflicting).build();
        fail("expected IllegalArgumentException for cel.* global: " + conflicting.name());
      } catch (IllegalArgumentException expected) {
        // expected
      }

    // Collisions with proto.* macros: both receiver-style and global-style.
    for (Macro conflicting :
        List.of(
            Macro.receiver("getExt", 2, noop),
            Macro.receiver("hasExt", 2, noop),
            Macro.global("proto.getExt", 2, noop),
            Macro.global("proto.hasExt", 2, noop)))
      try {
        Cel.newBuilder().macro(conflicting).build();
        fail("expected IllegalArgumentException for proto.*: " + conflicting.name());
      } catch (IllegalArgumentException expected) {
        // expected
      }

    // Variadic custom macros that overlap with standard macro arities.
    assertThrows(
        IllegalArgumentException.class,
        () -> Cel.newBuilder().macro(Macro.receiverVarargs("map", 2, noop)).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> Cel.newBuilder().macro(Macro.receiverVarargs("map", 3, noop)).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> Cel.newBuilder().macro(Macro.globalVarargs("has", 1, noop)).build());

    // Non-matching arity does not collide: map with arity 4 is not a standard map arity.
    Cel.newBuilder().macro(Macro.receiver("map", 4, noop)).build();
    // Non-matching receiver style: global "map" is not a receiver standard macro.
    Cel.newBuilder().macro(Macro.global("map", 2, noop)).build();
    // Non-matching name does not collide.
    Cel.newBuilder().macro(Macro.receiver("custom", 2, noop)).build();
    // Variadic with minimum above all standard arities does not collide.
    Cel.newBuilder().macro(Macro.receiverVarargs("map", 4, noop)).build();
    // Receiver "has" does not collide: standard has is global, not receiver.
    Cel.newBuilder().macro(Macro.receiver("has", 1, noop)).build();
    // exists_one with arity 3 does not collide: standard exists_one only accepts arity 2.
    Cel.newBuilder().macro(Macro.receiver("exists_one", 3, noop)).build();

    // Disabling the conflicting standard macro allows the custom registration.
    Cel.newBuilder().standardMacros(Set.of("has")).macro(Macro.receiver("map", 2, noop)).build();
    Cel.newBuilder().standardMacros(Set.of()).macro(Macro.global("has", 1, noop)).build();
    Cel.newBuilder().standardMacros(Set.of("has")).macro(Macro.global("cel.bind", 3, noop)).build();
    Cel.newBuilder().standardMacros(Set.of("has")).macro(Macro.receiver("bind", 3, noop)).build();

    // The error message identifies the conflicting macro.
    String message =
        assertThrows(
                IllegalArgumentException.class,
                () -> Cel.newBuilder().macro(Macro.receiver("map", 2, noop)).build())
            .getMessage();
    assertTrue(message.contains("map"));
    assertTrue(message.contains("standard"));

    // Custom macros that do not collide still expand correctly.
    Environment env =
        Cel.newBuilder().macro(Macro.global("choose", 1, (c, t, a) -> a.getFirst())).build();
    assertEquals(1L, env.compile("choose(1)").evaluate());

    // Standard macros still expand correctly when no custom macro collides.
    Environment withHas = Cel.newBuilder().standardMacros(Set.of("has")).build();
    assertEquals(true, withHas.compile("has({'a':1}.a)").evaluate());
    Environment withMap = Cel.newBuilder().build();
    assertEquals(List.of(2L, 3L), withMap.compile("[1,2].map(x, x + 1)").evaluate());

    // toBuilder preserves existing custom macros and rejects new conflicting ones.
    Environment base =
        Cel.newBuilder()
            .standardMacros(Set.of("has"))
            .macro(Macro.receiver("map", 2, noop))
            .build();
    assertThrows(
        IllegalArgumentException.class,
        () -> base.toBuilder().macro(Macro.global("has", 1, noop)).build());

    // An unknown standard-macro name never shadows anything.
    assertFalse(Macro.shadows("unknown", Macro.global("has", 1, noop)));
  }
}
