package dev.trufflecel;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ParserRecoveryTest {
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
  }
}
