package dev.trufflecel;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Regression coverage for the silent wrong-result bug where a {@code get*}-prefixed host function
 * with multiple overloads executed its first-registered binding on opaque unregistered argument
 * values instead of throwing {@code NO_OVERLOAD} like every non-{@code get*} custom name.
 */
public class GetOverloadReproTest {

  // Two non-overlapping overloads of a get*-prefixed name, dispatched over dyn variables. Before
  // the fix, a call with opaque unregistered Java objects ran the first-registered (string,int)
  // implementation silently and returned a garbage value built from undeclared argument objects.
  // After the fix, no declared overload accepts opaque values, so the call throws NO_OVERLOAD,
  // matching the behaviour of the same overload set under a non-get* name (see control below).
  @Test
  public void getPrefixedMultiOverloadOpaqueArgsThrowsNoOverload() {
    var cel =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .variable("y", CelType.DYN)
            .function("getFoo", List.of(CelType.STRING, CelType.INT), CelType.STRING, a -> "A:" + a)
            .function("getFoo", List.of(CelType.INT, CelType.STRING), CelType.STRING, a -> "B:" + a)
            .build();

    var thrown =
        assertThrows(
            CelException.class,
            () ->
                cel.compile("getFoo(x, y)").evaluate(Map.of("x", new Object(), "y", new Object())));
    assertEquals(CelException.Code.NO_OVERLOAD, thrown.code());
  }

  // Control: the identical overload set under a name without the get* prefix already rejected
  // opaque argument values with NO_OVERLOAD before the fix; it must continue to do so.
  @Test
  public void nonGetPrefixedMultiOverloadOpaqueArgsThrowsNoOverload() {
    var cel =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .variable("y", CelType.DYN)
            .function("fooBar", List.of(CelType.STRING, CelType.INT), CelType.STRING, a -> "A:" + a)
            .function("fooBar", List.of(CelType.INT, CelType.STRING), CelType.STRING, a -> "B:" + a)
            .build();

    var thrown =
        assertThrows(
            CelException.class,
            () ->
                cel.compile("fooBar(x, y)").evaluate(Map.of("x", new Object(), "y", new Object())));
    assertEquals(CelException.Code.NO_OVERLOAD, thrown.code());
  }

  // The fix must not regress custom-overload dispatch for get*-prefixed names: arguments whose
  // runtime types match a declared overload still reach the matching implementation.
  @Test
  public void getPrefixedMultiOverloadDispatchesToMatchingOverload() {
    var cel =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .variable("y", CelType.DYN)
            .function("getFoo", List.of(CelType.STRING, CelType.INT), CelType.STRING, a -> "A:" + a)
            .function("getFoo", List.of(CelType.INT, CelType.STRING), CelType.STRING, a -> "B:" + a)
            .build();

    assertEquals("A:[abc, 1]", cel.compile("getFoo(x, y)").evaluate(Map.of("x", "abc", "y", 1L)));
    assertEquals("B:[2, xyz]", cel.compile("getFoo(x, y)").evaluate(Map.of("x", 2L, "y", "xyz")));
  }

  // A binding explicitly declared with a dyn parameter is the escape hatch for arbitrary host
  // values: the @unregistered remap still leaves dyn parameters accepting the opaque value, so a
  // get*-prefixed two-argument function with a dyn parameter at the opaque position keeps
  // dispatching to its host implementation. The fix must not over-reject this case. (parse skips
  // the static check that, by design, does not let a get* single binding drive argument checking,
  // since the builtin time getters win the same name at runtime; the goal here is to exercise the
  // runtime accepts gate, which is where the fix lives.)
  @Test
  public void getPrefixedBindingWithDynParameterAcceptsOpaqueValue() {
    var cel =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .function(
                "getThing",
                List.of(CelType.DYN, CelType.INT),
                CelType.STRING,
                a -> "got:" + a.get(0) + "," + a.get(1))
            .build();

    var out = cel.parse("getThing(x, 1)").evaluate(Map.of("x", new Object()));
    assertTrue(out.toString(), out.toString().startsWith("got:java.lang.Object"));
    assertTrue(out.toString(), out.toString().endsWith(",1"));
  }

  // Builtin time getters keep their precedence and concrete type checking on timestamps and
  // durations even though they are reached through the same standard-overload fall-through path.
  @Test
  public void builtinTimeGettersStillDispatchOnTimestampsAndDurations() {
    var env = Cel.newBuilder().build();
    assertEquals(1970L, env.compile("timestamp(0).getFullYear()").evaluate());
    assertEquals(0L, env.compile("timestamp(0).getHours()").evaluate());
    assertEquals(3600L, env.compile("duration('1h').getSeconds()").evaluate());
    // Two-argument form with a string zone still dispatches through the builtin time-getter path
    // before reaching the legacy name-keyed registry; the zone argument affects the result.
    assertEquals(
        2020L, env.compile("timestamp('2020-01-01T02:00:00Z').getFullYear('UTC')").evaluate());
    assertEquals(
        2019L,
        env.compile("timestamp('2020-01-01T02:00:00Z').getFullYear('America/New_York')")
            .evaluate());

    // A plain opaque object passed to a builtin time getter still throws NO_OVERLOAD (the builtin
    // type-checks concretely and the legacy registry never reaches a stale first binding).
    var env2 = Cel.newBuilder().variable("x", CelType.DYN).build();
    var thrown =
        assertThrows(
            CelException.class,
            () -> env2.parse("getFullYear(x)").evaluate(Map.of("x", new Object())));
    assertEquals(CelException.Code.NO_OVERLOAD, thrown.code());
  }

  // The three+-argument fall-through path in Functions.applyHost draws on the same stale first
  // binding and the same accepts gate; opaque arguments must produce NO_OVERLOAD there too.
  @Test
  public void getPrefixedThreeArgOverloadOpaqueArgsThrowsNoOverload() {
    var cel =
        Cel.newBuilder()
            .variable("x", CelType.DYN)
            .variable("y", CelType.DYN)
            .variable("z", CelType.DYN)
            .function(
                "getTri",
                List.of(CelType.STRING, CelType.INT, CelType.BOOL),
                CelType.STRING,
                a -> "A:" + a)
            .function(
                "getTri",
                List.of(CelType.INT, CelType.STRING, CelType.BOOL),
                CelType.STRING,
                a -> "B:" + a)
            .build();

    var thrown =
        assertThrows(
            CelException.class,
            () ->
                cel.compile("getTri(x, y, z)")
                    .evaluate(Map.of("x", new Object(), "y", new Object(), "z", new Object())));
    assertEquals(CelException.Code.NO_OVERLOAD, thrown.code());
  }
}
