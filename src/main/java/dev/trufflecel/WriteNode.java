package dev.trufflecel;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.*;
import java.util.Map;

/** A comprehension/optional binding, observable once for each iteration. */
@GenerateWrapper
class WriteNode extends CelNode {
  private final String name;

  WriteNode() {
    this("", 0, 0);
  }

  WriteNode(String name, int start, int end) {
    this.name = name;
    sourceStart = start;
    sourceEnd = end;
  }

  Object execute(VirtualFrame frame, EvalContext ctx, Object value) {
    ctx.push(name, value);
    return value;
  }

  @GenerateWrapper.OutgoingConverter
  final Object instrumentResult(Object result) {
    return GuestValue.wrap(result);
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
