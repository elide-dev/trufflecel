package dev.trufflecel;

import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.*;

/**
 * Plan-time validation and constant folding. Every program is analyzed the same way: literal
 * validators run, constant regular expressions are compiled into their call nodes, and pure closed
 * subtrees are evaluated once with a counting budget. A subtree that always produces the same value
 * is recorded as a {@link Fold}; one that always fails is recorded with the exception it raises, so
 * evaluation reports the same category at the same budget cost without doing the work again.
 *
 * <p>Only the reporting policy varies. With {@link Environment.Validation#DEFERRED} the folds stay
 * silent and evaluation semantics are unchanged. With {@link Environment.Validation#STRICT} every
 * failure that occurs on every evaluation path, and every validator finding, is reported from
 * {@code compile}; laziness ({@code &&}, {@code ||}, {@code ?:}, macro bodies, optional fallbacks,
 * lazy block slots) and unknown precedence (a sibling that may be unknown wins over an error) are
 * respected, so a reported failure is one that no activation can avoid.
 *
 * <p>Analysis is one allocation-free traversal that records each node's purity and the depth of the
 * innermost binder any identifier in its subtree refers to; a subtree is closed when every
 * identifier it reads is bound inside it. The folding traversal runs only when some closed pure
 * subtree or host function call exists, so programs with nothing to fold pay almost nothing.
 */
final class Optimizer {
  /** A folded subtree: its value or its certain failure, and the budget the subtree consumed. */
  record Fold(Object value, CelException error, long cost) {}

  // Folded aggregates live in the AST (and the image heap), so their size is capped separately
  // from the evaluation budget that bounds the work of producing them.
  static final int MAX_WEIGHT = 4096;

  // Packed per-node analysis: bit 0 is purity, the rest is the binding depth plus two, so that an
  // unbound identifier (-1) and the "no identifiers" sentinel both fit.
  private static final int NO_IDENTIFIERS = 1 << 28, UNBOUND = -1;

  // Names with standard or extension overloads dispatch before a host binding of the same name,
  // so a static argument check against the host declaration would not describe evaluation. The
  // Checker mirrors this set for its single-global host fast path.
  static final Set<String> STANDARD =
      Set.of(
          "!",
          "neg",
          "hasValue",
          "value",
          "optional.of",
          "optional.ofNonZeroValue",
          "optional.none",
          "dyn",
          "type",
          "size",
          "int",
          "uint",
          "double",
          "bool",
          "string",
          "bytes",
          "timestamp",
          "duration",
          "==",
          "!=",
          "<",
          "<=",
          ">",
          ">=",
          "+",
          "-",
          "*",
          "/",
          "%",
          "in",
          "contains",
          "startsWith",
          "endsWith",
          "matches");

  private static final Set<String> NESTING =
      Set.of(
          "all",
          "exists",
          "exists_one",
          "existsOne",
          "map",
          "filter",
          "transformList",
          "transformMap",
          "transformMapEntry",
          "sortBy",
          "bind");

  private final Environment env;
  private final String source;
  private final boolean strict;
  private List<CelException.Issue> issues;
  // Validator findings already issued by analyze(), keyed by code and message, so a failure
  // captured on a foldable ancestor's Fold is not re-reported at the ancestor's offset.
  private Set<String> reported;
  // Lexical scope of binder variables and the depth that bound them; only binders allocate it.
  private String[] scopeNames;
  private int[] scopeDepths;
  private int scopeSize;
  // Results of the most recent analyze call, read by the caller before analyzing a sibling.
  private int minDepth;
  private boolean pure;
  // Whether any closed pure subtree or host function call makes the folding traversal necessary.
  private boolean candidates;
  // Total plan-time evaluation work, so that nested retries cannot multiply the budget.
  private long allowance;

  private Optimizer(Environment env, String source) {
    this.env = env;
    this.source = source;
    strict = env.validation == Environment.Validation.STRICT;
    allowance = env.budget;
  }

  /** Validate and fold a checked tree in place; the root may be replaced. */
  static ExprNode optimize(ExprNode root, Environment env, String source) {
    var optimizer = new Optimizer(env, source);
    ExprNode result = optimizer.analyze(root, 0, 0);
    if (optimizer.candidates) optimizer.fold(result, 0, true);
    if (optimizer.issues != null) {
      optimizer.issues.sort(Comparator.comparingInt(CelException.Issue::offset));
      throw new CelException(optimizer.issues);
    }
    return result;
  }

  /** Policy conditions can be hidden under a folded parent; share one bounded analysis budget. */
  static Boolean constantBoolean(ExprNode node, EvalContext context) {
    if (node.kind == ExprNode.Kind.CONSTANT && node.value instanceof Boolean value) return value;
    if (node.fold != null && node.fold.value() instanceof Boolean value) return value;
    if ((node.analysis & 1) == 0 || !context.canCharge(1)) return null;
    var free = new HashSet<String>();
    node.free(Set.of(), free);
    if (!free.isEmpty()) return null;
    node.resolveLocals(new ArrayList<>(), null);
    node.resolveLazyBindings(Map.of());
    try {
      Object value = node.execute(null, context);
      return value instanceof Boolean result ? result : null;
    } catch (CelException error) {
      // A failure (including exhausted analysis budget) is not a known Boolean result.
      return null;
    }
  }

  /** Replace annotated subtrees with their prebuilt results; used for uninstrumented targets. */
  static ExprNode rewrite(ExprNode node) {
    if (node.fold != null) return new Folded(node);
    for (int i = 0; i < node.children.length; i++) node.children[i] = rewrite(node.children[i]);
    return node;
  }

  private void issue(CelException.Code code, String message, ExprNode node) {
    if (issues == null) issues = new ArrayList<>();
    if (issues.size() < CelException.MAX_ISSUES)
      issues.add(node.issue(code, message).locate(source));
  }

  // Qualified names resolved to identifiers or constants keep parsed children that never execute.
  private static boolean leaf(ExprNode n) {
    return n.kind == ExprNode.Kind.IDENT || n.kind == ExprNode.Kind.CONSTANT;
  }

  private static int pack(int minDepth, boolean pure) {
    return (minDepth + 2) << 1 | (pure ? 1 : 0);
  }

  private static boolean foldable(ExprNode n, int depth) {
    return (n.analysis & 1) != 0 && (n.analysis >>> 1) - 2 > depth;
  }

  /**
   * Validators, analysis and constant-pattern precompilation in one post-order traversal. {@code
   * depth} counts the binders enclosing {@code n}; the result is left in {@link #minDepth} and
   * {@link #pure} and packed into the node.
   */
  private ExprNode analyze(ExprNode n, int depth, int nesting) {
    if (n.kind == ExprNode.Kind.IDENT) return record(n, binding(n.name), true, depth);
    if (n.kind == ExprNode.Kind.CONSTANT)
      return record(n, NO_IDENTIFIERS, !(n.value instanceof Unknown), depth);
    // Ordinary calls introduce no binders or aggregate validators. With no host bindings or
    // strict literal checks, only matches needs preparation beyond its children's analysis.
    if (n.kind == ExprNode.Kind.CALL
        && !strict
        && env.functions.isEmpty()
        && !n.name.equals("matches")) {
      int min = children(n, 0, n.children.length, depth, nesting);
      boolean clean = pure;
      if (clean && min > depth) candidates = true;
      return record(n, min, clean, depth);
    }
    if (n.kind == ExprNode.Kind.COMPREHENSION
        || n.kind == ExprNode.Kind.MACRO && NESTING.contains(n.name)) {
      nesting++;
      if (env.comprehensionNesting > 0 && nesting > env.comprehensionNesting)
        issue(CelException.Code.RESOURCE_EXHAUSTED, "comprehension nesting limit exceeded", n);
    }
    if (env.homogeneousLiterals) {
      if (n.kind == ExprNode.Kind.LIST) uniform(n.children, 0, 1, "list element");
      if (n.kind == ExprNode.Kind.MAP) {
        uniform(n.children, 0, 2, "map key");
        uniform(n.children, 1, 2, "map value");
      }
    }
    int min = NO_IDENTIFIERS;
    boolean clean = true;
    switch (n.kind) {
      case BLOCK_INDEX -> clean = false;
      case MACRO -> {
        if (n.name.equals("has")) {
          min = children(n, 0, 1, depth, nesting);
          clean = pure;
        } else {
          min = children(n, 0, 1, depth, nesting);
          clean = pure;
          String[] variables = (String[]) n.value;
          int outer = scopeSize;
          for (String variable : variables) bind(variable, depth + 1);
          min = Math.min(min, children(n, 1, n.children.length, depth + 1, nesting));
          clean &= pure;
          scopeSize = outer;
        }
      }
      case COMPREHENSION -> {
        String[] variables = (String[]) n.value;
        min = children(n, 0, 2, depth, nesting);
        clean = pure;
        int outer = scopeSize;
        for (String variable : variables) if (!variable.isEmpty()) bind(variable, depth + 1);
        min = Math.min(min, children(n, 2, 4, depth + 1, nesting));
        clean &= pure;
        scopeSize = outer;
        bind(variables[0], depth + 1);
        min = Math.min(min, children(n, 4, 5, depth + 1, nesting));
        clean &= pure;
        scopeSize = outer;
      }
      default -> {
        min = children(n, 0, n.children.length, depth, nesting);
        clean = pure;
      }
    }
    switch (n.kind) {
      case BLOCK, OBJECT -> clean = false;
      case SELECT -> clean &= !n.extensionSelect && n.fieldPlan == null;
      case CALL -> {
        boolean host = env.functions.containsKey(n.name);
        if (host) candidates = true;
        clean &= !host;
      }
      default -> {}
    }
    if (strict && literalConversion(n)) {
      try {
        Functions.apply(n.name, new Object[] {n.children[0].value}, env);
      } catch (CelException e) {
        issue(e.code(), e.getMessage(), n);
        remember(e);
      }
    }
    if (n.kind == ExprNode.Kind.CALL
        && !n.customOverloads
        && n.name.equals("matches")
        && n.children.length == 2
        && n.children[1].kind == ExprNode.Kind.CONSTANT
        && n.children[1].value instanceof String pattern) {
      try {
        n = new MatchesNode(n, Regex.compile(pattern, env), null);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED)
          throw new CelException(
              List.of(n.children[1].issue(e.code(), e.issues().get(0).message())));
        if (strict) {
          issue(e.code(), e.getMessage(), n.children[1]);
          remember(e);
        }
        n = new MatchesNode(n, null, e);
      }
    }
    if (clean && min > depth) candidates = true;
    return record(n, min, clean, depth);
  }

  /**
   * Analyze children {@code [from, to)} at {@code depth}; leaves the conjunction in {@link #pure}.
   */
  private int children(ExprNode n, int from, int to, int depth, int nesting) {
    int min = NO_IDENTIFIERS;
    boolean clean = true;
    for (int i = from; i < to; i++) {
      n.children[i] = analyze(n.children[i], depth, nesting);
      min = Math.min(min, minDepth);
      clean &= pure;
    }
    pure = clean;
    return min;
  }

  private ExprNode record(ExprNode n, int min, boolean clean, int depth) {
    n.analysis = pack(min, clean);
    minDepth = min;
    pure = clean;
    return n;
  }

  private void bind(String name, int depth) {
    if (scopeNames == null) {
      scopeNames = new String[8];
      scopeDepths = new int[8];
    } else if (scopeSize == scopeNames.length) {
      scopeNames = Arrays.copyOf(scopeNames, scopeSize * 2);
      scopeDepths = Arrays.copyOf(scopeDepths, scopeSize * 2);
    }
    scopeNames[scopeSize] = name;
    scopeDepths[scopeSize++] = depth;
  }

  /** The depth of the innermost binder of {@code name}, or {@link #UNBOUND} for a free variable. */
  private int binding(String name) {
    for (int i = scopeSize - 1; i >= 0; i--) if (scopeNames[i].equals(name)) return scopeDepths[i];
    return UNBOUND;
  }

  private void uniform(ExprNode[] children, int start, int step, String what) {
    CelType expected = null;
    for (int i = start; i < children.length; i += step) {
      ExprNode child = children[i];
      // Every aggregate entry was type checked, under either policy, before validation runs.
      CelType type = child.checkedType.finish();
      if (child.optionalEntry) type = Checker.parameter(type, 0);
      if (type.isDyn()) continue;
      if (expected == null) expected = type;
      else if (!expected.equals(type))
        issue(
            CelException.Code.TYPE,
            "heterogeneous " + what + ": expected " + expected + ", found " + type,
            child);
    }
  }

  // Fold the outermost closed subtrees; report certain failures on strictly evaluated paths.
  private void fold(ExprNode n, int depth, boolean strictPath) {
    if (leaf(n)) return;
    if (foldable(n, depth) && attempt(n)) {
      if (n.fold.error() != null) report(n, strictPath);
      return;
    }
    for (int i = 0; i < n.children.length; i++)
      fold(n.children[i], childDepth(n, i, depth), strictPath && strictPosition(n, i, depth));
    if (n.kind == ExprNode.Kind.CALL && n.children.length > 0) hostOverload(n, strictPath);
  }

  /** Binder bodies sit one binder deeper than the binder's range ({@code has} has only one). */
  private static int childDepth(ExprNode n, int i, int depth) {
    return switch (n.kind) {
      case MACRO -> i == 0 ? depth : depth + 1;
      case COMPREHENSION -> i < 2 ? depth : depth + 1;
      default -> depth;
    };
  }

  private boolean attempt(ExprNode n) {
    if (allowance <= 0) return false;
    // Each closed subtree starts with an empty activation; lexical slot numbers must be
    // relative to that subtree rather than to any surrounding source comprehension.
    n.resolveLocals(new ArrayList<>(), null);
    n.resolveLazyBindings(Map.of());
    var context = new EvalContext.Scoped(env, Map.of());
    Object value = null;
    CelException error = null;
    try {
      value = n.execute(null, context);
    } catch (CelException e) {
      if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) {
        allowance -= context.charged();
        return false;
      }
      error = e;
    }
    long cost = context.charged();
    allowance -= cost;
    if (error == null && weight(value) > MAX_WEIGHT) return false;
    n.fold = new Fold(value, error, cost);
    return true;
  }

  private static long weight(Object value) {
    if (value instanceof String s) return 1 + s.length() / 16;
    if (value instanceof Bytes b) return 1 + b.size() / 16;
    if (value instanceof OptionalValue o) return 1 + (o.present() ? weight(o.value()) : 0);
    long total = 1;
    if (value instanceof List<?> list) for (Object item : list) total += weight(item);
    if (value instanceof Map<?, ?> map)
      for (var entry : map.entrySet()) total += weight(entry.getKey()) + weight(entry.getValue());
    return total;
  }

  private void report(ExprNode n, boolean strictPath) {
    if (!strict) return;
    CelException error = n.fold.error();
    // The literal validators already reported this pattern or conversion. The folded node may be
    // the defect itself (a flat matches/timestamp/duration call), or a foldable ancestor (an
    // all-constant list, map or select, or a matches call whose receiver threw) that captured the
    // inner node's fresh exception on its own Fold. Dedupe the ancestor by code and message,
    // since the fold-time exception is a fresh instance with no shared identity.
    if (n instanceof MatchesNode matches && matches.error == error
        || literalConversion(n)
        || alreadyReported(error)) return;
    if (strictPath) issue(error.code(), error.issues().get(0).message(), n);
  }

  /** {@code timestamp('...')} and {@code duration('...')} are validated regardless of position. */
  private static boolean literalConversion(ExprNode n) {
    return n.kind == ExprNode.Kind.CALL
        && (n.name.equals("timestamp") || n.name.equals("duration"))
        && n.children.length == 1
        && n.children[0].kind == ExprNode.Kind.CONSTANT
        && n.children[0].value instanceof String;
  }

  /** Remember a validator finding so a captured fold failure with the same defect is suppressed. */
  private void remember(CelException error) {
    if (reported == null) reported = new HashSet<>();
    reported.add(validatorKey(error));
  }

  /** Whether {@code analyze} already issued a validator finding with this code and message. */
  private boolean alreadyReported(CelException error) {
    return reported != null && reported.contains(validatorKey(error));
  }

  /** A stable key for a validator finding; the fold-time exception is a fresh instance. */
  private static String validatorKey(CelException error) {
    return error.code().name() + "\n" + error.issues().get(0).message();
  }

  /** Whether an error in child {@code i} propagates from {@code n} whatever its siblings yield. */
  private boolean strictPosition(ExprNode n, int i, int depth) {
    return switch (n.kind) {
      case CALL -> {
        boolean nonStrict = false;
        for (var signature : env.overloads.getOrDefault(n.name, List.of()))
          nonStrict |= signature.nonStrict();
        yield !nonStrict
            && switch (n.name) {
              case "&&", "||", "@not_strictly_false" -> false;
              case "or", "orValue" -> i == 0;
              default -> siblingsConstant(n, i, depth);
            };
      }
      case CONDITIONAL -> i == 0;
      case MACRO -> n.name.equals("bind") ? i == 1 : n.name.equals("has") || i == 0;
      case COMPREHENSION -> i != 2 && i != 3;
      case BLOCK -> i == n.children.length - 1;
      default -> true;
    };
  }

  // Call arguments are evaluated together and an unknown sibling wins over an error.
  private static boolean siblingsConstant(ExprNode n, int i, int depth) {
    for (int j = 0; j < n.children.length; j++)
      if (j != i && !foldable(n.children[j], depth)) return false;
    return true;
  }

  /** A host function whose constant arguments no declared signature accepts always fails. */
  private void hostOverload(ExprNode n, boolean strictPath) {
    Environment.FunctionBinding binding = env.functions.get(n.name);
    if (binding == null
        || STANDARD.contains(n.name)
        || n.name.startsWith("get")
        || ExtensionFunctions.supports(n.name)) return;
    long cost = 1;
    var types = new ArrayList<CelType>();
    for (ExprNode child : n.children) {
      Object value;
      if (child.fold != null) {
        if (child.fold.error() != null) return;
        value = child.fold.value();
        cost += child.fold.cost();
      } else if (child.kind == ExprNode.Kind.CONSTANT && !(child.value instanceof Unknown)) {
        value = child.value;
        cost++;
      } else return;
      types.add(Values.type(value));
    }
    boolean accepted = false;
    for (var signature : env.overloads.get(n.name))
      accepted |= FunctionOverloads.matches(signature, Boolean.TRUE.equals(n.value), types);
    if (accepted) return;
    n.fold = new Fold(null, Values.overload(n.name), cost);
    report(n, strictPath);
  }

  /** A constant subtree replaced by its value or its certain failure at the original cost. */
  static final class Folded extends ExprNode {
    // This discarded source subtree is immutable and is not a Truffle child of the fast target.
    private final ExprNode original;
    private final long cost;
    private final CelException error;

    Folded(ExprNode original) {
      super(Kind.CONSTANT, "", original.fold.value(), original.offset);
      sourceRange(original.sourceStart, original.sourceEnd);
      copyLocation(original);
      optionalEntry = original.optionalEntry;
      statement = original.statement;
      checkedType = original.checkedType;
      this.original = original;
      cost = original.fold.cost();
      error = original.fold.error();
    }

    @Override
    void finishPlanning(ExprNode owner) {
      super.finishPlanning(owner);
      // Created after template cloning, before this source copy can be shared by lazy targets.
      original.finishPlanning(null);
    }

    @Override
    ExprNode restoreTrace() {
      return com.oracle.truffle.api.nodes.NodeUtil.cloneNode(original).restoreTrace();
    }

    @Override
    Object doExecute(VirtualFrame frame, EvalContext ctx) {
      ctx.charge(cost);
      // A CEL error is ordinary compiled control flow; the prebuilt exception needs no boundary.
      if (error != null) throw error;
      return value;
    }
  }

  /** {@code matches} with a constant pattern compiled once; the operands are still evaluated. */
  static final class MatchesNode extends ExprNode {
    @Child private Regex.MatchNode matcher;
    private final CelException error;

    MatchesNode(ExprNode original, Regex.Compiled pattern, CelException error) {
      super(Kind.CALL, original.name, original.value, original.offset, original.children);
      sourceRange(original.sourceStart, original.sourceEnd);
      copyLocation(original);
      optionalEntry = original.optionalEntry;
      statement = original.statement;
      checkedType = original.checkedType;
      canonicalId = original.canonicalId;
      this.matcher = pattern == null ? null : new Regex.MatchNode(pattern);
      this.error =
          error == null
              ? null
              : error.at(errorOffset, canonicalId, errorLine, errorColumn, errorOffsetEncoding);
    }

    @Override
    Object doExecute(VirtualFrame frame, EvalContext ctx) {
      ctx.tick();
      Object text = null;
      CelException failure = null;
      try {
        text = children[0].execute(frame, ctx);
      } catch (CelException e) {
        if (e.code() == CelException.Code.RESOURCE_EXHAUSTED) throw e;
        failure = e;
      }
      // The pattern literal still consumes its budget; it can neither fail nor be unknown.
      children[1].execute(frame, ctx);
      if (text instanceof Unknown) return text;
      if (failure != null) throw failure;
      if (!(text instanceof String s)) throw Values.overload("matches");
      if (error != null) throw error;
      return matcher.find(s);
    }
  }
}
