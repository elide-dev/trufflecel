package dev.trufflecel;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single regular-expression engine seam. Constant patterns are compiled once at plan time and
 * stored in the AST; dynamic patterns go through a bounded cache. Replacing the engine (for example
 * with a TruffleRegex RE2 flavor) only touches this class.
 */
final class Regex {
  private Regex() {}

  /** A compiled pattern; immutable and safe to share between evaluations and threads. */
  interface Compiled {
    boolean find(String text);

    Object apply(String name, Object[] arguments, EvalContext context);
  }

  // Dynamic patterns come from data, so both the entry count and the cached key size are bounded.
  private static final int CACHE_ENTRIES = 256, CACHE_KEY_LENGTH = 4096;
  private static final ConcurrentHashMap<String, Compiled> CACHE = new ConcurrentHashMap<>();

  /** Compile an RE2 pattern, reporting syntax failures as {@code INVALID_ARGUMENT}. */
  @TruffleBoundary
  static Compiled compile(String pattern) {
    try {
      return new Re2(Pattern.compile(pattern));
    } catch (PatternSyntaxException e) {
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid regular expression");
    }
  }

  private record Re2(Pattern pattern) implements Compiled {
    @Override
    @TruffleBoundary
    public boolean find(String text) {
      return pattern.matcher(text).find();
    }

    @Override
    @TruffleBoundary
    public Object apply(String name, Object[] arguments, EvalContext context) {
      String text = (String) arguments[0];
      var matcher = pattern.matcher(text);
      if (name.equals("regex.replace")) {
        long limit = arguments.length == 4 ? (Long) arguments[3] : -1;
        String replacement = (String) arguments[2];
        var output = new StringBuilder();
        int end = 0;
        for (long count = 0; (limit < 0 || count < limit) && matcher.find(); count++) {
          context.tick();
          context.charge(matcher.start() - end);
          output.append(text, end, matcher.start());
          for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
              if (++i == replacement.length())
                throw new IllegalArgumentException("trailing replacement escape");
              c = replacement.charAt(i);
              if (Character.isDigit(c)) {
                int group = Character.digit(c, 10);
                if (group > matcher.groupCount())
                  throw new IllegalArgumentException("unknown replacement group");
                String value = matcher.group(group);
                if (value != null) {
                  context.charge(value.length());
                  output.append(value);
                }
                continue;
              }
              if (c != '\\') throw new IllegalArgumentException("invalid replacement escape");
            }
            context.tick();
            output.append(c);
          }
          end = matcher.end();
        }
        context.charge(text.length() - end);
        return output.append(text, end, text.length()).toString();
      }
      boolean all = name.equals("regex.extractAll");
      // extract reports no match before checking capture count; extractAll checks it eagerly.
      boolean found = matcher.find();
      if (!all && !found) return OptionalValue.none();
      if (matcher.groupCount() > 1) throw new IllegalArgumentException("too many capture groups");
      int group = matcher.groupCount();
      var output = new java.util.ArrayList<String>();
      while (found) {
        context.tick();
        String value = matcher.group(group);
        if (value != null) {
          context.charge(value.length());
          if (!all) return OptionalValue.of(value);
          output.add(value);
        } else if (!all) return OptionalValue.none();
        found = matcher.find();
      }
      return java.util.List.copyOf(output);
    }
  }

  /** Compile a pattern that is not known until evaluation, reusing recent compilations. */
  @TruffleBoundary
  static Compiled cached(String pattern) {
    if (pattern.length() > CACHE_KEY_LENGTH) return compile(pattern);
    Compiled compiled = CACHE.get(pattern);
    if (compiled == null) {
      compiled = compile(pattern);
      if (CACHE.size() >= CACHE_ENTRIES) CACHE.clear();
      CACHE.put(pattern, compiled);
    }
    return compiled;
  }

  /** The number of cached dynamic patterns, for tests of the cache bound. */
  static int cacheSize() {
    return CACHE.size();
  }
}
