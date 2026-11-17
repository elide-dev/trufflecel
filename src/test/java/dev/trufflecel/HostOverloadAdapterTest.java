package dev.trufflecel;

import static org.junit.Assert.*;

import com.oracle.truffle.api.source.Source;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.Test;

/**
 * Plan-time host overload matching must mirror the {@link ObjectType} adapter refinement performed
 * by {@link FunctionOverloads#select} at runtime, so a constant-argument call that the runtime
 * accepts via an adapter is never prebuilt as a {@code NO_OVERLOAD} fold on uninstrumented targets
 * or rejected under {@link Environment.Validation#STRICT}.
 *
 * <p>The optimizer's {@code hostOverload} refines each constant argument through the registered
 * adapters exactly when {@code ExprNode.customOverloads} is set — i.e. when the runtime dispatch
 * reaches {@link FunctionOverloads#select} (multi-signature, or single signature declared through
 * the explicit-overload-id builder API whose {@code receiver} boxes to a non-null {@link Boolean}).
 * When {@code customOverloads} is false the runtime instead uses {@code Functions.applyHost}, which
 * matches only {@code Values.type} with no adapter refinement, so the optimizer must not refine
 * either. These tests pin both sides of that contract.
 */
public class HostOverloadAdapterTest {

  /** Adapter that treats any {@link String} as the {@code wrapped} runtime type. */
  private static ObjectType wrappedAdapter() {
    return new ObjectType() {
      @Override
      public String name() {
        return "wrapped";
      }

      @Override
      public Map<String, CelType> fields() {
        return Map.of();
      }

      @Override
      public Object construct(Map<String, Object> fields) {
        throw new UnsupportedOperationException("wrapped is not constructible");
      }

      @Override
      public Object get(Object value, String field) {
        throw new UnsupportedOperationException("wrapped has no fields");
      }

      @Override
      public boolean has(Object value, String field) {
        return false;
      }

      @Override
      public boolean accepts(Object value) {
        return value instanceof String;
      }
    };
  }

  /**
   * Multi-overload builder: {@code wrapLen(wrapped) -> int} and {@code wrapLen(int) -> int}. Both
   * signatures go through the explicit-overload-id API so the runtime dispatches through {@link
   * FunctionOverloads#select}, which applies adapter refinement.
   */
  private static Environment.Builder wrapBuilder(Environment.Validation validation) {
    return Cel.newBuilder()
        .objectType(wrappedAdapter())
        .validation(validation)
        .function(
            "wrapLen",
            "wrapLen_wrapped",
            false,
            List.of(CelType.of("wrapped")),
            CelType.INT,
            args -> (long) ((String) args.get(0)).length())
        .function(
            "wrapLen",
            "wrapLen_int",
            false,
            List.of(CelType.INT),
            CelType.INT,
            args -> (Long) args.get(0));
  }

  private static Environment wrapEnv() {
    return wrapBuilder(Environment.Validation.DEFERRED).build();
  }

  private static Environment strictWrapEnv() {
    return wrapBuilder(Environment.Validation.STRICT).build();
  }

  /**
   * Single-global host binding registered through the convenience (4-arg) API, so {@code
   * customOverloads} stays false and runtime dispatch uses {@code Functions.applyHost} with {@code
   * Values.type} only — no adapter refinement. The optimizer must not refine either, so a raw
   * {@code String} constant never matches the {@code wrapped}-typed binding on either target.
   */
  private static Environment singleGlobalConvenienceEnv() {
    return Cel.newBuilder()
        .objectType(wrappedAdapter())
        .function(
            "wrapLenOnly",
            List.of(CelType.of("wrapped")),
            CelType.INT,
            args -> (long) ((String) args.get(0)).length())
        .build();
  }

  /**
   * Single-global host binding registered through the explicit-overload-id (6-arg) API with {@code
   * receiver=false}, so {@code receiver()} boxes to {@link Boolean#FALSE} and {@code
   * customOverloads} becomes true; runtime dispatch uses {@link FunctionOverloads#select}, which
   * applies adapter refinement. The optimizer must refine too.
   */
  private static Environment singleGlobalExplicitEnv() {
    return Cel.newBuilder()
        .objectType(wrappedAdapter())
        .function(
            "wrapLenOnly",
            "wrapLenOnly_wrapped",
            false,
            List.of(CelType.of("wrapped")),
            CelType.INT,
            args -> (long) ((String) args.get(0)).length())
        .build();
  }

  private static Environment convertEnv() {
    return Cel.newBuilder()
        .function("convert", List.of(CelType.INT), CelType.STRING, a -> "int:" + a.get(0))
        .function("convert", List.of(CelType.STRING), CelType.STRING, a -> "str:" + a.get(0))
        .build();
  }

  /** Build an unchecked plan the same way {@link Environment#parse(String)} does. */
  private static PreparedProgram unchecked(Environment env, String source) {
    return env.prepare(source, new Parser(source, env).parse(), false);
  }

  private static Program plain(PreparedProgram template) {
    return template.instantiate();
  }

  private static Program instrumented(PreparedProgram template, String source, String label) {
    return template.instantiate(Source.newBuilder("cel", source, label).build());
  }

  private static Object outcome(Program program) {
    return outcome(program, Map.of());
  }

  private static Object outcome(Program program, Map<String, ?> bindings) {
    try {
      return program.evaluate(bindings);
    } catch (CelException e) {
      return List.of(e.code(), e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private static CelException.Code codeOf(Object outcome) {
    assertTrue("expected a CEL error outcome, got " + outcome, outcome instanceof List);
    return (CelException.Code) ((List<Object>) outcome).get(0);
  }

  @Test
  public void constantStringArgumentDispatchesViaAdapterOnBothTargets() {
    var env = wrapEnv();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        for (String source : List.of("wrapLen('hello')", "wrapLen('hel' + 'lo')")) {
          PreparedProgram template = unchecked(env, source);
          assertEquals(source, 5L, outcome(plain(template)));
          assertEquals(source, 5L, outcome(instrumented(template, source, "wrap.cel")));
        }
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void constantIntArgumentStillDispatchesToIntOverloadOnBothTargets() {
    var env = wrapEnv();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        PreparedProgram literal = unchecked(env, "wrapLen(7)");
        assertEquals(7L, outcome(plain(literal)));
        assertEquals(7L, outcome(instrumented(literal, "wrapLen(7)", "wrap.cel")));
        PreparedProgram folded = unchecked(env, "wrapLen(2 + 3)");
        assertEquals(5L, outcome(plain(folded)));
        assertEquals(5L, outcome(instrumented(folded, "wrapLen(2 + 3)", "wrap.cel")));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void strictValidationNoLongerRejectsAdapterMatchingConstantCall() {
    var env = strictWrapEnv();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        PreparedProgram template = unchecked(env, "wrapLen('hello')");
        assertEquals(5L, outcome(plain(template)));
        assertEquals(5L, outcome(instrumented(template, "wrapLen('hello')", "wrap.cel")));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void unmatchedConstantStillReportsNoOverloadOnBothTargets() {
    var env = wrapEnv();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        PreparedProgram template = unchecked(env, "wrapLen(true)");
        assertEquals(CelException.Code.NO_OVERLOAD, codeOf(outcome(plain(template))));
        assertEquals(
            CelException.Code.NO_OVERLOAD,
            codeOf(outcome(instrumented(template, "wrapLen(true)", "wrap.cel"))));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void singleGlobalConvenienceApiDoesNotRefineMatchingRuntime() {
    var env = singleGlobalConvenienceEnv();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        PreparedProgram template = unchecked(env, "wrapLenOnly('hello')");
        assertEquals(CelException.Code.NO_OVERLOAD, codeOf(outcome(plain(template))));
        assertEquals(
            CelException.Code.NO_OVERLOAD,
            codeOf(outcome(instrumented(template, "wrapLenOnly('hello')", "wrap.cel"))));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void singleGlobalExplicitOverloadIdRefinesLikeRuntime() {
    var env = singleGlobalExplicitEnv();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        PreparedProgram template = unchecked(env, "wrapLenOnly('hello')");
        assertEquals(5L, outcome(plain(template)));
        assertEquals(5L, outcome(instrumented(template, "wrapLenOnly('hello')", "wrap.cel")));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void variableArgumentDispatchesThroughSelectAndRemainsConsistent() {
    var env = wrapBuilder(Environment.Validation.DEFERRED).variable("s", CelType.DYN).build();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        PreparedProgram template = unchecked(env, "wrapLen(s)");
        Program uninstrumented = plain(template);
        Program instrumented = instrumented(template, "wrapLen(s)", "wrap.cel");
        assertEquals(5L, outcome(uninstrumented, Map.of("s", "hello")));
        assertEquals(5L, outcome(instrumented, Map.of("s", "hello")));
        assertEquals(7L, outcome(uninstrumented, Map.of("s", 7L)));
        assertEquals(7L, outcome(instrumented, Map.of("s", 7L)));
      } finally {
        context.leave();
      }
    }
  }

  @Test
  public void overloadDispatchWithoutAdaptersIsUnchangedOnBothTargets() {
    var env = convertEnv();
    try (var context = Context.newBuilder("cel").build()) {
      context.enter();
      try {
        PreparedProgram ints = unchecked(env, "convert(1)");
        PreparedProgram strs = unchecked(env, "convert('a')");
        assertEquals("int:1", outcome(plain(ints)));
        assertEquals("int:1", outcome(instrumented(ints, "convert(1)", "convert.cel")));
        assertEquals("str:a", outcome(plain(strs)));
        assertEquals("str:a", outcome(instrumented(strs, "convert('a')", "convert.cel")));
      } finally {
        context.leave();
      }
    }
  }
}
