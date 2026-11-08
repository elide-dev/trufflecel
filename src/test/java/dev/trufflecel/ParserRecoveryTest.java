package dev.trufflecel;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ParserRecoveryTest {
  @Test
  public void embeddedExpressionsRetainOriginalSourceOffsets() {
    Environment env = Cel.newBuilder().build();
    String expression = "[1].map(x, x + 1)";
    ExprNode plain = new Parser(expression, env).parse();
    ExprNode embedded = new Parser(expression, env, 17).parse();
    var left = Ast.preorder(Ast.snapshot(plain));
    var right = Ast.preorder(Ast.snapshot(embedded));
    for (int i = 0; i < left.size(); i++)
      assertEquals(left.get(i).offset() + 17, right.get(i).offset());
    assertEquals(17, embedded.sourceStart);
    assertEquals(17 + expression.length(), embedded.sourceEnd);
    assertEquals(plain.bindings[0].sourceStart + 17, embedded.bindings[0].sourceStart);
    for (String invalid :
        java.util.List.of("[1 + , 2 + ]", "@", "'\\q'", "1 + @", "9223372036854775808", "[")) {
      CelException normal =
          assertThrows(CelException.class, () -> new Parser(invalid, env).parse());
      CelException shifted =
          assertThrows(CelException.class, () -> new Parser(invalid, env, 17).parse());
      assertEquals(normal.issues().size(), shifted.issues().size());
      for (int i = 0; i < normal.issues().size(); i++)
        assertEquals(
            invalid, normal.issues().get(i).offset() + 17, shifted.issues().get(i).offset());
    }
    assertThrows(IllegalArgumentException.class, () -> new Parser("1", env, -1));
    assertThrows(IllegalArgumentException.class, () -> new Parser("1", env, Integer.MAX_VALUE));
  }

  @Test
  public void independentEntriesReportSeveralErrors() {
    Environment env = Cel.newBuilder().inferVariables(true).build();
    for (String source :
        new String[] {
          "[1 + , 2 + ]",
          "f(1 + , 2 + )",
          "{'a': , 'b': }",
          "Thing{a: ,b: }",
          "{'a' 1, 'b' 2}",
          "Thing{1:1,2:2}"
        }) {
      CelException error = assertThrows(CelException.class, () -> env.compile(source));
      assertEquals(source, CelException.Code.SYNTAX, error.code());
      assertTrue(source + " " + error.getMessage(), error.issues().size() >= 2);
      for (CelException.Issue issue : error.issues()) assertTrue(issue.line() > 0);
    }
    CelException bounded =
        assertThrows(CelException.class, () -> env.compile("f(" + ", ".repeat(200) + ")"));
    assertEquals(100, bounded.issues().size());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(
                CelException.class,
                () -> Cel.newBuilder().maxAstNodes(2).build().compile("[1 + , 2 + , 3 + ]"))
            .code());
    assertThrows(CelException.class, () -> env.compile("[1 + , 2 + @]"));
    assertThrows(CelException.class, () -> env.compile("[1 + , 2 +"));
    assertThrows(CelException.class, () -> env.compile("{'a' (1+2), 'b': }"));
    assertEquals(java.util.List.of(1L, 2L), env.compile("[1,2]").evaluate());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    Environment custom =
        Cel.newBuilder()
            .macro(
                Macro.global(
                    "f",
                    1,
                    (c, t, a) -> {
                      calls.incrementAndGet();
                      return a.get(0);
                    }))
            .build();
    assertThrows(CelException.class, () -> custom.compile("f(1 + )"));
    assertEquals(0, calls.get());
  }
}
