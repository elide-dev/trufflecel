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
    else ctx.push(name, stored);
    return value;
  }

  void clear(VirtualFrame frame, EvalContext ctx) {
    if (frameSlot >= 0) frame.setAuxiliarySlot(frameSlot, null);
    else ctx.pop();
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
