package dev.trufflecel;

import com.google.re2j.Pattern;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.math.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class Functions {
  private Functions() {}

  @TruffleBoundary
  static boolean stringPredicate(String name, Object left, Object right) {
    if (!(left instanceof String text && right instanceof String part)) throw Values.overload(name);
    return switch (name) {
      case "contains" -> text.contains(part);
      case "startsWith" -> text.startsWith(part);
      case "endsWith" -> text.endsWith(part);
      default -> throw Values.overload(name);
    };
  }

  static Object apply(String name, Object[] a, Environment env) {
    // Primitive scalar operations stay in the guest graph; generic libraries are host calls.
    if (a.length == 1) {
      if (name.equals("!") && a[0] instanceof Boolean b) return !b;
      if (name.equals("neg") && a[0] instanceof Long l) {
        try {
          return Math.negateExact(l);
        } catch (ArithmeticException e) {
          throw Values.error(CelException.Code.OVERFLOW, "numeric overflow");
        }
      }
    }
    if (a.length == 2 && a[0] instanceof Long x && a[1] instanceof Long y) {
      switch (name) {
        case "<":
          return x < y;
        case "<=":
          return x <= y;
        case ">":
          return x > y;
        case ">=":
          return x >= y;
        case "+", "-", "*", "/", "%":
          try {
            return arithmetic(name, x, y);
          } catch (ArithmeticException e) {
            throw Values.error(CelException.Code.OVERFLOW, "numeric overflow");
          }
      }
    }
    return applyHost(name, a, env);
  }

  @TruffleBoundary
  private static Object applyHost(String name, Object[] a, Environment env) {
    try {
      if (name.equals("optional.none") && a.length == 0) return OptionalValue.none();
      if (a.length == 1) return unary(name, a[0], env);
      if (a.length == 2) return binary(name, a[0], a[1], env);
      var f = env.functions.get(name);
      if (accepts(f, a)) return Values.in(f.implementation().apply(List.of(a)));
    } catch (CelException e) {
      throw e;
    } catch (ArithmeticException e) {
      throw Values.error(CelException.Code.OVERFLOW, "numeric overflow");
    } catch (IllegalArgumentException
        | DateTimeException
        | IndexOutOfBoundsException
        | ClassCastException e) {
      throw Values.error(
          CelException.Code.INVALID_ARGUMENT,
          "invalid argument to " + name + ": " + e.getMessage());
    }
    throw Values.overload(name);
  }

  private static Object unary(String name, Object v, Environment env) {
    switch (name) {
      case "!":
        // Boolean negation was handled by apply before crossing this boundary.
        break;
      case "neg":
        // Integer negation was handled by apply before crossing this boundary.
        if (v instanceof Double d) return -d;
        break;
      case "hasValue":
        return ExprNode.optional(v).present();
      case "value":
        OptionalValue optional = ExprNode.optional(v);
        if (!optional.present())
          throw Values.error(CelException.Code.INVALID_ARGUMENT, "optional has no value");
        return optional.value();
      case "optional.of":
        return OptionalValue.of(v);
      case "optional.ofNonZeroValue":
        boolean zero =
            Values.equal(v, 0L)
                || v.equals(false)
                || v.equals("")
                || v == CelNull.INSTANCE
                || v instanceof Bytes bytes && bytes.size() == 0
                || v instanceof List<?> list && list.isEmpty()
                || v instanceof Map<?, ?> map && map.isEmpty()
                || v.equals(Duration.ZERO)
                || v.equals(Instant.EPOCH);
        for (ObjectType type : env.runtimeObjectTypes) if (type.accepts(v)) zero = type.isZero(v);
        return zero ? OptionalValue.none() : OptionalValue.of(v);
      case "dyn":
        return v;
      case "type":
        for (ObjectType type : env.runtimeObjectTypes)
          if (type.accepts(v)) return CelType.of(type.name());
        return Values.type(v);
      case "size":
        if (v instanceof String s) return (long) s.codePointCount(0, s.length());
        if (v instanceof Bytes b) return (long) b.size();
        if (v instanceof List<?> l) return (long) l.size();
        if (v instanceof Map<?, ?> m) return (long) m.size();
        break;
      case "int":
        if (v instanceof Long) return v;
        if (v instanceof UInt u) {
          if (u.bits() < 0) throw new ArithmeticException();
          return u.bits();
        }
        if (v instanceof Double d) {
          if (!Double.isFinite(d) || d >= 0x1p63 || d <= -0x1p63) throw new ArithmeticException();
          return d.longValue();
        }
        if (v instanceof String s) return Long.parseLong(s);
        if (v instanceof Instant t) return t.getEpochSecond();
        break;
      case "uint":
        if (v instanceof UInt) return v;
        if (v instanceof Long l) {
          if (l < 0) throw new ArithmeticException();
          return new UInt(l);
        }
        if (v instanceof Double d) {
          if (!Double.isFinite(d) || d < 0 || d >= 0x1p64) throw new ArithmeticException();
          return new UInt(new BigDecimal(d).toBigInteger().longValue());
        }
        if (v instanceof String s) return UInt.parse(s);
        break;
      case "double":
        if (v instanceof Double) return v;
        if (v instanceof Long l) return l.doubleValue();
        if (v instanceof UInt u) return u.toBigInteger().doubleValue();
        if (v instanceof String s) {
          return switch (s) {
            case "NaN" -> Double.NaN;
            case "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY;
            case "-Infinity" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(s);
          };
        }
        break;
      case "bool":
        if (v instanceof Boolean) return v;
        if (v instanceof String s) {
          if (Set.of("true", "TRUE", "True", "t", "1").contains(s)) return true;
          if (Set.of("false", "FALSE", "False", "f", "0").contains(s)) return false;
          throw new IllegalArgumentException("invalid boolean");
        }
        break;
      case "string":
        if (v instanceof NetworkValue) return v.toString();
        if (v instanceof String) return v;
        if (v instanceof Boolean || v instanceof Long || v instanceof UInt) return v.toString();
        if (v instanceof Double d) return doubleString(d);
        if (v instanceof Bytes b) {
          try {
            return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b.toByteArray()))
                .toString();
          } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid UTF-8");
          }
        }
        if (v instanceof Instant t) return DateTimeFormatter.ISO_INSTANT.format(t);
        if (v instanceof Duration d) return durationString(d);
        break;
      case "bytes":
        if (v instanceof Bytes) return v;
        if (v instanceof String s) return new Bytes(s.getBytes(StandardCharsets.UTF_8));
        break;
      case "timestamp":
        if (v instanceof Instant) return v;
        if (v instanceof Long l) return checkedTimestamp(Instant.ofEpochSecond(l));
        if (v instanceof String s) return checkedTimestamp(OffsetDateTime.parse(s).toInstant());
        break;
      case "duration":
        if (v instanceof Duration) return v;
        if (v instanceof String s) return duration(s);
        break;
      default:
        if (name.startsWith("get")) return timePart(name, v, "UTC");
        var f = env.functions.get(name);
        if (accepts(f, v)) return Values.in(f.implementation().apply(List.of(v)));
    }
    throw Values.overload(name);
  }

  private static Object binary(String name, Object a, Object b, Environment env) {
    switch (name) {
      case "==", "!=":
        boolean equal = Values.equal(a, b);
        for (ObjectType type : env.runtimeObjectTypes)
          if (type.accepts(a) && type.accepts(b)) {
            equal = type.equal(a, b);
            break;
          }
        return name.equals("==") ? equal : !equal;
      case "<", "<=", ">", ">=":
        if ((a instanceof Double x && x.isNaN()) || (b instanceof Double y && y.isNaN()))
          return false;
        int c = compare(a, b);
        return switch (name) {
          case "<" -> c < 0;
          case "<=" -> c <= 0;
          case ">" -> c > 0;
          default -> c >= 0;
        };
      case "+":
        if (a instanceof String x && b instanceof String y) return x + y;
        if (a instanceof Bytes x && b instanceof Bytes y) return x.concat(y);
        if (a instanceof List<?> x && b instanceof List<?> y) {
          var r = new ArrayList<Object>(x);
          r.addAll(y);
          return List.copyOf(r);
        }
        if (a instanceof Instant x && b instanceof Duration y) return checkedTimestamp(x.plus(y));
        if (a instanceof Duration x && b instanceof Instant y) return checkedTimestamp(y.plus(x));
        if (a instanceof Duration x && b instanceof Duration y) return checkedDuration(x.plus(y));
        return arithmetic(name, a, b);
      case "-":
        if (a instanceof Instant x && b instanceof Instant y)
          return checkedDuration(Duration.between(y, x));
        if (a instanceof Instant x && b instanceof Duration y) return checkedTimestamp(x.minus(y));
        if (a instanceof Duration x && b instanceof Duration y) return checkedDuration(x.minus(y));
        return arithmetic(name, a, b);
      case "*", "/", "%":
        return arithmetic(name, a, b);
      case "in":
        if (b instanceof Map<?, ?> m) return Values.key(m, a) != null;
        if (b instanceof List<?> l) {
          for (Object v : l) if (Values.equal(a, v)) return true;
          return false;
        }
        break;
      case "contains", "startsWith", "endsWith":
        return stringPredicate(name, a, b);
      case "matches":
        if (a instanceof String x && b instanceof String y) return matches(x, y);
        break;
      default:
        if (name.startsWith("get") && b instanceof String zone) return timePart(name, a, zone);
        var f = env.functions.get(name);
        if (accepts(f, a, b)) return Values.in(f.implementation().apply(List.of(a, b)));
    }
    throw Values.overload(name);
  }

  static Object arithmetic(String op, Object a, Object b) {
    if (a instanceof Long x && b instanceof Long y) {
      if ((op.equals("/") || op.equals("%")) && y == 0)
        throw Values.error(CelException.Code.DIVIDE_BY_ZERO, "division by zero");
      return switch (op) {
        case "+" -> Math.addExact(x, y);
        case "-" -> Math.subtractExact(x, y);
        case "*" -> Math.multiplyExact(x, y);
        case "/" -> {
          if (x == Long.MIN_VALUE && y == -1) throw new ArithmeticException();
          yield x / y;
        }
        default -> x % y;
      };
    }
    if (a instanceof UInt x && b instanceof UInt y) {
      return unsignedArithmetic(op, x, y);
    }
    if (a instanceof Double x && b instanceof Double y)
      return switch (op) {
        case "+" -> x + y;
        case "-" -> x - y;
        case "*" -> x * y;
        case "/" -> x / y;
        default -> throw Values.overload(op);
      };
    throw Values.overload(op);
  }

  @TruffleBoundary
  private static Object unsignedArithmetic(String op, UInt x, UInt y) {
    BigInteger p = x.toBigInteger(), q = y.toBigInteger();
    if ((op.equals("/") || op.equals("%")) && q.signum() == 0)
      throw Values.error(CelException.Code.DIVIDE_BY_ZERO, "division by zero");
    BigInteger r =
        switch (op) {
          case "+" -> p.add(q);
          case "-" -> p.subtract(q);
          case "*" -> p.multiply(q);
          case "/" -> p.divide(q);
          default -> p.remainder(q);
        };
    if (r.signum() < 0 || r.bitLength() > 64) throw new ArithmeticException();
    return new UInt(r.longValue());
  }

  @TruffleBoundary
  static int compare(Object a, Object b) {
    if (Values.numeric(a) && Values.numeric(b)) {
      if (a instanceof Double x && x.isInfinite())
        return b instanceof Double y ? Double.compare(x, y) : (x > 0 ? 1 : -1);
      if (b instanceof Double y && y.isInfinite()) return y > 0 ? -1 : 1;
      if (a instanceof Double || b instanceof Double) {
        double x =
            a instanceof UInt u ? u.toBigInteger().doubleValue() : ((Number) a).doubleValue();
        double y =
            b instanceof UInt u ? u.toBigInteger().doubleValue() : ((Number) b).doubleValue();
        return x == y ? 0 : Double.compare(x, y);
      }
      return Values.decimal(a).compareTo(Values.decimal(b));
    }
    if (a instanceof String x && b instanceof String y) return compareStrings(x, y);
    if (a instanceof Bytes x && b instanceof Bytes y) return x.compareTo(y);
    if (a instanceof Boolean x && b instanceof Boolean y) return x.compareTo(y);
    if (a instanceof Instant x && b instanceof Instant y) return x.compareTo(y);
    if (a instanceof Duration x && b instanceof Duration y) return x.compareTo(y);
    throw Values.overload("compare");
  }

  static int compareStrings(String a, String b) {
    var x = a.codePoints().iterator();
    var y = b.codePoints().iterator();
    while (x.hasNext() && y.hasNext()) {
      int c = Integer.compare(x.nextInt(), y.nextInt());
      if (c != 0) return c;
    }
    return Boolean.compare(x.hasNext(), y.hasNext());
  }

  @TruffleBoundary
  static boolean matches(String s, String regex) {
    Pattern pattern;
    try {
      pattern = Pattern.compile(regex);
    } catch (com.google.re2j.PatternSyntaxException e) {
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid regular expression");
    }
    return pattern.matcher(s).find();
  }

  // Unchecked programs dispatch host functions by arity and runtime argument types, like
  // standard overloads; a declared binding never receives arguments it did not declare.
  private static boolean accepts(Environment.FunctionBinding f, Object... args) {
    if (f == null || f.arguments().size() != args.length) return false;
    for (int i = 0; i < args.length; i++)
      if (!f.arguments().get(i).accepts(Values.type(args[i]))) return false;
    return true;
  }

  static String doubleString(double d) {
    if (!Double.isFinite(d)) return Double.toString(d);
    if (d == Math.rint(d) && Math.abs(d) < 1e16)
      return String.format(java.util.Locale.ROOT, "%.0f", d);
    return Double.toString(d).replace("E", "e");
  }

  @TruffleBoundary
  static Duration duration(String text) {
    if (text.equals("0")) return Duration.ZERO;
    boolean neg = text.startsWith("-");
    String s = (neg || text.startsWith("+")) ? text.substring(1) : text;
    var matcher =
        Pattern.compile("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(h|ms|us|µs|μs|ns|m|s)").matcher(s);
    BigDecimal nanos = BigDecimal.ZERO;
    int end = 0;
    while (matcher.find()) {
      if (matcher.start() != end) throw new IllegalArgumentException("invalid duration");
      BigDecimal factor =
          new BigDecimal(
              switch (matcher.group(2)) {
                case "h" -> "3600000000000";
                case "m" -> "60000000000";
                case "s" -> "1000000000";
                case "ms" -> "1000000";
                case "us", "µs", "μs" -> "1000";
                default -> "1";
              });
      nanos = nanos.add(new BigDecimal(matcher.group(1)).multiply(factor));
      end = matcher.end();
    }
    if (end == 0 || end != s.length()) throw new IllegalArgumentException("invalid duration");
    BigInteger n = nanos.toBigInteger();
    if (neg) n = n.negate();
    BigInteger[] pair = n.divideAndRemainder(BigInteger.valueOf(1000000000));
    return checkedDuration(Duration.ofSeconds(pair[0].longValueExact(), pair[1].longValue()));
  }

  static Duration checkedDuration(Duration d) {
    BigDecimal seconds = BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9));
    if (seconds.abs().compareTo(new BigDecimal("315576000000.999999999")) > 0)
      throw new ArithmeticException();
    return d;
  }

  static Instant checkedTimestamp(Instant t) {
    if (t.getEpochSecond() < -62135596800L || t.getEpochSecond() > 253402300799L)
      throw new ArithmeticException();
    return t;
  }

  static String durationString(Duration d) {
    return BigDecimal.valueOf(d.getSeconds())
            .add(BigDecimal.valueOf(d.getNano(), 9))
            .stripTrailingZeros()
            .toPlainString()
        + "s";
  }

  @TruffleBoundary
  static Object timePart(String name, Object value, String zone) {
    if (value instanceof Duration d) {
      long seconds = d.getSeconds();
      if (seconds < 0 && d.getNano() != 0) seconds++;
      return switch (name) {
        case "getHours" -> seconds / 3600;
        case "getMinutes" -> seconds / 60;
        case "getSeconds" -> seconds;
        case "getMilliseconds" -> d.toMillis() % 1000;
        default -> throw Values.overload(name);
      };
    }
    if (!(value instanceof Instant t)) throw Values.overload(name);
    ZonedDateTime z = t.atZone(ZoneId.of(zone.matches("[0-9]{2}:[0-9]{2}") ? "+" + zone : zone));
    return switch (name) {
      case "getFullYear" -> (long) z.getYear();
      case "getMonth" -> (long) z.getMonthValue() - 1;
      case "getDayOfYear" -> (long) z.getDayOfYear() - 1;
      case "getDayOfMonth" -> (long) z.getDayOfMonth() - 1;
      case "getDate" -> (long) z.getDayOfMonth();
      case "getDayOfWeek" -> (long) z.getDayOfWeek().getValue() % 7;
      case "getHours" -> (long) z.getHour();
      case "getMinutes" -> (long) z.getMinute();
      case "getSeconds" -> (long) z.getSecond();
      case "getMilliseconds" -> (long) z.getNano() / 1000000;
      default -> throw Values.overload(name);
    };
  }
}
