package dev.trufflecel;

import java.util.*;

/** Conservative bounds on charged CEL operations, not CPU time or host callback work. */
public final class CostEstimator {
  private CostEstimator() {}

  public record Estimate(
      long minimum, OptionalLong maximum, Map<String, Long> sizeBounds, Set<String> unknowns) {
    public Estimate {
      sizeBounds = Map.copyOf(sizeBounds);
      unknowns = Set.copyOf(unknowns);
    }
  }

  public static Estimate estimate(Environment environment, String source) {
    return estimate(environment, source, Map.of());
  }

  /**
   * Size bounds are caller guarantees for collection-valued activation identifiers/attributes. They
   * do not apply to a local binding that shadows the same name. Sorting includes a conservative
   * quadratic comparison bound; host callbacks remain explicitly unbounded.
   */
  public static Estimate estimate(
      Environment environment, String source, Map<String, Long> sizeBounds) {
    Objects.requireNonNull(source);
    var bounds = Map.copyOf(sizeBounds);
    bounds.forEach(
        (name, size) -> {
          if (name.isBlank() || size < 0)
            throw new IllegalArgumentException("invalid collection size bound");
        });
    if (source.length() > environment.maxSourceLength)
      throw new CelException(CelException.Code.RESOURCE_EXHAUSTED, "source length limit exceeded");
    ExprNode expression = new Parser(source, environment).parse();
    environment.prepare(source, expression, true);
    var estimator = new Analysis(bounds, environment.overloads.keySet());
    long maximum = estimator.cost(expression);
    return new Estimate(
        0,
        maximum < 0 ? OptionalLong.empty() : OptionalLong.of(maximum),
        bounds,
        estimator.unknowns);
  }

  private static final class Analysis {
    private static final Set<String> FIXED_CALLS =
        Set.of(
            "+",
            "-",
            "neg",
            "*",
            "/",
            "%",
            "==",
            "!=",
            "<",
            "<=",
            ">",
            ">=",
            "!",
            "&&",
            "||",
            "in",
            "@not_strictly_false",
            "size",
            "type",
            "dyn",
            "int",
            "uint",
            "double",
            "bool",
            "string",
            "bytes",
            "timestamp",
            "duration",
            "contains",
            "startsWith",
            "endsWith",
            "matches",
            "value",
            "hasValue",
            "or",
            "orValue",
            "optional.of",
            "optional.none",
            "optional.ofNonZeroValue",
            "getFullYear",
            "getMonth",
            "getDayOfYear",
            "getDate",
            "getDayOfMonth",
            "getDayOfWeek",
            "getHours",
            "getMinutes",
            "getSeconds",
            "getMilliseconds");
    private final Map<String, Long> bounds;
    private final Set<String> hostFunctions;
    private final Set<String> unknowns = new LinkedHashSet<>();
    private final Deque<Set<String>> scopes = new ArrayDeque<>();

    Analysis(Map<String, Long> bounds, Set<String> hostFunctions) {
      this.bounds = bounds;
      this.hostFunctions = hostFunctions;
    }

    private long unknown(String reason) {
      unknowns.add(reason);
      return -1;
    }

    private long plus(long a, long b) {
      if (a < 0 || b < 0) return -1;
      if (Long.MAX_VALUE - a < b) return unknown("operation count exceeds long range");
      return a + b;
    }

    private long times(long a, long b) {
      if (a < 0 || b < 0) return -1;
      if (a != 0 && b > Long.MAX_VALUE / a) return unknown("operation count exceeds long range");
      return a * b;
    }

    private long cost(ExprNode node) {
      if (node.fold != null) return node.fold.cost();
      return switch (node.kind) {
        case CONSTANT, IDENT -> 1;
        case BLOCK, BLOCK_INDEX -> unknown("explicit lazy block cost is not modeled");
        case OBJECT -> unknown("application object constructor: " + node.name);
        case CALL -> {
          if (hostFunctions.contains(node.name)) yield unknown("host function: " + node.name);
          if (!FIXED_CALLS.contains(node.name))
            yield unknown("function cost is not modeled: " + node.name);
          yield children(node, 1);
        }
        case CONDITIONAL -> {
          long condition = cost(node.children[0]),
              yes = cost(node.children[1]),
              no = cost(node.children[2]);
          yield plus(1, plus(condition, yes < 0 || no < 0 ? -1 : Math.max(yes, no)));
        }
        case MACRO -> macro(node);
        case COMPREHENSION -> {
          long range = cost(node.children[0]);
          long size = size(node.children[0]);
          long initial = cost(node.children[1]);
          String[] names = (String[]) node.value;
          long condition = scopedCost(node.children[2], names);
          long step = scopedCost(node.children[3], names);
          yield plus(
              1,
              plus(
                  range,
                  plus(
                      initial,
                      plus(
                          times(size, plus(1, plus(condition, step))),
                          plus(
                              condition, scopedCost(node.children[4], new String[] {names[0]}))))));
        }
        default -> children(node, 1);
      };
    }

    private long children(ExprNode node, long base) {
      for (ExprNode child : node.children) base = plus(base, cost(child));
      return base;
    }

    private long macro(ExprNode node) {
      if (node.name.equals("has")) return children(node, 1);
      String[] names = (String[]) node.value;
      long range = cost(node.children[0]);
      if (node.name.equals("bind"))
        return plus(1, plus(range, scopedCost(node.children[1], names)));
      long size =
          node.name.equals("optMap") || node.name.equals("optFlatMap") ? 1 : size(node.children[0]);
      if (size == 0) return plus(1, range);
      long body = 1;
      for (int i = 1; i < node.children.length; i++)
        body = plus(body, scopedCost(node.children[i], names));
      long result = plus(1, plus(range, times(size, body)));
      // Runtime sortBy charges each comparison. n² conservatively bounds the JDK sort's
      // comparisons without assuming input order or a particular TimSort merge schedule.
      return node.name.equals("sortBy") ? plus(result, times(size, size)) : result;
    }

    private long scopedCost(ExprNode node, String[] names) {
      scopes.push(Set.copyOf(Arrays.asList(names)));
      try {
        return cost(node);
      } finally {
        scopes.pop();
      }
    }

    private long size(ExprNode node) {
      Object value = node.fold == null ? node.value : node.fold.value();
      if (value instanceof Collection<?> collection) return collection.size();
      if (value instanceof Map<?, ?> map) return map.size();
      if (node.kind == ExprNode.Kind.LIST) return node.children.length;
      if (node.kind == ExprNode.Kind.MAP) return node.children.length / 2;
      String name = node.qualifiedName();
      if (name != null) {
        String root = name.split("\\.", 2)[0];
        for (Set<String> scope : scopes)
          if (scope.contains(root)) return unknown("local collection size: " + name);
      }
      Long bound = name == null ? null : bounds.get(name);
      return bound == null
          ? unknown("collection size: " + (name == null ? "expression" : name))
          : bound;
    }
  }
}
