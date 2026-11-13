package dev.trufflecel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.Test;

/**
 * Regression coverage for the polyglot error-type classification bug: {@link GuestException} must
 * classify guest failures by {@link CelException.Code} (syntax vs non-syntax) rather than by the
 * throw site, so that checker/type errors surfacing through {@code Context.eval("cel", ...)} are
 * not reported as {@link PolyglotException#isSyntaxError() syntax errors}.
 */
public class PolyglotErrorTypeReproTest {

  private static PolyglotException evalExpectingError(Context context, String source) {
    try {
      context.eval("cel", source);
      fail("expected a PolyglotException for: " + source);
      return null;
    } catch (PolyglotException e) {
      return e;
    }
  }

  @Test
  public void syntaxErrorsAreReportedAsSyntaxErrors() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      for (String source :
          new String[] {
            "1 +", "1+", "", "'unterminated", "[1,", "{1: 2", "x ? 1", "1 = 2",
          }) {
        PolyglotException error = evalExpectingError(context, source);
        assertTrue(source + " should be a syntax error", error.isSyntaxError());
        assertFalse(source + " should not be an internal error", error.isInternalError());
        assertTrue(source + " should be a guest exception", error.isGuestException());
      }
    }
  }

  @Test
  public void checkerTypeErrorsAreNotSyntaxErrors() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      Map<String, String> cases = new LinkedHashMap<>();
      cases.put("1 + true", "no matching overload for +");
      cases.put("size(1)", "size requires a string, bytes, list, or map");
      cases.put("nosuch(1)", "undeclared function");
      cases.put("[1][true]", "list index must be int");
      cases.put("-'a'", "negation requires int or double");
      cases.put("[1].all(i, i)", "macro predicate must be bool");
      cases.put("1 && 'a'", "no matching overload for &&");
      cases.put("1.field", "no matching overload for select on int");
      cases.put("int(true)", "no matching overload for int(bool)");
      cases.put("1 in 2", "no matching overload for in");

      for (Map.Entry<String, String> entry : cases.entrySet()) {
        String source = entry.getKey();
        PolyglotException error = evalExpectingError(context, source);
        assertFalse(
            source + " (checker/type error) should not be a syntax error", error.isSyntaxError());
        assertFalse(source + " should not be an internal error", error.isInternalError());
        assertTrue(source + " should be a guest exception", error.isGuestException());
      }
    }
  }

  @Test
  public void runtimeErrorsAreNotSyntaxErrors() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      PolyglotException divideByZero = evalExpectingError(context, "1 / 0");
      assertFalse(
          "1 / 0 (runtime error) should not be a syntax error", divideByZero.isSyntaxError());
      assertFalse("1 / 0 should not be an internal error", divideByZero.isInternalError());
      assertTrue("1 / 0 should be a guest exception", divideByZero.isGuestException());

      PolyglotException moduloZero = evalExpectingError(context, "1 % 0");
      assertFalse("1 % 0 (runtime error) should not be a syntax error", moduloZero.isSyntaxError());
    }
  }

  @Test
  public void undeclaredVariableEvalFailureIsNotSyntaxError() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      PolyglotException error = evalExpectingError(context, "undeclaredvar + 1");
      assertFalse(
          "undeclared variable (eval-time failure with inferVariables) should not be a syntax error",
          error.isSyntaxError());
      assertFalse("should not be an internal error", error.isInternalError());
      assertTrue("should be a guest exception", error.isGuestException());
    }
  }

  @Test
  public void errorMessageIsPreserved() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      PolyglotException syntaxError = evalExpectingError(context, "1 +");
      assertTrue(syntaxError.getMessage(), syntaxError.getMessage() != null);
      assertFalse(syntaxError.getMessage().isEmpty());

      PolyglotException typeError = evalExpectingError(context, "1 + true");
      assertTrue(typeError.getMessage(), typeError.getMessage() != null);
      assertFalse(typeError.getMessage().isEmpty());
    }
  }

  @Test
  public void validExpressionsEvaluateWithoutError() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      assertEquals(7, context.eval("cel", "1 + 2 * 3").asInt());
      assertEquals(3, context.eval("cel", "[1, 2, 3].size()").asInt());
      assertTrue(context.eval("cel", "1 < 2").asBoolean());
    }
  }
}
