package dev.trufflecel;

import static org.junit.Assert.*;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.Test;

public class TruffleRegexOnlyTest {
  @Test
  public void explicitPolicyPreservesBoundsAndConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> Cel.newBuilder().truffleRegexOnly(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> Cel.newBuilder().maxRegexProgramSize(3).truffleRegexOnly(100));
    assertThrows(
        IllegalArgumentException.class,
        () -> Cel.newBuilder().truffleRegexOnly(100).maxRegexProgramSize(3));
    var limited = Cel.newBuilder().maxRegexProgramSize(3).build().toBuilder();
    assertThrows(IllegalArgumentException.class, () -> limited.truffleRegexOnly(100));
    var environment =
        Cel.newBuilder().truffleRegexOnly(10000).variable("s", CelType.STRING).build();
    assertEquals(10000, environment.toBuilder().build().regexParserTreeLimit);
    assertEquals(
        10000, EnvironmentYaml.read(EnvironmentYaml.write(environment)).regexParserTreeLimit);
    assertThrows(CelException.class, () -> Regex.compile("a", environment));
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> Regex.compile("a".repeat(4097), environment))
            .code());
  }

  @Test
  public void matchesAndFailuresNeverNeedCompatibilityFallback() {
    try (var context = Context.newBuilder("cel").build()) {
      context.initialize("cel");
      context.enter();
      Program program;
      try {
        var environment =
            Cel.newBuilder()
                .truffleRegexOnly(10000)
                .variable("s", CelType.STRING)
                .variable("p", CelType.STRING)
                .validation(Environment.Validation.STRICT)
                .build();
        program = environment.compile("s.matches('^[a-z]+$')");
        assertTrue(program.test(Map.of("s", "abc")));
        assertFalse(program.test(Map.of("s", "123")));
        assertTrue(environment.compile("s.matches(p)").test(Map.of("s", "abc", "p", "[a-z]+")));
        assertEquals(
            CelException.Code.INVALID_ARGUMENT,
            assertThrows(CelException.class, () -> environment.compile("s.matches('[')")).code());
        assertThrows(CelException.class, () -> environment.compile("s.matches('.{0,1000}x')"));
        assertThrows(
            CelException.class,
            () ->
                Cel.newBuilder()
                    .validation(Environment.Validation.STRICT)
                    .truffleRegexOnly(1)
                    .build()
                    .compile("'abc'.matches('(a|b)+c')"));
        assertThrows(
            CelException.class,
            () -> environment.compile("regex.extract(s,'a')").evaluate(Map.of("s", "a")));
        assertThrows(CelException.class, () -> Regex.compile("a", environment).programSize());
        // Parser budgets are part of cache identity: a generous compilation cannot bypass a
        // stricter caller.
        assertThrows(
            CelException.class,
            () ->
                Cel.newBuilder()
                    .validation(Environment.Validation.STRICT)
                    .truffleRegexOnly(1)
                    .build()
                    .compile("'abc'.matches('^[a-z]+$')"));
      } finally {
        context.leave();
      }
      assertThrows(CelException.class, () -> program.test(Map.of("s", "abc")));
    }
  }

  @Test
  public void sharedEngineDoesNotReuseProgramsAcrossRegexPolicies() {
    try (var engine = org.graalvm.polyglot.Engine.create();
        var compatibility =
            Context.newBuilder("cel").engine(engine).allowExperimentalOptions(true).build();
        var only =
            Context.newBuilder("cel")
                .engine(engine)
                .allowExperimentalOptions(true)
                .option("cel.TruffleRegexOnly", "true")
                .build()) {
      var source = org.graalvm.polyglot.Source.create("cel", "'Ა'.matches('\\\\p{Lu}')");
      assertFalse(compatibility.eval(source).asBoolean());
      assertTrue(only.eval(source).asBoolean());
      assertFalse(compatibility.eval(source).asBoolean());
      try (var same =
              Context.newBuilder("cel")
                  .engine(engine)
                  .allowExperimentalOptions(true)
                  .option("cel.TruffleRegexOnly", "true")
                  .build();
          var tiny =
              Context.newBuilder("cel")
                  .engine(engine)
                  .allowExperimentalOptions(true)
                  .option("cel.TruffleRegexOnly", "true")
                  .option("cel.RegexParserTreeLimit", "1")
                  .build()) {
        assertTrue(same.eval(source).asBoolean());
        var complex = org.graalvm.polyglot.Source.create("cel", "'abc'.matches('(a|b)+c')");
        assertTrue(same.eval(complex).asBoolean());
        assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> tiny.eval(complex));
      }
    }
  }
}
