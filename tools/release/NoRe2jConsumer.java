package dev.trufflecel;

import dev.trufflecel.*;
import java.util.Map;
import org.graalvm.polyglot.Context;

/** Packaged consumer: the optional RE2/J JAR must be physically absent. */
public final class NoRe2jConsumer {
  public static void main(String[] args) {
    for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator))
      if (entry.toLowerCase().contains("re2j-"))
        throw new AssertionError("RE2/J on classpath: " + entry);
    if (!Cel.compile("x + 1").evaluate(Map.of("x", 2L)).equals(3L)) throw new AssertionError();
    try {
      Cel.compile("s.matches('a')").evaluate(Map.of("s", "a"));
      throw new AssertionError("Compatibility backend unexpectedly available");
    } catch (CelException expected) {
      if (!expected.getMessage().contains("RE2/J backend is unavailable")) throw expected;
    }
    try (Context context =
        Context.newBuilder("cel")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.TreatPerformanceWarningsAsErrors", "all")
            .build()) {
      context.initialize("cel");
      context.enter();
      try {
        var environment =
            Cel.newBuilder()
                .truffleRegexOnly(10000)
                .variable("s", CelType.STRING)
                .variable("p", CelType.STRING)
                .build();
        var constant = environment.compile("s.matches('^[a-z]+@example[.]com$')");
        if (!constant.test(Map.of("s", "alice@example.com"))
            || constant.test(Map.of("s", "alice@other.com"))) throw new AssertionError();
        for (int i = 0; i < 2000; i++) constant.test(Map.of("s", "alice@example.com"));
        var target = (com.oracle.truffle.runtime.OptimizedCallTarget) constant.callTarget();
        target.compile(true);
        target.waitForCompilation();
        if (!target.isValidLastTier() || !constant.test(Map.of("s", "alice@example.com")))
          throw new AssertionError("Missing compiled match");
        var dynamic = environment.compile("s.matches(p)");
        if (!dynamic.test(Map.of("s", "abc", "p", "^[a-z]+$"))) throw new AssertionError();
        if (!environment.toBuilder().build().compile("s.matches('a')").test(Map.of("s", "a")))
          throw new AssertionError();
      } finally {
        context.leave();
      }
    }
    System.out.println("Packaged TruffleRegex-only execution passed without RE2/J");
  }
}
