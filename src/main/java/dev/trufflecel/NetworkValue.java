package dev.trufflecel;

import java.math.BigInteger;
import java.util.*;

/** Numeric IP/CIDR values. Parsing is purely local: never DNS or socket access. */
sealed interface NetworkValue {
  record Address(BigInteger bits, int width) implements NetworkValue {
    public Address {
      if (bits.signum() < 0 || bits.bitLength() > width || (width != 32 && width != 128))
        throw new IllegalArgumentException("invalid address");
    }

    public String toString() {
      if (width == 32) {
        long n = bits.longValue();
        return (n >>> 24) + "." + ((n >>> 16) & 255) + "." + ((n >>> 8) & 255) + "." + (n & 255);
      }
      int[] groups = new int[8];
      for (int i = 0; i < 8; i++) groups[i] = bits.shiftRight((7 - i) * 16).intValue() & 65535;
      int best = -1, length = 1;
      for (int i = 0; i < 8; ) {
        if (groups[i] != 0) {
          i++;
          continue;
        }
        int end = i;
        while (end < 8 && groups[end] == 0) end++;
        if (end - i > length) {
          best = i;
          length = end - i;
        }
        i = end;
      }
      var out = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        if (i == best) {
          out.append("::");
          i += length - 1;
          continue;
        }
        if (!out.isEmpty() && out.charAt(out.length() - 1) != ':') out.append(':');
        out.append(Integer.toHexString(groups[i]));
      }
      return out.toString();
    }

    Address masked(int prefix) {
      return new Address(bits.shiftRight(width - prefix).shiftLeft(width - prefix), width);
    }

    boolean unspecified() {
      return bits.signum() == 0;
    }

    boolean loopback() {
      return width == 32 ? bits.shiftRight(24).intValue() == 127 : bits.equals(BigInteger.ONE);
    }

    boolean linkUnicast() {
      return width == 32
          ? bits.shiftRight(16).intValue() == 0xa9fe
          : bits.shiftRight(118).intValue() == 0x3fa;
    }

    boolean multicast() {
      return width == 32
          ? bits.shiftRight(28).intValue() == 14
          : bits.shiftRight(120).intValue() == 255;
    }

    boolean linkMulticast() {
      return width == 32
          ? bits.shiftRight(8).intValue() == 0xe00000
          : (bits.shiftRight(112).intValue() & 0xff0f) == 0xff02;
    }

    boolean global() {
      return !unspecified()
          && !loopback()
          && !linkUnicast()
          && !multicast()
          && !(width == 32 && bits.longValue() == 0xffffffffL);
    }
  }

  record Prefix(Address address, int length) implements NetworkValue {
    public Prefix {
      if (length < 0 || length > address.width())
        throw new IllegalArgumentException("invalid prefix length");
    }

    public String toString() {
      return address + "/" + length;
    }

    boolean contains(Address other) {
      return address.width() == other.width()
          && address.masked(length).equals(other.masked(length));
    }
  }

  static boolean supports(String name) {
    return Set.of(
            "ip",
            "isIP",
            "ip.isCanonical",
            "cidr",
            "isCIDR",
            "family",
            "isUnspecified",
            "isLoopback",
            "isGlobalUnicast",
            "isLinkLocalMulticast",
            "isLinkLocalUnicast",
            "containsIP",
            "containsCIDR",
            "masked",
            "prefixLength")
        .contains(name);
  }

  static Object apply(String name, Object[] args) {
    Object v = args[0];
    return switch (name) {
      case "ip" -> v instanceof Prefix p ? p.address() : parse((String) v);
      case "cidr" -> prefix((String) v);
      case "isIP", "isCIDR" -> {
        try {
          if (name.equals("isIP")) parse((String) v);
          else prefix((String) v);
          yield true;
        } catch (IllegalArgumentException e) {
          yield false;
        }
      }
      case "ip.isCanonical" -> parse((String) v).toString().equals(v);
      case "family" -> ((Address) v).width() == 32 ? 4L : 6L;
      case "isUnspecified" -> ((Address) v).unspecified();
      case "isLoopback" -> ((Address) v).loopback();
      case "isGlobalUnicast" -> ((Address) v).global();
      case "isLinkLocalMulticast" -> ((Address) v).linkMulticast();
      case "isLinkLocalUnicast" -> ((Address) v).linkUnicast();
      case "containsIP" ->
          ((Prefix) v).contains(args[1] instanceof String s ? parse(s) : (Address) args[1]);
      case "containsCIDR" -> {
        Prefix other = args[1] instanceof String s ? prefix(s) : (Prefix) args[1];
        Prefix p = (Prefix) v;
        yield p.length() <= other.length() && p.contains(other.address());
      }
      case "masked" -> {
        Prefix p = (Prefix) v;
        yield new Prefix(p.address().masked(p.length()), p.length());
      }
      case "prefixLength" -> (long) ((Prefix) v).length();
      default -> throw Values.overload(name);
    };
  }

  static Prefix prefix(String text) {
    int slash = text.indexOf('/');
    if (slash < 0) throw new IllegalArgumentException("missing prefix");
    String length = text.substring(slash + 1);
    if (!length.matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("invalid prefix");
    return new Prefix(parse(text.substring(0, slash)), Integer.parseInt(length));
  }

  static Address parse(String text) {
    if (text.length() > 45 || text.isEmpty() || text.indexOf('%') >= 0)
      throw new IllegalArgumentException("invalid IP");
    if (!text.contains(":")) return new Address(ipv4(text), 32);
    if (!text.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("invalid IPv6");
    if (text.contains(".")) {
      int colon = text.lastIndexOf(':');
      BigInteger v4 = ipv4(text.substring(colon + 1));
      text =
          text.substring(0, colon + 1)
              + v4.shiftRight(16).toString(16)
              + ":"
              + v4.and(BigInteger.valueOf(65535)).toString(16);
    }
    int gap = text.indexOf("::");
    if (gap >= 0 && text.indexOf("::", gap + 2) >= 0)
      throw new IllegalArgumentException("multiple compressions");
    List<String> left = groups(gap < 0 ? text : text.substring(0, gap));
    List<String> right = gap < 0 ? List.of() : groups(text.substring(gap + 2));
    int missing = 8 - left.size() - right.size();
    if (gap < 0 ? missing != 0 : missing < 1)
      throw new IllegalArgumentException("invalid IPv6 length");
    BigInteger bits = BigInteger.ZERO;
    for (String part : left)
      bits = bits.shiftLeft(16).or(BigInteger.valueOf(Integer.parseInt(part, 16)));
    bits = bits.shiftLeft(missing * 16);
    for (String part : right)
      bits = bits.shiftLeft(16).or(BigInteger.valueOf(Integer.parseInt(part, 16)));
    if (bits.shiftRight(32).equals(BigInteger.valueOf(65535)))
      throw new IllegalArgumentException("IPv4-mapped IPv6 is not allowed");
    return new Address(bits, 128);
  }

  private static List<String> groups(String text) {
    if (text.isEmpty()) return List.of();
    List<String> parts = Arrays.asList(text.split(":", -1));
    for (String part : parts)
      if (!part.matches("[0-9a-fA-F]{1,4}"))
        throw new IllegalArgumentException("invalid IPv6 group");
    return parts;
  }

  private static BigInteger ipv4(String text) {
    String[] parts = text.split("\\.", -1);
    if (parts.length != 4) throw new IllegalArgumentException("invalid IPv4");
    long value = 0;
    for (String part : parts) {
      if (!part.matches("0|[1-9][0-9]{0,2}"))
        throw new IllegalArgumentException("invalid IPv4 octet");
      int n = Integer.parseInt(part);
      if (n > 255) throw new IllegalArgumentException("invalid IPv4 octet");
      value = (value << 8) | n;
    }
    return BigInteger.valueOf(value);
  }
}
