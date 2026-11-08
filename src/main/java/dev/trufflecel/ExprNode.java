package dev.trufflecel;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.instrumentation.*;
import java.util.*;

/** Each parsed expression becomes an adopted Truffle AST node, never a delegated interpreter. */
@GenerateWrapper
class ExprNode extends CelNode {
  boolean rootBody, statement;
  long canonicalId;
  int errorOffset = -1, errorLine = -1, errorColumn = -1;
  CelType checkedType;
  FieldPlan fieldPlan;
  // Plan-time constant fold; uninstrumented targets replace the subtree with its prebuilt result.
  Optimizer.Fold fold;
  // Plan-time purity and binding depth, packed by Optimizer; avoids per-node analysis maps.
  int analysis;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean customOverloads;
  List<String> overloadIds = List.of();
  @Child private FieldPlan.ReadNode fieldRead;

  void instantiateFieldPlans() {
    if (fieldPlan != null) fieldRead = insert(fieldPlan.instantiate());
    for (ExprNode child : children) child.instantiateFieldPlans();
  }

  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private ExprNode blockOwner;

  void resolveBlocks(ExprNode owner) {
    if (kind == Kind.BLOCK_INDEX) blockOwner = owner;
    for (ExprNode child : children) child.resolveBlocks(kind == Kind.BLOCK ? this : owner);
  }

  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean boundaryReads;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private int localSlot = -1;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private boolean frameLocalReads;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private boolean countResult;
  @Children WriteNode[] bindings;

  ExprNode() {
    this(Kind.CONSTANT, "", null, 0);
  }

  ExprNode sourceRange(int start, int end) {
    sourceStart = start;
    sourceEnd = end;
    return this;
  }

  @GenerateWrapper.OutgoingConverter
  final Object instrumentResult(Object result) {
    return GuestValue.wrap(result);
  }

  @Override
  public WrapperNode createWrapper(ProbeNode probe) {
    var wrapper = new ExprNodeWrapper(this, probe);
    // Collection/object parents inspect this flag after executing a child through its probe.
    wrapper.optionalEntry = optionalEntry;
    return wrapper;
  }

  @Override
  public boolean hasTag(Class<? extends Tag> tag) {
    return tag == StandardTags.ExpressionTag.class
        || (rootBody || statement) && tag == StandardTags.StatementTag.class
        || rootBody && (tag == StandardTags.RootTag.class || tag == StandardTags.RootBodyTag.class)
        || (kind == Kind.CALL || kind == Kind.BLOCK) && tag == StandardTags.CallTag.class
        || (kind == Kind.IDENT || kind == Kind.BLOCK_INDEX)
            && tag == StandardTags.ReadVariableTag.class;
  }

  @Override
  public Object getNodeObject() {
    return kind == Kind.IDENT || kind == Kind.BLOCK_INDEX
        ? GuestValue.wrap(Map.of(StandardTags.ReadVariableTag.NAME, name))
        : null;
  }

  enum Kind {
    BLOCK,
    BLOCK_INDEX,
    CONSTANT,
    IDENT,
    SELECT,
    INDEX,
    CALL,
    LIST,
    MAP,
    OBJECT,
    CONDITIONAL,
    MACRO,
    COMPREHENSION
  }

  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal Kind kind;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal String name;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal Object value;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean optionalEntry;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean extensionCall;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean extensionSelect;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean optionalFallback;
  @com.oracle.truffle.api.CompilerDirectives.CompilationFinal boolean stringPredicate;
  final int offset;
  private final int scalarOperator;
  @Children ExprNode[] children;

  ExprNode(Kind kind, String name, Object value, int offset, ExprNode... children) {
    scalarOperator = kind == Kind.CALL ? scalarCode(name) : 0;
    this.kind = kind;
    this.name = name;
    this.value = value;
    this.offset = offset;
    this.children = children;
  }

  private static int scalarCode(String name) {
    return switch (name) {
      case "+", "-", "*", "/", "%", "<", ">" -> name.charAt(0);
      case "<=" -> 'L';
      case ">=" -> 'G';
      case "==" -> 'E';
      case "!=" -> 'N';
      default -> 0;
    };
  }

  static ExprNode binary(String name, int offset, ExprNode left, ExprNode right) {
    return scalarCode(name) == 0 || name.equals("+") || name.equals("-") || name.equals("*")
        ? new ExprNode(Kind.CALL, name, false, offset, left, right)
        : new ScalarBinaryNode(name, offset, left, right);
  }

  private static final class ScalarBinaryNode extends ExprNode {
    ScalarBinaryNode(String name, int offset, ExprNode left, ExprNode right) {
      super(Kind.CALL, name, false, offset, left, right);
    }

    @Override
    Object doExecute(VirtualFrame frame, EvalContext ctx) {
      if (customOverloads) return super.doExecute(frame, ctx);
      ctx.tick();
      return scalarBinary(frame, ctx);
    }
  }

  String firstIdentifier() {
    if (kind == Kind.IDENT) return name;
    if (kind == Kind.CONSTANT) return null;
    for (ExprNode child : children) {
      String identifier = child.firstIdentifier();
      if (identifier != null) return identifier;
    }
    return null;
  }

  boolean hasBindings() {
    if (bindings != null) return true;
    for (ExprNode child : children) if (child.hasBindings()) return true;
    return false;
  }

  boolean escapesContext() {
    if (kind == Kind.CONSTANT) return false;
    if (kind == Kind.IDENT) return name.startsWith(".");
    if (kind == Kind.COMPREHENSION
        || kind == Kind.BLOCK
        || extensionCall
        || kind == Kind.SELECT && fieldPlan == null
        || kind == Kind.OBJECT
        || name.equals("sortBy")) return true;
    for (ExprNode child : children) if (child.escapesContext()) return true;
    return false;
  }

  void resolveLocals(ArrayList<String> locals, FrameDescriptor frameDescriptor) {
    if (kind == Kind.COMPREHENSION) {
      ((ComprehensionNode) this).resolveComprehensionLocals(locals);
      return;
    }
    if (kind == Kind.CONSTANT) return;
    if (kind == Kind.IDENT) {
      localSlot = locals.lastIndexOf(name);
      frameLocalReads = frameDescriptor != null;
      return;
    }
    int outerSize = locals.size();
    for (int i = 0; i < children.length; i++) {
      // A comprehension's range is evaluated before its iteration variables are bound.
      if (i == 1 && bindings != null) {
        String[] names = (String[]) value;
        for (int binding = 0; binding < bindings.length; binding++) {
          int slot = locals.size();
          if (frameDescriptor != null) {
            if (slot == frameDescriptor.getNumberOfAuxiliarySlots())
              frameDescriptor.findOrAddAuxiliarySlot(new Object());
            bindings[binding].frameSlot = slot;
          }
          locals.add(names[binding]);
        }
      }
      children[i].resolveLocals(locals, frameDescriptor);
    }
    while (locals.size() > outerSize) locals.remove(locals.size() - 1);
  }

  static ExprNode constant(Object value, int offset) {
    return new LiteralNode(value, offset);
  }

  ExprNode lowerUninstrumented() {
    // A folded subtree is replaced wholesale; lowering inside it would only be discarded.
    if (fold != null) return this;
    if (customOverloads) {
      for (int i = 0; i < children.length; i++) children[i] = children[i].lowerUninstrumented();
      return this;
    }
    int count = 0;
    ExprNode leaf = this;
    while (leaf.kind == Kind.CALL
        && !leaf.customOverloads
        && !Boolean.TRUE.equals(leaf.value)
        && leaf.children.length == 2
        && (leaf.name.equals("+") || leaf.name.equals("-") || leaf.name.equals("*"))
        && leaf.children[1].kind == Kind.CONSTANT
        && leaf.children[1].value instanceof Long) {
      count++;
      leaf = leaf.children[0];
    }
    if (count >= 2) return new ArithmeticChain(this, count, leaf.lowerUninstrumented());
    for (int i = 0; i < children.length; i++) children[i] = children[i].lowerUninstrumented();
    if (kind == Kind.CALL && (name.equals("&&") || name.equals("||"))) return new LogicalNode(this);
    if (kind == Kind.CALL && name.equals("size") && !extensionCall && children.length == 1) {
      ExprNode range = children[0];
      if (range.kind == Kind.MACRO
          && (range.name.equals("map")
              || range.name.equals("filter")
              || range.name.equals("transformList"))) {
        range.countResult = true;
        return new ComprehensionSize(this, range);
      }
    }
    return this;
  }

  /** Logical operators keep their error/unknown precedence without general function dispatch. */
  private static final class LogicalNode extends ExprNode {
    private final boolean decisive;

    LogicalNode(ExprNode original) {
      super(Kind.CALL, original.name, original.value, original.offset, original.children);
      decisive = original.name.equals("||");
      sourceRange(original.sourceStart, original.sourceEnd);
      copyLocation(original);
      optionalEntry = original.optionalEntry;
      statement = original.statement;
    }

    @Override
    Object doExecute(VirtualFrame frame, EvalContext ctx) {
      ctx.tick();
      return logical(frame, ctx, decisive);
    }
  }

  /** Counts results without retaining them; mapper visits, errors and budgets are unchanged. */
  private static final class ComprehensionSize extends ExprNode {
    ComprehensionSize(ExprNode outer, ExprNode range) {
      super(Kind.CALL, outer.name, outer.value, outer.offset, range);
      sourceRange(outer.sourceStart, outer.sourceEnd);
      copyLocation(outer);
      optionalEntry = outer.optionalEntry;
      statement = outer.statement;
    }

    @Override
    Object doExecute(VirtualFrame frame, EvalContext ctx) {
      ctx.tick();
      return children[0].execute(frame, ctx);
    }
  }

  /**
   * Constant-right arithmetic keeps intermediates unboxed and preserves source evaluation order.
   */
  private static final class ArithmeticChain extends ExprNode {
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final int[] operations;

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private final long[] constants;

    // Prefixes hold runtime operations/constants; tails hold their original error locations/IDs.
    // Two primitive arrays retain the full metadata without separate per-program allocations.
    private final int count;

    private final String firstOperation;

    ArithmeticChain(ExprNode outer, int count, ExprNode leaf) {
      super(Kind.CALL, outer.name, false, outer.offset, leaf);
      this.count = count;
      operations = new int[count * 4];
      constants = new long[count * 2];
      ExprNode node = outer;
      for (int i = count - 1; i >= 0; i--) {
        operations[count + i * 3] = node.errorOffset;
        operations[count + i * 3 + 1] = node.errorLine;
        operations[count + i * 3 + 2] = node.errorColumn;
        constants[count + i] = node.canonicalId;
        operations[i] = node.name.charAt(0);
        constants[i] = (Long) node.children[1].value;
        node = node.children[0];
      }
      firstOperation =
          switch (operations[0]) {
            case '+' -> "+";
            case '-' -> "-";
            default -> "*";
          };
      sourceRange(outer.sourceStart, outer.sourceEnd);
      copyLocation(outer);
      optionalEntry = outer.optionalEntry;
      statement = outer.statement;
    }

    @Override
    @com.oracle.truffle.api.nodes.ExplodeLoop
    Object doExecute(VirtualFrame frame, EvalContext ctx) {
      // The original outer-to-inner operator visits all precede evaluation of the left operand.
      ctx.charge(count);
      Object left;
      try {
        left = children[0].execute(frame, ctx);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        ctx.charge(count);
        throw e;
      }
      if (left instanceof Unknown) {
        ctx.charge(count);
        return left;
      }
      if (left instanceof Long number) {
        long result = number;
        for (int i = 0; i < count; i++) {
          ctx.tick(); // The right literal's visit still consumes its original budget.
          try {
            result =
                switch (operations[i]) {
                  case '+' -> Functions.add(result, constants[i]);
                  case '-' -> Functions.subtract(result, constants[i]);
                  default -> Functions.multiply(result, constants[i]);
                };
          } catch (CelException e) {
            // Outer constants are evaluated even when an inner operation fails.
            ctx.charge(count - i - 1);
            throw e.at(
                operations[count + i * 3],
                constants[count + i],
                operations[count + i * 3 + 1],
                operations[count + i * 3 + 2]);
          }
        }
        return result;
      }
      // These operators require matching numeric types; every right operand is a CEL int.
      ctx.charge(count);
      throw Values.overload(firstOperation)
          .at(operations[count], constants[count], operations[count + 1], operations[count + 2]);
    }
  }

  private static final class LiteralNode extends ExprNode {
    LiteralNode(Object value, int offset) {
      super(Kind.CONSTANT, "", value, offset);
    }

    @Override
    Object doExecute(VirtualFrame frame, EvalContext ctx) {
      ctx.tick();
      return value;
    }
  }

  String qualifiedName() {
    if (extensionSelect) return null;
    if (kind == Kind.IDENT) return name;
    if (kind == Kind.SELECT && !Boolean.TRUE.equals(value)) {
      String prefix = children[0].qualifiedName();
      if (prefix != null) return prefix + "." + name;
    }
    return null;
  }

  void free(Set<String> bound, Set<String> out) {
    String qualified = qualifiedName();
    if (qualified != null
        && Environment.builtinType(qualified) != null
        && !bound.contains(qualified)) return;
    if (kind == Kind.CALL && Boolean.TRUE.equals(value)) {
      String prefix = children[0].qualifiedName();
      if (prefix != null && prefix.startsWith(".")) prefix = prefix.substring(1);
      if (ExtensionFunctions.namespaced(prefix, name)
          || "optional".equals(prefix) && Set.of("of", "none", "ofNonZeroValue").contains(name)) {
        for (int i = 1; i < children.length; i++) children[i].free(bound, out);
        return;
      }
    }
    if (kind == Kind.IDENT && !bound.contains(name)) out.add(name);
    if (kind == Kind.COMPREHENSION) {
      children[0].free(bound, out);
      children[1].free(bound, out);
      var inner = new HashSet<>(bound);
      inner.addAll(Arrays.asList((String[]) value));
      children[2].free(inner, out);
      children[3].free(inner, out);
      var resultScope = new HashSet<>(bound);
      resultScope.add(((String[]) value)[0]);
      children[4].free(resultScope, out);
      return;
    }
    if (kind == Kind.MACRO && !name.equals("has")) {
      children[0].free(bound, out);
      var inner = new HashSet<>(bound);
      inner.addAll(Arrays.asList((String[]) value));
      for (int i = 1; i < children.length; i++) children[i].free(inner, out);
    } else for (ExprNode child : children) child.free(bound, out);
  }

  Object execute(VirtualFrame frame, EvalContext ctx) {
    try {
      return doExecute(frame, ctx);
    } catch (CelException e) {
      if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
      throw e.at(errorOffset, canonicalId, errorLine, errorColumn);
    }
  }

  void copyLocation(ExprNode original) {
    canonicalId = original.canonicalId;
    errorOffset = original.errorOffset;
    errorLine = original.errorLine;
    errorColumn = original.errorColumn;
  }

  Object doExecute(VirtualFrame frame, EvalContext ctx) {
    ctx.tick();
    return switch (kind) {
      case BLOCK -> block(frame, ctx);
      case BLOCK_INDEX ->
          ctx.block.read(
              blockOwner.children[Math.toIntExact((Long) value)],
              Math.toIntExact((Long) value),
              frame);
      case CONSTANT -> value;
      case IDENT ->
          checkedValue(
              localSlot >= 0
                  ? (frameLocalReads ? frame.getAuxiliarySlot(localSlot) : ctx.getLocal(localSlot))
                  : boundaryReads ? ctx.getBoundary(name) : ctx.get(name));
      case SELECT -> select(frame, ctx, false);
      case INDEX -> {
        Object a = children[0].execute(frame, ctx), b = children[1].execute(frame, ctx);
        if (a instanceof Unknown u) yield u;
        if (b instanceof Unknown u) yield u;
        boolean optional = Boolean.TRUE.equals(value) || a instanceof OptionalValue;
        if (a instanceof OptionalValue o) {
          if (!o.present()) yield o;
          a = o.value();
        }
        try {
          Object result = Values.index(a, b);
          yield optional ? OptionalValue.of(result) : result;
        } catch (CelException e) {
          if (optional
              && (e.code() == CelException.Code.NO_SUCH_KEY || e.code() == CelException.Code.INDEX))
            yield OptionalValue.none();
          throw e;
        }
      }
      case LIST -> list(frame, ctx);
      case MAP -> map(frame, ctx);
      case OBJECT -> object(frame, ctx);
      case CONDITIONAL -> {
        Object condition = children[0].execute(frame, ctx);
        if (condition instanceof Unknown) yield condition;
        if (!(condition instanceof Boolean b)) throw Values.overload("conditional");
        yield b ? children[1].execute(frame, ctx) : children[2].execute(frame, ctx);
      }
      case CALL -> call(frame, ctx);
      case MACRO -> macro(frame, ctx);
      case COMPREHENSION -> ((ComprehensionNode) this).executeComprehension(frame, ctx);
    };
  }

  private static Object checkedValue(Object value) {
    if (value instanceof CelException error) throw error;
    return value;
  }

  Object executePresence(VirtualFrame frame, EvalContext ctx) {
    try {
      return select(frame, ctx, true);
    } catch (CelException e) {
      if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
      throw e.at(errorOffset, canonicalId, errorLine, errorColumn);
    }
  }

  private Object select(VirtualFrame frame, EvalContext ctx, boolean presence) {
    Object operand = children[0].execute(frame, ctx);
    if (fieldRead != null && fieldRead.accepts(operand))
      return presence ? fieldRead.has(operand) : fieldRead.read(operand);
    return selectValue(ctx, presence, operand);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private Object selectValue(EvalContext ctx, boolean presence, Object operand) {
    if (operand instanceof Unknown) return operand;
    if (extensionSelect) {
      for (ObjectType type : ctx.environment.runtimeObjectTypes)
        if (type.accepts(operand)) {
          String extension = ctx.environment.resolve(name, type.extensions().keySet());
          if (extension == null)
            throw Values.error(CelException.Code.NO_SUCH_FIELD, "unregistered extension: " + name);
          return presence ? type.has(operand, extension) : Values.in(type.get(operand, extension));
        }
      throw Values.overload("protobuf extension");
    }
    boolean lifted = Boolean.TRUE.equals(value) || operand instanceof OptionalValue;
    if (operand instanceof OptionalValue o) {
      if (!o.present()) return presence ? false : o;
      operand = o.value();
    }
    if (operand instanceof Map<?, ?> map) {
      boolean present = map.containsKey(name);
      if (presence) return present;
      if (lifted) return present ? OptionalValue.of(map.get(name)) : OptionalValue.none();
      if (!present) throw Values.error(CelException.Code.NO_SUCH_KEY, "no such key: " + name);
      return map.get(name);
    }
    for (ObjectType type : ctx.environment.runtimeObjectTypes)
      if (type.accepts(operand)) {
        if (!type.fields().containsKey(name))
          throw Values.error(CelException.Code.NO_SUCH_FIELD, "no such field: " + name);
        if (presence) return type.has(operand, name);
        if (lifted)
          return type.has(operand, name)
              ? OptionalValue.of(type.get(operand, name))
              : OptionalValue.none();
        return Values.in(type.get(operand, name));
      }
    throw Values.overload("select " + name);
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object call(VirtualFrame frame, EvalContext ctx) {
    if (name.equals("@not_strictly_false")) {
      try {
        return !Boolean.FALSE.equals(children[0].execute(frame, ctx));
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        return true;
      }
    }

    if ((scalarOperator != 0 || stringPredicate)
        && children.length == 2
        && !extensionCall
        && !customOverloads) return scalarBinary(frame, ctx);
    if (name.equals("&&") || name.equals("||")) return logical(frame, ctx, name.equals("||"));
    if (optionalFallback && children.length == 2) {
      Object first = children[0].execute(frame, ctx);
      if (first instanceof Unknown) return first;
      OptionalValue o = optional(first);
      if (o.present()) return name.equals("or") ? o : o.value();
      Object fallback = children[1].execute(frame, ctx);
      return name.equals("or") && !(fallback instanceof Unknown) ? optional(fallback) : fallback;
    }
    Object[] args = new Object[children.length];
    CelException error = null;
    Unknown unknown = null;
    for (int i = 0; i < children.length; i++) {
      try {
        args[i] = children[i].execute(frame, ctx);
        if (args[i] instanceof Unknown u) unknown = unknown == null ? u : unknown.merge(u);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        error = e;
      }
    }
    if (unknown != null) return unknown;
    if (error != null) throw error;
    if (customOverloads) {
      var binding =
          FunctionOverloads.select(ctx.environment, name, Boolean.TRUE.equals(value), args);
      if (binding != null) return FunctionOverloads.invoke(binding, args);
      if (!FunctionOverloads.standard(name)) throw Values.overload(name);
    }
    if (extensionCall) return ExtensionFunctions.apply(name, args, ctx);
    return Functions.apply(name, args, ctx.environment);
  }

  // Avoid temporary argument arrays and repeated string dispatch in the interpreter.
  final Object scalarBinary(VirtualFrame frame, EvalContext ctx) {
    Object left = null, right = null;
    CelException error = null;
    try {
      left = children[0].execute(frame, ctx);
    } catch (CelException e) {
      if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
      error = e;
    }
    try {
      right = children[1].execute(frame, ctx);
    } catch (CelException e) {
      if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
      error = e;
    }
    if (left instanceof Unknown u) return right instanceof Unknown v ? u.merge(v) : u;
    if (right instanceof Unknown) return right;
    if (error != null) throw error;
    if (stringPredicate) return Functions.stringPredicate(name, left, right);
    if (left instanceof Long x && right instanceof Long y) {
      return switch (scalarOperator) {
        case '+' -> Functions.add(x, y);
        case '-' -> Functions.subtract(x, y);
        case '<' -> x < y;
        case '>' -> x > y;
        case 'L' -> x <= y;
        case 'G' -> x >= y;
        case 'E' ->
            ctx.environment.runtimeObjectTypes.length == 0
                ? x.longValue() == y.longValue()
                : Functions.apply(name, new Object[] {left, right}, ctx.environment);
        case 'N' ->
            ctx.environment.runtimeObjectTypes.length == 0
                ? x.longValue() != y.longValue()
                : Functions.apply(name, new Object[] {left, right}, ctx.environment);
        case '/' -> Functions.divide(x, y);
        case '%' -> Functions.remainder(x, y);
        default -> Functions.multiply(x, y);
      };
    }
    return Functions.apply(name, new Object[] {left, right}, ctx.environment);
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  final Object logical(VirtualFrame frame, EvalContext ctx, boolean decisive) {
    CelException error = null;
    Unknown unknown = null;
    for (ExprNode child : children) {
      try {
        Object result = child.execute(frame, ctx);
        if (result instanceof Boolean b) {
          if (b == decisive) return b;
        } else if (result instanceof Unknown u) unknown = unknown == null ? u : unknown.merge(u);
        else error = Values.overload(name);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        error = e;
      }
    }
    if (unknown != null) return unknown;
    if (error != null) throw error;
    return !decisive;
  }

  static OptionalValue optional(Object value) {
    if (value instanceof OptionalValue o) return o;
    throw Values.overload("optional");
  }

  private Object block(VirtualFrame frame, EvalContext ctx) {
    if (getSourceSection() != null) return instrumentedBlock(frame.materialize(), ctx);
    ctx.charge(children.length);
    EvalContext scoped = ctx.fork();
    scoped.block = new BlockScope(children, null, scoped);
    EvalContext body = ctx.fork();
    body.block = scoped.block;
    return children[children.length - 1].execute(frame, body);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private Object instrumentedBlock(
      com.oracle.truffle.api.frame.MaterializedFrame frame, EvalContext ctx) {
    ctx.charge(children.length);
    EvalContext scoped = ctx.fork();
    VirtualFrame captured = forkFrame(frame, scoped);
    scoped.block = new BlockScope(children, captured, scoped);
    EvalContext body = ctx.fork();
    body.block = scoped.block;
    return children[children.length - 1].execute(forkFrame(frame, body), body);
  }

  private static VirtualFrame forkFrame(com.oracle.truffle.api.frame.Frame frame, EvalContext ctx) {
    VirtualFrame copy =
        com.oracle.truffle.api.Truffle.getRuntime()
            .createVirtualFrame(new Object[] {ctx}, frame.getFrameDescriptor());
    // Blocks force context-based locals, so no auxiliary frame slots need copying.
    return copy;
  }

  static final class BlockScope {
    private final VirtualFrame frame;
    private final EvalContext context;
    private final Object[] results;
    private int depth;

    BlockScope(ExprNode[] expressions, VirtualFrame frame, EvalContext context) {
      this.frame = frame;
      this.context = context;
      results = new Object[expressions.length - 1];
    }

    Object read(ExprNode initializer, int index, VirtualFrame callerFrame) {
      if (frame != null) return readInstrumented(initializer, index);
      return readValue(initializer, index, callerFrame);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object readInstrumented(ExprNode initializer, int index) {
      return readValue(initializer, index, frame);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object instrumentedInitializer(ExprNode initializer, EvalContext localContext) {
      return initializer.execute(forkFrame(frame, localContext), localContext);
    }

    private Object readValue(ExprNode initializer, int index, VirtualFrame callerFrame) {
      Object result = results[index];
      if (result == null) {
        if (depth >= context.environment.maxDepth)
          throw Values.error(
              CelException.Code.RESOURCE_EXHAUSTED, "block evaluation nesting limit exceeded");
        depth++;
        try {
          EvalContext localContext = context.fork();
          result =
              frame == null
                  ? initializer.execute(callerFrame, localContext)
                  : instrumentedInitializer(initializer, localContext);
        } catch (CelException e) {
          result = e;
        } finally {
          depth--;
        }
        results[index] = result;
      }
      if (result instanceof CelException error) throw error;
      return result;
    }
  }

  private Object macro(VirtualFrame frame, EvalContext ctx) {
    if (name.equals("has")) return children[0].executePresence(frame, ctx);
    Object range = children[0].execute(frame, ctx);
    if (range instanceof Unknown) return range;
    String[] variables = (String[]) value;
    if (name.equals("bind")) {
      bindings[0].execute(frame, ctx, range);
      try {
        return children[1].execute(frame, ctx);
      } finally {
        bindings[0].clear(frame, ctx);
      }
    }
    if (name.equals("optMap") || name.equals("optFlatMap")) {
      OptionalValue o = optional(range);
      if (!o.present()) return o;
      bindings[0].execute(frame, ctx, o.value());
      try {
        Object mapped = children[1].execute(frame, ctx);
        if (mapped instanceof Unknown) return mapped;
        return name.equals("optMap") ? OptionalValue.of(mapped) : optional(mapped);
      } finally {
        bindings[0].clear(frame, ctx);
      }
    }
    Values.OwnedList values = HostCollections.range(range);
    boolean mapResult = name.equals("transformMap") || name.equals("transformMapEntry");
    boolean sorting = name.equals("sortBy");
    boolean mapping = name.equals("map") || name.startsWith("transform");
    boolean filtering = name.equals("filter");
    var mapped = mapResult ? HostCollections.<Object>map() : null;
    var sortKeys = sorting ? HostCollections.list() : null;
    long index = 0;
    var result =
        !countResult && !mapResult && (sorting || mapping || filtering)
            ? HostCollections.list()
            : null;
    long count = 0;
    Unknown unknown = null;
    CelException error = null;
    for (int position = 0; position < values.size(); position++) {
      Object item = values.get(position);
      ctx.tick();
      Object key = range instanceof List<?> && variables.length == 2 ? index : item;
      bindings[0].execute(frame, ctx, key);
      if (variables.length == 2)
        bindings[1].execute(
            frame, ctx, range instanceof Map<?, ?> m ? HostCollections.get(m, item) : item);
      index++;
      try {
        Object predicate = children[1].execute(frame, ctx);
        if (predicate instanceof Unknown u) {
          unknown = unknown == null ? u : unknown.merge(u);
          continue;
        }
        if (sorting) {
          HostCollections.add(sortKeys, predicate);
          HostCollections.add(result, item);
          continue;
        }
        if (mapping) {
          if (children.length == 3) {
            if (!(predicate instanceof Boolean b)) throw Values.overload(name);
            if (!b) continue;
            predicate = children[2].execute(frame, ctx);
            if (predicate instanceof Unknown u) {
              unknown = unknown == null ? u : unknown.merge(u);
              continue;
            }
          }
          if (name.equals("transformMap")) HostCollections.put(mapped, key, predicate);
          else if (name.equals("transformMapEntry")) {
            if (!(predicate instanceof Map<?, ?> entries)) throw Values.overload(name);
            HostCollections.mergeEntries(mapped, entries);
          } else if (countResult) count++;
          else HostCollections.add(result, predicate);
          continue;
        }
        if (!(predicate instanceof Boolean b)) throw Values.overload(name);
        if (name.equals("all") && !b) return false;
        if (name.equals("exists") && b) return true;
        if (b) {
          count++;
          if (filtering && !countResult) HostCollections.add(result, item);
        }
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        if (!name.equals("all") && !name.equals("exists")) throw e;
        error = e;
      } finally {
        clearBindings(frame, ctx);
      }
    }
    if (unknown != null) return unknown;
    if (error != null) throw error;
    if (countResult) return count;
    return switch (name) {
      case "all" -> true;
      case "exists" -> false;
      case "exists_one", "existsOne" -> count == 1;
      case "transformMap", "transformMapEntry" -> HostCollections.freezeMap(mapped);
      case "sortBy" -> ExtensionFunctions.sortBy(result, sortKeys, ctx);
      default -> HostCollections.freezeList(result);
    };
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private void clearBindings(VirtualFrame frame, EvalContext ctx) {
    for (int binding = bindings.length - 1; binding >= 0; binding--)
      bindings[binding].clear(frame, ctx);
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object list(VirtualFrame frame, EvalContext ctx) {
    var list = HostCollections.list();
    Unknown unknown = null;
    for (ExprNode child : children) {
      Object v = child.execute(frame, ctx);
      if (v instanceof Unknown u) unknown = unknown == null ? u : unknown.merge(u);
      if (child.optionalEntry && !(v instanceof Unknown)) {
        OptionalValue o = optional(v);
        if (!o.present()) continue;
        v = o.value();
      }
      HostCollections.add(list, v);
    }
    return unknown == null ? HostCollections.freezeList(list) : unknown;
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object map(VirtualFrame frame, EvalContext ctx) {
    var map = HostCollections.<Object>map();
    Unknown unknown = null;
    for (int i = 0; i < children.length; i += 2) {
      Object k = children[i].execute(frame, ctx), v = children[i + 1].execute(frame, ctx);
      if (k instanceof Unknown u) {
        unknown = unknown == null ? u : unknown.merge(u);
        continue;
      }
      if (v instanceof Unknown u) {
        unknown = unknown == null ? u : unknown.merge(u);
        continue;
      }
      if (children[i + 1].optionalEntry) {
        OptionalValue o = optional(v);
        if (!o.present()) continue;
        v = o.value();
      }
      if (!Values.validKey(k))
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "invalid map key");
      if (Values.key(map, k) != null)
        throw Values.error(CelException.Code.INVALID_ARGUMENT, "duplicate map key");
      HostCollections.put(map, k, v);
    }
    return unknown == null ? HostCollections.freezeMap(map) : unknown;
  }

  @com.oracle.truffle.api.nodes.ExplodeLoop
  private Object object(VirtualFrame frame, EvalContext ctx) {
    var fields = HostCollections.<String>map();
    String[] names = (String[]) value;
    for (int i = 0; i < children.length; i++) {
      Object v = children[i].execute(frame, ctx);
      if (children[i].optionalEntry && !(v instanceof Unknown)) {
        OptionalValue o = optional(v);
        if (!o.present()) continue;
        v = o.value();
      }
      if (v instanceof Unknown) return v;
      HostCollections.put(fields, names[i], v);
    }
    return construct(ctx, fields);
  }

  @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
  private Object construct(EvalContext ctx, Map<String, Object> fields) {
    try {
      Object constructed =
          ctx.environment.objectTypes.get(name).construct(Collections.unmodifiableMap(fields));
      if (constructed instanceof WellKnownTypes.Any any) {
        String type = any.typeUrl().substring(any.typeUrl().lastIndexOf('/') + 1);
        ObjectType adapter = ctx.environment.objectTypes.get(type);
        if (adapter == null)
          throw Values.error(CelException.Code.INVALID_ARGUMENT, "unregistered Any type: " + type);
        constructed = adapter.decode(any.bytes());
      }
      return constructed;
    } catch (ArithmeticException | java.time.DateTimeException e) {
      throw Values.error(CelException.Code.OVERFLOW, "protobuf value out of range");
    }
  }
}
