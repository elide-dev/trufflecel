package dev.trufflecel;

import static org.junit.Assert.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.*;
import com.oracle.truffle.api.interop.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Test;
import com.oracle.truffle.runtime.OptimizedCallTarget;

public class InstrumentationTest {
  public static final class Events {
    final Set<Class<? extends Tag>> tags = new HashSet<>();
    final List<String> sections = new ArrayList<>();
    final List<Long> localValues = new ArrayList<>();
    int failures;
  }

  @TruffleInstrument.Registration(
      id = "cel-test-events",
      name = "CEL event test",
      services = Events.class)
  public static final class TestInstrument extends TruffleInstrument {
    @Override
    protected void onCreate(Env env) {
      var events = new Events();
      env.registerService(events);
      env.getInstrumenter()
          .attachExecutionEventListener(
              SourceSectionFilter.newBuilder()
                  .sourceIs(source -> "cel".equals(source.getLanguage()))
                  .build(),
              new ExecutionEventListener() {
                @Override
                public void onEnter(EventContext context, VirtualFrame frame) {
                  record(context, frame.materialize());
                }

                @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
                private void record(
                    EventContext context, com.oracle.truffle.api.frame.Frame frame) {
                  events.sections.add(
                      context.getInstrumentedSourceSection().getCharacters().toString());
                  for (var tag :
                      List.of(
                          StandardTags.ExpressionTag.class,
                          StandardTags.StatementTag.class,
                          StandardTags.RootTag.class,
                          StandardTags.RootBodyTag.class,
                          StandardTags.CallTag.class,
                          StandardTags.ReadVariableTag.class,
                          StandardTags.WriteVariableTag.class))
                    if (context.hasTag(tag)) events.tags.add(tag);
                  if (context.hasTag(StandardTags.ReadVariableTag.class)) {
                    try {
                      Object metadata = context.getNodeObject();
                      String name =
                          InteropLibrary.getUncached()
                              .readMember(metadata, StandardTags.ReadVariableTag.NAME)
                              .toString();
                      if (name.equals("x")) {
                        var library = NodeLibrary.getUncached();
                        Object scope = library.getScope(context.getInstrumentedNode(), frame, true);
                        Object value = InteropLibrary.getUncached().readMember(scope, "x");
                        events.localValues.add(InteropLibrary.getUncached().asLong(value));
                      }
                    } catch (InteropException e) {
                      throw new AssertionError(e);
                    }
                  }
                }

                @Override
                public void onReturnValue(
                    EventContext context, VirtualFrame frame, Object result) {}

                @Override
                public void onReturnExceptional(
                    EventContext context, VirtualFrame frame, Throwable failure) {
                  events.failures++;
                }
              });
    }
  }

  @Test
  public void instrumentsSourceSpansBindingsAndErrors() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      Events events =
          context.getEngine().getInstruments().get("cel-test-events").lookup(Events.class);
      String expression = "[1,2].map(x,\n x + 1).size()";
      assertEquals(
          2L,
          context.eval(Source.newBuilder("cel", expression, "instrumented.cel").build()).asLong());
      assertTrue(events.sections.contains(expression));
      assertTrue(events.sections.contains("x + 1"));
      assertTrue(events.sections.contains("[1,2]"));
      assertEquals(List.of(1L, 2L), events.localValues);
      assertEquals(7, events.tags.size());
      assertTrue(context.eval("cel", "has({'x':1}.x)").asBoolean());
      assertFalse(context.eval("cel", "false && (1 / 0 == 0)").asBoolean());
      assertEquals(1L, context.eval("cel", "{'x':[1,2]}.x[0]").asLong());
      assertEquals(1L, context.eval("cel", "true ? 1 : 1 / 0").asLong());
      assertEquals(2L, context.eval("cel", "optional.of(1).optMap(x,x+1).value()").asLong());
      assertEquals(1L, context.eval("cel", "google.protobuf.Int64Value{value:1}").asLong());
      assertEquals(1L, context.eval("cel", "[?optional.of(1), ?optional.none()][0]").asLong());
      assertEquals(
          1L, context.eval("cel", "{'x':0, ?'y':optional.of(1), ?'z':optional.none()}.y").asLong());
      assertEquals(
          1L, context.eval("cel", "google.protobuf.Int64Value{?value:optional.of(1)}").asLong());
      assertEquals(0, events.failures);
      assertThrows(
          org.graalvm.polyglot.PolyglotException.class, () -> context.eval("cel", "1 / 0"));
      assertTrue(events.failures > 0);
    }
  }

  @Test
  public void debuggerSeesComprehensionLocals() throws Exception {
    try (Context context = Context.newBuilder("cel").build()) {
      var source = Source.newBuilder("cel", "[1,2].map(x,\n x + 1).size()", "debug.cel").build();
      assertEquals(2L, context.eval(source).asLong());
      var values = new ArrayList<String>();
      var debugger = com.oracle.truffle.api.debug.Debugger.find(context.getEngine());
      try (var session =
          debugger.startSession(
              event -> {
                var scope = event.getTopStackFrame().getScope();
                assertNotNull(scope);
                assertNotNull(scope.getDeclaredValue("x"));
                values.add(scope.getDeclaredValue("x").toDisplayString());
              })) {
        session.install(
            com.oracle.truffle.api.debug.Breakpoint.newBuilder(source.getURI()).lineIs(2).build());
        assertEquals(2L, context.eval(source).asLong());
        assertEquals(List.of("1", "2"), values);
      }
    }
  }

  @Test
  public void directApiCanOptIntoInstrumentation() throws Exception {
    var source =
        com.oracle.truffle.api.source.Source.newBuilder("cel", "x + 1", "direct.cel").build();
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    assertThrows(IllegalStateException.class, () -> env.compile(source));
    try (Context context = Context.newBuilder("cel").build()) {
      Events events =
          context.getEngine().getInstruments().get("cel-test-events").lookup(Events.class);
      context.initialize("cel");
      context.enter();
      try {
        assertEquals(4L, env.compile(source).evaluate(Map.of("x", 3L)));
        assertEquals(8L, env.prepare("x + 1").instantiate(source).evaluate(Map.of("x", 7L)));
        assertEquals(List.of(3L, 7L), events.localValues);
        var other =
            com.oracle.truffle.api.source.Source.newBuilder("cel", "x - 1", "other.cel").build();
        assertThrows(IllegalArgumentException.class, () -> env.prepare("x + 1").instantiate(other));
        var foreign =
            com.oracle.truffle.api.source.Source.newBuilder("js", "x + 1", "other.js").build();
        assertThrows(IllegalArgumentException.class, () -> env.compile(foreign));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void instrumentedProgramsStillCompile() throws Exception {
    try (Context context =
        Context.newBuilder("cel")
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.TreatPerformanceWarningsAsErrors", "all")
            .build()) {
      context.getEngine().getInstruments().get("cel-test-events").lookup(Events.class);
      context.enter();
      try {
        var source =
            com.oracle.truffle.api.source.Source.newBuilder("cel", "x * 2 + 1", "compiled.cel")
                .build();
        var program = Cel.newBuilder().variable("x", CelType.INT).build().compile(source);
        for (int i = 0; i < 100; i++) assertEquals(7L, program.evaluate(Map.of("x", 3L)));
        assertTrue(program.callTarget() instanceof OptimizedCallTarget);
        var target = (OptimizedCallTarget) program.callTarget();
        target.compile(true);
        target.waitForCompilation();
        assertTrue(target.isValidLastTier());
        assertEquals(11L, program.evaluate(Map.of("x", 5L)));
        assertTrue(target.isValidLastTier());
      } finally {
        context.leave();
      }
    }
  }
}
