package dev.trufflecel;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ParserTest {
  @Test
  public void malformedProgramsFailBeforeExecution() {
    for (String source :
        List.of(
            "",
            " ",
            "// nothing",
            "1 +",
            "(1",
            "[1",
            "{1:}",
            "1 2",
            "@",
            "f(1,)",
            "a..b",
            "has(a)",
            "[1].all(1, true)",
            "[1].all(x)",
            "as",
            "٣",
            "x٣",
            "0xＦＦ",
            "0xffffffffffffffff",
            "18446744073709551616u",
            "-1u",
            "1.2u",
            "1e10000",
            "0x",
            "1e",
            "a.``,",
            "a.`bad\\field`",
            "a.`no close",
            "a.`\n`",
            "'unterminated",
            "'line\nbreak'",
            "'\\q'",
            "'\\x0'",
            "'\\xzz'",
            "'\\uD800'",
            "'\\U00110000'",
            "b'\\u0041'",
            "'abc\\",
            "! -1",
            "Foo{a:1,a:2}")) {
      assertThrows(source, CelException.class, () -> Cel.compile(source));
    }
    CelException e = assertThrows(CelException.class, () -> Cel.compile("1 +"));
    assertEquals(CelException.Code.SYNTAX, e.code());
    assertTrue(e.offset() >= 0);
    assertTrue(e.getMessage().contains("offset"));
  }

  @Test
  public void incompleteOperatorsReportTheirOriginalOffset() {
    for (String suffix : List.of("&", "|", "=", "& 2", "| 2", "= 2")) {
      var failure = assertThrows(CelException.class, () -> Cel.compile("1 " + suffix));
      assertEquals(CelException.Code.SYNTAX, failure.code());
      assertEquals(2, failure.offset());
    }
  }

  @Test
  public void literalEscapesAndRawForms() {
    assertEquals(
        "\u0007\b\f\n\r\t\u000b\\?\"'`",
        Cel.compile("'\\a\\b\\f\\n\\r\\t\\v\\\\\\?\\\"\\\'\\`'").evaluate());
    assertEquals("ABC😀", Cel.compile("'\\x41\\102\\u0043\\U0001f600'").evaluate());
    assertEquals("\\n", Cel.compile("r'\\n'").evaluate());
    assertEquals("a\nb", Cel.compile("'''a\nb'''").evaluate());
    assertArrayEquals(new byte[] {(byte) 255, 0}, (byte[]) Cel.compile("b'\\xff\\000'").evaluate());
    assertEquals(0.5, Cel.compile(".5").evaluate());
    assertEquals(16L, Cel.compile("0x10").evaluate());
    assertEquals(-16L, Cel.compile("-0x10").evaluate());
    assertEquals(true, Cel.compile("!!!!true").evaluate());
    assertEquals(2L, Cel.compile("----2").evaluate());
    assertEquals(2L, Cel.compile("-(-2)").evaluate());
    assertEquals(3L, Cel.compile("// pre\n1+2//tail").evaluate());
    assertEquals(7L, Cel.compile("{'in':7}.`in`").evaluate());
  }

  @Test
  public void inputLimitsPreventStackOverflow() {
    var env = Cel.newBuilder().maxSourceLength(10).build();
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> env.compile("123456789012")).code());
    var small = Cel.newBuilder().maxDepth(16).build();
    for (String source :
        List.of(
            "(".repeat(20) + "1" + ")".repeat(20),
            "!".repeat(20) + "true",
            "x" + ".a".repeat(20),
            "1" + "+1".repeat(20)))
      assertThrows(source, CelException.class, () -> small.parse(source));
    assertThrows(CelException.class, () -> Cel.compile("1" + "+1".repeat(10000)));
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().maxDepth(0));
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().maxDepth(513));
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().maxSourceLength(0));
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().evaluationBudget(0));
    assertTrue(Cel.compile(String.join(" && ", Collections.nCopies(32, "true"))).test());
    assertEquals(25L, Cel.compile(String.join(" + ", Collections.nCopies(25, "1"))).evaluate());
  }
}
