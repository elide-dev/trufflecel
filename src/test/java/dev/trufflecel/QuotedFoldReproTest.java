package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Regression coverage for source locations of CEL expressions embedded inside quoted YAML scalars.
 * Reported bug: {@link ConfigurationData#policyValue} paired the YAML-decoded quoted scalar with a
 * raw document base offset, so diagnostics on tokens past the first YAML line fold drifted left by
 * the folded-region size; the caret landed on indentation instead of the defect.
 */
public class QuotedFoldReproTest {

  private static void assertDocumentLocation(String yaml, CelException error, String token) {
    int offset = yaml.indexOf(token);
    assertTrue("token '" + token + "' must occur in the policy", offset >= 0);
    assertEquals(yaml.indexOf(token), error.offset());
    int lineStart = yaml.lastIndexOf('\n', offset - 1) + 1;
    long line = 1 + yaml.substring(0, offset).chars().filter(c -> c == '\n').count();
    assertEquals((int) line, error.line());
    assertEquals(offset - lineStart + 1, error.column());
  }

  private static Ast.Node find(Ast.Node node, String call) {
    if (node.kind() == Ast.Kind.CALL && node.name().equals(call)) return node;
    for (Ast.Node child : node.children()) {
      Ast.Node found = find(child, call);
      if (found != null) return found;
    }
    return null;
  }

  @Test
  public void foldedDoubleQuotedConditionReportsExactDocumentOffsets() {
    // Condition's decoded value is `AA + undeclared`, but YAML folds `\n` + indentation into a
    // single space, so the raw interior is `AA +\n      undeclared`. Declaring `AA` leaves
    // `undeclared` as the only diagnostic, which must point at its true document position.
    var env = Cel.newBuilder().variable("AA", CelType.INT).build();
    String yaml =
        """
        name: fold
        rule:
          match:
            - condition: "AA +
              undeclared"
              output: 'true'
        """;
    var error = assertThrows(CelException.class, () -> PolicyCompiler.compile(env, yaml));
    assertDocumentLocation(yaml, error, "undeclared");
    assertEquals(CelException.OffsetEncoding.UTF16, error.offsetEncoding());
    assertTrue(error.format(yaml), error.format(yaml).endsWith("      undeclared\"\n      ^"));
  }

  @Test
  public void controlSingleLineQuotedScalarHasNoLocationDrift() {
    // A single-line quoted scalar without escapes decodes to its raw interior, so offsets stay
    // exact both before and after the fix; this isolates folding, not quoting, as the trigger.
    var env = Cel.newBuilder().variable("AA", CelType.INT).build();
    String yaml =
        """
        name: control
        rule:
          match:
            - condition: "AA + undeclared"
              output: 'true'
        """;
    var error = assertThrows(CelException.class, () -> PolicyCompiler.compile(env, yaml));
    assertDocumentLocation(yaml, error, "undeclared");
  }

  @Test
  public void foldedSingleQuotedConditionReportsExactDocumentOffsets() {
    // Single-quoted scalars fold by the same YAML rule; `''` would be an escape, but absent it the
    // raw interior tokenizes identically and keeps document-accurate offsets.
    var env = Cel.newBuilder().variable("AA", CelType.INT).build();
    String yaml =
        """
        name: fold-single
        rule:
          match:
            - condition: 'AA +
              undeclared'
              output: 'true'
        """;
    var error = assertThrows(CelException.class, () -> PolicyCompiler.compile(env, yaml));
    assertDocumentLocation(yaml, error, "undeclared");
  }

  @Test
  public void validatorFindingOnFoldedTokenReportsExactDocumentOffsets() {
    var env =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .validator(context -> {
              Ast.Node and = find(context.root(), "&&");
              assertNotNull("root tree must contain &&", and);
              return List.of(context.issue(and, CelException.Code.TYPE, "validator finding"));
            })
            .build();
    String yaml =
        """
        name: validator
        rule:
          match:
            - condition: "x > 0
                &&
              x < 1"
              output: 'true'
        """;
    var error = assertThrows(CelException.class, () -> PolicyCompiler.compile(env, yaml));
    assertDocumentLocation(yaml, error, "&&");
  }

  @Test
  public void escapedDoubleQuotedScalarsStillParseAgainstTheDecodedValue() {
    var env = Cel.newBuilder().variable("x", CelType.BOOL).build();
    // `tr\u0075e` decodes to `true`; the raw interior carries the backslash escape, which the
    // CEL parser cannot tokenize outside string literals, so the decoded value must remain the
    // parser source. Using a non-constant `x` keeps the first choice non-unconditional. The
    // regression guard is that the policy still parses and evaluates; the raw interior would not.
    String clean =
        """
        name: escape-clean
        rule:
          match:
            - condition: "tr\\u0075e && x"
              output: "'yes'"
            - output: "'no'"
        """;
    assertEquals("yes", PolicyCompiler.compile(env, clean).evaluate(Map.of("x", true)));
    assertEquals("no", PolicyCompiler.compile(env, clean).evaluate(Map.of("x", false)));
    // A folded, escaped scalar must still parse against the decoded value too.
    String folded =
        """
        name: escape-fold
        rule:
          match:
            - condition: "tr\\u0075e &&
                x"
              output: "'yes'"
            - output: "'no'"
        """;
    assertEquals("yes", PolicyCompiler.compile(env, folded).evaluate(Map.of("x", true)));
    assertEquals("no", PolicyCompiler.compile(env, folded).evaluate(Map.of("x", false)));
    // A diagnostic in an escaped quoted scalar must still be a checker finding, not a SYNTAX
    // error from a raw-interior backslash leaking into tokenization.
    String diagnostic =
        """
        name: escape-diag
        rule:
          match:
            - condition: "tr\\u0075e && undeclared"
              output: "'true'"
        """;
    var error = assertThrows(CelException.class, () -> PolicyCompiler.compile(env, diagnostic));
    assertNotEquals(CelException.Code.SYNTAX, error.code());
    assertTrue(error.getMessage(), error.getMessage().contains("undeclared"));
  }

  @Test
  public void escapedSingleQuotedScalarsStillParseAgainstTheDecodedValue() {
    var env = Cel.newBuilder().variable("x", CelType.STRING).build();
    // `''` is the only escaped sequence in a single-quoted YAML scalar and decodes to one
    // `'`; in a CEL string the raw `''` would survive as two quote chars, so the decoded value
    // must remain the parser source to preserve the YAML-decoded CEL semantics.
    String clean =
        """
        name: escape-single
        rule:
          match:
            - condition: 'x == "isn''t"'
              output: "'yes'"
            - output: "'no'"
        """;
    assertEquals("yes", PolicyCompiler.compile(env, clean).evaluate(Map.of("x", "isn't")));
    assertEquals("no", PolicyCompiler.compile(env, clean).evaluate(Map.of("x", "isn''t")));
    // A diagnostic in an escaped single-quoted scalar must still be a checker finding too.
    String diagnostic =
        """
        name: escape-single-diag
        rule:
          match:
            - condition: 'x == "isn''t" && undeclared'
              output: "'true'"
        """;
    var error = assertThrows(CelException.class, () -> PolicyCompiler.compile(env, diagnostic));
    assertNotEquals(CelException.Code.SYNTAX, error.code());
    assertTrue(error.getMessage(), error.getMessage().contains("undeclared"));
  }
}
