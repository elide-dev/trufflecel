package dev.trufflecel;

import java.math.BigInteger;

/** An unsigned 64-bit CEL integer. The bits accessor preserves all 64 bits. */
public record UInt(long bits) implements Comparable<UInt> {
  public static UInt parse(String value) {
    return new UInt(Long.parseUnsignedLong(value));
  }

  public BigInteger toBigInteger() {
    return new BigInteger(Long.toUnsignedString(bits));
  }

  @Override
  public int compareTo(UInt other) {
    return Long.compareUnsigned(bits, other.bits);
  }

  @Override
  public String toString() {
    return Long.toUnsignedString(bits);
  }
}
