package dev.trufflecel;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.*;
import java.util.Map;

/** A comprehension/optional binding, observable once for each iteration. */
@GenerateWrapper
class WriteNode extends CelNode {
  private final String name;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal int frameSlot = -1;
  // True once Truffle has wrapped this node for instrumentation (createWrapper ran). Re-enter
  // (ProbeNode.UNWIND_ACTION_REENTER re-running execute without clear) can only happen through a
  // wrapper, so the re-entrant-safe push/clear path is only needed when this is set; uninstrumented
  // evaluation keeps the plain push/pop fast path. A plain (non-CompilationFinal) field so a guard
  // deopts and recompiles if an instrument attaches after the node was already compiled.
  private boolean instrumented;

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
    else if (instrumented) ctx.pushReentrant(this, name, stored);
    else ctx.push(name, stored);
    return value;
  }

  void clear(VirtualFrame frame, EvalContext ctx) {
    // When instrumented, the generated wrapper replaces bindings[i] in the @Children array; clear
    // is then dispatched on the wrapper, whose own frameSlot/scope state were never set by
    // execute (the wrapper delegates execute to this node). Forward to the delegate so the
    // scope depth recorded on the first push is restored rather than reading the wrapper's
    // untouched defaults.
    WriteNode delegate =
        this instanceof WrapperNode wrapper ? (WriteNode) wrapper.getDelegateNode() : this;
    if (delegate.frameSlot >= 0) frame.setAuxiliarySlot(delegate.frameSlot, null);
    else if (delegate.instrumented) ctx.clearReentrant(delegate);
    else ctx.pop();
  }

  @GenerateWrapper.OutgoingConverter
  final Object instrumentResult(Object result) {
    return GuestValue.wrap(result instanceof ExprNode.LazyBinding lazy ? lazy.display() : result);
  }

  @Override
  public WrapperNode createWrapper(ProbeNode probe) {
    instrumented = true;
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
