package dev.trufflecel;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.*;

/** Canonical CEL accumulator loops, including arbitrary non-macro comprehensions. */
final class ComprehensionNode extends ExprNode {
  ComprehensionNode(String[] names, ExprNode... children) {
    this(names, 0, children);
  }

  ComprehensionNode(String[] names, int offset, ExprNode... children) {
    super(Kind.COMPREHENSION, "", names, offset, children);
    bindings = new WriteNode[names[2].isEmpty() ? 2 : 3];
    for (int i = 0; i < bindings.length; i++) bindings[i] = new WriteNode(names[i], offset, offset);
  }

  void resolveComprehensionLocals(ArrayList<String> locals) {
    children[0].resolveLocals(locals, null);
    children[1].resolveLocals(locals, null);
    int outer = locals.size();
    String[] names = (String[]) value;
    locals.add(names[0]);
    locals.add(names[1]);
    if (!names[2].isEmpty()) locals.add(names[2]);
    children[2].resolveLocals(locals, null);
    children[3].resolveLocals(locals, null);
    while (locals.size() > outer + 1) locals.remove(locals.size() - 1);
    children[4].resolveLocals(locals, null);
    locals.remove(locals.size() - 1);
  }

  Object executeComprehension(VirtualFrame frame, EvalContext ctx) {
    Object range = children[0].execute(frame, ctx);
    Attribute rangeAttribute = ctx.attribute();
    if (range == PartialState.MISSING) return ctx.consume(range);
    if (range instanceof Unknown) return range;
    Object accumulator = children[1].execute(frame, ctx);
    Attribute accumulatorAttribute = ctx.attribute();
    Values.OwnedList items = HostCollections.range(range);
    String[] names = (String[]) value;
    long index = 0;
    for (int position = 0; position < items.size(); position++) {
      Object item = items.get(position);
      ctx.tick();
      ctx.attribute(accumulatorAttribute);
      try {
        bindings[0].execute(frame, ctx, accumulator);
        ctx.attribute(
            range instanceof List<?> && names[2].isEmpty()
                ? ctx.qualify(rangeAttribute, index)
                : null);
        bindings[1].execute(
            frame,
            ctx,
            ctx.iterationValue(
                range instanceof List<?> && !names[2].isEmpty() ? index : item, ctx.attribute()));
        if (bindings.length == 3) {
          ctx.attribute(ctx.qualify(rangeAttribute, range instanceof Map<?, ?> ? item : index));
          bindings[2].execute(
              frame,
              ctx,
              ctx.iterationValue(
                  range instanceof Map<?, ?> map ? HostCollections.get(map, item) : item,
                  ctx.attribute()));
        }
        index++;
        Object condition = children[2].execute(frame, ctx);
        if (Boolean.FALSE.equals(condition)) break;
        if (condition instanceof Unknown) return condition;
        if (!Boolean.TRUE.equals(condition)) throw Values.overload("comprehension condition");
        try {
          accumulator = children[3].execute(frame, ctx);
          accumulatorAttribute = ctx.attribute();
        } catch (CelException e) {
          if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
          accumulator = e;
          accumulatorAttribute = null;
        }
      } finally {
        for (int i = bindings.length - 1; i >= 0; i--) bindings[i].clear(frame, ctx);
      }
    }
    ctx.attribute(accumulatorAttribute);
    try {
      bindings[0].execute(frame, ctx, accumulator);
      return children[4].execute(frame, ctx);
    } finally {
      bindings[0].clear(frame, ctx);
    }
  }
}
