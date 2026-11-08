package dev.trufflecel;

import static dev.trufflecel.CanonicalAst.*;
import static org.junit.Assert.*;

import com.google.protobuf.Message;
import java.util.*;
import org.junit.Test;

public final class AstLocationTest {
  private static final String SOURCE = "// 😀\n1 / x";
  private static final Environment ENV = Cel.newBuilder().variable("x", CelType.INT).build();

  @Test
  public void sourceTreesRetainUtf16LocationsThroughCompilationAndEditing() {
    Ast ast = Ast.parse(ENV, SOURCE);
    int position = SOURCE.indexOf('/', SOURCE.indexOf('\n'));
    assertNode(ast.root(), position, CelException.OffsetEncoding.UTF16, 2, 3);
    assertFailure(ast.compile(ENV), position, CelException.OffsetEncoding.UTF16, 2, 3);
    Ast edited = ast.editor().transform(node -> node.withChildren(node.children())).freeze();
    assertNode(edited.root(), position, CelException.OffsetEncoding.UTF16, 2, 3);
    assertFailure(edited.compile(ENV), position, CelException.OffsetEncoding.UTF16, 2, 3);
    assertEquals(
        CelException.OffsetEncoding.UTF16,
        new Ast.Node(0, Ast.Kind.IDENT, "x", null, List.of(), false, position).offsetEncoding());
    assertEquals(
        CelException.OffsetEncoding.UTF16,
        new Ast.Node(0, Ast.Kind.IDENT, "x", null, List.of(), false, position, false, null)
            .offsetEncoding());
  }

  @Test
  public void importedTreesRetainCanonicalLocationsThroughMixedEditsAndValidators() {
    Message parsed = parse(ENV, SOURCE), checked = check(ENV, parsed);
    int position = SOURCE.codePointCount(0, SOURCE.lastIndexOf('/'));
    for (Message input : List.of(parsed, checked, toV1Alpha1(parsed), toV1Alpha1(checked))) {
      Ast ast = ast(ENV, input);
      assertNode(ast.root(), position, CelException.OffsetEncoding.CODE_POINT, 2, 3);
      assertNode(ast.root().optional(true), position, CelException.OffsetEncoding.CODE_POINT, 2, 3);
      assertFailure(ast.compile(ENV), position, CelException.OffsetEncoding.CODE_POINT, 2, 3);
      Ast edited =
          ast.editor().replace(ast.root().children().getFirst().id(), Ast.Node.literal(2)).freeze();
      assertNode(edited.root(), position, CelException.OffsetEncoding.CODE_POINT, 2, 3);
      assertEquals(
          CelException.OffsetEncoding.UTF16, edited.root().children().getFirst().offsetEncoding());
      assertFailure(edited.compile(ENV), position, CelException.OffsetEncoding.CODE_POINT, 2, 3);
      var rejecting =
          ENV.toBuilder()
              .validator(
                  context -> {
                    assertNode(
                        context.root(), position, CelException.OffsetEncoding.CODE_POINT, 2, 3);
                    return List.of(
                        context.issue(
                            context.root(), CelException.Code.TYPE, "application finding"));
                  })
              .build();
      for (Runnable compile :
          List.<Runnable>of(() -> ast.compile(rejecting), () -> program(rejecting, input))) {
        CelException failure = assertThrows(CelException.class, compile::run);
        assertEquals(CelException.Code.TYPE, failure.code());
        assertEquals(position, failure.offset());
        assertEquals(CelException.OffsetEncoding.CODE_POINT, failure.offsetEncoding());
        assertEquals(2, failure.line());
        assertEquals(3, failure.column());
        assertTrue(failure.format(SOURCE), failure.format(SOURCE).endsWith("1 / x\n  ^"));
      }
      // Sparse node locations do not supply a complete source map for a newly serialized AST.
      assertTrue(
          messages(child(CanonicalAst.parsed(edited), "source_info"), "positions").isEmpty());
    }
  }

  @Test
  public void absentCanonicalLocationsRemainUnknown() {
    Message parsed = parse(ENV, SOURCE);
    Message unknown =
        parsed.toBuilder()
            .clearField(parsed.getDescriptorForType().findFieldByName("source_info"))
            .build();
    Ast ast = ast(ENV, unknown);
    for (Ast.Node node : ast.nodes())
      assertNode(node, -1, CelException.OffsetEncoding.CODE_POINT, -1, -1);
    assertFailure(ast.compile(ENV), -1, CelException.OffsetEncoding.CODE_POINT, -1, -1);
    var rejecting =
        ENV.toBuilder()
            .validator(
                context ->
                    List.of(
                        context.issue(context.root(), CelException.Code.TYPE, "unlocated finding")))
            .build();
    CelException failure = assertThrows(CelException.class, () -> ast.compile(rejecting));
    assertEquals(-1, failure.offset());
    assertEquals(-1, failure.line());
    assertEquals(CelException.OffsetEncoding.CODE_POINT, failure.offsetEncoding());
    assertEquals("unlocated finding", failure.format(SOURCE));
  }

  @Test
  public void nestedCanonicalColumnsCountCodePointsOnTheSameLine() {
    String source = "['😀', 1 / x]";
    int character = source.indexOf('/'), point = source.codePointCount(0, character);
    Ast canonical = ast(ENV, parse(ENV, source));
    Ast.Node division =
        canonical.nodes().stream()
            .filter(node -> node.name().equals("/"))
            .findFirst()
            .orElseThrow();
    assertNode(division, point, CelException.OffsetEncoding.CODE_POINT, 1, point + 1);
    assertFailure(
        canonical.compile(ENV), point, CelException.OffsetEncoding.CODE_POINT, 1, point + 1);
    Ast original = Ast.parse(ENV, source);
    assertFailure(
        original.compile(ENV), character, CelException.OffsetEncoding.UTF16, 1, character + 1);
  }

  @Test
  public void explicitLocationsAndValidatorHelpersDoNotGuessOffsetUnits() {
    int position = SOURCE.codePointCount(0, SOURCE.lastIndexOf('x'));
    var node =
        new Ast.Node(
            1,
            Ast.Kind.IDENT,
            "x",
            null,
            List.of(),
            false,
            position,
            false,
            null,
            CelException.OffsetEncoding.CODE_POINT);
    var context = new Validator.Context(SOURCE, node, CelType.INT, Map.of());
    CelException.Issue issue = context.issue(node, CelException.Code.TYPE, "finding");
    assertEquals(CelException.OffsetEncoding.CODE_POINT, issue.offsetEncoding());
    assertEquals(2, issue.line());
    assertEquals(5, issue.column());
    assertTrue(issue.format(SOURCE).endsWith("1 / x\n    ^"));
    assertThrows(
        NullPointerException.class,
        () -> new Ast.Node(0, Ast.Kind.IDENT, "x", null, List.of(), false, -1, false, null, null));
  }

  @Test
  public void checkedReferencesRetainExecutableNamesConstantsAndSourceLocations() {
    String source = "// 😀\nshort";
    Message parsed = parse(Cel.newBuilder().inferVariables(true).build(), source);
    Message expression = child(parsed, "expr");
    long id = (Long) field(expression, "id");
    int position = source.codePointCount(0, source.indexOf("short"));
    var environment = Cel.newBuilder().variable("pkg.short", CelType.INT).build();
    for (Message reference :
        List.of(
            message("cel.expr.Reference", "name", "pkg.short"),
            message(
                "cel.expr.Reference",
                "name",
                "pkg.Color.RED",
                "value",
                message("cel.expr.Constant", "int64_value", 7L)))) {
      Message checked =
          message(
              "cel.expr.CheckedExpr",
              "expr",
              expression,
              "source_info",
              child(parsed, "source_info"),
              "reference_map",
              Map.of(id, reference));
      long expected = has(reference, "value") ? 7L : 9L;
      for (Message input : List.of(checked, toV1Alpha1(checked))) {
        Ast owned = ast(environment, input);
        assertNode(owned.root(), position, CelException.OffsetEncoding.CODE_POINT, 2, 1);
        if (has(reference, "value")) {
          assertEquals(Ast.Kind.CONSTANT, owned.root().kind());
          assertEquals(7L, owned.root().value());
        } else assertEquals("pkg.short", owned.root().name());
        assertEquals(expected, program(environment, input).evaluate(Map.of("pkg.short", 9)));
        assertEquals(expected, owned.compile(environment).evaluate(Map.of("pkg.short", 9)));
        Ast edited = owned.editor().transform(node -> node.withChildren(node.children())).freeze();
        assertEquals(expected, edited.compile(environment).evaluate(Map.of("pkg.short", 9)));
        assertEquals(CelType.INT, edited.compile(environment).resultType());
      }
    }
  }

  private static void assertNode(
      Ast.Node node, int offset, CelException.OffsetEncoding encoding, int line, int column) {
    assertEquals(offset, node.offset());
    assertEquals(encoding, node.offsetEncoding());
    assertEquals(line, node.line());
    assertEquals(column, node.column());
  }

  private static void assertFailure(
      Program program, int offset, CelException.OffsetEncoding encoding, int line, int column) {
    CelException failure = assertThrows(CelException.class, () -> program.evaluate(Map.of("x", 0)));
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, failure.code());
    assertEquals(offset, failure.offset());
    assertEquals(encoding, failure.offsetEncoding());
    assertEquals(line, failure.line());
    assertEquals(column, failure.column());
  }
}
