package dev.trufflecel;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.*;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.SourceSection;
import java.util.*;

/** Source and lexical state exposed lazily, without allocating metadata during evaluation. */
@ExportLibrary(NodeLibrary.class)
abstract class CelNode extends Node implements InstrumentableNode {
  int sourceStart, sourceEnd;

  @Override
  public boolean isInstrumentable() {
    return getSourceSection() != null;
  }

  // Root source lookup can reach other languages' parsers during Native Image analysis.
  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  @Override
  public SourceSection getSourceSection() {
    var root = getRootNode();
    SourceSection section = root == null ? null : root.getSourceSection();
    return section == null
        ? null
        : section.getSource().createSection(sourceStart, sourceEnd - sourceStart);
  }

  @ExportMessage
  boolean hasScope(Frame frame) {
    return frame != null;
  }

  @ExportMessage
  Object getScope(Frame frame, boolean nodeEnter) throws UnsupportedMessageException {
    if (frame == null) throw UnsupportedMessageException.create();
    return new DebugScope(((EvalContext) frame.getArguments()[0]).visibleBindings());
  }

  @ExportLibrary(InteropLibrary.class)
  static final class DebugScope implements TruffleObject {
    private final Map<String, Object> bindings;

    DebugScope(Map<String, Object> bindings) {
      this.bindings = bindings;
    }

    @ExportMessage
    boolean isScope() {
      return true;
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
    Object toDisplayString(boolean sideEffects) {
      return "CEL activation";
    }

    @ExportMessage
    boolean hasMembers() {
      return true;
    }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getMembers(boolean internal) {
      return GuestValue.wrap(List.copyOf(bindings.keySet()));
    }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isMemberReadable(String name) {
      return bindings.containsKey(name);
    }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object readMember(String name) throws UnknownIdentifierException {
      if (!bindings.containsKey(name)) throw UnknownIdentifierException.create(name);
      return GuestValue.wrap(bindings.get(name));
    }
  }
}
