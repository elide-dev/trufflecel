package dev.trufflecel;

import org.junit.Test;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.proxy.*;
import com.oracle.truffle.api.interop.*;
import java.util.*;
import java.math.BigInteger;
import static org.junit.Assert.*;

public class InteropTest {
  @Test
  public void registeredLanguageAndBindings() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      assertEquals(7, context.eval("cel", "1+2*3").asInt());
      var bindings = context.getBindings("cel");
      bindings.putMember(
          "user", ProxyObject.fromMap(Map.of("age", 30, "roles", ProxyArray.fromArray("admin"))));
      assertTrue(context.eval("cel", "user.age >= 18 && 'admin' in user.roles").asBoolean());
      bindings.putMember("n", 1);
      assertEquals(2, context.eval("cel", "n+1").asInt());
      bindings.putMember("n", 5);
      assertEquals(6, context.eval("cel", "n+1").asInt());
      assertTrue(bindings.hasMember("n"));
      assertTrue(bindings.getMemberKeys().contains("n"));
      assertEquals(5, bindings.getMember("n").asInt());
      bindings.putMember("nothing", null);
      assertTrue(context.eval("cel", "nothing == null").asBoolean());
      bindings.putMember("flag", true);
      assertTrue(context.eval("cel", "flag").asBoolean());
      bindings.putMember("wholeDouble", 1.0);
      assertTrue(context.eval("cel", "type(wholeDouble) == double").asBoolean());
      bindings.putMember("fraction", 1.5);
      assertEquals(1.5, context.eval("cel", "fraction").asDouble(), 0);
      assertEquals("CEL bindings", bindings.toString());
      assertFalse(
          assertThrows(PolyglotException.class, () -> context.eval("cel", "1/0"))
              .isInternalError());
      assertTrue(
          assertThrows(PolyglotException.class, () -> context.eval("cel", "1+")).isSyntaxError());
    }
  }

  @Test
  public void hostHashEntriesAndLimits() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      var bindings = context.getBindings("cel");
      bindings.putMember(
          "lookup",
          ProxyHashMap.from(new LinkedHashMap<Object, Object>(Map.of(1L, "one", "two", 2L))));
      assertTrue(context.eval("cel", "lookup[1] == 'one' && lookup.two == 2").asBoolean());
      ProxyArray huge =
          new ProxyArray() {
            public Object get(long index) {
              throw new AssertionError("oversize array must not be read");
            }

            public void set(long index, Value value) {
              throw new UnsupportedOperationException();
            }

            public long getSize() {
              return 1_000_001;
            }
          };
      assertThrows(IllegalArgumentException.class, () -> bindings.putMember("huge", huge));

      assertThrows(
          IllegalArgumentException.class,
          () -> bindings.putMember("pointer", (ProxyNativeObject) () -> 42L));
      assertEquals("18446744073709551615", context.eval("cel", "18446744073709551615u").toString());
    }
  }

  @Test
  public void resultsRetainTypesAndCollections() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      assertTrue(context.eval("cel", "null").isNull());
      var list = context.eval("cel", "[1, 'two', null]");
      assertEquals(3, list.getArraySize());
      assertEquals(1, list.getArrayElement(0).asLong());
      assertEquals("two", list.getArrayElement(1).asString());
      assertThrows(ArrayIndexOutOfBoundsException.class, () -> list.getArrayElement(10));
      var map = context.eval("cel", "{'a':1, 2:'b'}");
      assertEquals(1, map.getMember("a").asInt());
      assertEquals(2, map.getHashSize());
      assertEquals("b", map.getHashValue(2).asString());
      assertEquals(Set.of("a"), map.getMemberKeys());
      assertFalse(map.hasMember("missing"));
      var entries = map.getHashEntriesIterator();
      int count = 0;
      while (entries.hasIteratorNextElement()) {
        assertEquals(2, entries.getIteratorNextElement().getArraySize());
        count++;
      }
      assertEquals(2, count);
      var bytes = context.eval("cel", "b'\\xff'");
      assertEquals(255, bytes.getArrayElement(0).asInt());
      assertEquals(
          new BigInteger("18446744073709551615"),
          context.eval("cel", "18446744073709551615u").asBigInteger());
      assertEquals(1, context.eval("cel", "1u").asLong());
    }
  }

  @Test
  public void interopBoundaryRejectsInvalidOperations() throws Exception {
    InteropLibrary lib = InteropLibrary.getUncached();
    Object map = GuestValue.wrap(Map.of("a", 1L));
    assertThrows(UnknownIdentifierException.class, () -> lib.readMember(map, "missing"));
    assertThrows(UnknownKeyException.class, () -> lib.readHashValue(map, "missing"));
    assertThrows(UnsupportedMessageException.class, () -> lib.getArraySize(map));
    assertThrows(
        UnsupportedMessageException.class, () -> lib.getMembers(GuestValue.wrap(List.of())));
    assertThrows(
        UnsupportedMessageException.class, () -> lib.getHashSize(GuestValue.wrap(List.of())));
    assertThrows(
        UnsupportedMessageException.class,
        () -> lib.getHashEntriesIterator(GuestValue.wrap(List.of())));
    Object iterator = lib.getHashEntriesIterator(GuestValue.wrap(Map.of()));
    assertThrows(StopIterationException.class, () -> lib.getIteratorNextElement(iterator));
    var scope = new CelLanguage.Scope(null);
    assertThrows(UnknownIdentifierException.class, () -> lib.readMember(scope, "missing"));
    assertTrue(lib.hasLanguageId(scope));
    assertEquals("cel", lib.getLanguageId(scope));
    assertTrue(lib.isScope(scope));
    assertThrows(CelException.class, () -> GuestValue.unwrap(1L, 101));
    assertEquals(CelNull.INSTANCE, GuestValue.unwrap(GuestValue.wrap(CelNull.INSTANCE), 0));
    for (long bits :
        new long[] {
          0, 127, 128, 32767, 32768, Integer.MAX_VALUE, 1L << 40, Long.MAX_VALUE, Long.MIN_VALUE, -1
        }) {
      // Wrap through the guest representation directly to exercise every numeric width.
      var ctor = new UInt(bits);
      Object guest = GuestValue.wrap(ctor);
      assertTrue(lib.isNumber(guest));
      assertEquals(ctor.toBigInteger(), lib.asBigInteger(guest));
      if (lib.fitsInByte(guest)) assertEquals((byte) bits, lib.asByte(guest));
      else assertThrows(UnsupportedMessageException.class, () -> lib.asByte(guest));
      if (lib.fitsInShort(guest)) assertEquals((short) bits, lib.asShort(guest));
      else assertThrows(UnsupportedMessageException.class, () -> lib.asShort(guest));
      if (lib.fitsInInt(guest)) assertEquals((int) bits, lib.asInt(guest));
      else assertThrows(UnsupportedMessageException.class, () -> lib.asInt(guest));
      if (lib.fitsInLong(guest)) assertEquals(bits, lib.asLong(guest));
      else assertThrows(UnsupportedMessageException.class, () -> lib.asLong(guest));
      if (lib.fitsInFloat(guest))
        assertEquals(ctor.toBigInteger().floatValue(), lib.asFloat(guest), 0);
      else assertThrows(UnsupportedMessageException.class, () -> lib.asFloat(guest));
      if (lib.fitsInDouble(guest))
        assertEquals(ctor.toBigInteger().doubleValue(), lib.asDouble(guest), 0);
      else assertThrows(UnsupportedMessageException.class, () -> lib.asDouble(guest));
    }
  }

  @Test
  public void aggregateInteropRejectsNumericAndMissingElementReads() throws Exception {
    var empty = (GuestValue) GuestValue.wrap(List.of());
    assertFalse(empty.fitsInByte());
    assertFalse(empty.fitsInShort());
    assertFalse(empty.fitsInInt());
    assertFalse(empty.fitsInLong());
    assertFalse(empty.fitsInFloat());
    assertFalse(empty.fitsInDouble());
    assertThrows(UnsupportedMessageException.class, empty::asBigInteger);
    assertFalse(empty.isMemberReadable("x"));
    assertFalse(empty.isHashEntryReadable("x"));
    assertThrows(UnknownKeyException.class, () -> empty.readHashValue("x"));
    assertFalse(empty.isArrayElementReadable(0));
    var bytes = (GuestValue) GuestValue.wrap(new Bytes(new byte[] {1}));
    assertEquals(1, bytes.getArraySize());
    assertFalse(bytes.isArrayElementReadable(1));
    assertFalse(bytes.isArrayElementReadable(-1));
    var number = (GuestValue) GuestValue.wrap(new UInt(1));
    assertThrows(UnsupportedMessageException.class, number::getArraySize);
    assertFalse(number.isArrayElementReadable(0));
    var map = (GuestValue) GuestValue.wrap(Map.of(new UInt(1), "one"));
    assertTrue(map.isHashEntryReadable(number));
    assertEquals("one", map.readHashValue(number));
    assertFalse(map.isHashEntryReadable(GuestValue.wrap(new UInt(2))));
    assertThrows(UnknownKeyException.class, () -> map.readHashValue(GuestValue.wrap(new UInt(2))));
    assertEquals(1.25, GuestValue.unwrap(1.25f, 0));
    try (Context context = Context.newBuilder("cel").build()) {
      context.getBindings("cel").putMember("fraction", BigInteger.ONE.shiftLeft(64));
      assertEquals(0x1p64, context.eval("cel", "fraction").asDouble(), 0.0);
    }
  }

  @Test
  public void hostInputLimitsRejectWideAndCumulativelyLargeObjects() {
    try (Context context = Context.newBuilder("cel").build()) {
      var bindings = context.getBindings("cel");
      ProxyArray million =
          new ProxyArray() {
            public long getSize() {
              return 1_000_000;
            }

            public Object get(long index) {
              return 1L;
            }

            public void set(long index, Value value) {
              throw new UnsupportedOperationException();
            }
          };
      assertThrows(IllegalArgumentException.class, () -> bindings.putMember("large", million));
      ProxyObject tooManyKeys =
          new ProxyObject() {
            public Object getMember(String name) {
              throw new AssertionError("must reject key count before values");
            }

            public Object getMemberKeys() {
              return new ProxyArray() {
                public long getSize() {
                  return 1_000_001;
                }

                public Object get(long index) {
                  return "key";
                }

                public void set(long index, Value value) {
                  throw new UnsupportedOperationException();
                }
              };
            }

            public boolean hasMember(String name) {
              return true;
            }

            public void putMember(String name, Value value) {
              throw new UnsupportedOperationException();
            }
          };
      assertThrows(IllegalArgumentException.class, () -> bindings.putMember("wide", tooManyKeys));
    }
  }

  @Test
  public void hostByteArraysBindAsCelBytes() {
    for (boolean allAccess : List.of(true, false)) {
      var builder = Context.newBuilder("cel");
      if (allAccess) builder.allowHostAccess(HostAccess.ALL);
      try (Context context = builder.build()) {
        var bindings = context.getBindings("cel");
        bindings.putMember("ba", new byte[] {(byte) 255});
        assertTrue(context.eval("cel", "type(ba) == bytes").asBoolean());
        assertEquals(255, context.eval("cel", "ba").getArrayElement(0).asInt());
        assertEquals(1, context.eval("cel", "size(ba)").asInt());
        assertTrue(context.eval("cel", "ba == b'\\xff'").asBoolean());
        assertEquals(1, context.eval("cel", "size(bytes(ba))").asInt());
        bindings.putMember("empty", new byte[0]);
        assertTrue(context.eval("cel", "type(empty) == bytes").asBoolean());
        assertEquals(0, context.eval("cel", "size(empty)").asInt());
        bindings.putMember("multi", new byte[] {1, 2, (byte) 200});
        assertTrue(context.eval("cel", "type(multi) == bytes").asBoolean());
        assertEquals(3, context.eval("cel", "size(multi)").asInt());
        var multiVal = context.eval("cel", "multi");
        assertEquals(1, multiVal.getArrayElement(0).asInt());
        assertEquals(2, multiVal.getArrayElement(1).asInt());
        assertEquals(200, multiVal.getArrayElement(2).asInt());
        var guest = context.eval("cel", "b'\\xff'");
        bindings.putMember("roundtrip", guest);
        assertTrue(context.eval("cel", "type(roundtrip) == bytes").asBoolean());
        assertEquals(255, context.eval("cel", "roundtrip").getArrayElement(0).asInt());
        if (allAccess) {
          bindings.putMember("ints", new int[] {255});
          assertFalse(context.eval("cel", "type(ints) == bytes").asBoolean());
          assertEquals(255, context.eval("cel", "ints[0]").asInt());
        } else {
          // Under default (restrictive) host access int[] previously silently degraded to an empty
          // map; it must now bind as a list of ints under both access modes.
          bindings.putMember("ints", new int[] {255});
          assertFalse(context.eval("cel", "type(ints) == bytes").asBoolean());
          assertTrue(context.eval("cel", "type(ints) == list").asBoolean());
          assertEquals(255, context.eval("cel", "ints[0]").asInt());
        }
      }
    }
  }

  @Test
  public void hostTemporalBindings() {
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      var bindings = context.getBindings("cel");
      var instant = java.time.Instant.parse("2023-01-01T00:00:00Z");
      var duration = java.time.Duration.ofSeconds(3);
      // Instant and Duration bind as first-class CEL temporals (previously threw).
      bindings.putMember("ts", instant);
      bindings.putMember("d", duration);
      assertTrue(context.eval("cel", "ts == timestamp(\"2023-01-01T00:00:00Z\")").asBoolean());
      assertTrue(context.eval("cel", "d == duration(\"3s\")").asBoolean());
      assertTrue(
          context.eval("cel", "type(ts) == type(timestamp(\"2023-01-01T00:00:00Z\"))").asBoolean());
      assertTrue(context.eval("cel", "type(d) == type(duration(\"3s\"))").asBoolean());
      assertEquals(2023L, context.eval("cel", "ts.getFullYear()").asLong());
      assertEquals(3L, context.eval("cel", "d.getSeconds()").asLong());
      assertEquals("2023-01-01T00:00:00Z", context.eval("cel", "string(ts)").asString());
      assertEquals("3s", context.eval("cel", "string(d)").asString());
      // Temporal arithmetic with bound values.
      assertTrue(context.eval("cel", "ts + d == timestamp(\"2023-01-01T00:00:03Z\")").asBoolean());
      assertTrue(context.eval("cel", "(ts + d) - ts == duration(\"3s\")").asBoolean());
      // Legacy java.util.Date binds as a timestamp via HostObject.asInstant.
      var date = new java.util.Date(instant.toEpochMilli());
      bindings.putMember("dt", date);
      assertTrue(context.eval("cel", "dt == timestamp(\"2023-01-01T00:00:00Z\")").asBoolean());
      assertTrue(
          context.eval("cel", "type(dt) == type(timestamp(\"2023-01-01T00:00:00Z\"))").asBoolean());
      // Nested temporals inside host maps are converted recursively.
      var map = new LinkedHashMap<Object, Object>();
      map.put("ts", instant);
      map.put("d", duration);
      map.put("plain", "plain");
      bindings.putMember("m", ProxyHashMap.from(map));
      assertTrue(context.eval("cel", "m.ts == timestamp(\"2023-01-01T00:00:00Z\")").asBoolean());
      assertTrue(context.eval("cel", "m.d == duration(\"3s\")").asBoolean());
      assertEquals("plain", context.eval("cel", "m.plain").asString());
      // Nested temporals inside host lists are converted recursively.
      bindings.putMember("lst", ProxyArray.fromArray(instant, duration));
      assertTrue(context.eval("cel", "lst[0] == timestamp(\"2023-01-01T00:00:00Z\")").asBoolean());
      assertTrue(context.eval("cel", "lst[1] == duration(\"3s\")").asBoolean());
      // Re-binding over an existing temporal binding still works.
      bindings.putMember("ts", java.time.Instant.parse("2024-06-15T12:00:00Z"));
      assertTrue(context.eval("cel", "ts == timestamp(\"2024-06-15T12:00:00Z\")").asBoolean());
    }
  }

  @Test
  public void hostContainersBindUnderDefaultAccess() {
    // Under Truffle's default (restrictive) host access, raw Java Map/List/Set/array instances
    // must convert to proper CEL map/list values when bound via bindings.putMember, rather than
    // silently degrading to empty CEL maps. This must hold under both default and unrestricted
    // host access (matching the byte[]/Instant/Duration precedent).
    for (boolean allAccess : List.of(true, false)) {
      var builder = Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false");
      if (allAccess) builder.allowHostAccess(HostAccess.ALL);
      try (Context context = builder.build()) {
        var bindings = context.getBindings("cel");

        // Plain Java Map converts to a CEL map with its entries intact.
        var map = new LinkedHashMap<Object, Object>();
        map.put("a", "b");
        map.put("n", 42L);
        bindings.putMember("m", map);
        assertTrue(context.eval("cel", "type(m) == map").asBoolean());
        assertEquals(2, context.eval("cel", "size(m)").asInt());
        assertTrue(context.eval("cel", "'a' in m").asBoolean());
        assertEquals("b", context.eval("cel", "m.a").asString());
        assertEquals(42L, context.eval("cel", "m.n").asLong());

        // Plain Java List converts to a CEL list with its elements intact.
        var list = new ArrayList<Object>(List.of("a", "bb"));
        bindings.putMember("lst", list);
        assertTrue(context.eval("cel", "type(lst) == list").asBoolean());
        assertEquals(2, context.eval("cel", "size(lst)").asInt());
        assertEquals("a", context.eval("cel", "lst[0]").asString());
        assertTrue(context.eval("cel", "'a' in lst").asBoolean());

        // A Set is iterable and converts to a CEL list of its elements.
        bindings.putMember("set", new HashSet<>(List.of("a")));
        assertTrue(context.eval("cel", "type(set) == list").asBoolean());
        assertEquals(1, context.eval("cel", "size(set)").asInt());
        assertTrue(context.eval("cel", "'a' in set").asBoolean());

        // Primitive arrays convert to typed lists (not bytes, not empty maps).
        bindings.putMember("ints", new int[] {255, 1});
        assertTrue(context.eval("cel", "type(ints) == list").asBoolean());
        assertFalse(context.eval("cel", "type(ints) == bytes").asBoolean());
        assertEquals(2, context.eval("cel", "size(ints)").asInt());
        assertEquals(255, context.eval("cel", "ints[0]").asInt());
        assertEquals(1, context.eval("cel", "ints[1]").asInt());

        bindings.putMember("longs", new long[] {1L, 2L, 3L});
        assertEquals(3, context.eval("cel", "size(longs)").asInt());
        assertEquals(3L, context.eval("cel", "longs[2]").asLong());

        bindings.putMember("doubles", new double[] {1.5, 2.5});
        assertEquals(2.5, context.eval("cel", "doubles[1]").asDouble(), 0);

        bindings.putMember("bools", new boolean[] {true, false});
        assertTrue(context.eval("cel", "bools[0]").asBoolean());
        assertFalse(context.eval("cel", "bools[1]").asBoolean());

        bindings.putMember("shorts", new short[] {(short) 7, (short) 9});
        assertTrue(context.eval("cel", "type(shorts) == list").asBoolean());
        assertEquals(2, context.eval("cel", "size(shorts)").asInt());
        assertEquals(7, context.eval("cel", "shorts[0]").asInt());
        assertEquals(9, context.eval("cel", "shorts[1]").asInt());

        bindings.putMember("floats", new float[] {1.5f, 2.5f});
        assertEquals(2, context.eval("cel", "size(floats)").asInt());
        assertEquals(1.5, context.eval("cel", "floats[0]").asDouble(), 0);
        assertEquals(2.5, context.eval("cel", "floats[1]").asDouble(), 0);

        // Object[] of mixed scalars converts to a list.
        bindings.putMember("objs", new Object[] {1L, "x", true});
        assertEquals(3, context.eval("cel", "size(objs)").asInt());
        assertEquals(1L, context.eval("cel", "objs[0]").asLong());
        assertEquals("x", context.eval("cel", "objs[1]").asString());
        assertTrue(context.eval("cel", "objs[2]").asBoolean());

        // Nested containers convert recursively.
        var nested = new LinkedHashMap<Object, Object>();
        nested.put("list", new ArrayList<Object>(List.of(1L, 2L)));
        var inner = new LinkedHashMap<Object, Object>();
        inner.put("k", "v");
        nested.put("map", inner);
        bindings.putMember("nx", nested);
        assertEquals(2, context.eval("cel", "size(nx.list)").asInt());
        assertEquals(2L, context.eval("cel", "nx.list[1]").asLong());
        assertEquals("v", context.eval("cel", "nx.map.k").asString());

        // Byte arrays remain bytes (precedent preserved).
        bindings.putMember("ba", new byte[] {(byte) 255});
        assertTrue(context.eval("cel", "type(ba) == bytes").asBoolean());
        assertEquals(255, context.eval("cel", "ba").getArrayElement(0).asInt());
        assertTrue(context.eval("cel", "ba == b'\\xff'").asBoolean());

        // Empty Java containers convert to empty CEL containers of the right type.
        bindings.putMember("emptyList", new ArrayList<Object>());
        assertTrue(context.eval("cel", "type(emptyList) == list").asBoolean());
        assertEquals(0, context.eval("cel", "size(emptyList)").asInt());
        bindings.putMember("emptyMap", new LinkedHashMap<Object, Object>());
        assertTrue(context.eval("cel", "type(emptyMap) == map").asBoolean());
        assertEquals(0, context.eval("cel", "size(emptyMap)").asInt());

        // A polyglot proxy with no keys still binds as an intentional empty CEL map.
        bindings.putMember("proxy", ProxyObject.fromMap(Map.of()));
        assertTrue(context.eval("cel", "type(proxy) == map").asBoolean());
        assertEquals(0, context.eval("cel", "size(proxy)").asInt());

        // Binding map values that are null produces CEL null, not a dropped entry.
        var withNull = new LinkedHashMap<Object, Object>();
        withNull.put("k", null);
        bindings.putMember("withNull", withNull);
        assertTrue(context.eval("cel", "'k' in withNull").asBoolean());
        assertTrue(context.eval("cel", "withNull.k == null").asBoolean());
        assertEquals(1, context.eval("cel", "size(withNull)").asInt());
      }
    }
  }

  @Test
  public void hostUnsupportedTypesRejectedLoudly() {
    // Types unwrap cannot represent (BigDecimal, LocalDate) must be rejected at putMember with a
    // loud error rather than silently degrading to an empty CEL map, under both default and
    // unrestricted host access.
    for (boolean allAccess : List.of(true, false)) {
      var builder = Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false");
      if (allAccess) builder.allowHostAccess(HostAccess.ALL);
      try (Context context = builder.build()) {
        var bindings = context.getBindings("cel");
        var bigDecimal = new java.math.BigDecimal("1.5");
        var localDate = java.time.LocalDate.of(2020, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> bindings.putMember("bd", bigDecimal));
        assertThrows(IllegalArgumentException.class, () -> bindings.putMember("ld", localDate));
        // The rejected bindings were not stored.
        assertFalse(bindings.hasMember("bd"));
        assertFalse(bindings.hasMember("ld"));
        // A subsequent valid binding still works after a rejected one.
        bindings.putMember("ok", 1);
        assertEquals(2, context.eval("cel", "ok+1").asInt());
      }
    }
  }

  @Test
  public void hostContainerConversionPreservesScalarTypes() {
    // Scalars reached inside host containers must retain their CEL types (long/double/string/
    // bool/bytes/instant/duration), exercised under default host access where the host-object
    // wrapper is unwrapped to raw Java and converted by instanceof.
    try (Context context =
        Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false").build()) {
      var bindings = context.getBindings("cel");
      var map = new LinkedHashMap<Object, Object>();
      map.put("i", 1);
      map.put("l", 1L);
      map.put("d", 1.5);
      map.put("s", "hi");
      map.put("b", true);
      map.put("bytes", new byte[] {1});
      map.put("ts", java.time.Instant.parse("2023-01-01T00:00:00Z"));
      map.put("dur", java.time.Duration.ofSeconds(3));
      bindings.putMember("m", map);
      assertTrue(context.eval("cel", "type(m.i) == int").asBoolean());
      assertTrue(context.eval("cel", "type(m.l) == int").asBoolean());
      assertTrue(context.eval("cel", "type(m.d) == double").asBoolean());
      assertTrue(context.eval("cel", "type(m.s) == string").asBoolean());
      assertTrue(context.eval("cel", "type(m.b) == bool").asBoolean());
      assertTrue(context.eval("cel", "type(m.bytes) == bytes").asBoolean());
      assertTrue(context.eval("cel", "m.ts == timestamp(\"2023-01-01T00:00:00Z\")").asBoolean());
      assertTrue(context.eval("cel", "m.dur == duration(\"3s\")").asBoolean());
      assertEquals(1L, context.eval("cel", "m.i").asLong());
      assertEquals(1.5, context.eval("cel", "m.d").asDouble(), 0);
    }
  }

  @Test
  public void hostPrimitiveArraySizeLimits() {
    // Primitive arrays that exceed the per-array size bound or the overall host data size bound are
    // rejected loudly at putMember rather than silently accepted, under both default and
    // unrestricted host access.
    for (boolean allAccess : List.of(true, false)) {
      var builder = Context.newBuilder("cel").option("engine.WarnInterpreterOnly", "false");
      if (allAccess) builder.allowHostAccess(HostAccess.ALL);
      try (Context context = builder.build()) {
        var bindings = context.getBindings("cel");
        var tooLong = new int[1_000_001];
        assertThrows(IllegalArgumentException.class, () -> bindings.putMember("tooLong", tooLong));
        assertFalse(bindings.hasMember("tooLong"));
        // An array at the per-array limit still exceeds the remaining host data budget after the
        // entry decrement, so it is also rejected.
        var atLimit = new int[1_000_000];
        assertThrows(IllegalArgumentException.class, () -> bindings.putMember("atLimit", atLimit));
        assertFalse(bindings.hasMember("atLimit"));
        // A subsequent valid binding still works after rejected ones.
        bindings.putMember("ok", new int[] {1});
        assertEquals(1, context.eval("cel", "ok[0]").asInt());
      }
    }
  }
}
