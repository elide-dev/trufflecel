package dev.trufflecel;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** CEL extension overloads; no class discovery or host method access. */
final class ExtensionFunctions {
  private ExtensionFunctions() {}

  private static final Set<String> NAMES =
      Set.of(
          "@sortByAssociatedKeys",
          "cel.@mapInsert",
          "sets.contains",
          "sets.equivalent",
          "sets.intersects",
          "base64.encode",
          "base64.decode",
          "base64.encodeUrl",
          "base64.decodeUrl",
          "json.encode",
          "json.parse",
          "yaml.encode",
          "yaml.parse",
          "charAt",
          "indexOf",
          "lastIndexOf",
          "lowerAscii",
          "upperAscii",
          "replace",
          "split",
          "substring",
          "trim",
          "join",
          "strings.quote",
          "format",
          "reverse",
          "slice",
          "flatten",
          "distinct",
          "lists.range",
          "sort",
          "math.greatest",
          "math.least",
          "math.ceil",
          "math.floor",
          "math.round",
          "math.trunc",
          "math.abs",
          "math.sign",
          "math.isNaN",
          "math.isInf",
          "math.isFinite",
          "math.bitAnd",
          "math.bitOr",
          "math.bitXor",
          "math.bitNot",
          "math.bitShiftLeft",
          "math.bitShiftRight",
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
          "prefixLength");

  static boolean supports(String name) {
    return NAMES.contains(name);
  }

  static boolean namespaced(String prefix, String name) {
    return NAMES.contains(prefix + "." + name);
  }

  static boolean comparable(CelType type) {
    return type.isDyn()
        || type.isNumeric()
        || Set.of(CelType.STRING, CelType.BYTES, CelType.BOOL, CelType.TIMESTAMP, CelType.DURATION)
            .contains(type);
  }

  private static CelType p(CelType t, int i) {
    return Checker.parameter(t, i);
  }

  private static boolean matches(List<CelType> types, CelType... expected) {
    if (types.size() != expected.length) return false;
    for (int i = 0; i < expected.length; i++) if (!expected[i].accepts(types.get(i))) return false;
    return true;
  }

  static CelType check(String name, List<CelType> t, boolean strict, int offset) {
    if (!supports(name)) return null;
    CelType a = t.isEmpty() ? CelType.DYN : t.get(0), result = CelType.DYN;
    CelType list = CelType.list(CelType.DYN),
        str = CelType.STRING,
        integer = CelType.INT,
        ip = CelType.of("net.IP"),
        cidr = CelType.of("net.CIDR");
    boolean ok;
    switch (name) {
      case "@sortByAssociatedKeys":
        ok = matches(t, list, list) && comparable(p(t.get(1), 0));
        result = a;
        break;
      case "cel.@mapInsert":
        ok =
            matches(t, CelType.map(CelType.DYN, CelType.DYN), CelType.map(CelType.DYN, CelType.DYN))
                || t.size() == 3 && CelType.map(CelType.DYN, CelType.DYN).accepts(a);
        result = a;
        break;
      case "sets.contains", "sets.equivalent", "sets.intersects":
        ok = matches(t, list, list);
        result = CelType.BOOL;
        break;
      case "json.encode", "yaml.encode":
        ok = t.size() == 1;
        result = str;
        break;
      case "json.parse", "yaml.parse":
        ok = matches(t, str) || matches(t, str, CelType.TYPE);
        result =
            new CelType("optional_type", List.of(t.size() == 2 ? p(t.get(1), 0) : CelType.DYN));
        break;
      case "base64.encode", "base64.encodeUrl":
        ok = matches(t, CelType.BYTES);
        result = str;
        break;
      case "base64.decode", "base64.decodeUrl":
        ok = matches(t, str);
        result = CelType.BYTES;
        break;
      case "charAt":
        ok = matches(t, str, integer);
        result = str;
        break;
      case "indexOf", "lastIndexOf":
        ok = matches(t, str, str) || matches(t, str, str, integer);
        result = integer;
        break;
      case "substring":
        ok = matches(t, str, integer) || matches(t, str, integer, integer);
        result = str;
        break;
      case "replace":
        ok = matches(t, str, str, str) || matches(t, str, str, str, integer);
        result = str;
        break;
      case "split":
        ok = matches(t, str, str) || matches(t, str, str, integer);
        result = CelType.list(str);
        break;
      case "lowerAscii", "upperAscii", "trim", "strings.quote":
        ok = matches(t, str);
        result = str;
        break;
      case "format":
        ok = matches(t, str, list);
        result = str;
        break;
      case "join":
        ok = matches(t, CelType.list(str)) || matches(t, CelType.list(str), str);
        result = str;
        break;
      case "reverse":
        ok = matches(t, str) || matches(t, list);
        result = a;
        break;
      case "slice":
        ok = matches(t, list, integer, integer);
        result = a;
        break;
      case "flatten":
        ok = matches(t, list) || matches(t, list, integer);
        result = list;
        break;
      case "distinct", "sort":
        ok = matches(t, list) && (!name.equals("sort") || comparable(p(a, 0)));
        result = a;
        break;
      case "lists.range":
        ok = matches(t, integer);
        result = CelType.list(integer);
        break;
      case "ip":
        ok = matches(t, str) || matches(t, cidr);
        result = ip;
        break;
      case "cidr":
        ok = matches(t, str);
        result = cidr;
        break;
      case "isIP", "isCIDR", "ip.isCanonical":
        ok = matches(t, str);
        result = CelType.BOOL;
        break;
      case "family":
        ok = matches(t, ip);
        result = integer;
        break;
      case "prefixLength":
        ok = matches(t, cidr);
        result = integer;
        break;
      case "masked":
        ok = matches(t, cidr);
        result = cidr;
        break;
      case "containsIP":
        ok = matches(t, cidr, ip) || matches(t, cidr, str);
        result = CelType.BOOL;
        break;
      case "containsCIDR":
        ok = matches(t, cidr, cidr) || matches(t, cidr, str);
        result = CelType.BOOL;
        break;
      case "isUnspecified",
      "isLoopback",
      "isGlobalUnicast",
      "isLinkLocalMulticast",
      "isLinkLocalUnicast":
        ok = matches(t, ip);
        result = CelType.BOOL;
        break;
      case "math.greatest", "math.least":
        ok = !t.isEmpty();
        result = null;
        for (CelType type : t) {
          if (t.size() == 1 && type.name().equals("list")) type = p(type, 0);
          ok &= type.isDyn() || type.isNumeric();
          result = result == null ? type : CelType.join(result, type);
        }
        if (result == null) result = CelType.DYN;
        break;
      case "math.abs", "math.sign":
        ok = t.size() == 1 && (a.isDyn() || a.isNumeric());
        result = a;
        break;
      case "math.ceil", "math.floor", "math.round", "math.trunc":
        ok = matches(t, CelType.DOUBLE);
        result = CelType.DOUBLE;
        break;
      case "math.isNaN", "math.isInf", "math.isFinite":
        ok = matches(t, CelType.DOUBLE);
        result = CelType.BOOL;
        break;
      default:
        boolean unary = name.equals("math.bitNot"), shift = name.startsWith("math.bitShift");
        ok =
            (a.isDyn() || a.equals(integer) || a.equals(CelType.UINT))
                && t.size() == (unary ? 1 : 2);
        if (ok && !unary) ok = shift ? integer.accepts(t.get(1)) : a.accepts(t.get(1));
        result = a;
    }
    if (strict && !ok)
      throw new CelException(
          CelException.Code.TYPE, "invalid extension arguments: " + name, offset);
    return result;
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static Object apply(String name, Object[] args, EvalContext ctx) {
    // Runtime validation remains mandatory for dyn and unchecked programs.
    try {
      for (Object arg : args) {
        if (arg instanceof String str) ctx.charge(str.length());
        if (arg instanceof List<?> list) ctx.charge(list.size());
      }
      check(name, Arrays.stream(args).map(Values::type).toList(), true, -1);
      if (name.startsWith("math.")) return math(name.substring(5), args, ctx);
      if (NetworkValue.supports(name)) return NetworkValue.apply(name, args);
      if (name.startsWith("json.") || name.startsWith("yaml."))
        return EncoderFunctions.apply(name, args, ctx);
      Object a = args[0];
      switch (name) {
        case "@sortByAssociatedKeys":
          if (((List<?>) a).size() != ((List<?>) args[1]).size())
            throw new IllegalArgumentException("Sort key count differs from list size");
          return sortBy(new ArrayList<>((List<?>) a), new ArrayList<>((List<?>) args[1]), ctx);
        case "cel.@mapInsert":
          var merged = HostCollections.<Object>map();
          HostCollections.mergeEntries(merged, (Map<?, ?>) a);
          HostCollections.mergeEntries(
              merged, args.length == 2 ? (Map<?, ?>) args[1] : Map.of(args[1], args[2]));
          return HostCollections.freezeMap(merged);
        case "sets.contains":
          return containsAll((List<?>) a, (List<?>) args[1], ctx);
        case "sets.equivalent":
          return containsAll((List<?>) a, (List<?>) args[1], ctx)
              && containsAll((List<?>) args[1], (List<?>) a, ctx);
        case "sets.intersects":
          for (Object item : (List<?>) args[1])
            if (setContains((List<?>) a, item, ctx)) return true;
          return false;
        case "base64.encode", "base64.encodeUrl":
          Bytes bytes = (Bytes) a;
          ctx.charge(4L * ((bytes.size() + 2L) / 3));
          return (name.equals("base64.encodeUrl") ? Base64.getUrlEncoder() : Base64.getEncoder())
              .encodeToString(bytes.toByteArray());
        case "base64.decode", "base64.decodeUrl":
          return new Bytes(
              (name.equals("base64.decodeUrl") ? Base64.getUrlDecoder() : Base64.getDecoder())
                  .decode(((String) a).replace("\r", "").replace("\n", "")));
        case "lists.range":
          {
            long count = (Long) a;
            if (count < 0 || count > 1_000_000)
              throw new IllegalArgumentException("range limit exceeded");
            ctx.charge(count);
            var out = new ArrayList<Object>((int) count);
            for (long i = 0; i < count; i++) out.add(i);
            return List.copyOf(out);
          }
        case "slice":
          {
            List<?> list = (List<?>) a;
            return List.copyOf(list.subList(index(args[1]), index(args[2])));
          }
        case "flatten":
          {
            int depth = args.length == 1 ? 1 : index(args[1]);
            if (depth < 0) throw new IllegalArgumentException("negative depth");
            var out = new ArrayList<Object>();
            flatten((List<?>) a, depth, out, 0, ctx);
            return List.copyOf(out);
          }
        case "distinct":
          {
            var out = new ArrayList<Object>();
            for (Object v : (List<?>) a) {
              boolean seen = false;
              for (Object x : out)
                if (equal(v, x, ctx)) {
                  seen = true;
                  break;
                }
              if (!seen) out.add(v);
            }
            return List.copyOf(out);
          }
        case "sort":
          return sortBy((List<?>) a, (List<?>) a, ctx);
        case "reverse":
          {
            if (a instanceof List<?> l) {
              var out = new ArrayList<>(l);
              Collections.reverse(out);
              return List.copyOf(out);
            }
            int[] cps = ((String) a).codePoints().toArray();
            var out = new StringBuilder();
            for (int i = cps.length - 1; i >= 0; i--) out.appendCodePoint(cps[i]);
            return out.toString();
          }
        case "join":
          {
            String sep = args.length == 1 ? "" : (String) args[1];
            var strings = new ArrayList<String>();
            for (Object v : (List<?>) a) {
              ctx.charge(((String) v).length() + (long) sep.length());
              strings.add((String) v);
            }
            return String.join(sep, strings);
          }
        default:
          return strings(name, (String) a, args, ctx);
      }
    } catch (CelException e) {
      if (e.code() == CelException.Code.TYPE) throw Values.overload(name);
      throw e;
    } catch (ArithmeticException e) {
      throw Values.error(CelException.Code.OVERFLOW, "numeric overflow");
    } catch (IllegalArgumentException | IndexOutOfBoundsException | ClassCastException e) {
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid extension argument: " + name);
    }
  }

  private static boolean equal(Object a, Object b, EvalContext ctx) {
    ctx.tick();
    return Values.equal(a, b);
  }

  private static int index(Object value) {
    return Math.toIntExact((Long) value);
  }

  private static boolean containsAll(List<?> values, List<?> requested, EvalContext ctx) {
    for (Object item : requested) if (!setContains(values, item, ctx)) return false;
    return true;
  }

  private static boolean setContains(List<?> values, Object item, EvalContext ctx) {
    for (Object candidate : values) {
      ctx.tick();
      if ((Boolean) Functions.apply("==", new Object[] {candidate, item}, ctx.environment))
        return true;
    }
    return false;
  }

  private static void flatten(
      List<?> list, int depth, List<Object> out, int nesting, EvalContext ctx) {
    if (nesting > 100 || out.size() + list.size() > 1_000_000)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "flatten limit exceeded");
    ctx.charge(list.size());
    for (Object item : list)
      if (depth > 0 && item instanceof List<?> child)
        flatten(child, depth - 1, out, nesting + 1, ctx);
      else out.add(item);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  static Object sortBy(List<?> values, List<?> keys, EvalContext ctx) {
    var order = new ArrayList<Integer>();
    Class<?> kind = null;
    for (int i = 0; i < keys.size(); i++) {
      Object k = keys.get(i);
      if (kind != null && kind != k.getClass()) throw Values.overload("sort keys");
      kind = k.getClass();
      Functions.compare(k, k);
      order.add(i);
    }
    order.sort(
        (i, j) -> {
          ctx.tick();
          return Functions.compare(keys.get(i), keys.get(j));
        });
    var out = new ArrayList<Object>();
    for (int i : order) out.add(values.get(i));
    return List.copyOf(out);
  }

  private static Object math(String name, Object[] a, EvalContext ctx) {
    if (name.equals("greatest") || name.equals("least")) {
      List<?> values = a.length == 1 && a[0] instanceof List<?> l ? l : Arrays.asList(a);
      if (values.isEmpty()) throw new IllegalArgumentException("empty extrema");
      Object best = values.get(0);
      for (Object value : values) {
        ctx.tick();
        if (!Values.numeric(value)) throw Values.overload(name);
        if (value instanceof Double d && d.isNaN()) return d;
        int c =
            value instanceof Double d && !Double.isFinite(d)
                    || best instanceof Double e && !Double.isFinite(e)
                ? Functions.compare(value, best)
                : Values.decimal(value).compareTo(Values.decimal(best));
        if (name.equals("greatest") ? c > 0 : c < 0) best = value;
      }
      return best;
    }
    Object v = a[0];
    if (name.equals("abs")) {
      if (v instanceof Long l) return Math.absExact(l);
      if (v instanceof UInt) return v;
      return Math.abs((Double) v);
    }
    if (name.equals("sign")) {
      if (v instanceof Long l) return (long) Long.signum(l);
      if (v instanceof UInt u) return new UInt(u.bits() == 0 ? 0 : 1);
      return Math.signum((Double) v);
    }
    if (name.startsWith("bit")) {
      boolean unsigned = v instanceof UInt;
      long x = unsigned ? ((UInt) v).bits() : (Long) v, y = 0;
      if (a.length > 1) y = a[1] instanceof UInt u ? u.bits() : (Long) a[1];
      if (name.startsWith("bitShift") && y < 0)
        throw new IllegalArgumentException("negative offset");
      long r =
          switch (name) {
            case "bitAnd" -> x & y;
            case "bitOr" -> x | y;
            case "bitXor" -> x ^ y;
            case "bitNot" -> ~x;
            case "bitShiftLeft" -> y >= 64 ? 0 : x << y;
            default -> y >= 64 ? 0 : x >>> y;
          };
      return unsigned ? new UInt(r) : r;
    }
    double d = (Double) v;
    return switch (name) {
      case "ceil" -> Math.ceil(d);
      case "floor" -> Math.floor(d);
      case "round" ->
          Double.isFinite(d) && Math.abs(d) < 0x1p52
              ? Math.copySign(Math.floor(Math.abs(d) + 0.5), d)
              : d;
      case "trunc" -> d < 0 ? Math.ceil(d) : Math.floor(d);
      case "isNaN" -> Double.isNaN(d);
      case "isInf" -> Double.isInfinite(d);
      case "isFinite" -> Double.isFinite(d);
      default -> throw Values.overload(name);
    };
  }

  private static int offset(String s, Object index) {
    int i = index(index);
    int length = s.codePointCount(0, s.length());
    if (i < 0 || i > length) throw new IllegalArgumentException("string index out of range");
    return s.offsetByCodePoints(0, i);
  }

  private static Object strings(String name, String s, Object[] a, EvalContext ctx) {
    switch (name) {
      case "charAt":
        {
          int i = offset(s, a[1]);
          return i == s.length() ? "" : new String(Character.toChars(s.codePointAt(i)));
        }
      case "substring":
        return s.substring(offset(s, a[1]), a.length == 2 ? s.length() : offset(s, a[2]));
      case "indexOf", "lastIndexOf":
        {
          int from = a.length == 3 ? offset(s, a[2]) : name.equals("indexOf") ? 0 : s.length();
          int i =
              name.equals("indexOf")
                  ? s.indexOf((String) a[1], from)
                  : s.lastIndexOf((String) a[1], from);
          return i < 0 ? -1L : (long) s.codePointCount(0, i);
        }
      case "lowerAscii", "upperAscii":
        {
          var out = new StringBuilder();
          boolean lower = name.equals("lowerAscii");
          for (char c : s.toCharArray())
            out.append(
                lower && c >= 'A' && c <= 'Z'
                    ? (char) (c + 32)
                    : !lower && c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
          return out.toString();
        }
      case "trim":
        {
          int start = 0, end = s.length();
          while (start < end && space(s.codePointAt(start)))
            start += Character.charCount(s.codePointAt(start));
          while (end > start && space(s.codePointBefore(end)))
            end -= Character.charCount(s.codePointBefore(end));
          return s.substring(start, end);
        }
      case "strings.quote":
        return quote(s);
      case "format":
        return format(s, (List<?>) a[1], ctx);
      case "split":
        return split(s, (String) a[1], a.length == 3 ? index(a[2]) : -1);
      case "replace":
        return replace(s, (String) a[1], (String) a[2], a.length == 4 ? index(a[3]) : -1, ctx);
      default:
        throw Values.overload(name);
    }
  }

  private static boolean space(int c) {
    return c >= 9 && c <= 13
        || c == 0x20
        || c == 0x85
        || c == 0xa0
        || c == 0x1680
        || c >= 0x2000 && c <= 0x200a
        || c == 0x2028
        || c == 0x2029
        || c == 0x202f
        || c == 0x205f
        || c == 0x3000;
  }

  private static List<String> split(String s, String sep, int limit) {
    var out = new ArrayList<String>();
    if (limit == 0) return out;
    int start = 0;
    if (sep.isEmpty()) {
      for (int i = 0; i < s.length() && (limit < 0 || out.size() < limit - 1); ) {
        int end = i + Character.charCount(s.codePointAt(i));
        out.add(s.substring(i, end));
        i = end;
        start = end;
      }
      if (start < s.length()) out.add(s.substring(start));
      return List.copyOf(out);
    }
    int at;
    while ((limit < 0 || out.size() < limit - 1) && (at = s.indexOf(sep, start)) >= 0) {
      out.add(s.substring(start, at));
      start = at + sep.length();
    }
    out.add(s.substring(start));
    return List.copyOf(out);
  }

  private static String replace(
      String s, String old, String replacement, int limit, EvalContext ctx) {
    if (limit == 0) return s;
    long matches = old.isEmpty() ? s.codePointCount(0, s.length()) + 1L : 0;
    if (!old.isEmpty())
      for (int at = 0; (at = s.indexOf(old, at)) >= 0; at += old.length()) matches++;
    if (limit > 0) matches = Math.min(matches, limit);
    ctx.charge(matches * replacement.length());
    var out = new StringBuilder();
    int start = 0, count = 0;
    if (old.isEmpty()) {
      out.append(replacement);
      count++;
      while (start < s.length()) {
        int end = start + Character.charCount(s.codePointAt(start));
        out.append(s, start, end);
        start = end;
        if (limit < 0 || count++ < limit) out.append(replacement);
      }
      return out.toString();
    }
    int at;
    while ((limit < 0 || count < limit) && (at = s.indexOf(old, start)) >= 0) {
      out.append(s, start, at).append(replacement);
      start = at + old.length();
      count++;
      if (out.length() > 1_000_000)
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "replacement limit exceeded");
    }
    return out.append(s.substring(start)).toString();
  }

  private static String quote(String s) {
    var out = new StringBuilder("\"");
    for (int c : s.codePoints().toArray()) {
      String escaped =
          switch (c) {
            case 7 -> "\\a";
            case 8 -> "\\b";
            case 9 -> "\\t";
            case 10 -> "\\n";
            case 11 -> "\\v";
            case 12 -> "\\f";
            case 13 -> "\\r";
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            default -> null;
          };
      if (escaped != null) out.append(escaped);
      else if (Character.isISOControl(c)) out.append(String.format(Locale.ROOT, "\\u%04x", c));
      else out.appendCodePoint(c);
    }
    return out.append('"').toString();
  }

  private static String text(Object v, EvalContext ctx) {
    ctx.tick();
    if (v == CelNull.INSTANCE) return "null";
    if (v instanceof String str) {
      ctx.charge(str.length());
      return str;
    }
    if (v instanceof Boolean || v instanceof Long || v instanceof UInt) return v.toString();
    if (v instanceof Double d) return Functions.doubleString(d);
    if (v instanceof Bytes b) {
      ctx.charge(b.size());
      return (String) Functions.apply("string", new Object[] {b}, null);
    }
    if (v instanceof CelType t) return t.name();
    if (v instanceof Instant t) return t.toString();
    if (v instanceof Duration d) return Functions.durationString(d);
    if (v instanceof List<?> list) {
      var parts = new ArrayList<String>();
      for (Object item : list) parts.add(text(item, ctx));
      return "[" + String.join(", ", parts) + "]";
    }
    if (v instanceof Map<?, ?> map) {
      var parts = new ArrayList<String>();
      for (var entry : map.entrySet())
        parts.add(text(entry.getKey(), ctx) + ": " + text(entry.getValue(), ctx));
      Collections.sort(parts);
      return "{" + String.join(", ", parts) + "}";
    }
    throw Values.overload("format");
  }

  private static String format(String s, List<?> values, EvalContext ctx) {
    var out = new StringBuilder();
    int arg = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '%') {
        out.append(c);
        continue;
      }
      if (++i == s.length()) throw new IllegalArgumentException("unterminated format");
      if (s.charAt(i) == '%') {
        out.append('%');
        continue;
      }
      int precision = 6;
      if (s.charAt(i) == '.') {
        int start = ++i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        precision = Integer.parseInt(s.substring(start, i));
        if (precision > 1000) throw new IllegalArgumentException("precision limit");
      }
      c = s.charAt(i);
      Object v = values.get(arg++);
      String part;
      if (c == 's') part = text(v, ctx);
      else if (c == 'b' && v instanceof Boolean b) part = b ? "1" : "0";
      else if (c == 'x' || c == 'X') {
        if (v instanceof String str)
          part = HexFormat.of().formatHex(str.getBytes(StandardCharsets.UTF_8));
        else if (v instanceof Bytes bytes) part = HexFormat.of().formatHex(bytes.toByteArray());
        else part = integer(v).toString(16);
        if (c == 'X') part = part.toUpperCase(Locale.ROOT);
      } else if (c == 'd' || c == 'b' || c == 'o') {
        if (c == 'd' && v instanceof Double d && !Double.isFinite(d)) part = d.toString();
        else part = integer(v).toString(c == 'b' ? 2 : c == 'o' ? 8 : 10);
      } else if (c == 'f' || c == 'e') {
        if (!Values.numeric(v)) throw Values.overload("format");
        if (v instanceof Double d && !Double.isFinite(d)) part = d.toString();
        else {
          BigDecimal decimal = Values.decimal(v);
          if (c == 'f') part = decimal.setScale(precision, RoundingMode.HALF_EVEN).toPlainString();
          else {
            BigDecimal rounded =
                decimal.round(new MathContext(precision + 1, RoundingMode.HALF_EVEN));
            int exponent = rounded.signum() == 0 ? 0 : rounded.precision() - rounded.scale() - 1;
            part =
                rounded
                        .movePointLeft(exponent)
                        .setScale(precision, RoundingMode.HALF_EVEN)
                        .toPlainString()
                    + "e"
                    + (exponent < 0 ? "-" : "+")
                    + (Math.abs(exponent) < 10 ? "0" : "")
                    + Math.abs(exponent);
          }
          if (v instanceof Double d && d == 0.0 && Double.doubleToRawLongBits(d) < 0)
            part = "-" + part;
        }
      } else throw new IllegalArgumentException("unknown format clause");
      ctx.charge(part.length());
      out.append(part);
      if (out.length() > 1_000_000)
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "format limit exceeded");
    }
    return out.toString();
  }

  private static BigInteger integer(Object value) {
    if (value instanceof Long l) return BigInteger.valueOf(l);
    if (value instanceof UInt u) return u.toBigInteger();
    throw Values.overload("integer format");
  }
}
