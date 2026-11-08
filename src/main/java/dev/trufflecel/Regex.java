package dev.trufflecel;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
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

  private static final TruffleLanguage.LanguageReference<CelLanguage> LANGUAGE =
      TruffleLanguage.LanguageReference.create(CelLanguage.class);

  /** A compiled pattern; immutable and safe to share between evaluations and threads. */
  interface Compiled {
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
      return bounded(new Re2(Pattern.compile(pattern)), limit);
    } catch (PatternSyntaxException e) {
      throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid regular expression");
    }
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
      String pattern = ((Re2) fallback).pattern.pattern();
      source = eligible(pattern) ? pattern : null;
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
      return handle.find(this, text);
    }

    @TruffleBoundary
    private synchronized Entry initialize() {
      if (entry == null) {
        CelLanguage.Scope scope = CelLanguage.currentScope();
        Handle handle = scope == null ? null : scope.regex(source);
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
    private final CallTarget target;

    private Handle(Object token, TruffleContext owner, CallTarget target) {
      this.token = token;
      this.owner = owner;
      this.target = target;
    }

    boolean isCurrent(Object expectedToken) {
      return token == expectedToken && owner.isEntered();
    }

    boolean find(Node location, String text) {
      return (boolean) target.call(location, text, 0);
    }
  }

  /** The context owns this complete call tree; it is never adopted by a reusable CEL program. */
  private static final class DispatchRoot extends RootNode {
    private final Object regex;
    @Child private InteropLibrary library;

    private DispatchRoot(Object regex) {
      super(LANGUAGE.get(null));
      this.regex = regex;
      library = InteropLibrary.getFactory().create(regex);
    }

    @Override
    public boolean isInternal() {
      return true;
    }

    @Override
    protected boolean isInstrumentable() {
      return false;
    }

    @Override
    public Object execute(VirtualFrame frame) {
      try {
        // Reuse the call's argument array. The adopted cached library can inline its dispatch,
        // while the program reaches this root only through its weak context-owned handle.
        return library.invokeMember(regex, "execBoolean", frame.getArguments());
      } catch (InteropException failure) {
        throw CompilerDirectives.shouldNotReachHere(failure);
      }
    }
  }

  @TruffleBoundary
  static Handle accelerate(TruffleLanguage.Env env, Object token, String pattern) {
    Source source =
        Source.newBuilder("regex", "/" + pattern + "/", "CEL regular expression")
            .internal(true)
            .cached(false)
            .option("regex.Flavor", "RE2")
            .option("regex.Encoding", "UTF-16")
            .option("regex.BooleanMatch", "true")
            .option("regex.MaxParserTreeSize", "100000")
            .build();
    try {
      Object regex = env.parseInternal(source).call();
      return InteropLibrary.getUncached().isNull(regex)
          ? null
          : new Handle(token, env.getContext(), new DispatchRoot(regex).getCallTarget());
    } catch (AbstractTruffleException failure) {
      try {
        // A valid RE2/J pattern can exceed TRegex's supported syntax/automaton limits.
        if (InteropLibrary.getUncached().getExceptionType(failure) == ExceptionType.PARSE_ERROR)
          return null;
      } catch (InteropException invalidException) {
        throw CompilerDirectives.shouldNotReachHere(invalidException);
      }
      throw failure;
    }
  }

  private record Re2(Pattern pattern) implements Compiled {
    @Override
    public int programSize() {
      return pattern.programSize();
    }

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
