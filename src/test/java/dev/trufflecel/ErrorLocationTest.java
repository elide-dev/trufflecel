package dev.trufflecel;

import static org.junit.Assert.*;
import static dev.trufflecel.CanonicalAst.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class ErrorLocationTest {
  private static CelException failure(Program program, Map<String, Object> input) {
    return assertThrows(CelException.class, () -> program.evaluate(input));
  }

  @Test
  public void innermostFailureKeepsItsLocationThroughFoldingAndLowering() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    for (String source : List.of("1 +\n10 / x", "1 +\n(10 / 0) + x")) {
      var program = env.prepare(source).instantiate();
      var error = failure(program, Map.of("x", 0L));
      assertEquals(CelException.Code.DIVIDE_BY_ZERO, error.code());
      assertEquals(source.indexOf('/'), error.offset());
      assertEquals(2, error.line());
      assertEquals(source.indexOf('/') - source.indexOf('\n'), error.column());
      assertTrue(error.expressionId() > 0);
      assertEquals(error.expressionId(), failure(program, Map.of("x", 0L)).expressionId());
    }
    String source = "x + 1 + 2";
    var error = failure(env.compile(source), Map.of("x", Long.MAX_VALUE));
    assertEquals(source.indexOf('+'), error.offset());
    assertEquals(CelException.Code.OVERFLOW, error.code());
  }

  @Test
  public void arithmeticChainRetainsEachOriginalOperatorLocation() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    String source = "(x + 1)\n * 2\n - 3";
    var program = env.compile(source);
    for (var input :
        Map.of(Long.MAX_VALUE, '+', Long.MAX_VALUE / 2, '*', Long.MIN_VALUE / 2 - 1, '-')
            .entrySet()) {
      var error = failure(program, Map.of("x", input.getKey()));
      int position = source.indexOf(input.getValue());
      assertEquals(CelException.Code.OVERFLOW, error.code());
      assertEquals(position, error.offset());
      int lastNewline = source.lastIndexOf('\n', position);
      assertEquals(position - lastNewline, error.column());
      assertEquals(
          1 + source.substring(0, position).chars().filter(c -> c == '\n').count(), error.line());
      assertTrue(error.expressionId() > 0);
    }
  }

  @Test
  public void arithmeticChainRetainsFullWidthCanonicalIds() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    var parsed = parse(env, "x + 1 + 2");
    var expr = child(parsed, "expr");
    var call = child(expr, "call_expr");
    var args = new ArrayList<>(messages(call, "args"));
    var inner = args.get(0);
    long id = (1L << 40) + 7;
    args.set(
        0,
        inner.toBuilder().setField(inner.getDescriptorForType().findFieldByName("id"), id).build());
    var updatedCall =
        call.toBuilder()
            .setField(call.getDescriptorForType().findFieldByName("args"), args)
            .build();
    var updatedExpr =
        expr.toBuilder()
            .setField(expr.getDescriptorForType().findFieldByName("call_expr"), updatedCall)
            .build();
    var updated =
        parsed.toBuilder()
            .setField(parsed.getDescriptorForType().findFieldByName("expr"), updatedExpr)
            .build();
    var error = failure(program(env, updated), Map.of("x", Long.MAX_VALUE));
    assertEquals(CelException.Code.OVERFLOW, error.code());
    assertEquals(id, error.expressionId());
  }

  @Test
  public void logicalLoweringPreservesUtf16SourcePositions() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    String source = "true &&\n ('😀' == '' || 10 / x == 0)";
    var error = failure(env.compile(source), Map.of("x", 0L));
    assertEquals(CelException.Code.DIVIDE_BY_ZERO, error.code());
    assertEquals(source.indexOf('/'), error.offset());
    assertEquals(2, error.line());
    assertEquals(source.indexOf('/') - source.indexOf('\n'), error.column());
    assertTrue(error.expressionId() > 0);
  }

  @Test
  public void callbackExceptionsAreNotMutatedAcrossConcurrentPrograms() throws Exception {
    var original = new CelException(CelException.Code.INDEX, "host failure");
    var env =
        Cel.newBuilder()
            .function(
                "f",
                List.of(),
                CelType.INT,
                args -> {
                  throw original;
                })
            .build();
    var first = env.compile("f()");
    var second = env.compile("1 + f()");
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Integer>>();
      for (int i = 0; i < 40; i++) {
        Program program = i % 2 == 0 ? first : second;
        tasks.add(() -> failure(program, Map.of()).offset());
      }
      var results = pool.invokeAll(tasks);
      for (int i = 0; i < results.size(); i++)
        assertEquals(Integer.valueOf(i % 2 == 0 ? 0 : 4), results.get(i).get());
    }
    assertEquals(-1, original.offset());
    assertEquals(-1, original.expressionId());
    assertEquals("host failure", original.getMessage());
  }

  @Test
  public void canonicalIdsAndSourcePositionsSurviveImport() {
    var env = Cel.newBuilder().variable("x", CelType.INT).build();
    for (String source : List.of("10 / x", "1\n/ x", "('😀' == '') ? 1 :\n10/x")) {
      var parsed = parse(env, source);
      for (var ast : List.of(parsed, check(env, parsed))) {
        var error = failure(program(env, ast), Map.of("x", 0L));
        assertEquals(source.codePointCount(0, source.indexOf('/')), error.offset());
        assertEquals(source.contains("\n") ? 2 : 1, error.line());
        assertTrue(error.expressionId() > 0);
      }
    }
    var parsed = parse(env, "10/x");
    long id = (Long) field(child(parsed, "expr"), "id");
    var noSource = message("cel.expr.ParsedExpr", "expr", child(parsed, "expr"));
    var error = failure(program(env, noSource), Map.of("x", 0L));
    assertEquals(id, error.expressionId());
    assertEquals(-1, error.offset());
    assertEquals(-1, error.line());
    var badLines =
        message(
            "cel.expr.ParsedExpr",
            "expr",
            child(parsed, "expr"),
            "source_info",
            message("cel.expr.SourceInfo", "line_offsets", List.of(2, 1)));
    assertThrows(IllegalArgumentException.class, () -> program(env, badLines));
    var absentPosition =
        message(
            "cel.expr.ParsedExpr",
            "expr",
            child(parsed, "expr"),
            "source_info",
            message("cel.expr.SourceInfo", "positions", Map.of(id, -1)));
    assertEquals(-1, failure(program(env, absentPosition), Map.of("x", 0L)).offset());
  }

  @Test
  public void budgetFailuresIdentifyThePolicyRoot() {
    var program = Cel.newBuilder().evaluationBudget(1).build().compile("1 + 2 + 3");
    var error = failure(program, Map.of());
    assertEquals(CelException.Code.RESOURCE_EXHAUSTED, error.code());
    assertTrue(error.expressionId() > 0);
    assertEquals(6, error.offset());
  }
}
