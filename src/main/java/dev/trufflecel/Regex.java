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
