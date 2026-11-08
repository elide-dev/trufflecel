package dev.trufflecel;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.instrumentation.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.*;
import java.util.*;
import org.graalvm.polyglot.Context;

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
      program =
          Cel.newBuilder()
              .inferVariables(true)
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
    private final Map<String, Regex.Handle> regexes = new HashMap<>();

    Scope(Env env) {
      this.env = env;
    }

    @CompilerDirectives.TruffleBoundary
    synchronized Regex.Handle regex(String pattern) {
      if (regexes.containsKey(pattern)) return regexes.get(pattern);
      Regex.Handle handle = Regex.accelerate(env, regexToken, pattern);
      if (regexes.size() >= 256) regexes.clear();
      regexes.put(pattern, handle);
      return handle;
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
        values.put(name, GuestValue.unwrap(value, 0));
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
