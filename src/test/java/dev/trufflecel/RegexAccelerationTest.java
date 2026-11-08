package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.utilities.TruffleWeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.graalvm.polyglot.Context;
import org.junit.Test;

public class RegexAccelerationTest {
  private static final String EMAIL = "^[a-z]+@example[.]com$";

  private static Context context() {
    Context context = Context.newBuilder("cel").build();
    context.initialize("cel");
    return context;
  }

  private static Program program(String pattern) {
    return template(pattern).instantiate();
  }

  private static PreparedProgram template(String pattern) {
    return Cel.newBuilder()
        .variable("s", CelType.STRING)
        .build()
        .prepare("s.matches('" + pattern + "')");
  }

  private static Regex.MatchNode matcher(Program program) {
    var nodes =
        NodeUtil.findAllNodeInstances(program.callTarget().getRootNode(), Regex.MatchNode.class);
    assertEquals(1, nodes.size());
    return nodes.getFirst();
  }

  @Test
  public void firstUseWithoutAnInitializedContextStaysStandalone() {
    Program standalone = program(EMAIL);
    assertNull(CelLanguage.currentScope());
    assertFalse(matcher(standalone).isAccelerated());
    assertEquals(true, standalone.evaluate(Map.of("s", "alice@example.com")));
    try (var context = context()) {
      context.enter();
      try {
        assertEquals(false, standalone.evaluate(Map.of("s", "alice@other.com")));
        assertFalse(matcher(standalone).isAccelerated());
        assertEquals(0, CelLanguage.currentScope().regexCount());
      } finally {
        context.leave();
      }
    }
    try (var context = Context.newBuilder().build()) {
      context.enter();
      try {
        Program uninitialized = program(EMAIL);
        assertNull(CelLanguage.currentScope());
        assertEquals(true, uninitialized.evaluate(Map.of("s", "alice@example.com")));
        assertFalse(matcher(uninitialized).isAccelerated());
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void acceleratorChecksTheOwningContextOnEveryCallAndDisposesItsCache() {
    PreparedProgram template = template(EMAIL);
    Program program;
    CelLanguage.Scope ownerScope;
    try (var owner = context()) {
      owner.enter();
      try {
        program = template.instantiate();
        assertEquals(true, program.evaluate(Map.of("s", "alice@example.com")));
        assertTrue(matcher(program).isAccelerated());
        ownerScope = CelLanguage.currentScope();
        Regex.Handle handle = ownerScope.regex(EMAIL);
        assertSame(handle, ownerScope.regex(EMAIL));
        assertFalse(handle.isCurrent(new Object()));
        try (var other = context()) {
          other.enter();
          try {
            assertEquals(false, program.evaluate(Map.of("s", "alice@other.com")));
            assertFalse(matcher(program).isAccelerated());
            assertEquals(0, CelLanguage.currentScope().regexCount());
          } finally {
            other.leave();
          }
        }
        assertEquals(true, program.evaluate(Map.of("s", "alice@example.com")));
        assertTrue(matcher(program).isAccelerated());
      } finally {
        owner.leave();
      }
      assertEquals(false, program.evaluate(Map.of("s", "alice@other.com")));
      assertFalse(matcher(program).isAccelerated());
      assertEquals(1, ownerScope.regexCount());
    }
    assertEquals(0, ownerScope.regexCount());
    assertEquals(true, program.evaluate(Map.of("s", "alice@example.com")));
    assertFalse(matcher(program).isAccelerated());
  }

  @Test
  public void unsupportedAndUnicodeSensitivePatternsKeepRe2SemanticsAndLimits() {
    try (var context = context()) {
      context.enter();
      try {
        for (String pattern : List.of("(?mi)a", "é", "\\p{Lu}", "\\d+", "a".repeat(4097))) {
          Regex.Compiled fallback = Regex.compile(pattern, 10000);
          var matcher = new Regex.MatchNode(fallback);
          for (String input : List.of("A", "é", "12", "\u1c90"))
            assertEquals(fallback.find(input), matcher.find(input));
          assertFalse(matcher.isAccelerated());
        }
        assertEquals(0, CelLanguage.currentScope().regexCount());
        Program unsupported = program(".{0,1000}x");
        assertEquals(true, unsupported.evaluate(Map.of("s", "abcx")));
        assertFalse(matcher(unsupported).isAccelerated());
        assertNull(CelLanguage.currentScope().regex(".{0,1000}x"));
        assertEquals(1, CelLanguage.currentScope().regexCount());
        assertEquals(
            CelException.Code.RESOURCE_EXHAUSTED,
            assertThrows(
                    CelException.class,
                    () ->
                        Cel.newBuilder()
                            .maxRegexProgramSize(4)
                            .variable("s", CelType.STRING)
                            .build()
                            .compile("s.matches('a{20}')"))
                .code());
        assertEquals(
            CelException.Code.INVALID_ARGUMENT,
            assertThrows(CelException.class, () -> Regex.compile("[", 10000)).code());
        assertEquals(1, CelLanguage.currentScope().regexCount());
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void eligibleBooleanPatternsAgreeOnUnicodeAndEmptyInputs() {
    try (var context = context()) {
      context.enter();
      try {
        for (String pattern :
            List.of(
                "", EMAIL, ".", "^.$", "[^a]", "^[^a]$", "[a-z]+", "(a|b)*", "a{0,3}", "^$",
                "a/b")) {
          Regex.Compiled fallback = Regex.compile(pattern, 10000);
          var matcher = new Regex.MatchNode(fallback);
          for (String input :
              List.of(
                  "",
                  "a",
                  "abc",
                  "ab\n",
                  "é",
                  "😀",
                  "\ud800",
                  "\udc00",
                  "alice@example.com",
                  "a/b"))
            assertEquals(pattern + " / " + input, fallback.find(input), matcher.find(input));
          assertTrue(pattern, matcher.isAccelerated());
        }
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void captureExtensionsPreserveNullableLoopMatchBoundaries() {
    var environment = Cel.newBuilder().variable("s", CelType.STRING).build();
    try (var context = context()) {
      context.enter();
      try {
        var input = Map.<String, Object>of("s", "aaa");
        assertEquals(
            OptionalValue.of(""),
            environment.compile("regex.extract(s, '(?:|a)*')").evaluate(input));
        assertEquals(
            List.of("", "", "", ""),
            environment.compile("regex.extractAll(s, '(?:|a)*')").evaluate(input));
        assertEquals(
            "XaXaXaX", environment.compile("regex.replace(s, '(?:|a)*', 'X')").evaluate(input));
        assertEquals(0, CelLanguage.currentScope().regexCount());
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void contextCacheIsBoundedAndClearedWeakHandlesFallBack() throws Exception {
    PreparedProgram template = template(EMAIL);
    try (var context = context()) {
      context.enter();
      try {
        Program program = template.instantiate();
        assertEquals(true, program.evaluate(Map.of("s", "alice@example.com")));
        var matcher = matcher(program);
        assertTrue(matcher.isAccelerated());
        var scope = CelLanguage.currentScope();
        for (int i = 0; i < 257; i++) assertNotNull(scope.regex("literal" + i));
        assertTrue(scope.regexCount() <= 256);
        scope.clearRegexes();
        // Simulate collection after eviction, without relying on the host's GC schedule.
        var entryField = Regex.MatchNode.class.getDeclaredField("entry");
        entryField.setAccessible(true);
        Object entry = entryField.get(matcher);
        var handleField = entry.getClass().getDeclaredField("handle");
        handleField.setAccessible(true);
        ((TruffleWeakReference<?>) handleField.get(entry)).clear();
        assertFalse(matcher.isAccelerated());
        assertEquals(true, program.evaluate(Map.of("s", "alice@example.com")));
        assertEquals(0, scope.regexCount());
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void sharedProgramCanAccelerateAndFallBackConcurrently() throws Exception {
    PreparedProgram template = template(EMAIL);
    Program program;
    try (var owner = context();
        var executor = Executors.newFixedThreadPool(2)) {
      owner.enter();
      try {
        program = template.instantiate();
        assertEquals(true, program.evaluate(Map.of("s", "alice@example.com")));
        assertTrue(matcher(program).isAccelerated());
      } finally {
        owner.leave();
      }
      var start = new CountDownLatch(1);
      var accelerated =
          executor.submit(
              () -> {
                owner.enter();
                try {
                  start.countDown();
                  for (int i = 0; i < 200; i++)
                    assertEquals(true, program.evaluate(Map.of("s", "alice@example.com")));
                  assertTrue(matcher(program).isAccelerated());
                } finally {
                  owner.leave();
                }
              });
      var fallback =
          executor.submit(
              () -> {
                start.await();
                for (int i = 0; i < 200; i++)
                  assertEquals(false, program.evaluate(Map.of("s", "alice@other.com")));
                assertFalse(matcher(program).isAccelerated());
                return null;
              });
      accelerated.get();
      fallback.get();
    }
  }
}
