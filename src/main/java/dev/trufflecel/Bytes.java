package dev.trufflecel;

import java.util.Arrays;

/** Immutable bytes; copies at the embedding boundary. */
public final class Bytes implements Comparable<Bytes> {
  private final byte[] value;

  public Bytes(byte[] value) {
    this.value = value.clone();
  }

  public byte[] toByteArray() {
    return value.clone();
  }

  // Trusted internal codecs may read this owned array during a call. Never mutate it,
  // expose it to host code, or use it with a decoder that retains borrowed storage.
  byte[] arrayForRead() {
    return value;
  }

  public int size() {
    return value.length;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Bytes b && Arrays.equals(value, b.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public int compareTo(Bytes other) {
    return Arrays.compareUnsigned(value, other.value);
  }

  Bytes concat(Bytes other) {
    byte[] result = Arrays.copyOf(value, value.length + other.value.length);
    System.arraycopy(other.value, 0, result, value.length, other.value.length);
    return new Bytes(result);
  }
}
