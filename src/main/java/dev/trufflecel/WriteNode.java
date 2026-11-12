package dev.trufflecel;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.*;
import java.util.Map;

/** A comprehension/optional binding, observable once for each iteration. */
@GenerateWrapper
class WriteNode extends CelNode {
  private final String name;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal int frameSlot = -1;

  WriteNode() {
    this("", 0, 0);
  }

  WriteNode(String name, int start, int end) {
    this.name = name;
    sourceStart = start;
    sourceEnd = end;
  }

  Object execute(VirtualFrame frame, EvalContext ctx, Object value) {
    Object stored = ctx.store(value);
    if (frameSlot >= 0) frame.setAuxiliarySlot(frameSlot, stored);
    else ctx.pushReentrant(this, name, stored);
    return value;
  }

  void clear(VirtualFrame frame, EvalContext ctx) {
    // When instrumented, the generated wrapper replaces bindings[i] in the @Children array; clear
    // is then dispatched on the wrapper, whose own frameSlot/scope state were never set by
    // execute (the wrapper delegates execute to this node). Forward to the delegate so the
    // pair it owns is removed rather than reading the wrapper's untouched defaults. Ownership is
    // recorded on every push, so a wrapper inserted between a push and its clear stays balanced.
    WriteNode delegate =
        this instanceof WrapperNode wrapper ? (WriteNode) wrapper.getDelegateNode() : this;
    if (delegate.frameSlot >= 0) frame.setAuxiliarySlot(delegate.frameSlot, null);
    else ctx.clearReentrant(delegate);
  }

  @GenerateWrapper.OutgoingConverter
  final Object instrumentResult(Object result) {
    return GuestValue.wrap(result instanceof ExprNode.LazyBinding lazy ? lazy.display() : result);
  }

  @Override
  public WrapperNode createWrapper(ProbeNode probe) {
    return new WriteNodeWrapper(this, probe);
  }

  @Override
  public boolean hasTag(Class<? extends Tag> tag) {
    return tag == StandardTags.WriteVariableTag.class;
  }

  @Override
  public Object getNodeObject() {
    return GuestValue.wrap(Map.of(StandardTags.WriteVariableTag.NAME, name));
  }
}
