package dev.trufflecel;

import static org.junit.Assert.*;
import dev.cel.common.CelErrorCode;
import dev.cel.common.CelValidationException;
import dev.cel.runtime.CelEvaluationException;
import java.util.Map;
import org.junit.Test;

/** Negative outcomes are compared by phase/category, never incidental diagnostic wording. */
public final class SemanticsTest {
  @Test
  public void parserAndCheckerRejectionsAgreeWithBothUpstreamParsers() throws Exception {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    for (String engine : new String[] {"cel-java", "cel-java-pratt-optimized"}) {
      try (var upstream = new Engines(engine)) {
        for (String source : new String[] {"x +", "[1,", "'unterminated", "(1 + 2"}) {
          assertEquals(
              source,
              CelException.Code.SYNTAX,
              assertThrows(CelException.class, () -> env.compile(source)).code());
          assertThrows(
              source, CelValidationException.class, () -> upstream.compiler.parse(source).getAst());
        }
        for (String source : new String[] {"1 + true", "size(1)", "[1][true]", "x && true"}) {
          assertEquals(
              source,
              CelException.Code.TYPE,
              assertThrows(CelException.class, () -> env.compile(source)).code());
          var parsed = upstream.compiler.parse(source).getAst();
          assertThrows(
              source, CelValidationException.class, () -> upstream.compiler.check(parsed).getAst());
        }
      }
    }
  }

  @Test
  public void evaluationFailuresAndLazySuppressionHaveSpecificOutcomes() throws Exception {
    var sources =
        new String[] {"x / 0", "x % 0", "9223372036854775807 + x", "[1][x + 1]", "{'a': 1}[email]"};
    var bindings = Map.<String, Object>of("x", 1L, "email", "b");
    var ours =
        new CelException.Code[] {
          CelException.Code.DIVIDE_BY_ZERO,
          CelException.Code.DIVIDE_BY_ZERO,
          CelException.Code.OVERFLOW,
          CelException.Code.INDEX,
          CelException.Code.NO_SUCH_KEY
        };
    var theirs =
        new CelErrorCode[] {
          CelErrorCode.DIVIDE_BY_ZERO,
          CelErrorCode.DIVIDE_BY_ZERO,
          CelErrorCode.NUMERIC_OVERFLOW,
          CelErrorCode.INDEX_OUT_OF_BOUNDS,
          CelErrorCode.ATTRIBUTE_NOT_FOUND
        };
    for (String engine : new String[] {"cel-java", "cel-java-pratt-optimized"}) {
      try (var upstream = new Engines(engine)) {
        for (int i = 0; i < sources.length; i++) {
          String source = sources[i];
          var program =
              Cel.newBuilder()
                  .variable("x", CelType.INT)
                  .variable("email", CelType.STRING)
                  .build()
                  .compile(source);
          assertEquals(
              source,
              ours[i],
              assertThrows(CelException.class, () -> program.evaluate(bindings)).code());
          var reference =
              upstream.runtime.createProgram(
                  (dev.cel.common.CelAbstractSyntaxTree) upstream.prepare(source));
          assertEquals(
              source,
              theirs[i],
              assertThrows(CelEvaluationException.class, () -> reference.eval(bindings))
                  .getErrorCode());
        }
        for (String source :
            new String[] {"false && (1 / 0 == 0)", "true || (1 / 0 == 0)", "true ? 7 : 1 / 0"}) {
          var reference =
              upstream.runtime.createProgram(
                  (dev.cel.common.CelAbstractSyntaxTree) upstream.prepare(source));
          assertEquals(
              source,
              reference.eval(Map.of()),
              Cel.newBuilder().build().compile(source).evaluate(Map.of()));
        }
      }
    }
  }
}
