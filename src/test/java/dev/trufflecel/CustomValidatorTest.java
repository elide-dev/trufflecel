package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CustomValidatorTest {
  private static CelException.Issue issue(String message, int offset) {
    return new CelException.Issue(CelException.Code.TYPE, message, offset, -1, -1);
  }

  @Test
  public void validatesOriginalSyntaxWithImmutableCheckedTypesAndNoHostConstants() {
    var callbacks = new AtomicInteger();
    Environment environment =
        Cel.newBuilder()
            .constant("hidden", Map.of("x", 1))
            .validator(
                context -> {
                  callbacks.incrementAndGet();
                  assertEquals("hidden", context.source());
                  assertEquals(Ast.Kind.IDENT, context.root().kind());
                  assertNull(context.root().value());
                  assertEquals(CelType.of("map"), context.type(context.root()));
                  assertEquals(context.resultType(), context.type(context.root()));
                  assertEquals(CelType.DYN, context.type(Ast.Node.identifier("other")));
                  assertThrows(UnsupportedOperationException.class, () -> context.types().clear());
                  return List.of();
                })
            .build();
    assertEquals(Map.of("x", 1L), environment.compile("hidden").evaluate());
    assertEquals(Map.of("x", 1L), environment.toBuilder().build().compile("hidden").evaluate());
    assertEquals(2, callbacks.get());
    Cel.newBuilder()
        .constant("pkg.hidden", 1)
        .validator(
            context -> {
              assertEquals(Ast.Kind.SELECT, context.root().kind());
              assertEquals(CelType.INT, context.type(context.root()));
              assertEquals(CelType.DYN, context.type(context.root().children().get(0)));
              return List.of();
            })
        .build()
        .compile("pkg.hidden");
    var map = new HashMap<Long, CelType>();
    map.put(1L, CelType.INT);
    var context = new Validator.Context("", Ast.Node.literal(1), CelType.INT, map);
    map.clear();
    assertEquals(Map.of(1L, CelType.INT), context.types());
  }

  @Test
  public void reportsCustomAndBuiltinFindingsTogetherAndBoundsOutput() {
    Environment environment =
        Cel.newBuilder()
            .variable("x", CelType.INT)
            .validation(Environment.Validation.STRICT)
            .validator(context -> List.of(issue("application policy", 0)))
            .build();
    CelException error = assertThrows(CelException.class, () -> environment.compile("[x, 1 / 0]"));
    assertEquals(2, error.issues().size());
    assertEquals("application policy", error.issues().get(0).message());
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, error.issues().get(1).code());
    assertEquals(1, error.line());
    Environment many =
        Cel.newBuilder().validator(context -> Collections.nCopies(150, issue("deny", 0))).build();
    assertEquals(100, assertThrows(CelException.class, () -> many.compile("true")).issues().size());
    assertEquals(100, assertThrows(CelException.class, () -> many.parse("true")).issues().size());
    var expressions = new ArrayList<String>();
    expressions.add("x");
    for (int i = 0; i < 150; i++) expressions.add("1 / 0");
    String source = "[" + String.join(",", expressions) + "]";
    assertEquals(
        100, assertThrows(CelException.class, () -> environment.compile(source)).issues().size());
    Environment resources = environment.toBuilder().maxRegexProgramSize(1).build();
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> resources.compile("'x'.matches('abcdef')")).code());
  }

  @Test
  public void validatesImportedTreesAndCopiesMacrosAndRegistrations() {
    Macro identity = Macro.global("identity", 1, (context, target, arguments) -> arguments.get(0));
    var calls = new AtomicInteger();
    Environment environment =
        Cel.newBuilder()
            .macro(identity)
            .standardMacros(Set.of("has"))
            .validator(
                context -> {
                  calls.incrementAndGet();
                  assertTrue(context.root().id() > 0);
                  return List.of();
                })
            .build()
            .toBuilder()
            .build();
    assertEquals(3L, environment.compile("identity(3)").evaluate());
    assertEquals(3L, Ast.of(Ast.Node.literal(3)).compile(environment).evaluate());
    assertEquals(2, calls.get());
    assertThrows(CelException.class, () -> environment.compile("[1].map(x, x)"));
    assertEquals(List.of(identity), environment.customMacros);
    assertEquals(Set.of("has"), environment.standardMacros);
  }

  @Test
  public void callbacksArePerCompilationAndMalformedCallbackResultsFailClearly() throws Exception {
    var calls = new AtomicInteger();
    var environment =
        Cel.newBuilder()
            .validator(
                context -> {
                  calls.incrementAndGet();
                  return List.of();
                })
            .build();
    try (var executor = Executors.newFixedThreadPool(4)) {
      var work = new ArrayList<Callable<Object>>();
      for (int i = 0; i < 32; i++) work.add(() -> environment.compile("1+2").evaluate());
      for (var result : executor.invokeAll(work)) assertEquals(3L, result.get());
    }
    assertEquals(32, calls.get());
    assertThrows(NullPointerException.class, () -> Cel.newBuilder().validator(null));
    assertThrows(
        NullPointerException.class,
        () -> Cel.newBuilder().validator(context -> null).build().compile("true"));
    assertThrows(
        NullPointerException.class,
        () ->
            Cel.newBuilder()
                .validator(context -> Collections.singletonList(null))
                .build()
                .compile("true"));
    assertThrows(
        NullPointerException.class,
        () -> new Validator.Context(null, Ast.Node.literal(true), CelType.BOOL, Map.of()));
    assertThrows(
        NullPointerException.class, () -> new Validator.Context("", null, CelType.BOOL, Map.of()));
    assertThrows(
        NullPointerException.class,
        () -> new Validator.Context("", Ast.Node.literal(true), null, Map.of()));
  }
}
