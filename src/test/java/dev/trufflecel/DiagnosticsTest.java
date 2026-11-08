package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public final class DiagnosticsTest {
  @Test
  public void checkerReportsIndependentFindingsInSourceOrder() {
    String source = "[1 + true,\n missing, {'x': unknown}, Missing{a: nope}]";
    CelException error =
        assertThrows(CelException.class, () -> Cel.newBuilder().build().compile(source));
    assertEquals(5, error.issues().size());
    assertEquals(CelException.Code.TYPE, error.code());
    int previous = -1;
    for (var issue : error.issues()) {
      assertTrue(issue.offset() >= previous);
      assertTrue(issue.line() > 0);
      previous = issue.offset();
    }
    assertTrue(error.getMessage(), error.getMessage().contains("unknown object type"));
    assertTrue(error.getMessage(), error.getMessage().contains("undeclared reference: nope"));
    assertTrue(error.format(source).contains("\n^") || error.format(source).contains(" ^"));
    assertThrows(UnsupportedOperationException.class, () -> error.issues().clear());
  }

  @Test
  public void checkerBoundsDiagnosticVolumeAndKeepsDynamicParsing() {
    var entries = new ArrayList<String>();
    for (int i = 0; i < 150; i++) entries.add("missing" + i);
    CelException error =
        assertThrows(
            CelException.class,
            () -> Cel.newBuilder().build().compile("[" + String.join(",", entries) + "]"));
    assertEquals(CelException.MAX_ISSUES, error.issues().size());
    assertEquals(3L, Cel.newBuilder().build().parse("x + y").evaluate(Map.of("x", 1, "y", 2)));
    CelException bounded =
        assertThrows(
            CelException.class,
            () -> Cel.newBuilder().maxAstNodes(2).build().compile("[missing, other]"));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, bounded.code());
    assertEquals(1, bounded.issues().size());
  }

  @Test
  public void environmentCopiesPreserveConfigurationAndStayIndependent() {
    Environment original =
        Cel.newBuilder()
            .container("pkg")
            .variable("pkg.x", CelType.INT)
            .constant("pkg.answer", 40)
            .alias("xAlias", "pkg.x")
            .expectedResultType(CelType.INT)
            .function(
                "plus",
                "plus_int",
                false,
                List.of(CelType.INT),
                CelType.INT,
                args -> (Long) args.get(0) + 1)
            .validation(Environment.Validation.STRICT)
            .homogeneousLiterals(true)
            .maxComprehensionNesting(2)
            .maxAstNodes(1000)
            .maxDepth(50)
            .maxSourceLength(1000)
            .maxRegexProgramSize(100)
            .evaluationBudget(100)
            .build();
    Environment copy = original.toBuilder().variable("pkg.y", CelType.INT).build();
    assertEquals(42L, copy.compile("plus(xAlias) + answer").evaluate(Map.of("pkg.x", 1)));
    assertEquals(2L, copy.compile("y").evaluate(Map.of("pkg.y", 2)));
    assertThrows(CelException.class, () -> original.compile("y"));
    assertEquals(original.constructorOnlyTypes, copy.constructorOnlyTypes);
    assertEquals(original.runtimeObjectTypes.length, copy.runtimeObjectTypes.length);
    assertEquals(original.libraryPolicy.libraries, copy.libraryPolicy.libraries);
    assertEquals(original.aliases, copy.aliases);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            copy.toBuilder()
                .function("another", "plus_int", false, List.of(), CelType.INT, args -> 0L));
    Environment limited =
        Cel.newBuilder()
            .macros(false)
            .inferVariables(true)
            .libraries(Map.of())
            .allowedFunctions(Set.of("+"))
            .build()
            .toBuilder()
            .build();
    assertEquals(3L, limited.compile("x + 1").evaluate(Map.of("x", 2)));
    assertThrows(CelException.class, () -> limited.compile("size(x)"));
    assertThrows(CelException.class, () -> limited.compile("has(x.y)"));
    assertThrows(NullPointerException.class, () -> new Environment.Builder(null));
  }

  @Test
  public void offsetEncodingKeepsNumericPositionsAndFormatsUnicodeCarets() {
    String source = "😀\n'😀' + nope";
    int javaOffset = source.indexOf("nope");
    int pointOffset = source.codePointCount(0, javaOffset);
    var javaIssue = new CelException.Issue(CelException.Code.TYPE, "unknown", javaOffset, -1, -1);
    var pointIssue =
        new CelException.Issue(
            CelException.Code.TYPE,
            "unknown",
            pointOffset,
            -1,
            -1,
            CelException.OffsetEncoding.CODE_POINT);
    assertEquals(CelException.OffsetEncoding.UTF16, javaIssue.offsetEncoding());
    assertEquals(javaOffset, javaIssue.locate(source).offset());
    assertEquals(pointOffset, pointIssue.locate(source).offset());
    assertEquals(8, javaIssue.locate(source).column());
    assertEquals(7, pointIssue.locate(source).column());
    assertEquals(2, pointIssue.locate(source).line());
    assertTrue(javaIssue.format(source), javaIssue.format(source).endsWith("'😀' + nope\n      ^"));
    assertTrue(
        pointIssue.format(source), pointIssue.format(source).endsWith("'😀' + nope\n      ^"));
    assertEquals(
        CelException.OffsetEncoding.CODE_POINT,
        new CelException(List.of(pointIssue)).offsetEncoding());
    assertEquals(
        "end at offset 1 (line 1, column 2)\n😀\n ^",
        new CelException.Issue(
                CelException.Code.SYNTAX, "end", 1, -1, -1, CelException.OffsetEncoding.CODE_POINT)
            .format("😀"));
    assertEquals(
        "outside at offset 2",
        new CelException.Issue(
                CelException.Code.SYNTAX,
                "outside",
                2,
                -1,
                -1,
                CelException.OffsetEncoding.CODE_POINT)
            .format("😀"));
    assertThrows(
        NullPointerException.class,
        () -> new CelException.Issue(CelException.Code.TYPE, "x", 0, 1, 1, null));
    String longLine = "😀".repeat(500);
    String clipped =
        new CelException.Issue(
                CelException.Code.TYPE,
                "middle",
                250,
                -1,
                -1,
                CelException.OffsetEncoding.CODE_POINT)
            .format(longLine);
    assertTrue(clipped.contains("..." + "😀".repeat(160) + "..."));
    assertTrue(clipped.endsWith(" ".repeat(83) + "^"));
  }

  @Test
  public void rendersCaretsWithoutRetainingOrDumpingTheWholeSource() {
    String source = "first\n\tbad\r\nlast";
    var issue = new CelException.Issue(CelException.Code.SYNTAX, "expected expression", 7, -1, -1);
    assertEquals(
        "expected expression at offset 7 (line 2, column 2)\n bad \n ^", issue.format(source));
    var atStart = new CelException(CelException.Code.TYPE, "bad", 0);
    assertTrue(atStart.format("\nx").endsWith("\n\n^"));
    assertTrue(
        new CelException(CelException.Code.TYPE, "end", 3).format("abc").endsWith("abc\n   ^"));
    String longLine = "a".repeat(500);
    String clipped = new CelException(CelException.Code.TYPE, "middle", 250).format(longLine);
    assertTrue(clipped.contains("..." + "a".repeat(160) + "..."));
    assertTrue(clipped.length() < 350);
    var absent = new CelException(CelException.Code.TYPE, "unknown source");
    assertEquals("unknown source", absent.format(""));
    assertEquals(
        "outside at offset 10",
        new CelException(CelException.Code.TYPE, "outside", 10).format("x"));
    assertThrows(NullPointerException.class, () -> absent.format(null));
    var multiple =
        new CelException(
            List.of(new CelException.Issue(CelException.Code.TYPE, "first", -1, -1, -1), issue));
    var located = multiple.locate(source);
    assertEquals(2, located.issues().get(1).line());
    assertSame(located, located.locate(source));
    assertTrue(located.format(source).startsWith("first\n\n"));
  }
}
