package dev.trufflecel;

import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.*;
import java.util.*;
import dev.elide.truffledata.AbstractRow;

/** Explicit interop for CEL aggregate values; never exposes arbitrary Java methods. */
@ExportLibrary(InteropLibrary.class)
final class GuestValue implements TruffleObject {
  final Object value;

  private GuestValue(Object value) {
    this.value = value;
  }

  static Object wrap(Object value) {
    if (value instanceof Boolean
        || value instanceof Long
        || value instanceof Double
        || value instanceof String) return value;
    return new GuestValue(value);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static Object unwrap(Object value, int depth) throws InteropException {
    return unwrap(value, depth, new int[] {1_000_000});
  }

  private static Object unwrap(Object value, int depth, int[] remaining) throws InteropException {
    if (--remaining[0] < 0)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "host data size limit exceeded");
    if (depth > 100)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "host data nesting limit exceeded");
    if (value instanceof GuestValue g) return g.value;
    if (value instanceof AbstractRow row) {
      var result = new LinkedHashMap<String, Object>();
      for (int i = 0; i < row.schema().size(); i++) {
        String name = row.schema().field(i).name();
        if (row.schema().indexOf(name) != i)
          throw Values.error(CelException.Code.INVALID_ARGUMENT, "Ambiguous data field: " + name);
        Object item = dev.elide.truffledata.DataLibrary.factory().getUncached().read(row, i);
        if (item != dev.elide.truffledata.Missing.INSTANCE)
          result.put(name, unwrap(item, depth + 1, remaining));
      }
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof dev.elide.truffledata.ExactNumber
        || value == dev.elide.truffledata.Missing.INSTANCE) return DataValues.from(value);
    if (value instanceof Double || value instanceof Float) return ((Number) value).doubleValue();
    if (value instanceof byte[] b) return new Bytes(b);
    InteropLibrary lib = InteropLibrary.getUncached(value);
    if (lib.isNull(value)) return CelNull.INSTANCE;
    if (lib.isBoolean(value)) return lib.asBoolean(value);
    if (lib.isString(value)) return lib.asString(value);
    if (lib.fitsInLong(value)) return lib.asLong(value);
    if (lib.fitsInDouble(value)) return lib.asDouble(value);
    if (lib.isInstant(value)) return lib.asInstant(value);
    if (lib.isDuration(value)) return lib.asDuration(value);
    if (lib.hasHashEntries(value)) {
      var result = new LinkedHashMap<Object, Object>();
      Object iterator = lib.getHashEntriesIterator(value);
      InteropLibrary it = InteropLibrary.getUncached(iterator);
      while (it.hasIteratorNextElement(iterator)) {
        Object entry = it.getIteratorNextElement(iterator);
        InteropLibrary e = InteropLibrary.getUncached(entry);
        result.put(
            unwrap(e.readArrayElement(entry, 0), depth + 1, remaining),
            unwrap(e.readArrayElement(entry, 1), depth + 1, remaining));
      }
      return Collections.unmodifiableMap(result);
    }
    if (lib.hasArrayElements(value)) {
      long length = lib.getArraySize(value);
      if (length > 1000000)
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "host array size limit exceeded");
      var result = new ArrayList<Object>();
      for (long i = 0; i < length; i++)
        result.add(unwrap(lib.readArrayElement(value, i), depth + 1, remaining));
      return List.copyOf(result);
    }
    if (lib.hasMembers(value)) {
      var result = new LinkedHashMap<String, Object>();
      Object members = lib.getMembers(value);
      InteropLibrary keys = InteropLibrary.getUncached(members);
      long count = keys.getArraySize(members);
      if (count > 1000000)
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "host map size limit exceeded");
      for (long i = 0; i < count; i++) {
        String key = (String) keys.readArrayElement(members, i);
        result.put(key, unwrap(lib.readMember(value, key), depth + 1, remaining));
      }
      return Collections.unmodifiableMap(result);
    }
    throw UnsupportedTypeException.create(new Object[] {value}, "unsupported CEL input");
  }

  @ExportMessage
  boolean isNumber() {
    return value instanceof UInt;
  }

  @ExportMessage
  boolean fitsInByte() {
    return value instanceof UInt u && u.bits() >= 0 && u.bits() <= Byte.MAX_VALUE;
  }

  @ExportMessage
  boolean fitsInShort() {
    return value instanceof UInt u && u.bits() >= 0 && u.bits() <= Short.MAX_VALUE;
  }

  @ExportMessage
  boolean fitsInInt() {
    return value instanceof UInt u && u.bits() >= 0 && u.bits() <= Integer.MAX_VALUE;
  }

  @ExportMessage
  boolean fitsInLong() {
    return value instanceof UInt u && u.bits() >= 0;
  }

  @ExportMessage
  boolean fitsInBigInteger() {
    return value instanceof UInt;
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  boolean fitsInFloat() {
    return value instanceof UInt u
        && new java.math.BigDecimal(u.toBigInteger().floatValue())
            .toBigInteger()
            .equals(u.toBigInteger());
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  boolean fitsInDouble() {
    return value instanceof UInt u
        && new java.math.BigDecimal(u.toBigInteger().doubleValue())
            .toBigInteger()
            .equals(u.toBigInteger());
  }

  @ExportMessage
  byte asByte() throws UnsupportedMessageException {
    if (!fitsInByte()) throw UnsupportedMessageException.create();
    return (byte) ((UInt) value).bits();
  }

  @ExportMessage
  short asShort() throws UnsupportedMessageException {
    if (!fitsInShort()) throw UnsupportedMessageException.create();
    return (short) ((UInt) value).bits();
  }

  @ExportMessage
  int asInt() throws UnsupportedMessageException {
    if (!fitsInInt()) throw UnsupportedMessageException.create();
    return (int) ((UInt) value).bits();
  }

  @ExportMessage
  long asLong() throws UnsupportedMessageException {
    if (!fitsInLong()) throw UnsupportedMessageException.create();
    return ((UInt) value).bits();
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  java.math.BigInteger asBigInteger() throws UnsupportedMessageException {
    if (!fitsInBigInteger()) throw UnsupportedMessageException.create();
    return ((UInt) value).toBigInteger();
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  float asFloat() throws UnsupportedMessageException {
    if (!fitsInFloat()) throw UnsupportedMessageException.create();
    return ((UInt) value).toBigInteger().floatValue();
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  double asDouble() throws UnsupportedMessageException {
    if (!fitsInDouble()) throw UnsupportedMessageException.create();
    return ((UInt) value).toBigInteger().doubleValue();
  }

  @ExportMessage
  boolean isNull() {
    return value == CelNull.INSTANCE;
  }

  @ExportMessage
  boolean hasArrayElements() {
    return value instanceof List<?> || value instanceof Bytes;
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  long getArraySize() throws UnsupportedMessageException {
    if (value instanceof List<?> l) return l.size();
    if (value instanceof Bytes b) return b.size();
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  boolean isArrayElementReadable(long index) {
    return index >= 0
        && (value instanceof List<?> l && index < l.size()
            || value instanceof Bytes b && index < b.size());
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object readArrayElement(long index) throws InvalidArrayIndexException {
    if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
    return value instanceof Bytes b
        ? (long) (b.toByteArray()[(int) index] & 255)
        : wrap(((List<?>) value).get((int) index));
  }

  @ExportMessage
  boolean hasMembers() {
    return value instanceof Map<?, ?>;
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object getMembers(boolean includeInternal) throws UnsupportedMessageException {
    if (!(value instanceof Map<?, ?> m)) throw UnsupportedMessageException.create();
    return wrap(m.keySet().stream().filter(k -> k instanceof String).toList());
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  boolean isMemberReadable(String member) {
    return value instanceof Map<?, ?> m && m.containsKey(member);
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object readMember(String member) throws UnknownIdentifierException {
    if (!isMemberReadable(member)) throw UnknownIdentifierException.create(member);
    return wrap(((Map<?, ?>) value).get(member));
  }

  @ExportMessage
  boolean hasHashEntries() {
    return value instanceof Map<?, ?>;
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  long getHashSize() throws UnsupportedMessageException {
    if (value instanceof Map<?, ?> m) return m.size();
    throw UnsupportedMessageException.create();
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  boolean isHashEntryReadable(Object key) {
    return value instanceof Map<?, ?> m
        && Values.key(m, key instanceof GuestValue guest ? guest.value : Values.in(key)) != null;
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object readHashValue(Object key) throws UnknownKeyException {
    if (!(value instanceof Map<?, ?> m)) throw UnknownKeyException.create(key);
    Object k = Values.key(m, key instanceof GuestValue guest ? guest.value : Values.in(key));
    if (k == null) throw UnknownKeyException.create(key);
    return wrap(m.get(k));
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object getHashEntriesIterator() throws UnsupportedMessageException {
    if (!(value instanceof Map<?, ?> m)) throw UnsupportedMessageException.create();
    return new Entries(m.entrySet().iterator());
  }

  @ExportMessage
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  Object toDisplayString(boolean allowSideEffects) {
    return value.toString();
  }

  @ExportLibrary(InteropLibrary.class)
  static final class Entries implements TruffleObject {
    final Iterator<? extends Map.Entry<?, ?>> iterator;

    Entries(Iterator<? extends Map.Entry<?, ?>> iterator) {
      this.iterator = iterator;
    }

    @ExportMessage
    boolean isIterator() {
      return true;
    }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasIteratorNextElement() {
      return iterator.hasNext();
    }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getIteratorNextElement() throws StopIterationException {
      if (!iterator.hasNext()) throw StopIterationException.create();
      var entry = iterator.next();
      return wrap(List.of(entry.getKey(), entry.getValue()));
    }
  }
}
