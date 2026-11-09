package dev.trufflecel;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public final class PojoTest {
  @Test
  public void generatedRecordSnapshotsOwnNestedCollectionsBytesAndEnums() {
    var addresses = new ArrayList<>(List.of(new PojoFixtures.Address(123, "Main")));
    var group = new ArrayList<>(List.of(1, 2));
    Map<String, List<Integer>> groups = new LinkedHashMap<>(Map.of("team", group));
    byte[] bytes = {1, 2};
    var original =
        new PojoFixtures.User(
            42, addresses, groups, PojoFixtures.State.ACTIVE, bytes, new Object());
    var type = PojoFixtures_UserCel.INSTANCE;
    var owned = type.snapshot(original);
    addresses.clear();
    group.clear();
    groups.clear();
    bytes[0] = 9;
    var environment =
        PojoFixtures_AddressCel.INSTANCE
            .register(type.register(Cel.newBuilder()))
            .variable("u", CelType.of(type.name()))
            .build();
    assertTrue(
        environment
            .compile(
                "u.id == 42 && u.addresses[0].zip == 123 && u.groups.team == [1, 2] && u.state =="
                    + " 'ACTIVE' && u.payload == b'\\x01\\x02'")
            .test(Map.of("u", owned)));
    assertFalse(type.accepts(original));
    assertTrue(type.accepts(owned));
    assertEquals("example.User", owned.typeName());
    assertFalse(owned.fields().containsKey("ignored"));
    assertThrows(UnsupportedOperationException.class, () -> owned.fields().clear());
    assertTrue(owned.toString().startsWith("example.User"));
    assertEquals(owned, type.construct(owned.fields()));
    assertEquals(owned.hashCode(), type.construct(owned.fields()).hashCode());
    assertNotEquals(owned, "other");
    assertNotEquals(
        owned, PojoFixtures_AddressCel.INSTANCE.snapshot(new PojoFixtures.Address(123, "Main")));
    assertNotEquals(owned, type.construct(Map.of("id", 0)));
    assertEquals(
        CelType.of("example.User"), environment.compile("type(u)").evaluate(Map.of("u", owned)));
    assertEquals(123L, environment.compile("example.Address{zip: 123}.zip").evaluate());
  }

  @Test
  public void beanAccessorsAndScalarsAreDirectAndNormalized() {
    var bean = new PojoFixtures.Bean("first");
    bean.count = 7;
    var type = PojoFixtures_BeanCel.INSTANCE;
    var owned = type.snapshot(bean);
    bean.setLabel("changed");
    bean.count = 10;
    assertEquals(
        Map.of(
            "count",
            7L,
            "label",
            "first",
            "ready",
            true,
            "enabled",
            true,
            "URL",
            "https://example.test",
            "renamed",
            4L),
        owned.fields());
    var scalar =
        new PojoFixtures.Scalars(
            (byte) 1,
            (short) 2,
            3,
            4,
            5,
            6,
            true,
            'x',
            (byte) 7,
            (short) 8,
            9,
            10L,
            11F,
            12D,
            false,
            'y',
            "z",
            new UInt(13),
            new Bytes(new byte[] {1}),
            Instant.EPOCH,
            Duration.ZERO);
    var values = PojoFixtures_ScalarsCel.INSTANCE.snapshot(scalar).fields();
    assertEquals(1L, values.get("a"));
    assertEquals(5D, values.get("e"));
    assertEquals("x", values.get("h"));
    assertEquals("y", values.get("p"));
    assertEquals(Instant.EPOCH, values.get("t"));
    assertEquals(Duration.ZERO, values.get("u"));
    var absent =
        PojoFixtures_UserCel.INSTANCE.snapshot(
            new PojoFixtures.User(0, null, null, null, null, null));
    assertEquals(CelNull.INSTANCE, absent.fields().get("addresses"));
    assertEquals(CelNull.INSTANCE, absent.fields().get("groups"));
    assertEquals(CelNull.INSTANCE, absent.fields().get("state"));
    assertEquals(CelNull.INSTANCE, absent.fields().get("payload"));
    assertTrue(PojoFixtures_UserCel.INSTANCE.has(absent, "state"));
  }

  @Test
  public void constructorsValidateOwnershipTypesAndPresence() {
    var type = PojoFixtures_UserCel.INSTANCE;
    Object partial = type.construct(Map.of("id", 1));
    assertTrue(type.has(partial, "id"));
    assertFalse(type.has(partial, "state"));
    assertEquals(1L, type.get(partial, "id"));
    assertThrows(CelException.class, () -> type.get(partial, "state"));
    assertThrows(CelException.class, () -> type.construct(Map.of("missing", 1)));
    assertThrows(CelException.class, () -> type.has(partial, "missing"));
    assertThrows(CelException.class, () -> type.get(new Object(), "id"));
    assertThrows(CelException.class, () -> type.construct(Map.of("id", "wrong")));
    assertThrows(CelException.class, () -> type.construct(Map.of("id", List.of(1))));
    assertThrows(CelException.class, () -> type.construct(Map.of("id", Map.of())));
    assertThrows(
        CelException.class, () -> type.construct(Map.of("addresses", List.of(new Object()))));
    assertThrows(
        CelException.class,
        () -> type.construct(Map.of("addresses", List.of(type.construct(Map.of())))));
    assertThrows(
        CelException.class, () -> type.construct(Map.of("groups", Map.of("a", List.of("wrong")))));
    assertEquals(
        CelNull.INSTANCE,
        type.get(type.construct(Collections.singletonMap("state", null)), "state"));
    assertThrows(NullPointerException.class, () -> type.snapshot(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> type.snapshot(new PojoFixtures.User(0, null, null, null, null, null), 0, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> type.snapshot(new PojoFixtures.User(0, null, null, null, null, null), 1, 0));
  }

  @Test
  public void sharedTraversalBoundsCyclesDepthAndNodeCount() {
    var type = PojoFixtures_RecursiveCel.INSTANCE;
    var child = new PojoFixtures.Recursive();
    var root = new PojoFixtures.Recursive();
    root.next = child;
    root.children = List.of(child, child);
    assertNotNull(type.snapshot(root));
    child.next = root;
    assertEquals(
        CelException.Code.INVALID_ARGUMENT,
        assertThrows(CelException.class, () -> type.snapshot(root)).code());
    child.next = null;
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> type.snapshot(root, 1, 100)).code());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> type.snapshot(root, 100, 2)).code());
    var context = new PojoType.SnapshotContext(100, 100);
    assertThrows(CelException.class, () -> context.scalar(new Object()));
    assertThrows(
        CelException.class,
        () ->
            context.list(
                List.of(1),
                value -> {
                  throw new CelException(CelException.Code.INVALID_ARGUMENT, "bad value");
                }));
    assertEquals(List.of(1L), context.list(List.of(1), context::scalar));
    assertThrows(
        CelException.class,
        () ->
            context.map(
                Map.of("a", 1),
                context::scalar,
                value -> {
                  throw new CelException(CelException.Code.INVALID_ARGUMENT, "bad value");
                }));
    assertEquals(Map.of("a", 1L), context.map(Map.of("a", 1), context::scalar, context::scalar));
  }

  @Test
  public void constructorsBoundExistingOwnedGraphsAndMapsRejectNullKeys() {
    var type = PojoFixtures_RecursiveCel.INSTANCE;
    Object previous = type.construct(Map.of());
    for (int i = 1; i < 100; i++) previous = type.construct(Map.of("next", previous));
    Object deepest = previous;
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> type.construct(Map.of("next", deepest))).code());
    assertEquals(
        CelException.Code.RESOURCE_EXHAUSTED,
        assertThrows(CelException.class, () -> type.construct(Map.of("children", List.of(deepest))))
            .code());
    var userType = PojoFixtures_UserCel.INSTANCE;
    var user =
        userType.snapshot(
            new PojoFixtures.User(
                1,
                List.of(new PojoFixtures.Address(1, "A")),
                Map.of("group", List.of(1)),
                PojoFixtures.State.ACTIVE,
                new byte[] {1},
                null));
    // Existing owned values are inspected through both nested collections and objects.
    var envelope = PojoFixtures_EnvelopeCel.INSTANCE;
    Object wrapped = envelope.construct(Map.of("user", user));
    assertEquals(user, envelope.get(wrapped, "user"));
    assertEquals(user, userType.construct(user.fields()));
    assertThrows(
        CelException.class,
        () ->
            userType.snapshot(
                new PojoFixtures.User(
                    1, List.of(), Collections.singletonMap(null, List.of(1)), null, null, null)));
    assertThrows(
        CelException.class,
        () -> userType.construct(Map.of("groups", Collections.singletonMap(null, List.of(1)))));
  }

  @Test
  public void pojoSnapshotEqualityPreservesIeeeNanSemantics() {
    var type = PojoFixtures_ScalarsCel.INSTANCE;
    var environment =
        type.register(Cel.newBuilder())
            .variable("a", CelType.of(type.name()))
            .variable("b", CelType.of(type.name()))
            .build();
    var nanA = type.construct(Map.of("f", Double.NaN));
    var nanB = type.construct(Map.of("f", Double.NaN));
    var nanBoxedA = type.construct(Map.of("n", Double.NaN));
    var nanBoxedB = type.construct(Map.of("n", Double.NaN));
    var finiteA = type.construct(Map.of("f", 1.5));
    var finiteB = type.construct(Map.of("f", 1.5));
    var different = type.construct(Map.of("f", 2.5));
    var nanBindings = Map.<String, Object>of("a", nanA, "b", nanB);

    // Whole-snapshot == must be false for NaN-containing POJOs (IEEE/CEL semantics).
    assertFalse(environment.compile("a == b").test(nanBindings));
    assertTrue(environment.compile("a != b").test(nanBindings));
    // List membership must also be false: Values.equal routes PojoType.Value pairs field-wise.
    assertFalse(environment.compile("a in [b]").test(nanBindings));
    // Field-level comparison bypasses adapter dispatch and is already IEEE-correct.
    assertFalse(environment.compile("a.f == b.f").test(nanBindings));
    // Boxed Double fields with NaN are also IEEE-unequal at the snapshot level.
    assertFalse(type.equal(nanBoxedA, nanBoxedB));
    assertFalse(Values.equal(nanBoxedA, nanBoxedB));
    // Non-NaN snapshots compare equal.
    assertTrue(environment.compile("a == b").test(Map.of("a", finiteA, "b", finiteB)));
    assertTrue(environment.compile("a in [b]").test(Map.of("a", finiteA, "b", finiteB)));
    assertTrue(type.equal(finiteA, finiteB));
    assertTrue(Values.equal(finiteA, finiteB));
    // Distinct non-NaN snapshots compare unequal.
    assertFalse(environment.compile("a == b").test(Map.of("a", finiteA, "b", different)));
    assertFalse(type.equal(finiteA, different));
    assertFalse(Values.equal(finiteA, different));
    // Direct adapter equal() is IEEE-safe.
    assertFalse(type.equal(nanA, nanB));
    // Direct Values.equal() is IEEE-safe for POJO snapshot pairs.
    assertFalse(Values.equal(nanA, nanB));
    // Host-side Value.equals retains Java equality (NaN-reflexive), unchanged by the fix.
    assertEquals(nanA, nanB);
    assertEquals(nanA.hashCode(), nanB.hashCode());
    // Adapter equal() short-circuits when either operand is not accepted by this type.
    var userValue = PojoFixtures_UserCel.INSTANCE.construct(Map.of());
    assertFalse(type.equal(new Object(), nanA));
    assertFalse(type.equal(nanA, new Object()));
    assertFalse(type.equal(nanA, userValue));
    // Values.equal routes mismatched-type PojoType.Value pairs through the typeName guard.
    assertFalse(Values.equal(nanA, userValue));
    // Values.equal rejects same-type snapshots with differing field counts.
    var onlyF = type.construct(Map.of("f", 1.5));
    var fAndN = type.construct(Map.of("f", 1.5, "n", 2.5));
    assertFalse(Values.equal(onlyF, fAndN));
    // Values.equal rejects same-type, same-size snapshots with a key missing from the right.
    var onlyN = type.construct(Map.of("n", 2.5));
    assertFalse(Values.equal(onlyF, onlyN));
    // Values.equal falls back to host equals when only the left operand is a PojoType.Value.
    assertFalse(Values.equal(nanA, "x"));
  }
}
