package dev.trufflecel;

import java.util.*;
import java.util.function.Function;

/** Immutable compiler configuration; contains no per-evaluation mutable state. */
public final class Environment {
  public record FunctionBinding(
      List<CelType> arguments, CelType result, Function<List<Object>, Object> implementation) {
    public FunctionBinding {
      arguments = List.copyOf(arguments);
      Objects.requireNonNull(result);
      Objects.requireNonNull(implementation);
    }
  }

  final Map<String, CelType> variables;
  final Map<String, Object> constants;
  final Map<String, ObjectType> objectTypes;
  final ObjectType[] runtimeObjectTypes;
  final Map<String, FunctionBinding> functions;
  final String container;
  final boolean inferVariables, macros;
  final long budget;
  final int maxSourceLength, maxDepth;

  private Environment(Builder b) {
    variables = Map.copyOf(b.variables);
    constants = Map.copyOf(b.constants);
    objectTypes = Map.copyOf(b.objectTypes);
    var runtimeTypes = new ArrayList<ObjectType>();
    for (var entry : objectTypes.entrySet())
      if (!b.constructorOnlyTypes.contains(entry.getKey())) runtimeTypes.add(entry.getValue());
    runtimeObjectTypes = runtimeTypes.toArray(ObjectType[]::new);
    functions = Map.copyOf(b.functions);
    container = b.container;
    inferVariables = b.inferVariables;
    macros = b.macros;
    budget = b.budget;
    maxSourceLength = b.maxSourceLength;
    maxDepth = b.maxDepth;
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
    ExprNode node = new Parser(source, this).parse();
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
    var declarations = new LinkedHashMap<>(variables);
    if (inferVariables) {
      var free = new TreeSet<String>();
      node.free(Set.of(), free);
      for (String name : free)
        if (resolve(name, constants.keySet()) == null
            && resolve(name, objectTypes.keySet()) == null
            && builtinType(name) == null
            && resolve(name, declarations.keySet()) == null) declarations.put(name, CelType.DYN);
    }
    var checker = new Checker(this, declarations, check);
    CelType result = checker.check(node, Map.of()).finish();
    return new PreparedProgram(source, declarations.keySet(), result, node, this);
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
    private String container = "";
    private boolean inferVariables, macros = true;
    private long budget = 1_000_000;
    private int maxSourceLength = 100_000, maxDepth = 256;

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
      functions.put(name, new FunctionBinding(arguments, result, implementation));
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

    public Environment build() {
      return new Environment(this);
    }
  }
}
