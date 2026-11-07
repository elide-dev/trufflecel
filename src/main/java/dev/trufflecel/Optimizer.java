package dev.trufflecel;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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
 */
final class Optimizer {
  /** A folded subtree: its value or its certain failure, and the budget the subtree consumed. */
  record Fold(Object value, CelException error, long cost) {}

  // Folded aggregates live in the AST (and the image heap), so their size is capped separately
  // from the evaluation budget that bounds the work of producing them.
  static final int MAX_WEIGHT = 4096;

  // Names with standard or extension overloads dispatch before a host binding of the same name,
  // so a static argument check against the host declaration would not describe evaluation.
  private static final Set<String> STANDARD =
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
  private final Map<ExprNode, Set<String>> free = new IdentityHashMap<>();
  private final Map<ExprNode, Boolean> pure = new IdentityHashMap<>();
  private final List<CelException.Issue> issues = new ArrayList<>();
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
    ExprNode result = optimizer.validate(root, 0);
    optimizer.analyze(result);
    optimizer.fold(result, true);
    optimizer.finish();
    return result;
  }

  /** Replace annotated subtrees with their prebuilt results; used for uninstrumented targets. */
  static ExprNode rewrite(ExprNode node) {
    if (node.fold != null) return new Folded(node);
    for (int i = 0; i < node.children.length; i++) node.children[i] = rewrite(node.children[i]);
    return node;
  }

  private void finish() {
    if (issues.isEmpty()) return;
    issues.sort(Comparator.comparingInt(CelException.Issue::offset));
    throw new CelException(issues);
  }

  private void issue(CelException.Code code, String message, int offset) {
    issues.add(new CelException.Issue(code, message, offset, -1, -1).locate(source));
  }

  // Qualified names resolved to identifiers or constants keep parsed children that never execute.
  private static boolean leaf(ExprNode n) {
    return n.kind == ExprNode.Kind.IDENT || n.kind == ExprNode.Kind.CONSTANT;
  }

  // Validators: comprehension nesting, homogeneous literals and constant regular expressions.
  private ExprNode validate(ExprNode n, int nesting) {
    if (leaf(n)) return n;
    if (n.kind == ExprNode.Kind.COMPREHENSION
        || n.kind == ExprNode.Kind.MACRO && NESTING.contains(n.name)) {
      nesting++;
      if (env.comprehensionNesting > 0 && nesting > env.comprehensionNesting)
        issue(
            CelException.Code.RESOURCE_EXHAUSTED, "comprehension nesting limit exceeded", n.offset);
    }
    if (env.homogeneousLiterals) {
      if (n.kind == ExprNode.Kind.LIST) uniform(n.children, 0, 1, "list element");
      if (n.kind == ExprNode.Kind.MAP) {
        uniform(n.children, 0, 2, "map key");
        uniform(n.children, 1, 2, "map value");
      }
    }
    for (int i = 0; i < n.children.length; i++) n.children[i] = validate(n.children[i], nesting);
    if (strict && literalConversion(n)) {
      try {
        Functions.apply(n.name, new Object[] {n.children[0].value}, env);
      } catch (CelException e) {
        issue(e.code(), e.getMessage(), n.offset);
      }
    }
    if (n.kind == ExprNode.Kind.CALL
        && n.name.equals("matches")
        && n.children.length == 2
        && n.children[1].kind == ExprNode.Kind.CONSTANT
        && n.children[1].value instanceof String pattern) {
      try {
        return new MatchesNode(n, Regex.compile(pattern), null);
      } catch (CelException e) {
        if (strict) issue(e.code(), e.getMessage(), n.children[1].offset);
        return new MatchesNode(n, null, e);
      }
    }
    return n;
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
            child.offset);
    }
  }

  // Purity and free identifiers, so that only closed, side-effect-free subtrees are evaluated.
  private void analyze(ExprNode n) {
    var names = new HashSet<String>();
    boolean clean;
    switch (n.kind) {
      case CONSTANT -> clean = !(n.value instanceof Unknown);
      case IDENT -> {
        names.add(n.name);
        clean = true;
      }
      case BLOCK_INDEX -> clean = false;
      case BLOCK, OBJECT -> clean = children(n, names) && false;
      case SELECT -> clean = children(n, names) && !n.extensionSelect && n.fieldPlan == null;
      case CALL -> clean = children(n, names) && !env.functions.containsKey(n.name);
      case MACRO -> {
        if (n.name.equals("has")) clean = children(n, names);
        else {
          analyze(n.children[0]);
          names.addAll(free.get(n.children[0]));
          clean = pure.get(n.children[0]);
          for (int i = 1; i < n.children.length; i++)
            clean &= scoped(n.children[i], (String[]) n.value, names);
        }
      }
      case COMPREHENSION -> {
        String[] variables = (String[]) n.value;
        clean = true;
        for (int i = 0; i < 2; i++) {
          analyze(n.children[i]);
          names.addAll(free.get(n.children[i]));
          clean &= pure.get(n.children[i]);
        }
        clean &= scoped(n.children[2], variables, names);
        clean &= scoped(n.children[3], variables, names);
        clean &= scoped(n.children[4], new String[] {variables[0]}, names);
      }
      default -> clean = children(n, names);
    }
    free.put(n, names);
    pure.put(n, clean);
  }

  private boolean children(ExprNode n, Set<String> names) {
    boolean clean = true;
    for (ExprNode child : n.children) {
      analyze(child);
      names.addAll(free.get(child));
      clean &= pure.get(child);
    }
    return clean;
  }

  private boolean scoped(ExprNode body, String[] bound, Set<String> names) {
    analyze(body);
    var inner = new HashSet<>(free.get(body));
    inner.removeAll(Arrays.asList(bound));
    names.addAll(inner);
    return pure.get(body);
  }

  private boolean foldable(ExprNode n) {
    return pure.get(n) && free.get(n).isEmpty();
  }

  // Fold the outermost closed subtrees; report certain failures on strictly evaluated paths.
  private void fold(ExprNode n, boolean strictPath) {
    if (leaf(n)) return;
    if (foldable(n) && attempt(n)) {
      if (n.fold.error() != null) report(n, strictPath);
      return;
    }
    for (int i = 0; i < n.children.length; i++)
      fold(n.children[i], strictPath && strictPosition(n, i));
    if (n.kind == ExprNode.Kind.CALL && n.children.length > 0) hostOverload(n, strictPath);
  }

  private boolean attempt(ExprNode n) {
    if (allowance <= 0) return false;
    var context = new EvalContext(env, Map.of());
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
    // The literal validators already reported this pattern or conversion.
    if (n instanceof MatchesNode matches && matches.error == error || literalConversion(n)) return;
    if (strictPath) issue(error.code(), error.getMessage(), n.offset);
  }

  /** {@code timestamp('...')} and {@code duration('...')} are validated regardless of position. */
  private static boolean literalConversion(ExprNode n) {
    return n.kind == ExprNode.Kind.CALL
        && (n.name.equals("timestamp") || n.name.equals("duration"))
        && n.children.length == 1
        && n.children[0].kind == ExprNode.Kind.CONSTANT
        && n.children[0].value instanceof String;
  }

  /** Whether an error in child {@code i} propagates from {@code n} whatever its siblings yield. */
  private boolean strictPosition(ExprNode n, int i) {
    return switch (n.kind) {
      case CALL ->
          switch (n.name) {
            case "&&", "||", "@not_strictly_false" -> false;
            case "or", "orValue" -> i == 0;
            default -> siblingsConstant(n, i);
          };
      case CONDITIONAL -> i == 0;
      case MACRO -> n.name.equals("has") || n.name.equals("bind") || i == 0;
      case COMPREHENSION -> i != 2 && i != 3;
      case BLOCK -> i == n.children.length - 1;
      default -> true;
    };
  }

  // Call arguments are evaluated together and an unknown sibling wins over an error.
  private boolean siblingsConstant(ExprNode n, int i) {
    for (int j = 0; j < n.children.length; j++)
      if (j != i && !foldable(n.children[j])) return false;
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
    boolean accepted = binding.arguments().size() == types.size();
    for (int i = 0; accepted && i < types.size(); i++)
      accepted = binding.arguments().get(i).accepts(types.get(i));
    if (accepted) return;
    n.fold = new Fold(null, Values.overload(n.name), cost);
    report(n, strictPath);
  }

  /** A constant subtree replaced by its value or its certain failure at the original cost. */
  static final class Folded extends ExprNode {
    private final long cost;
    private final CelException error;

    Folded(ExprNode original) {
      super(Kind.CONSTANT, "", original.fold.value(), original.offset);
      sourceRange(original.sourceStart, original.sourceEnd);
      optionalEntry = original.optionalEntry;
      statement = original.statement;
      checkedType = original.checkedType;
      cost = original.fold.cost();
      error = original.fold.error();
    }

    @Override
    Object execute(VirtualFrame frame, EvalContext ctx) {
      ctx.charge(cost);
      if (error != null) raise(error);
      return value;
    }
  }

  @TruffleBoundary
  private static void raise(CelException error) {
    throw error;
  }

  /** {@code matches} with a constant pattern compiled once; the operands are still evaluated. */
  static final class MatchesNode extends ExprNode {
    private final Regex.Compiled pattern;
    private final CelException error;

    MatchesNode(ExprNode original, Regex.Compiled pattern, CelException error) {
      super(Kind.CALL, original.name, original.value, original.offset, original.children);
      sourceRange(original.sourceStart, original.sourceEnd);
      optionalEntry = original.optionalEntry;
      statement = original.statement;
      checkedType = original.checkedType;
      canonicalId = original.canonicalId;
      this.pattern = pattern;
      this.error = error;
    }

    @Override
    Object execute(VirtualFrame frame, EvalContext ctx) {
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
      if (error != null) raise(error);
      return pattern.find(s);
    }
  }
}
