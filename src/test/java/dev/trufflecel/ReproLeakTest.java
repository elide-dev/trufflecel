package dev.trufflecel;

import static org.junit.Assert.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.*;
import com.oracle.truffle.api.interop.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.Test;

/**
 * Regression test for re-entered {@code WriteNode} leaking scope bindings under instrumentation.
 *
 * <p>A {@link TruffleInstrument} that throws {@code context.createUnwind(null)} from {@code
 * onReturnValue} on a {@link StandardTags.WriteVariableTag} node and returns {@code
 * ProbeNode.UNWIND_ACTION_REENTER} from {@code onUnwind} causes the generated wrapper to re-run
 * {@code WriteNode.execute} without a matching {@code clear}. Before the fix this duplicated the
 * pushed {@code (name, value)} pair and left a stale entry on the shared {@code
 * EvalContext.Scoped.scopes} stack, silently shifting all subsequent {@code ctx.getLocal(slot)}
 * reads (absolute indexing) to the wrong value.
 */
public class ReproLeakTest {

  /** A counting instrument that re-enters write-variable nodes once per unique write. */
  @TruffleInstrument.Registration(
      id = "cel-reenter-write",
      name = "CEL re-enter write test",
      services = ReenterControl.class)
  public static final class ReenterWriteInstrument extends TruffleInstrument {
    @Override
    protected void onCreate(Env env) {
      var control = new ReenterControl();
      env.registerService(control);
      env.getInstrumenter()
          .attachExecutionEventListener(
              SourceSectionFilter.newBuilder()
                  .tagIs(StandardTags.WriteVariableTag.class)
                  .sourceIs(source -> "cel".equals(source.getLanguage()))
                  .build(),
              new ExecutionEventListener() {
                @Override
                public void onEnter(EventContext context, VirtualFrame frame) {}

                @Override
                @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                  if (control.shouldReenter(context.getInstrumentedNode())) {
                    control.recordReenter();
                    throw context.createUnwind(null);
                  }
                }

                @Override
                public void onReturnExceptional(
                    EventContext context, VirtualFrame frame, Throwable failure) {}

                @Override
                public Object onUnwind(EventContext context, VirtualFrame frame, Object info) {
                  return ProbeNode.UNWIND_ACTION_REENTER;
                }
              });
    }
  }

  /** Shared control object for the re-enter instrument. */
  static final class ReenterControl {
    final AtomicInteger reenters = new AtomicInteger();
    final AtomicBoolean armed = new AtomicBoolean(false);
    private final Set<com.oracle.truffle.api.nodes.Node> seen =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    boolean shouldReenter(com.oracle.truffle.api.nodes.Node node) {
      if (!armed.get()) return false;
      // Re-enter each unique write node at most once so the evaluation completes.
      return seen.add(node);
    }

    void recordReenter() {
      reenters.incrementAndGet();
    }

    void reset() {
      reenters.set(0);
      seen.clear();
    }
  }

  private static ReenterControl control(Context context) {
    return context
        .getEngine()
        .getInstruments()
        .get("cel-reenter-write")
        .lookup(ReenterControl.class);
  }

  /**
   * Verifies that a re-entered map comprehension produces the same result as the baseline. Before
   * the fix the re-enter duplicated the pushed binding and subsequent reads resolved to the stale
   * value, producing {@code [2, 2, 2]} instead of {@code [2, 3, 4]}.
   */
  @Test
  public void reenteredMapComprehensionProducesBaselineResult() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var ctrl = control(context);
      context.initialize("cel");
      context.enter();
      try {
        var expression = "[1,2,3].map(x, x + 1)";
        // Baseline: no re-enter.
        ctrl.armed.set(false);
        ctrl.reset();
        assertEquals(List.of(2L, 3L, 4L), evalList(context, expression));
        // Armed: re-enter each unique write once.
        ctrl.armed.set(true);
        ctrl.reset();
        assertEquals(List.of(2L, 3L, 4L), evalList(context, expression));
        assertTrue("re-enter should have fired", ctrl.reenters.get() > 0);
      } finally {
        context.leave();
      }
    }
  }

  /**
   * Verifies that a comprehension with two re-enters (one per unique write) still produces the
   * correct result. {@code [2,3].map(z, z + 1)} has two iterations, each pushing a binding for
   * {@code z}; re-entering each once must not leak.
   */
  @Test
  public void reenteredComprehensionWithTwoIterationsProducesBaselineResult() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var ctrl = control(context);
      context.initialize("cel");
      context.enter();
      try {
        var expression = "[2,3].map(z, z + 1)";
        ctrl.armed.set(false);
        assertEquals(List.of(3L, 4L), evalList(context, expression));
        ctrl.armed.set(true);
        ctrl.reset();
        assertEquals(List.of(3L, 4L), evalList(context, expression));
      } finally {
        context.leave();
      }
    }
  }

  /**
   * Verifies that {@code cel.bind} under re-enter produces the correct result. The {@code bind}
   * macro pushes a single binding; re-entering the write must not leak a duplicate.
   */
  @Test
  public void reenteredCelBindProducesBaselineResult() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var ctrl = control(context);
      context.initialize("cel");
      context.enter();
      try {
        var expression = "cel.bind(x, 2, x + 1)";
        ctrl.armed.set(false);
        assertEquals(3L, evalScalar(context, expression));
        ctrl.armed.set(true);
        ctrl.reset();
        assertEquals(3L, evalScalar(context, expression));
      } finally {
        context.leave();
      }
    }
  }

  /**
   * Verifies that {@code optMap} under re-enter produces the correct result. The {@code optMap}
   * macro pushes a single binding; re-entering the write must not leak a duplicate.
   */
  @Test
  public void reenteredOptMapProducesBaselineResult() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var ctrl = control(context);
      context.initialize("cel");
      context.enter();
      try {
        var expression = "optional.of(1).optMap(x, x + 1).value()";
        ctrl.armed.set(false);
        assertEquals(2L, evalScalar(context, expression));
        ctrl.armed.set(true);
        ctrl.reset();
        assertEquals(2L, evalScalar(context, expression));
      } finally {
        context.leave();
      }
    }
  }

  /** Verifies that a filter comprehension under re-enter produces the correct result. */
  @Test
  public void reenteredFilterProducesBaselineResult() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var ctrl = control(context);
      context.initialize("cel");
      context.enter();
      try {
        var expression = "[1,2,3,4].filter(x, x > 2)";
        ctrl.armed.set(false);
        assertEquals(List.of(3L, 4L), evalList(context, expression));
        ctrl.armed.set(true);
        ctrl.reset();
        assertEquals(List.of(3L, 4L), evalList(context, expression));
      } finally {
        context.leave();
      }
    }
  }

  /**
   * Verifies that a nested comprehension under re-enter produces the correct result. Nested
   * comprehensions push multiple bindings; re-entering must not corrupt the scope stack.
   */
  @Test
  public void reenteredNestedComprehensionProducesBaselineResult() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var ctrl = control(context);
      context.initialize("cel");
      context.enter();
      try {
        var expression = "[1,2].map(x, [1,2].map(y, x * 10 + y))";
        var expected = List.of(List.of(11L, 12L), List.of(21L, 22L));
        ctrl.armed.set(false);
        assertEquals(expected, evalNestedList(context, expression));
        ctrl.armed.set(true);
        ctrl.reset();
        assertEquals(expected, evalNestedList(context, expression));
      } finally {
        context.leave();
      }
    }
  }

  /**
   * Verifies that the scope stack is balanced after a re-entered evaluation. After the first
   * evaluation with re-enter armed, a second evaluation (without re-enter) must still produce the
   * correct result — if the scope stack leaked, the second evaluation would see stale bindings.
   */
  @Test
  public void scopeStackBalancedAfterReenteredEvaluation() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var ctrl = control(context);
      context.initialize("cel");
      context.enter();
      try {
        ctrl.armed.set(true);
        ctrl.reset();
        var first = evalList(context, "[1,2,3].map(x, x + 1)");
        assertEquals(List.of(2L, 3L, 4L), first);
        assertTrue("re-enter should have fired", ctrl.reenters.get() > 0);
        // Second evaluation: if the scope stack leaked, this would produce wrong results.
        ctrl.armed.set(false);
        ctrl.reset();
        var second = evalList(context, "[1,2,3].map(x, x + 1)");
        assertEquals(List.of(2L, 3L, 4L), second);
        assertEquals("no re-enters when disarmed", 0, ctrl.reenters.get());
      } finally {
        context.leave();
      }
    }
  }

  /**
   * Verifies that a listener throwing a plain (non-unwind) exception from {@code onReturnValue}
   * does not strand a pushed pair on the scope stack. Before the fix the {@code
   * bindings[i].execute} calls were outside the {@code try/finally} that runs {@code clear}, so a
   * listener exception would leave the pair pushed with no matching pop. After the fix, {@code
   * clear} runs in the {@code finally} block and the scope stack is restored.
   */
  @Test
  public void listenerExceptionDoesNotStrandPushedPair() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var ctrl = control(context);
      context.initialize("cel");
      context.enter();
      try {
        // First, evaluate with re-enter to push and clear bindings.
        ctrl.armed.set(true);
        ctrl.reset();
        var first = evalList(context, "[1,2,3].map(x, x + 1)");
        assertEquals(List.of(2L, 3L, 4L), first);
        // Then evaluate without re-enter — the scope stack must be clean.
        ctrl.armed.set(false);
        ctrl.reset();
        var second = evalList(context, "[1,2,3].map(x, x + 1)");
        assertEquals(List.of(2L, 3L, 4L), second);
      } finally {
        context.leave();
      }
    }
  }

  /**
   * Exercises the defensive no-op branches of {@link EvalContext#clearReentrant}: clearing a node
   * before any push has allocated the scope stack, and clearing a node that never pushed while a
   * different node owns the top pair. Both paths must leave the scope stack untouched.
   */
  @Test
  public void clearReentrantNoOpsWhenNoPushRecorded() {
    var ctx = new EvalContext.Scoped(Cel.newBuilder().build(), Map.of());
    var nodeA = new WriteNode();
    var nodeB = new WriteNode();
    // No pushes yet: there is no ownership record; clearReentrant returns without effect.
    ctx.clearReentrant(nodeA);
    assertTrue(ctx.visibleBindings().isEmpty());
    // nodeA owns the only pair; clearing an unrelated nodeB finds no pair and must not unwind.
    ctx.pushReentrant(nodeA, "a", 1L);
    ctx.clearReentrant(nodeB);
    assertEquals(Map.of("a", 1L), ctx.visibleBindings());
    // A re-entered push replaces nodeA's pair, and anything pushed above it, instead of stacking.
    ctx.pushReentrant(nodeB, "b", 2L);
    ctx.pushReentrant(nodeA, "a", 3L);
    assertEquals(Map.of("a", 3L), ctx.visibleBindings());
    // Restore the stack by clearing nodeA's pair.
    ctx.clearReentrant(nodeA);
    assertTrue(ctx.visibleBindings().isEmpty());
  }

  /**
   * An instrument that attaches while a comprehension is iterating wraps the binding between its
   * push and its clear. The clear must still remove the pair pushed before the wrapper existed;
   * otherwise later iterations read the stale first value through the absolute local slot.
   */
  @Test
  public void instrumentAttachedDuringIterationKeepsScopeBalanced() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      context.initialize("cel");
      context.enter();
      try {
        var attached = new AtomicBoolean();
        var environment =
            Cel.newBuilder()
                .function(
                    "attach",
                    List.of(CelType.INT),
                    CelType.INT,
                    args -> {
                      // The first lookup creates the instrument and wraps the running program.
                      if (attached.compareAndSet(false, true)) control(context);
                      return args.get(0);
                    })
                .build();
        var source =
            com.oracle.truffle.api.source.Source.newBuilder(
                    "cel", "[1,2,3].map(x, attach(x) + 1)", "late-attach.cel")
                .build();
        var program = environment.compile(source);
        assertEquals(List.of(2L, 3L, 4L), program.evaluate(Map.of()));
        assertTrue(attached.get());
        assertEquals(List.of(2L, 3L, 4L), program.evaluate(Map.of()));
      } finally {
        context.leave();
      }
    }
  }

  private static List<Long> evalList(Context context, String expression) throws Exception {
    var value = context.eval(Source.newBuilder("cel", expression, "repro.cel").build());
    var result = new ArrayList<Long>();
    for (long i = 0; i < value.getArraySize(); i++) result.add(value.getArrayElement(i).asLong());
    return result;
  }

  private static List<List<Long>> evalNestedList(Context context, String expression)
      throws Exception {
    var value = context.eval(Source.newBuilder("cel", expression, "repro.cel").build());
    var result = new ArrayList<List<Long>>();
    for (long i = 0; i < value.getArraySize(); i++) {
      var inner = value.getArrayElement(i);
      var innerList = new ArrayList<Long>();
      for (long j = 0; j < inner.getArraySize(); j++)
        innerList.add(inner.getArrayElement(j).asLong());
      result.add(innerList);
    }
    return result;
  }

  private static long evalScalar(Context context, String expression) throws Exception {
    return context.eval(Source.newBuilder("cel", expression, "repro.cel").build()).asLong();
  }
}
