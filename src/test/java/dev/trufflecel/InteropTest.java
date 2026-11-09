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
}
