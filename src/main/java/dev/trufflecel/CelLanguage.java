package dev.trufflecel;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.instrumentation.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.options.*;

/** Polyglot registration; the direct Java API does not require a Polyglot Context. */
@TruffleLanguage.Registration(
    id = "cel",
    name = "TruffleCEL",
    version = "0.1.0",
    defaultMimeType = "application/cel",
    characterMimeTypes = "application/cel",
    contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
@ProvidedTags({
  StandardTags.ExpressionTag.class,
  StandardTags.StatementTag.class,
  StandardTags.RootTag.class,
  StandardTags.RootBodyTag.class,
  StandardTags.CallTag.class,
  StandardTags.ReadVariableTag.class,
  StandardTags.WriteVariableTag.class
})
public final class CelLanguage extends TruffleLanguage<CelLanguage.Scope> {
  @Option(
      name = "TruffleRegexOnly",
      help = "Use TruffleRegex without RE2/J compatibility fallback.",
      category = OptionCategory.USER,
      stability = OptionStability.EXPERIMENTAL)
  static final OptionKey<Boolean> TRUFFLE_REGEX_ONLY = new OptionKey<>(false);

  @Option(
      name = "RegexParserTreeLimit",
      help = "Parser-tree node bound in TruffleRegex-only mode.",
      category = OptionCategory.USER,
      stability = OptionStability.EXPERIMENTAL)
  static final OptionKey<Integer> REGEX_PARSER_TREE_LIMIT = new OptionKey<>(10000);

  @Override
  protected OptionDescriptors getOptionDescriptors() {
    return new CelLanguageOptionDescriptors();
  }

  @Override
  protected boolean areOptionsCompatible(OptionValues first, OptionValues second) {
    if (!first.get(TRUFFLE_REGEX_ONLY).equals(second.get(TRUFFLE_REGEX_ONLY))) return false;
    return !first.get(TRUFFLE_REGEX_ONLY)
        || first.get(REGEX_PARSER_TREE_LIMIT).equals(second.get(REGEX_PARSER_TREE_LIMIT));
  }

  private static final LanguageReference<CelLanguage> LANGUAGE =
      LanguageReference.create(CelLanguage.class);

  static CelLanguage current(com.oracle.truffle.api.source.Source source) {
    if (!"cel".equals(source.getLanguage()))
      throw new IllegalArgumentException("Expected a CEL source");
    Context.getCurrent().initialize("cel");
    return LANGUAGE.get(null);
  }

  private static final ContextReference<Scope> CONTEXT = ContextReference.create(CelLanguage.class);

  @CompilerDirectives.TruffleBoundary
  static Scope currentScope() {
    try {
      // ContextReference.get is undefined without an entered context, including with assertions.
      Context.getCurrent();
      return CONTEXT.get(null);
    } catch (IllegalStateException noContext) {
      return null;
    }
  }

  @Override
  protected Scope createContext(Env env) {
    return new Scope(env);
  }

  @Override
  protected void disposeContext(Scope scope) {
    scope.clearRegexes();
  }

  @Override
  protected Object getScope(Scope scope) {
    return scope;
  }

  @Override
  protected CallTarget parse(ParsingRequest request) {
    final String sourceName = request.getSource().getName();
    final Program program;
    try {
      var builder = Cel.newBuilder().inferVariables(true);
      var scope = CONTEXT.get(null);
      if (scope.env.getOptions().get(TRUFFLE_REGEX_ONLY))
        builder.truffleRegexOnly(scope.env.getOptions().get(REGEX_PARSER_TREE_LIMIT));
      program =
          builder
              .build()
              .prepare(request.getSource().getCharacters().toString())
              .adopt(this, request.getSource());
    } catch (CelException failure) {
      throw new GuestException(failure, true);
    }
    return new RootNode(this) {
      @Override
      public Object execute(VirtualFrame frame) {
        try {
          return GuestValue.wrap(program.evaluateInternal(CONTEXT.get(this).values));
        } catch (CelException failure) {
          throw new GuestException(failure, false);
        }
      }

      @Override
      public String getName() {
        return "CEL: " + sourceName;
      }
    }.getCallTarget();
  }

  @ExportLibrary(InteropLibrary.class)
  static final class Scope implements TruffleObject {
    final Map<String, Object> values = new LinkedHashMap<>();
    final Object regexToken = new Object();
    private final Env env;

    private record RegexKey(String pattern, int limit, boolean strict) {}

    private final LinkedHashMap<RegexKey, Object> regexes = new LinkedHashMap<>();

    Scope(Env env) {
      this.env = env;
    }

    @CompilerDirectives.TruffleBoundary
    synchronized Regex.Handle regex(String pattern) {
      return regex(pattern, 100000, false);
    }

    @CompilerDirectives.TruffleBoundary
    synchronized Regex.Handle regex(String pattern, int limit, boolean strict) {
      var key = new RegexKey(pattern, limit, strict);
      Object result = regexes.get(key);
      if (result == null && !regexes.containsKey(key)) {
        try {
          result = Regex.accelerate(env, regexToken, pattern, limit, strict);
        } catch (CelException error) {
          result = new Regex.Failure(error);
        }
        if (regexes.size() >= 256) regexes.pollFirstEntry();
        regexes.put(key, result);
      }
      if (result instanceof Regex.Failure failure) throw failure.exception();
      return (Regex.Handle) result;
    }

    @CompilerDirectives.TruffleBoundary
    synchronized void clearRegexes() {
      regexes.clear();
    }

    synchronized int regexCount() {
      return regexes.size();
    }

    @ExportMessage
    boolean hasMembers() {
      return true;
    }

    @ExportMessage
    @CompilerDirectives.TruffleBoundary
    Object getMembers(boolean includeInternal) {
      return GuestValue.wrap(List.copyOf(values.keySet()));
    }

    @ExportMessage
    @CompilerDirectives.TruffleBoundary
    boolean isMemberReadable(String name) {
      return values.containsKey(name);
    }

    @ExportMessage
    @CompilerDirectives.TruffleBoundary
    Object readMember(String name) throws UnknownIdentifierException {
      if (!values.containsKey(name)) throw UnknownIdentifierException.create(name);
      return GuestValue.wrap(values.get(name));
    }

    @ExportMessage
    @CompilerDirectives.TruffleBoundary
    boolean isMemberModifiable(String name) {
      return values.containsKey(name);
    }

    @ExportMessage
    @CompilerDirectives.TruffleBoundary
    boolean isMemberInsertable(String name) {
      return !values.containsKey(name);
    }

    @ExportMessage
    @CompilerDirectives.TruffleBoundary
    void writeMember(String name, Object value) throws UnsupportedTypeException {
      try {
        Object toUnwrap = value;
        try {
          Object hostObject = env.asHostObject(value);
          if (hostObject instanceof byte[] b) toUnwrap = b;
        } catch (ClassCastException e) {
          // Not a host object; unwrap the original value.
        }
        values.put(name, GuestValue.unwrap(toUnwrap, 0));
      } catch (InteropException | CelException e) {
        throw UnsupportedTypeException.create(new Object[] {value}, e.getMessage());
      }
    }

    @ExportMessage
    boolean hasLanguageId() {
      return true;
    }

    @ExportMessage
    String getLanguageId() {
      return "cel";
    }

    @ExportMessage
    Object toDisplayString(boolean allowSideEffects) {
      return "CEL bindings";
    }

    @ExportMessage
    boolean isScope() {
      return true;
    }
  }
}
