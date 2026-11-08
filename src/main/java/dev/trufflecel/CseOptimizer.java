package dev.trufflecel;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Conservative lazy CSE. Host calls, object operations and binder scopes never coalesce. */
final class CseOptimizer {
  private static final Set<String> CALLS =
      Set.of(
          "+",
          "-",
          "*",
          "/",
          "%",
          "neg",
          "!",
          "<",
          "<=",
          ">",
          ">=",
          "&&",
          "||",
          "contains",
          "startsWith",
          "endsWith",
          "matches",
          "size");
  private final Environment environment;
  private final IdentityHashMap<ExprNode, ExprNode> originals = new IdentityHashMap<>();
  private boolean changed;

  private CseOptimizer(Environment environment) {
    this.environment = environment;
  }

  static ExprNode optimize(ExprNode input, Environment environment) {
    var optimizer = new CseOptimizer(environment);
    ExprNode copy = NodeUtil.cloneNode(input);
    optimizer.remember(input, copy);
    ExprNode result = optimizer.region(copy);
    if (!optimizer.changed) return input;
    try {
      environment.checkDepth(result);
      return result;
    } catch (CelException limit) {
      // Optimizing a valid source must not introduce a depth or node-limit rejection.
      return input;
    }
  }

  /** Standard cel.block/index export uses fresh IDs for synthetic blocks and slot templates. */
  static ExprNode export(ExprNode input, Environment environment) {
    ExprNode result = optimize(input, environment);
    clearSyntheticIds(result);
    return result;
  }

  private static void clearSyntheticIds(ExprNode node) {
    if (node instanceof Block) node.canonicalId = 0;
    for (ExprNode child : node.children) clearSyntheticIds(child);
  }

  private void remember(ExprNode original, ExprNode copy) {
    originals.put(copy, original);
    for (int i = 0; i < original.children.length; i++)
      remember(original.children[i], copy.children[i]);
  }

  private static boolean boundary(ExprNode node) {
    return node.kind == ExprNode.Kind.MACRO
        || node.kind == ExprNode.Kind.COMPREHENSION
        || node.kind == ExprNode.Kind.BLOCK;
  }

  private ExprNode region(ExprNode root) {
    // Explicit block indexes belong to their lexical owner; do not introduce a nearer block.
    // `has` inspects a selection node's shape and must not receive a wrapper in its place.
    if (root.kind == ExprNode.Kind.BLOCK
        || root.kind == ExprNode.Kind.MACRO && Set.of("has", "bind").contains(root.name))
      return root;
    if (boundary(root)) {
      for (int i = 0; i < root.children.length; i++) root.children[i] = region(root.children[i]);
      return root;
    }
    nestedRegions(root);
    var region = new Region();
    region.collect(root);
    ExprNode result = region.replace(root);
    if (region.slots.isEmpty()) return result;
    changed = true;
    var children = new ArrayList<>(region.slots);
    children.add(result);
    return new Block(root, originals.get(root), children.toArray(ExprNode[]::new));
  }

  private void nestedRegions(ExprNode node) {
    for (int i = 0; i < node.children.length; i++) {
      ExprNode child = node.children[i];
      if (boundary(child)) node.children[i] = region(child);
      else nestedRegions(child);
    }
  }

  private record Key(ExprNode.Kind kind, String name, Object value, List<Integer> children) {}

  private record Info(int key, int size, boolean candidate) {}

  private final class Region {
    private final Map<Key, Integer> intern = new HashMap<>();
    private final Map<Integer, Integer> counts = new HashMap<>();
    private final Map<Integer, Integer> indexes = new HashMap<>();
    private final IdentityHashMap<ExprNode, Info> infos = new IdentityHashMap<>();
    private final List<ExprNode> slots = new ArrayList<>();

    private Info collect(ExprNode node) {
      if (boundary(node)) return null;
      boolean pure = true;
      int size = 1;
      var keys = new ArrayList<Integer>();
      // Resolved identifiers/constants can retain parsed children that never execute.
      boolean leaf = node.kind == ExprNode.Kind.IDENT || node.kind == ExprNode.Kind.CONSTANT;
      if (!leaf) {
        for (ExprNode child : node.children) {
          Info info = collect(child);
          if (info == null) pure = false;
          else {
            size += info.size;
            keys.add(info.key);
          }
        }
      }
      pure &=
          switch (node.kind) {
            case CONSTANT -> scalar(node.value);
            case IDENT -> true;
            case CALL -> CALLS.contains(node.name) && !environment.functions.containsKey(node.name);
            case CONDITIONAL -> true;
            default -> false;
          };
      if (!pure) return null;
      Key key = new Key(node.kind, node.name, node.value, List.copyOf(keys));
      int id = intern.computeIfAbsent(key, unused -> intern.size());
      boolean candidate = !leaf && node.fold == null && size >= 3;
      if (candidate) counts.merge(id, 1, Integer::sum);
      var info = new Info(id, size, candidate);
      infos.put(node, info);
      return info;
    }

    private ExprNode replace(ExprNode node) {
      if (boundary(node)) return node;
      Info info = infos.get(node);
      if (info != null && info.candidate && counts.get(info.key) > 1) {
        Integer index = indexes.get(info.key);
        if (index == null) {
          index = slots.size();
          indexes.put(info.key, index);
          ExprNode initializer = NodeUtil.cloneNode(node);
          clearIds(initializer);
          slots.add(initializer);
        }
        return new Index(node, index);
      }
      for (int i = 0; i < node.children.length; i++) node.children[i] = replace(node.children[i]);
      return node;
    }
  }

  private static boolean scalar(Object value) {
    return value instanceof Long
        || value instanceof Double
        || value instanceof Boolean
        || value instanceof String
        || value instanceof UInt
        || value instanceof Bytes
        || value instanceof Instant
        || value instanceof Duration
        || value instanceof CelType
        || value == CelNull.INSTANCE;
  }

  private static void clearIds(ExprNode node) {
    node.canonicalId = 0;
    for (ExprNode child : node.children) clearIds(child);
  }

  /** Administrative nodes charge no extra CEL operations; cache hits replay the original cost. */
  private static final class Block extends ExprNode {
    private final ExprNode original;

    Block(ExprNode root, ExprNode original, ExprNode[] children) {
      super(Kind.BLOCK, "cel.block", null, root.offset, children);
      this.original = original;
      checkedType = root.checkedType;
      optionalEntry = root.optionalEntry;
      copyLocation(root);
      sourceRange(root.sourceStart, root.sourceEnd);
    }

    @Override
    ExprNode restoreTrace() {
      return NodeUtil.cloneNode(original).restoreTrace();
    }

    @Override
    Object doExecute(VirtualFrame frame, EvalContext context) {
      EvalContext captured = context.fork();
      captured.block = new BlockScope(children, null, captured, true);
      EvalContext body = context.fork();
      body.block = captured.block;
      return children[children.length - 1].execute(frame, body);
    }
  }

  private static final class Index extends ExprNode {
    @Child private ExprNode original;

    Index(ExprNode original, int index) {
      super(Kind.BLOCK_INDEX, "cel.index", (long) index, original.offset);
      this.original = original;
      checkedType = original.checkedType;
      macroCall = original.macroCall;
      optionalEntry = original.optionalEntry;
      copyLocation(original);
      sourceRange(original.sourceStart, original.sourceEnd);
    }

    @Override
    void resolveLazyBindings(Map<String, ExprNode> owners) {
      original.resolveLazyBindings(owners);
    }

    @Override
    void resolveLocals(ArrayList<String> locals, FrameDescriptor descriptor) {
      original.resolveLocals(locals, descriptor);
    }

    @Override
    Object doExecute(VirtualFrame frame, EvalContext context) {
      return context.block.read(original, Math.toIntExact((Long) value), frame);
    }
  }
}
