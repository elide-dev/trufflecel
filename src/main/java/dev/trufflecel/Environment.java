package dev.trufflecel;

import java.util.*;
import java.util.function.Function;

/** Immutable compiler configuration; contains no per-evaluation mutable state. */
public final class Environment {
  /**
   * When certain failures are reported. Analysis, constant folding and regular-expression
   * precompilation always run at compile time; the policy only decides whether a subexpression that
   * fails on every evaluation path is rejected by {@code compile} or raised, with the same category
   * and budget cost, by {@code evaluate}.
   */
  public enum Validation {
    /** Keep CEL evaluation semantics: constant failures are raised when evaluated. */
    DEFERRED,
    /**
     * Reject at compile time every failure that no evaluation can avoid, together with invalid
     * regular-expression, timestamp and duration literals; all findings are reported together.
     */
    STRICT
  }

  public record FunctionBinding(
      List<CelType> arguments, CelType result, Function<List<Object>, Object> implementation) {
    public FunctionBinding {
      arguments = List.copyOf(arguments);
      Objects.requireNonNull(result);
      Objects.requireNonNull(implementation);
    }
  }

  final Map<String, CelType> variables;
  private final Set<String> variableNames;
  final Map<String, Object> constants;
  final Map<String, ObjectType> objectTypes;
  final ObjectType[] runtimeObjectTypes;
  final Map<String, FunctionBinding> functions;
  final Map<String, List<FunctionOverload>> overloads;

  /** A named signature; receiver signatures include the receiver as argument zero. */
  public record FunctionOverload(String id, Boolean receiver, FunctionBinding binding) {
    public FunctionOverload {
      if (Objects.requireNonNull(id).isEmpty())
        throw new IllegalArgumentException("empty overload id");
      Objects.requireNonNull(binding);
      if (Boolean.TRUE.equals(receiver) && binding.arguments().isEmpty())
        throw new IllegalArgumentException("receiver overload needs a receiver argument");
    }
  }

  final String container;
  final boolean inferVariables, macros;
  final long budget;
  final int maxSourceLength, maxDepth, comprehensionNesting;
  final Validation validation;
  final boolean homogeneousLiterals;

  private Environment(Builder b) {
    variables = Map.copyOf(b.variables);
    variableNames = Set.copyOf(variables.keySet());
    constants = Map.copyOf(b.constants);
    objectTypes = Map.copyOf(b.objectTypes);
    var runtimeTypes = new ArrayList<ObjectType>();
    for (var entry : objectTypes.entrySet())
      if (!b.constructorOnlyTypes.contains(entry.getKey())) runtimeTypes.add(entry.getValue());
    runtimeObjectTypes = runtimeTypes.toArray(ObjectType[]::new);
    functions = Map.copyOf(b.functions);
    var signatures = new LinkedHashMap<String, List<FunctionOverload>>();
    b.overloads.forEach((name, values) -> signatures.put(name, List.copyOf(values)));
    overloads = Map.copyOf(signatures);
    container = b.container;
    inferVariables = b.inferVariables;
    macros = b.macros;
    budget = b.budget;
    maxSourceLength = b.maxSourceLength;
    maxDepth = b.maxDepth;
    comprehensionNesting = b.comprehensionNesting;
    validation = b.validation;
    homogeneousLiterals = b.homogeneousLiterals;
  }

  /**
   * Compile an instrumentable program in an entered Polyglot context; initializes CEL if needed.
   */
  public Program compile(com.oracle.truffle.api.source.Source source) {
    var language = CelLanguage.current(source);
    return prepare(source.getCharacters().toString()).adopt(language, source);
  }

  public Program compile(String source) {
    return compile(source, true);
  }

  /** Parse and plan without type checking, for dynamic CEL integrations. */
  public Program parse(String source) {
    return compile(source, false);
  }

  /**
   * Parse and type-check without creating a runtime call target, suitable for image construction.
   */
  public PreparedProgram prepare(String source) {
    return prepare(source, true);
  }

  private Program compile(String source, boolean check) {
    return prepare(source, check).adopt();
  }

  private PreparedProgram prepare(String source, boolean check) {
    Objects.requireNonNull(source);
    if (source.length() > maxSourceLength)
      throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "source length limit exceeded");
    try {
      return prepare(source, new Parser(source, this).parse(), check);
    } catch (CelException e) {
      throw e.locate(source);
    }
  }

  /** Reject trees deeper than the configured limit before any recursive traversal. */
  void checkDepth(ExprNode node) {
    record Pending(ExprNode node, int depth) {}
    var pending = new ArrayDeque<Pending>();
    pending.push(new Pending(node, 1));
    while (!pending.isEmpty()) {
      Pending item = pending.pop();
      if (item.depth() > maxDepth)
        throw Values.error(CelException.Code.RESOURCE_EXHAUSTED, "AST depth limit exceeded");
      for (ExprNode child : item.node().children)
        pending.push(new Pending(child, item.depth() + 1));
    }
  }

  PreparedProgram prepare(String source, ExprNode node, boolean check) {
    checkDepth(node);
    Map<String, CelType> declarations = variables;
    if (inferVariables) {
      var free = new TreeSet<String>();
      node.free(Set.of(), free);
      for (String name : free)
        if (resolve(name, constants.keySet()) == null
            && resolve(name, objectTypes.keySet()) == null
            && builtinType(name) == null
            && resolve(name, declarations.keySet()) == null) {
          if (declarations == variables) declarations = new LinkedHashMap<>(variables);
          declarations.put(name, CelType.DYN);
        }
    }
    var checker = new Checker(this, declarations, check);
    CelType result = checker.check(node, Map.of()).finish();
    FieldPlan.prepare(node, this);
    node = Optimizer.optimize(node, this, source);
    // Folded values have precise types, so hidden mismatches (for example a dyn-wrapped
    // string used as a list index) become type findings once the folds are known.
    if (check && validation == Validation.STRICT)
      new Checker(this, declarations, true).check(node, Map.of());
    return new PreparedProgram(
        source,
        declarations == variables ? variableNames : declarations.keySet(),
        result,
        node,
        this);
  }

  String resolve(String name, Set<String> names) {
    if (name.startsWith(".")) return names.contains(name.substring(1)) ? name.substring(1) : null;
    String prefix = container;
    while (!prefix.isEmpty()) {
      String candidate = prefix + "." + name;
      if (names.contains(candidate)) return candidate;
      int dot = prefix.lastIndexOf('.');
      prefix = dot < 0 ? "" : prefix.substring(0, dot);
    }
    return names.contains(name) ? name : null;
  }

  static CelType builtinType(String name) {
    return switch (name) {
      case "bool" -> CelType.BOOL;
      case "int" -> CelType.INT;
      case "uint" -> CelType.UINT;
      case "double" -> CelType.DOUBLE;
      case "string" -> CelType.STRING;
      case "bytes" -> CelType.BYTES;
      case "null_type" -> CelType.NULL;
      case "list", "map", "type", "optional_type", "net.IP", "net.CIDR" -> CelType.of(name);
      case "google.protobuf.Timestamp" -> CelType.TIMESTAMP;
      case "google.protobuf.Duration" -> CelType.DURATION;
      default -> null;
    };
  }

  public static final class Builder {
    private final Map<String, CelType> variables = new LinkedHashMap<>();
    private final Map<String, Object> constants = new LinkedHashMap<>();
    private final Map<String, ObjectType> objectTypes = new LinkedHashMap<>();
    private final Set<String> constructorOnlyTypes = new HashSet<>();
    private final Map<String, FunctionBinding> functions = new LinkedHashMap<>();
    private final Map<String, List<FunctionOverload>> overloads = new LinkedHashMap<>();
    private final Set<String> overloadIds = new HashSet<>();
    private String container = "";
    private boolean inferVariables, macros = true;
    private long budget = 1_000_000;
    private int maxSourceLength = 100_000, maxDepth = 256, comprehensionNesting;
    private Validation validation = Validation.DEFERRED;
    private boolean homogeneousLiterals;

    public Builder() {
      WellKnownTypes.register(this);
    }

    public Builder variable(String name, CelType type) {
      variables.put(Objects.requireNonNull(name), Objects.requireNonNull(type));
      return this;
    }

    public Builder constant(String name, Object value) {
      constants.put(Objects.requireNonNull(name), Values.in(value));
      return this;
    }

    public Builder objectType(ObjectType type) {
      String name = type.name();
      objectTypes.put(name, type);
      constructorOnlyTypes.remove(name);
      return this;
    }

    // Well-known factories unwrap to CEL values and never accept runtime objects.
    Builder constructorType(ObjectType type) {
      String name = type.name();
      objectTypes.put(name, type);
      constructorOnlyTypes.add(name);
      return this;
    }

    public Builder function(
        String name,
        List<CelType> arguments,
        CelType result,
        Function<List<Object>, Object> implementation) {
      return addFunction(
          name,
          name + "#" + overloads.getOrDefault(name, List.of()).size(),
          null,
          arguments,
          result,
          implementation);
    }

    /** Register a distinct global or receiver signature, with an ID exported in checked ASTs. */
    public Builder function(
        String name,
        String overloadId,
        boolean receiver,
        List<CelType> arguments,
        CelType result,
        Function<List<Object>, Object> implementation) {
      return addFunction(name, overloadId, receiver, arguments, result, implementation);
    }

    private Builder addFunction(
        String name,
        String id,
        Boolean receiver,
        List<CelType> arguments,
        CelType result,
        Function<List<Object>, Object> implementation) {
      Objects.requireNonNull(name);
      if (Set.of("&&", "||", "?:", "or", "orValue", "@not_strictly_false").contains(name))
        throw new IllegalArgumentException("cannot overload lazy control function: " + name);
      var binding = new FunctionBinding(arguments, result, implementation);
      var signature = new FunctionOverload(id, receiver, binding);
      if (overloadIds.contains(id))
        throw new IllegalArgumentException("duplicate overload id: " + id);
      for (var existing : overloads.getOrDefault(name, List.of())) {
        if ((receiver == null
                || existing.receiver() == null
                || receiver.equals(existing.receiver()))
            && FunctionOverloads.overlaps(existing.binding().arguments(), arguments))
          throw new IllegalArgumentException(
              "ambiguous overloads: " + existing.id() + " and " + id);
      }
      overloadIds.add(id);
      overloads.computeIfAbsent(name, unused -> new ArrayList<>()).add(signature);
      functions.putIfAbsent(name, binding);
      return this;
    }

    public Builder container(String name) {
      container = Objects.requireNonNull(name);
      return this;
    }

    public Builder inferVariables(boolean enabled) {
      inferVariables = enabled;
      return this;
    }

    public Builder macros(boolean enabled) {
      macros = enabled;
      return this;
    }

    public Builder evaluationBudget(long steps) {
      if (steps <= 0) throw new IllegalArgumentException("budget must be positive");
      budget = steps;
      return this;
    }

    public Builder maxSourceLength(int length) {
      if (length <= 0) throw new IllegalArgumentException("length must be positive");
      maxSourceLength = length;
      return this;
    }

    public Builder maxDepth(int depth) {
      if (depth <= 0 || depth > 512) throw new IllegalArgumentException("depth must be in [1,512]");
      maxDepth = depth;
      return this;
    }

    /** Report certain failures at compile time ({@link Validation#STRICT}) or at evaluation. */
    public Builder validation(Validation policy) {
      validation = Objects.requireNonNull(policy);
      return this;
    }

    /**
     * Reject list literals whose elements, and map literals whose keys or values, have different
     * static types. Elements of type {@code dyn} are exempt. Reported under either policy.
     */
    public Builder homogeneousLiterals(boolean enabled) {
      homogeneousLiterals = enabled;
      return this;
    }

    /**
     * Reject comprehension macros (including {@code cel.bind}) and canonical comprehensions nested
     * deeper than {@code depth}. Unlimited by default; reported under either policy.
     */
    public Builder maxComprehensionNesting(int depth) {
      if (depth <= 0) throw new IllegalArgumentException("depth must be positive");
      comprehensionNesting = depth;
      return this;
    }

    public Environment build() {
      return new Environment(this);
    }
  }
}
