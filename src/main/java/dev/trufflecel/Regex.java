package dev.trufflecel;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.utilities.TruffleWeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single regular-expression engine seam. Constant patterns are compiled once at plan time and
 * stored in the AST; dynamic patterns go through a bounded cache. Constant boolean matches can use
 * TruffleRegex inside their first evaluation's CEL context. RE2/J preserves validation, resource
 * accounting, captures, and evaluation without an entered context.
 */
final class Regex {
  private Regex() {}

  /** A compiled pattern; immutable and safe to share between evaluations and threads. */
  interface Compiled {
    String source();

    boolean find(String text);

    int programSize();

    Object apply(String name, Object[] arguments, EvalContext context);
  }

  // Dynamic patterns come from data, so both the entry count and the cached key size are bounded.
  private static final int CACHE_ENTRIES = 256, CACHE_KEY_LENGTH = 4096;
  private static final ConcurrentHashMap<String, Compiled> CACHE = new ConcurrentHashMap<>();

  /** Compile an RE2 pattern, reporting syntax failures as {@code INVALID_ARGUMENT}. */
  @TruffleBoundary
  static Compiled compile(String pattern, int limit) {
    try {
      return bounded(Re2Regex.compile(pattern), limit);
    } catch (LinkageError missing) {
      throw Values.error(
          CelException.Code.INVALID_ARGUMENT,
          "RE2/J backend is unavailable; add com.google.re2j:re2j:1.8 or select truffleRegexOnly");
    }
  }

  static Compiled compile(String pattern, Environment environment) {
    if (environment.regexParserTreeLimit == 0)
      return compile(pattern, environment.maxRegexProgramSize);
    if (pattern.length() > CACHE_KEY_LENGTH)
      throw Values.error(
          CelException.Code.RESOURCE_EXHAUSTED,
          "TruffleRegex-only pattern length exceeds 4096 UTF-16 code units");
    var compiled = new TruffleOnly(pattern, environment.regexParserTreeLimit);
    compiled.handle(); // Validate syntax/capability and the parser bound during preparation.
    return compiled;
  }

  private record TruffleOnly(String source, int parserLimit) implements Compiled {
    private Handle handle() {
      CelLanguage.Scope scope = CelLanguage.currentScope();
      if (scope == null)
        throw Values.error(
            CelException.Code.INVALID_ARGUMENT,
            "TruffleRegex-only mode requires an initialized, entered CEL context");
      Handle handle = scope.regex(source, parserLimit, true);
      if (handle == null)
        throw Values.error(
            CelException.Code.INVALID_ARGUMENT, "pattern is unsupported by TruffleRegex RE2");
      return handle;
    }

    public boolean find(String text) {
      return handle().find(text);
    }

    public int programSize() {
      throw Values.error(
          CelException.Code.INVALID_ARGUMENT,
          "RE2 instruction counts are unavailable in TruffleRegex-only mode");
    }

    public Object apply(String name, Object[] arguments, EvalContext context) {
      throw Values.error(
          CelException.Code.INVALID_ARGUMENT,
          "regex extraction and replacement are unavailable in TruffleRegex-only mode");
    }
  }

  static Compiled cached(String pattern, Environment environment) {
    return environment.regexParserTreeLimit == 0
        ? cached(pattern, environment.maxRegexProgramSize)
        : compile(pattern, environment);
  }

  private static Compiled bounded(Compiled compiled, int limit) {
    if (compiled.programSize() > limit)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "regex program size limit exceeded");
    return compiled;
  }

  /** Runtime specialization only; prepared templates never create or retain a Polyglot context. */
  static final class MatchNode extends Node {
    private static final Entry UNAVAILABLE = new Entry(null, null);
    private final Compiled fallback;
    private final String source;
    @CompilationFinal private volatile Entry entry;

    MatchNode(Compiled fallback) {
      this.fallback = fallback;
      String pattern = fallback.source();
      source = fallback instanceof TruffleOnly || eligible(pattern) ? pattern : null;
    }

    boolean find(String text) {
      if (source == null) return fallback.find(text);
      Entry current = entry;
      if (current == null) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        current = initialize();
      }
      Handle handle = current.handle == null ? null : current.handle.get();
      if (handle == null || !handle.isCurrent(current.token)) return fallback.find(text);
      return handle.find(text);
    }

    @TruffleBoundary
    private synchronized Entry initialize() {
      if (entry == null) {
        CelLanguage.Scope scope = CelLanguage.currentScope();
        Handle handle =
            scope == null
                ? null
                : fallback instanceof TruffleOnly only
                    ? scope.regex(source, only.parserLimit(), true)
                    : scope.regex(source);
        entry =
            handle == null
                ? UNAVAILABLE
                : new Entry(scope.regexToken, new TruffleWeakReference<>(handle));
      }
      return entry;
    }

    /** Qualification checks use this after execution to distinguish acceleration from fallback. */
    boolean isAccelerated() {
      Entry current = entry;
      Handle handle = current == null || current.handle == null ? null : current.handle.get();
      return handle != null && handle.isCurrent(current.token);
    }

    private record Entry(Object token, TruffleWeakReference<Handle> handle) {}
  }

  // The initial subset excludes all escapes and special groups. In particular, Unicode properties
  // and case folding use different Unicode tables in RE2/J and TruffleRegex. Literal ASCII,
  // ordinary groups, classes, alternation, anchors and repetition need neither feature.
  private static boolean eligible(String pattern) {
    if (pattern.length() > CACHE_KEY_LENGTH || pattern.contains("(?")) return false;
    for (int i = 0; i < pattern.length(); i++)
      if (pattern.charAt(i) > 127 || pattern.charAt(i) == '\\') return false;
    return true;
  }

  /** Owned strongly by Scope; reusable programs hold only a TruffleWeakReference to this handle. */
  static final class Handle {
    private final Object token;
    private final TruffleContext owner;
    private final Object regex;

    private Handle(Object token, TruffleContext owner, Object regex) {
      this.token = token;
      this.owner = owner;
      this.regex = regex;
    }

    boolean isCurrent(Object expectedToken) {
      return token == expectedToken && owner.isEntered();
    }

    boolean find(String text) {
      try {
        // A cached receiver or DirectCallNode here must never become a child of the CEL program:
        // it would strongly retain the regex language, engine and its open contexts.
        return (boolean) InteropLibrary.getUncached().invokeMember(regex, "execBoolean", text, 0);
      } catch (InteropException failure) {
        throw CompilerDirectives.shouldNotReachHere(failure);
      }
    }
  }

  @TruffleBoundary
  static Handle accelerate(TruffleLanguage.Env env, Object token, String pattern) {
    return accelerate(env, token, pattern, 100000, false);
  }

  @TruffleBoundary
  static Handle accelerate(
      TruffleLanguage.Env env, Object token, String pattern, int parserLimit, boolean strict) {
    Source source =
        Source.newBuilder("regex", "/" + pattern + "/", "CEL regular expression")
            .internal(true)
            .cached(false)
            .option("regex.Flavor", "RE2")
            .option("regex.Encoding", "UTF-16")
            .option("regex.BooleanMatch", "true")
            .option("regex.MaxParserTreeSize", Integer.toString(parserLimit))
            .build();
    try {
      Object regex = env.parseInternal(source).call();
      return InteropLibrary.getUncached().isNull(regex)
          ? null
          : new Handle(token, env.getContext(), regex);
    } catch (AbstractTruffleException failure) {
      try {
        // A valid RE2/J pattern can exceed TRegex's supported syntax/automaton limits.
        if (InteropLibrary.getUncached().getExceptionType(failure) == ExceptionType.PARSE_ERROR) {
          if (strict)
            throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid regular expression");
          return null;
        }
      } catch (InteropException invalidException) {
        throw CompilerDirectives.shouldNotReachHere(invalidException);
      }
      throw failure;
    }
  }

  /** Compile a pattern that is not known until evaluation, reusing recent compilations. */
  @TruffleBoundary
  static Compiled cached(String pattern, int limit) {
    if (pattern.length() > CACHE_KEY_LENGTH) return compile(pattern, limit);
    Compiled compiled = CACHE.get(pattern);
    if (compiled == null) {
      compiled = compile(pattern, limit);
      if (CACHE.size() >= CACHE_ENTRIES) CACHE.clear();
      CACHE.put(pattern, compiled);
    }
    return bounded(compiled, limit);
  }

  /** The number of cached dynamic patterns, for tests of the cache bound. */
  static int cacheSize() {
    return CACHE.size();
  }
}
