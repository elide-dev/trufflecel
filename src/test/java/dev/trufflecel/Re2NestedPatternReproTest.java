package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class Re2NestedPatternReproTest {

  private static String nested(int depth) {
    return "(".repeat(depth) + "a" + ")".repeat(depth);
  }

  @Test
  public void deeplyNestedConstantPatternIsRejectedAsCelExceptionNotStackOverflow() {
    var env = Cel.newBuilder().variable("s", CelType.STRING).build();
    var source = "s.matches('" + nested(5000) + "')";
    var thrown = assertThrows(CelException.class, () -> env.compile(source));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, thrown.code());
  }

  @Test
  public void deeplyNestedDynamicPatternSyncIsRejectedAsCelExceptionNotStackOverflow() {
    var env = Cel.newBuilder().variable("s", CelType.STRING).variable("p", CelType.STRING).build();
    var program = env.compile("s.matches(p)");
    var thrown =
        assertThrows(
            CelException.class, () -> program.evaluate(Map.of("s", "a", "p", nested(5000))));
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, thrown.code());
  }

  @Test
  public void deeplyNestedDynamicPatternAsyncCompletesExceptionallyWithoutHanging()
      throws Exception {
    var env = Cel.newBuilder().variable("s", CelType.STRING).variable("p", CelType.STRING).build();
    var program = env.compile("s.matches(p)");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future = program.evalAsync(Map.of("s", "a", "p", nested(20000)), executor);
      try {
        var thrown = assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof CelException);
        assertEquals(
            CelException.Code.RESOURCE_EXHAUSTED, ((CelException) thrown.getCause()).code());
      } finally {
        assertTrue("async future must complete even on a hostile pattern", future.isDone());
      }
    }
  }

  @Test
  public void asyncEvaluationCompletesExceptionallyOnRawError() throws Exception {
    var env =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .function(
                "bomb",
                List.of(CelType.INT),
                CelType.INT,
                args -> {
                  throw new StackOverflowError("simulated overflow");
                })
            .build();
    var program = env.compile("bomb(x)");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future = program.evalAsync(Map.of("x", 1L), executor);
      try {
        var thrown = assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof StackOverflowError);
      } finally {
        assertTrue("async future must complete on a raw Error", future.isDone());
      }
    }
  }

  @Test
  public void shallowlyNestedPatternsStillCompileAndMatch() {
    var env = Cel.newBuilder().variable("s", CelType.STRING).build();
    for (int depth : List.of(0, 1, 5, 50, 200)) {
      var program = env.compile("s.matches('" + nested(depth) + "')");
      assertTrue("depth " + depth, program.test(Map.of("s", "a")));
      assertFalse("depth " + depth, program.test(Map.of("s", "b")));
    }
  }

  @Test
  public void escapesAndCharacterClassesDoNotInflateNestingDepth() {
    assertTrue(Regex.compile("[(]".repeat(2000), 10000).find("(".repeat(2000)));
    assertTrue(Regex.compile("\\(".repeat(2000), 10000).find("(".repeat(2000)));
    // An escape inside a character class must not be treated as a group open/close.
    assertTrue(Regex.compile("[\\d]", 10000).find("1"));
    assertTrue(Regex.compile("[a\\-z]", 10000).find("-"));
  }

  @Test
  public void stackOverflowDuringCompileIsConvertedToResourceExhausted() throws Exception {
    // Depth 1000 passes the nesting guard (max <= MAX_NESTING_DEPTH) and compiles on a
    // normal stack, but RE2/J's recursive compile pipeline overflows a deliberately small
    // stack; the backstop catch must convert that StackOverflowError into a CelException
    // rather than letting a raw Error escape.
    String pattern = nested(1000);
    assertNull("pattern compiles on a default stack", compileOnStack(pattern, 0L));
    var thrown = compileOnStack(pattern, 128L * 1024L);
    assertNotNull("small-stack compile must throw", thrown);
    assertTrue(thrown instanceof CelException);
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, ((CelException) thrown).code());
  }

  private static Throwable compileOnStack(String pattern, long stackSize) throws Exception {
    var ref = new AtomicReference<Throwable>();
    var done = new CountDownLatch(1);
    var t =
        new Thread(
            null,
            () -> {
              try {
                Regex.compile(pattern, 10000);
              } catch (Throwable e) {
                ref.set(e);
              } finally {
                done.countDown();
              }
            },
            "re2-compile-" + stackSize,
            stackSize);
    t.start();
    done.await(30, TimeUnit.SECONDS);
    return ref.get();
  }
}
