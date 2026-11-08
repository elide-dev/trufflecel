package dev.trufflecel;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;

/** Owned, immutable syntax trees, with navigation and an isolated mutable editor. */
public final class Ast {
  public enum Kind {
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

  /**
   * A structural expression. Zero IDs are assigned when a tree is frozen; positive IDs remain
   * stable. Object/macro/comprehension names use immutable string lists in {@code value}; call,
   * select and index values are receiver/optional booleans. Children are in evaluation order.
   */
  public record Node(
      long id,
      Kind kind,
      String name,
      Object value,
      List<Node> children,
      boolean optionalEntry,
      int offset,
      boolean extensionSelect,
      Node macroCall) {
    public Node {
      if (id < 0) throw new IllegalArgumentException("negative expression id");
      Objects.requireNonNull(kind);
      Objects.requireNonNull(name);
      children = List.copyOf(children);
      if (value instanceof List<?> names) {
        for (Object entry : names)
          if (!(entry instanceof String)) throw new IllegalArgumentException("expected names");
        value = List.copyOf(names);
      } else if (value != null
          && !(value instanceof Boolean
              || value instanceof Long
              || value instanceof Double
              || value instanceof String
              || value instanceof UInt
              || value instanceof Bytes
              || value instanceof CelNull
              || value instanceof CelType
              || value instanceof Instant
              || value instanceof Duration))
        throw new IllegalArgumentException("unsupported AST value");
    }

    public Node(
        long id,
        Kind kind,
        String name,
        Object value,
        List<Node> children,
        boolean optionalEntry,
        int offset) {
      this(id, kind, name, value, children, optionalEntry, offset, false, null);
    }

    public static Node literal(Object value) {
      Object scalar = Values.in(value);
      if (scalar instanceof List<?> || scalar instanceof Map<?, ?>)
        throw new IllegalArgumentException("use list/map AST factories for aggregate literals");
      return new Node(0, Kind.CONSTANT, "", scalar, List.of(), false, -1);
    }

    public static Node identifier(String name) {
      if (Objects.requireNonNull(name).isEmpty()) throw new IllegalArgumentException("empty name");
      return new Node(0, Kind.IDENT, name, null, List.of(), false, -1);
    }

    public static Node call(String function, Node... arguments) {
      return new Node(0, Kind.CALL, function, false, List.of(arguments), false, -1);
    }

    public static Node receiverCall(Node receiver, String function, Node... arguments) {
      var all = new ArrayList<Node>();
      all.add(receiver);
      all.addAll(List.of(arguments));
      return new Node(0, Kind.CALL, function, true, all, false, -1);
    }

    public static Node select(Node operand, String field, boolean optional) {
      return new Node(0, Kind.SELECT, field, optional, List.of(operand), false, -1);
    }

    public static Node index(Node operand, Node index, boolean optional) {
      return new Node(0, Kind.INDEX, "", optional, List.of(operand, index), false, -1);
    }

    public static Node list(Node... elements) {
      return new Node(0, Kind.LIST, "", null, List.of(elements), false, -1);
    }

    /** Alternating keys and values; optional-entry flags belong to values. */
    public static Node map(Node... entries) {
      return new Node(0, Kind.MAP, "", null, List.of(entries), false, -1);
    }

    public static Node object(String type, Map<String, Node> fields) {
      return new Node(
          0,
          Kind.OBJECT,
          type,
          List.copyOf(fields.keySet()),
          List.copyOf(fields.values()),
          false,
          -1);
    }

    public static Node conditional(Node condition, Node yes, Node no) {
      return new Node(0, Kind.CONDITIONAL, "", null, List.of(condition, yes, no), false, -1);
    }

    public static Node comprehension(
        String accumulator,
        String iterator,
        String iterator2,
        Node range,
        Node initial,
        Node condition,
        Node step,
        Node result) {
      return new Node(
          0,
          Kind.COMPREHENSION,
          "",
          List.of(accumulator, iterator, iterator2),
          List.of(range, initial, condition, step, result),
          false,
          -1);
    }

    public Node optional(boolean optional) {
      return new Node(
          id, kind, name, value, children, optional, offset, extensionSelect, macroCall);
    }

    /** Edits discard original macro syntax, which no longer describes the edited expansion. */
    public Node withChildren(List<Node> replacements) {
      return new Node(
          id, kind, name, value, replacements, optionalEntry, offset, extensionSelect, null);
    }
  }

  private final Node root;
  private final String source;

  private Ast(Node root, String source, int maxNodes, int maxDepth) {
    validate(root, maxNodes, maxDepth);
    var ids = new HashSet<Long>();
    long next = 1;
    for (Node node : preorder(root)) {
      if (node.id != 0 && !ids.add(node.id))
        throw new IllegalArgumentException("duplicate expression id");
      if (node.id == Long.MAX_VALUE) throw new IllegalArgumentException("expression id overflow");
      next = Math.max(next, node.id + 1);
    }
    this.root = assign(root, new long[] {next});
    this.source = source;
  }

  public static Ast of(Node root) {
    return new Ast(root, "", 100_000, 256);
  }

  /** Parse without type checking; configured syntax, expansion and structural limits apply. */
  public static Ast parse(Environment environment, String source) {
    Objects.requireNonNull(source);
    environment.checkSource(source);
    try {
      ExprNode tree = new Parser(source, environment).parse();
      environment.checkDepth(tree);
      return new Ast(snapshot(tree), source, environment.maxAstNodes, environment.maxDepth);
    } catch (CelException failure) {
      throw failure.locate(source);
    }
  }

  public Node root() {
    return root;
  }

  public String source() {
    return source;
  }

  public List<Node> nodes() {
    return preorder(root);
  }

  public Optional<Node> find(long id) {
    return nodes().stream().filter(node -> node.id == id).findFirst();
  }

  public String unparse() {
    return Unparser.unparse(this);
  }

  public Editor editor() {
    return new Editor(this);
  }

  /** Recheck edited trees; type metadata from a previous compilation is never trusted. */
  public PreparedProgram prepare(Environment environment) {
    return environment.prepare("", lower(root, environment), true);
  }

  public Program compile(Environment environment) {
    return prepare(environment).instantiate();
  }

  /** Editors are thread-confined; frozen snapshots share only immutable nodes. */
  public static final class Editor {
    private Node root;

    private Editor(Ast ast) {
      root = ast.root;
    }

    public Editor replace(long id, Node replacement) {
      Objects.requireNonNull(replacement);
      validate(replacement, 100_000, 256);
      boolean[] found = {false};
      Node edited =
          rewrite(
              root,
              node -> {
                if (node.id != id) return node;
                found[0] = true;
                return replacement;
              });
      if (!found[0]) throw new IllegalArgumentException("unknown expression id: " + id);
      validate(edited, 100_000, 256);
      root = edited;
      return this;
    }

    /** Bottom-up replacement, validating each completed edit before retaining it. */
    public Editor transform(UnaryOperator<Node> transform) {
      Node edited = rewrite(root, Objects.requireNonNull(transform));
      validate(edited, 100_000, 256);
      root = edited;
      return this;
    }

    public Ast freeze() {
      return Ast.of(root);
    }
  }

  private static Node rewrite(Node node, UnaryOperator<Node> transform) {
    var children = new ArrayList<Node>();
    boolean changed = false;
    for (Node child : node.children) {
      Node replacement = rewrite(child, transform);
      children.add(replacement);
      changed |= replacement != child;
    }
    return Objects.requireNonNull(transform.apply(changed ? node.withChildren(children) : node));
  }

  private static Node assign(Node node, long[] next) {
    if (node.id == 0 && next[0] == Long.MAX_VALUE)
      throw new IllegalArgumentException("expression id overflow");
    var children = node.children.stream().map(child -> assign(child, next)).toList();
    return new Node(
        node.id == 0 ? next[0]++ : node.id,
        node.kind,
        node.name,
        node.value,
        children,
        node.optionalEntry,
        node.offset,
        node.extensionSelect,
        node.macroCall);
  }

  static List<Node> preorder(Node root) {
    var result = new ArrayList<Node>();
    var pending = new ArrayDeque<Node>();
    pending.push(root);
    while (!pending.isEmpty()) {
      Node node = pending.pop();
      result.add(node);
      for (int i = node.children.size() - 1; i >= 0; i--) pending.push(node.children.get(i));
    }
    return List.copyOf(result);
  }

  static int validate(Node root, int maxNodes, int maxDepth) {
    record Pending(Node node, int depth) {}
    var pending = new ArrayDeque<Pending>();
    pending.push(new Pending(Objects.requireNonNull(root), 1));
    int count = 0;
    while (!pending.isEmpty()) {
      var item = pending.pop();
      if (++count > maxNodes || item.depth > maxDepth)
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "AST size/depth limit exceeded");
      Node node = item.node;
      int size = node.children.size();
      boolean valid =
          switch (node.kind) {
            case CONSTANT -> size == 0 && node.value != null && !(node.value instanceof List<?>);
            case IDENT -> size == 0 && !node.name.isEmpty();
            case BLOCK_INDEX -> size == 0;
            case SELECT -> size == 1 && node.value instanceof Boolean;
            case INDEX -> size == 2 && node.value instanceof Boolean;
            case CONDITIONAL -> size == 3;
            case MAP -> size % 2 == 0;
            case OBJECT -> node.value instanceof List<?> names && names.size() == size;
            case COMPREHENSION ->
                size == 5
                    && node.value instanceof List<?> names
                    && names.size() == 3
                    && !names.get(0).equals("")
                    && !names.get(1).equals("")
                    && !names.get(0).equals(names.get(1))
                    && !names.get(0).equals(names.get(2))
                    && !names.get(1).equals(names.get(2));
            case BLOCK -> size > 0;
            case CALL ->
                node.value instanceof Boolean && (!Boolean.TRUE.equals(node.value) || size > 0);
            case LIST -> true;
            case MACRO -> validMacro(node);
          };
      if (!valid) throw new IllegalArgumentException("invalid " + node.kind + " expression");
      if (node.kind == Kind.BLOCK_INDEX
          && !(node.value instanceof Long index && index >= 0 && index <= Integer.MAX_VALUE))
        throw new IllegalArgumentException("invalid block index");
      for (Node child : node.children) pending.push(new Pending(child, item.depth + 1));
      if (node.macroCall != null) pending.push(new Pending(node.macroCall, item.depth + 1));
    }
    return count;
  }

  private static boolean validMacro(Node node) {
    if (node.name.equals("has"))
      return node.children.size() == 1 && node.children.get(0).kind == Kind.SELECT;
    if (!(node.value instanceof List<?> names) || names.isEmpty() || names.size() > 2) return false;
    for (Object name : names) if (name.equals("") || ((String) name).contains(".")) return false;
    if (names.size() == 2 && names.get(0).equals(names.get(1))) return false;
    int children = node.children.size();
    return switch (node.name) {
      case "bind", "exists_one", "filter", "optMap", "optFlatMap", "sortBy" ->
          names.size() == 1 && children == 2;
      case "all", "exists", "existsOne" -> children == 2;
      case "map" -> names.size() == 1 && (children == 2 || children == 3);
      case "transformList", "transformMap", "transformMapEntry" ->
          names.size() == 2 && (children == 2 || children == 3);
      default -> false;
    };
  }

  static Node snapshot(ExprNode node) {
    Object value = node.value instanceof String[] names ? List.of(names) : node.value;
    return new Node(
        node.canonicalId,
        Kind.valueOf(node.kind.name()),
        node.name,
        value,
        Arrays.stream(node.children).map(Ast::snapshot).toList(),
        node.optionalEntry,
        node.offset,
        node.extensionSelect,
        node.macroCall);
  }

  static ExprNode lower(Node root, Environment environment) {
    return lower(root, environment, -1);
  }

  static ExprNode lower(Node root, Environment environment, int defaultOffset) {
    validate(root, environment.maxAstNodes, environment.maxDepth);
    return lower(root, defaultOffset);
  }

  // Only for snapshots that have already passed structural validation.
  static ExprNode restore(Node root) {
    return lower(root, -1);
  }

  private static ExprNode lower(Node node, int defaultOffset) {
    ExprNode[] children =
        node.children.stream().map(child -> lower(child, defaultOffset)).toArray(ExprNode[]::new);
    Object value = node.value instanceof List<?> names ? names.toArray(String[]::new) : node.value;
    ExprNode result =
        node.kind == Kind.COMPREHENSION
            ? new ComprehensionNode(
                (String[]) value, node.offset < 0 ? defaultOffset : node.offset, children)
            : new ExprNode(
                ExprNode.Kind.valueOf(node.kind.name()),
                node.name,
                value,
                node.offset < 0 ? defaultOffset : node.offset,
                children);
    result.canonicalId = node.id;
    result.optionalEntry = node.optionalEntry;
    result.extensionSelect = node.extensionSelect;
    result.macroCall = node.macroCall;
    result.errorOffset = node.offset;
    if (node.kind == Kind.MACRO && !node.name.equals("has")) {
      String[] names = (String[]) value;
      result.bindings = new WriteNode[names.length];
      for (int i = 0; i < names.length; i++)
        result.bindings[i] = new WriteNode(names[i], node.offset, node.offset);
    }
    return result;
  }
}
