package dev.trufflecel;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.*;
import java.util.*;

/** Polyglot registration; the direct Java API does not require a Polyglot Context. */
@TruffleLanguage.Registration(
    id = "cel",
    name = "TruffleCEL",
    version = "0.1.0",
    defaultMimeType = "application/cel",
    characterMimeTypes = "application/cel",
    contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class CelLanguage extends TruffleLanguage<CelLanguage.Scope> {
  private static final ContextReference<Scope> CONTEXT = ContextReference.create(CelLanguage.class);

  @Override
  protected Scope createContext(Env env) {
    return new Scope();
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
      program = Cel.compile(request.getSource().getCharacters().toString());
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

    @ExportMessage
    boolean hasMembers() {
      return true;
    }

    @ExportMessage
    Object getMembers(boolean includeInternal) {
      return GuestValue.wrap(List.copyOf(values.keySet()));
    }

    @ExportMessage
    boolean isMemberReadable(String name) {
      return values.containsKey(name);
    }

    @ExportMessage
    Object readMember(String name) throws UnknownIdentifierException {
      if (!values.containsKey(name)) throw UnknownIdentifierException.create(name);
      return GuestValue.wrap(values.get(name));
    }

    @ExportMessage
    boolean isMemberModifiable(String name) {
      return values.containsKey(name);
    }

    @ExportMessage
    boolean isMemberInsertable(String name) {
      return !values.containsKey(name);
    }

    @ExportMessage
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
