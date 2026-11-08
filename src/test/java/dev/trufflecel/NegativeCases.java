package dev.trufflecel;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Independently specified negative and edge cases shared by the TruffleCEL matrix runners and the
 * cel-java differential runner. Every case records the rejection phase and, where the contract
 * defines one, the stable {@link CelException.Code}; diagnostic wording is never part of the
 * contract. This table has no cel-java dependency: upstream expectations are recorded as plain
 * {@code CelErrorCode} names so the differential runner can resolve them.
 */
public final class NegativeCases {
  private NegativeCases() {}

  public enum Phase {
    PARSE,
    CHECK,
    EVALUATE
  }

  public enum Area {
    MALFORMED,
    DECLARATIONS,
    UNKNOWNS,
    LIMITS,
    COLLECTIONS,
    ARITHMETIC,
    CONVERSIONS,
    SHORT_CIRCUIT,
    COMPREHENSIONS,
    OPTIONALS,
    PROTOBUF
  }

  /** Marker for a successful evaluation whose value is not part of the assertion. */
  public static final Object ANY = new Object();

  /**
   * The outcome of one path. A null phase means the expression evaluates to {@code value}; a null
   * code with a non-null phase means an error in that phase whose category the contract leaves
   * open. In an unchecked outcome, {@code CHECK} means the planner still rejects the expression
   * statically (message literal validation) even though type checking is disabled.
   */
  public record Outcome(Phase phase, CelException.Code code, Object value) {
    public static Outcome error(Phase phase, CelException.Code code) {
      return new Outcome(Objects.requireNonNull(phase), code, null);
    }

    public static Outcome value(Object expected) {
      return new Outcome(null, null, expected);
    }

    public boolean isError() {
      return phase != null;
    }

    /** Observe an evaluation; errors carry their diagnostic as the value for reporting only. */
    public static Outcome observe(Supplier<Object> evaluation) {
      try {
        return value(evaluation.get());
      } catch (CelException e) {
        return new Outcome(Phase.EVALUATE, e.code(), e.getMessage());
      }
    }

    /** Compare an observed evaluation outcome with this expectation. */
    public void verify(String path, Outcome actual) {
      if (isError()) {
        if (actual.phase() != Phase.EVALUATE)
          throw new AssertionError(path + ": expected an evaluation error, got " + actual.value());
        if (code != null && code != actual.code())
          throw new AssertionError(
              path + ": expected " + code + ", got " + actual.code() + ": " + actual.value());
        return;
      }
      if (actual.phase() != null)
        throw new AssertionError(path + ": unexpected error " + actual.value());
      if (value != ANY && !Objects.equals(value, actual.value()))
        throw new AssertionError(path + ": expected " + value + ", got " + actual.value());
    }
  }

  /**
   * Recorded behavior of cel-java 0.15.0 for the checked source. {@code folds} marks an intentional
   * constant-evaluation failure: cel-java's optimized configuration rejects the expression at
   * optimization time, while its plain configurations fail at evaluation with {@code code}.
   * TruffleCEL's deferred policy raises the same failure at evaluation, and every folded case also
   * records that strict validation rejects it at compile time ({@link Case#strict()}). {@code
   * pratt} overrides the expectation for cel-java's Pratt parser when the two upstream parsers
   * disagree.
   */
  public record Upstream(
      Kind kind, Phase phase, String code, boolean folds, String note, Upstream pratt) {
    public enum Kind {
      REJECTS,
      ACCEPTS,
      NOT_APPLICABLE
    }

    public static Upstream rejects(Phase phase) {
      return new Upstream(Kind.REJECTS, phase, null, false, null, null);
    }

    public static Upstream rejects(String code) {
      return new Upstream(Kind.REJECTS, Phase.EVALUATE, code, false, null, null);
    }

    public static Upstream folds(String code) {
      return new Upstream(Kind.REJECTS, Phase.EVALUATE, code, true, null, null);
    }

    public static Upstream accepts() {
      return new Upstream(Kind.ACCEPTS, null, null, false, null, null);
    }

    /** cel-java legitimately differs from TruffleCEL; the reason is part of the record. */
    public static Upstream diverges(Upstream actual, String reason) {
      return new Upstream(
          actual.kind,
          actual.phase,
          actual.code,
          actual.folds,
          Objects.requireNonNull(reason),
          null);
    }

    public static Upstream notApplicable(String reason) {
      return new Upstream(
          Kind.NOT_APPLICABLE, null, null, false, Objects.requireNonNull(reason), null);
    }

    /** The default (ANTLR) and Pratt parsers disagree; both outcomes are recorded. */
    public static Upstream perParser(Upstream antlr, Upstream pratt) {
      return new Upstream(antlr.kind, antlr.phase, antlr.code, antlr.folds, antlr.note, pratt);
    }

    public Upstream forParser(boolean prattParser) {
      return prattParser && pratt != null ? pratt : this;
    }

    public boolean applicable() {
      return kind != Kind.NOT_APPLICABLE;
    }

    public boolean diverges() {
      return note != null && kind != Kind.NOT_APPLICABLE;
    }
  }

  public record Declaration(String name, CelType type) {}

  /** Host function behavior, expressed so both runtimes can bind an equivalent implementation. */
  public enum Behavior {
    IDENTITY,
    THROWS_ILLEGAL_ARGUMENT,
    THROWS_NO_SUCH_KEY
  }

  public record Fn(String name, List<CelType> arguments, CelType result, Behavior behavior) {
    public Function<List<Object>, Object> implementation() {
      return args ->
          switch (behavior) {
            case IDENTITY -> args.get(0);
            case THROWS_ILLEGAL_ARGUMENT -> throw new IllegalArgumentException("host rejected");
            case THROWS_NO_SUCH_KEY ->
                throw new CelException(CelException.Code.NO_SUCH_KEY, "host key missing");
          };
    }
  }

  public static final String PROTO3 = "cel.expr.conformance.proto3.TestAllTypes";
  public static final String PROTO2 = "cel.expr.conformance.proto2.TestAllTypes";

  public record Config(
      String name,
      List<Declaration> variables,
      List<Fn> functions,
      String container,
      boolean macros,
      boolean inferVariables,
      long budget,
      int maxDepth,
      int maxSourceLength,
      boolean protobuf) {
    Config budget(long steps) {
      return new Config(
          name + "/budget=" + steps,
          variables,
          functions,
          container,
          macros,
          inferVariables,
          steps,
          maxDepth,
          maxSourceLength,
          protobuf);
    }

    Config depth(int depth) {
      return new Config(
          name + "/depth=" + depth,
          variables,
          functions,
          container,
          macros,
          inferVariables,
          budget,
          depth,
          maxSourceLength,
          protobuf);
    }

    Config sourceLength(int length) {
      return new Config(
          name + "/source=" + length,
          variables,
          functions,
          container,
          macros,
          inferVariables,
          budget,
          maxDepth,
          length,
          protobuf);
    }

    Config container(String name) {
      return new Config(
          this.name + "/container=" + name,
          variables,
          functions,
          name,
          macros,
          inferVariables,
          budget,
          maxDepth,
          maxSourceLength,
          protobuf);
    }

    Config noMacros() {
      return new Config(
          name + "/nomacros",
          variables,
          functions,
          container,
          false,
          inferVariables,
          budget,
          maxDepth,
          maxSourceLength,
          protobuf);
    }

    Config inferring() {
      return new Config(
          name + "/infer",
          variables,
          functions,
          container,
          macros,
          true,
          budget,
          maxDepth,
          maxSourceLength,
          protobuf);
    }

    Config declare(String variable, CelType type) {
      var vars = new ArrayList<>(variables);
      vars.add(new Declaration(variable, type));
      return new Config(
          name + "/" + variable + ":" + type,
          vars,
          functions,
          container,
          macros,
          inferVariables,
          budget,
          maxDepth,
          maxSourceLength,
          protobuf);
    }

    /**
     * Build the TruffleCEL environment with the fixture adapter or the shipped protobuf adapter.
     */
    public Environment environment(boolean productionAdapter) {
      return environment(productionAdapter, false);
    }

    /** The same environment with strict compile-time validation. */
    public Environment environment(boolean productionAdapter, boolean strict) {
      var builder = Cel.newBuilder();
      if (strict) builder.validation(Environment.Validation.STRICT);
      if (protobuf) {
        if (productionAdapter) ProductionCorpus.register(builder);
        else TestObjects.register(builder);
      }
      for (Declaration d : variables) builder.variable(d.name(), d.type());
      for (Fn f : functions)
        builder.function(f.name(), f.arguments(), f.result(), f.implementation());
      builder
          .container(container)
          .macros(macros)
          .inferVariables(inferVariables)
          .evaluationBudget(budget)
          .maxDepth(maxDepth)
          .maxSourceLength(maxSourceLength);
      return builder.build();
    }
  }

  private static final List<Fn> FUNCTIONS =
      List.of(
          new Fn("same", List.of(CelType.INT), CelType.INT, Behavior.IDENTITY),
          new Fn("boom", List.of(CelType.INT), CelType.INT, Behavior.THROWS_ILLEGAL_ARGUMENT),
          new Fn("missing", List.of(CelType.STRING), CelType.INT, Behavior.THROWS_NO_SUCH_KEY));

  public static final Config STANDARD =
      new Config(
          "standard",
          List.of(
              new Declaration("x", CelType.INT),
              new Declaration("y", CelType.INT),
              new Declaration("u", CelType.UINT),
              new Declaration("d", CelType.DOUBLE),
              new Declaration("s", CelType.STRING),
              new Declaration("b", CelType.BOOL),
              new Declaration("bs", CelType.BYTES),
              new Declaration("xs", CelType.list(CelType.INT)),
              new Declaration("m", CelType.map(CelType.STRING, CelType.STRING)),
              new Declaration("dynv", CelType.DYN),
              new Declaration("t", CelType.TIMESTAMP),
              new Declaration("dur", CelType.DURATION)),
          FUNCTIONS,
          "",
          true,
          false,
          1_000_000,
          256,
          100_000,
          false);

  public static final Config PROTO =
      new Config(
          "proto",
          concat(
              STANDARD.variables(),
              List.of(
                  new Declaration("msg", CelType.of(PROTO3)),
                  new Declaration("msg2", CelType.of(PROTO2)))),
          FUNCTIONS,
          "",
          true,
          false,
          1_000_000,
          256,
          100_000,
          true);

  private static <T> List<T> concat(List<T> a, List<T> b) {
    var all = new ArrayList<>(a);
    all.addAll(b);
    return List.copyOf(all);
  }

  /** Fixture-adapter bindings; the production runner converts protobuf fixtures itself. */
  public static Map<String, Object> defaultBindings(Config config) {
    var bindings = new LinkedHashMap<String, Object>();
    bindings.put("x", 1L);
    bindings.put("y", 2L);
    bindings.put("u", new UInt(3L));
    bindings.put("d", 1.5);
    bindings.put("s", "a");
    bindings.put("b", true);
    bindings.put("bs", new Bytes(new byte[] {'a', 'b', 'c'}));
    bindings.put("xs", List.of(1L, 2L, 3L));
    bindings.put("m", Map.of("a", "b"));
    bindings.put("dynv", 1L);
    bindings.put("t", Instant.EPOCH);
    bindings.put("dur", Duration.ofSeconds(1));
    if (config.protobuf()) {
      bindings.put("msg", proto3(Map.of("single_int64", 1L)));
      bindings.put("msg2", proto2(Map.of()));
    }
    return bindings;
  }

  public static Object proto3(Map<String, ?> fields) {
    return TestObjects.PROTOS.construct(PROTO3, fields);
  }

  public static Object proto2(Map<String, ?> fields) {
    return TestObjects.PROTOS.construct(PROTO2, fields);
  }

  public static Object nested3(Map<String, ?> fields) {
    return TestObjects.PROTOS.construct(PROTO3 + ".NestedMessage", fields);
  }

  /**
   * One case. {@code unchecked} describes the outcome when the checker is bypassed (dynamic
   * integrations and canonical ParsedExpr import); null means it matches {@code checked}. {@code
   * strict} is the outcome under {@link Environment.Validation#STRICT}: a failure that occurs on
   * every evaluation path moves to the check phase with its evaluation category, and everything
   * else is unchanged.
   */
  public record Case(
      String id,
      Area area,
      Config config,
      String source,
      Map<String, Object> bindings,
      Outcome checked,
      Outcome unchecked,
      Outcome strict,
      Upstream upstream) {
    public Outcome unchecked() {
      return unchecked == null ? checked : unchecked;
    }

    /** Whether strict validation rejects at compile time what the deferred policy raises later. */
    public boolean strictRejects() {
      return checked.phase() == Phase.EVALUATE && strict.phase() == Phase.CHECK;
    }

    @Override
    public String toString() {
      return id;
    }
  }

  private static final class Builder {
    final String id;
    final String source;
    Area area;
    Config config = STANDARD;
    Map<String, Object> bindings;
    Outcome checked;
    Outcome unchecked;
    Outcome strict;
    Upstream upstream;

    Builder(String id, String source) {
      this.id = id;
      this.source = source;
    }

    Builder config(Config config) {
      this.config = config;
      return this;
    }

    Builder bind(String name, Object value) {
      if (bindings == null) bindings = defaultBindings(config);
      bindings.put(name, value);
      return this;
    }

    Builder bindings(Map<String, Object> only) {
      bindings = new LinkedHashMap<>(only);
      return this;
    }

    Builder unchecked(Outcome outcome) {
      unchecked = outcome;
      return this;
    }

    Builder unchecked(CelException.Code code) {
      return unchecked(Outcome.error(Phase.EVALUATE, code));
    }

    Builder upstream(Upstream expectation) {
      upstream = expectation;
      return this;
    }

    /** Strict validation rejects the expression at compile time with this category. */
    Builder strict(CelException.Code code) {
      strict = Outcome.error(Phase.CHECK, code);
      return this;
    }

    void add(Outcome outcome) {
      checked = outcome;
      if (bindings == null) bindings = defaultBindings(config);
      Objects.requireNonNull(upstream, id + " needs an upstream expectation");
      if (checked.phase() == Phase.CHECK)
        Objects.requireNonNull(unchecked, id + " needs unchecked");
      if (strict == null) strict = checked;
      if (upstream.folds() && strict.phase() != Phase.CHECK)
        throw new IllegalStateException(id + " folds upstream; record its strict outcome");
      if (!CASES.add(id)) throw new IllegalStateException("duplicate case " + id);
      ALL.add(
          new Case(
              id,
              area,
              config,
              source,
              Collections.unmodifiableMap(bindings),
              checked,
              unchecked,
              strict,
              upstream));
    }

    void syntax() {
      if (upstream == null) upstream = Upstream.rejects(Phase.PARSE);
      add(Outcome.error(Phase.PARSE, CelException.Code.SYNTAX));
    }

    void type(CelException.Code uncheckedCode) {
      if (upstream == null) upstream = Upstream.rejects(Phase.CHECK);
      unchecked(uncheckedCode);
      add(Outcome.error(Phase.CHECK, CelException.Code.TYPE));
    }

    void type(Outcome uncheckedOutcome) {
      if (upstream == null) upstream = Upstream.rejects(Phase.CHECK);
      unchecked(uncheckedOutcome);
      add(Outcome.error(Phase.CHECK, CelException.Code.TYPE));
    }

    /** Static rejections that the unchecked planner also enforces (message literal shape). */
    void staticType() {
      type(Outcome.error(Phase.CHECK, CelException.Code.TYPE));
    }

    void error(CelException.Code code, String upstreamCode) {
      if (upstream == null) upstream = Upstream.rejects(upstreamCode);
      add(Outcome.error(Phase.EVALUATE, code));
    }

    void error(CelException.Code code) {
      Objects.requireNonNull(upstream, id + " needs an upstream expectation");
      add(Outcome.error(Phase.EVALUATE, code));
    }

    void limit() {
      if (upstream == null) upstream = LIMIT;
      add(Outcome.error(Phase.EVALUATE, CelException.Code.RESOURCE_EXHAUSTED));
    }

    void parseLimit() {
      if (upstream == null) upstream = LIMIT;
      add(Outcome.error(Phase.PARSE, CelException.Code.RESOURCE_EXHAUSTED));
    }

    void value(Object expected) {
      if (upstream == null) upstream = Upstream.accepts();
      add(Outcome.value(expected));
    }

    void unknown(String... attributes) {
      if (upstream == null) upstream = UNKNOWNS;
      add(Outcome.value(new Unknown(Set.of(attributes))));
    }
  }

  private static final List<Case> ALL = new ArrayList<>();
  private static final Set<String> CASES = new HashSet<>();
  private static Area current;

  private static Builder c(String id, String source) {
    var b = new Builder(id, source);
    b.area = current;
    return b;
  }

  private static final Upstream UNKNOWNS = Upstream.notApplicable("explicit unknown bindings");
  // Unchecked construction with an ill-typed field fails at evaluation; the category is defined
  // by the adapter (NO_OVERLOAD for the fixture, INVALID_ARGUMENT for the shipped adapter).
  private static final Outcome ILL_TYPED_CONSTRUCTION = Outcome.error(Phase.EVALUATE, null);
  private static final Upstream LIMIT = Upstream.notApplicable("TruffleCEL resource limit");
  private static final Upstream WKT_UNVALIDATED =
      Upstream.diverges(
          Upstream.accepts(),
          "cel-java does not validate Duration and Timestamp ranges in message literals");
  private static final String TIME_RANGE =
      "cel-java reports timestamp and duration range failures as INTERNAL_ERROR";
  private static final String OPTIONAL_VALUE =
      "cel-java reports optional.value() on an absent optional as INTERNAL_ERROR";

  static {
    malformed();
    declarations();
    unknowns();
    limits();
    collections();
    arithmetic();
    conversions();
    shortCircuit();
    comprehensions();
    optionals();
    protobuf();
  }

  public static List<Case> all() {
    return Collections.unmodifiableList(ALL);
  }

  public static List<Case> area(Area area) {
    return ALL.stream().filter(c -> c.area() == area).toList();
  }

  private static void malformed() {
    current = Area.MALFORMED;
    for (var entry :
        new String[][] {
          {"trailing-operator", "x +"},
          {"unterminated-list", "[1,"},
          {"unterminated-single-quote", "'unterminated"},
          {"unterminated-double-quote", "\"abc"},
          {"unterminated-triple-quote", "'''abc"},
          {"unterminated-raw", "r\"abc"},
          {"unterminated-paren", "(1 + 2"},
          {"extra-paren", "1)"},
          {"adjacent-literals", "1 2"},
          {"adjacent-strings-no-operator", "'a' 'b'"},
          {"empty-map-value", "{'a': }"},
          {"unterminated-map", "{1: 2"},
          {"dangling-select", "x."},
          {"dangling-index", "x["},
          {"lone-dot", "."},
          {"unary-plus", "1 + +2"},
          {"invalid-escape", "'\\q'"},
          {"short-unicode-escape", "'\\u12'"},
          {"short-hex-escape", "'\\x'"},
          {"octal-escape-out-of-range", "'\\400'"},
          {"surrogate-escape", "'\\ud800'"},
          {"code-point-out-of-range", "'\\U00110000'"},
          {"bytes-unicode-escape", "b'\\u00ff'"},
          {"incomplete-hex", "0x"},
          {"incomplete-exponent", "1e"},
          {"double-dot-number", "1.2.3"},
          {"ternary-missing-else", "x ? 1"},
          {"ternary-missing-then", "x ? : 2"},
          {"ternary-extra-else", "1 ? 2 : 3 : 4"},
          {"extra-bracket", "[1, 2]]"},
          {"assignment", "1 = 2"},
          {"arrow", "x => 1"},
          {"at-sign", "@x"},
          {"postfix-not", "x!"},
          {"dangling-in", "x in"},
          {"keyword-in-alone", "in"},
          {"macro-missing-comma", "[1, 2, 3].all(x x > 1)"},
          {"empty-argument", "same(,)"},
          {"double-select", "x..y"},
          {"double-comma-list", "[1,,2]"},
          {"leading-comma-list", "[,1]"},
          {"null-coalesce", "x ?? y"},
          {"trailing-plus", "1 +"},
          {"empty-source", ""},
          {"whitespace-source", "   "},
          {"numeric-field", "x.1"},
          {"index-two-arguments", "x[1, 2]"},
          {"dangling-optional-select", "x.?"},
          {"reserved-while", "while == 1"},
          {"reserved-select-in", "x.in"},
          {"hash-comment", "1 # comment"},
          {"lone-minus", "-"},
          {"lone-not", "!"},
          {"empty-index", "[1][]"},
          {"call-on-call", "same(1)(2)"},
          {"identifier-starts-with-digit", "1abc"},
          {"slice", "[1][0:1]"},
          {"int-literal-overflow", "9223372036854775808"},
          {"uint-literal-overflow", "18446744073709551616u"},
          {"hex-literal-overflow", "0x8000000000000000"},
          {"comprehension-non-identifier", "[1].all(1, true)"},
          {"comprehension-qualified-variable", "[1].all(x.y, true)"},
          {"has-non-select", "has(x)"},
          {"has-index", "has(xs[0])"},
          {"has-call", "has(same(1))"},
          {"map-key-trailing-colon", "{'a':}"},
          {"nested-unbalanced", "[{(1]})"},
        }) c(entry[0], entry[1]).syntax();
    // "[" [ExprList] [","] "]" admits a lone comma; cel-java's Pratt parser rejects it.
    var prattRejects = Upstream.perParser(Upstream.accepts(), Upstream.rejects(Phase.PARSE));
    c("lone-comma-list", "[,].size()").upstream(prattRejects).value(0L);
    c("lone-comma-map", "{,}.size()").upstream(prattRejects).value(0L);
    c("lone-comma-message", PROTO3 + "{,}.single_int64")
        .config(PROTO)
        .upstream(prattRejects)
        .value(0L);
    c("trailing-comma-list", "[1, 2,].size()").value(2L);
  }

  private static void declarations() {
    current = Area.DECLARATIONS;
    c("int-plus-bool", "1 + true").type(CelException.Code.NO_OVERLOAD);
    c("size-of-int", "size(1)").type(CelException.Code.NO_OVERLOAD);
    c("list-index-bool", "[1][true]").type(CelException.Code.NO_OVERLOAD);
    c("int-and", "x && true").type(CelException.Code.NO_OVERLOAD);
    c("not-int", "!1").type(CelException.Code.NO_OVERLOAD);
    c("negate-string", "-'a'").type(CelException.Code.NO_OVERLOAD);
    c("double-modulo", "1.0 % 2.0").type(CelException.Code.NO_OVERLOAD);
    c("int-plus-uint", "1 + 1u").type(CelException.Code.NO_OVERLOAD);
    c("int-less-string", "1 < 'x'").type(CelException.Code.NO_OVERLOAD);
    c("list-less-list", "[1] < [2]").type(CelException.Code.NO_OVERLOAD);
    c("int-condition", "1 ? 2 : 3").type(CelException.Code.NO_OVERLOAD);
    c("int-select", "1.field").type(CelException.Code.NO_OVERLOAD);
    c("in-int", "1 in 2").type(CelException.Code.NO_OVERLOAD);
    c("contains-int", "'x'.contains(1)").type(CelException.Code.NO_OVERLOAD);
    c("int-of-bool", "int(true)").type(CelException.Code.NO_OVERLOAD);
    c("duration-of-int", "duration(1)").type(CelException.Code.NO_OVERLOAD);
    c("size-no-arguments", "size()").type(CelException.Code.NO_OVERLOAD);
    c("size-two-arguments", "size('a', 'b')").type(CelException.Code.NO_OVERLOAD);
    c("has-no-arguments", "has()").type(CelException.Code.NO_OVERLOAD);
    c("undeclared-variable", "undeclared + 1").type(CelException.Code.UNDECLARED);
    c("undeclared-function", "nosuch(1)").type(CelException.Code.NO_OVERLOAD);
    c("undeclared-member-function", "x.nosuch()").type(CelException.Code.NO_OVERLOAD);
    c("int-field", "x.foo").type(CelException.Code.NO_OVERLOAD);
    c("list-field", "xs.foo").type(CelException.Code.NO_OVERLOAD);
    c("int-string-equality", "x == 'a'").type(Outcome.value(false));
    c("map-type-equality", "{'a': 1} == {'a': 'b'}").type(Outcome.value(false));
    c("list-type-equality", "[1] == ['a']").type(Outcome.value(false));
    c("null-plus-int", "null + 1").type(CelException.Code.NO_OVERLOAD);
    c("timestamp-minus-int", "t - 1").type(CelException.Code.NO_OVERLOAD);
    c("duration-times-int", "dur * 2").type(CelException.Code.NO_OVERLOAD);
    c("bytes-plus-string", "b'abc' + 'a'").type(CelException.Code.NO_OVERLOAD);
    c("string-times-int", "'a' * 2").type(CelException.Code.NO_OVERLOAD);
    c("list-plus-int", "[1] + 1").type(CelException.Code.NO_OVERLOAD);
    c("map-plus-map", "{'a': 1} + {'b': 2}").type(CelException.Code.NO_OVERLOAD);
    c("int-divided-by-double", "1 / 1.0").type(CelException.Code.NO_OVERLOAD);
    c("type-plus-int", "type(1) + 1").type(CelException.Code.NO_OVERLOAD);
    c("string-index", "'abc'[0]").type(CelException.Code.NO_OVERLOAD);
    c("bytes-index", "bs[0]").type(CelException.Code.NO_OVERLOAD);
    c("size-of-null", "size(null)").type(CelException.Code.NO_OVERLOAD);
    c("string-in-int", "'a' in 1").type(CelException.Code.NO_OVERLOAD);
    c("date-function-on-int", "x.getFullYear()").type(CelException.Code.NO_OVERLOAD);
    c("int-of-duration", "int(dur)").type(CelException.Code.NO_OVERLOAD);
    c("function-arity", "same(1, 2)").type(CelException.Code.NO_OVERLOAD);
    c("function-argument-type", "same('a')").type(CelException.Code.NO_OVERLOAD);
    c("function-uint-argument", "same(dyn(1u))")
        .upstream(Upstream.folds("OVERLOAD_NOT_FOUND"))
        .strict(CelException.Code.NO_OVERLOAD)
        .error(CelException.Code.NO_OVERLOAD);
    c("comprehension-variable-escapes", "[1].map(v, v)[0] + v").type(CelException.Code.UNDECLARED);
    c("binding-variable-escapes", "cel.bind(v, 1, v) + v").type(CelException.Code.UNDECLARED);
    c("comprehension-over-int", "x.all(i, i > 0)").type(CelException.Code.NO_OVERLOAD);
    c("comprehension-over-null", "null.all(i, true)").type(CelException.Code.NO_OVERLOAD);
    c("non-bool-predicate", "[1].all(i, i)").type(CelException.Code.NO_OVERLOAD);
    c("non-bool-exists-one", "[1].exists_one(i, 1)").type(CelException.Code.NO_OVERLOAD);
    c("all-on-bool", "[1].all(i, i > 0).all(j, j)").type(CelException.Code.NO_OVERLOAD);
    c("filter-non-bool", "xs.map(i, i).filter(i, i)").type(CelException.Code.NO_OVERLOAD);
    c("unregistered-type-variable", "other.field")
        .config(STANDARD.declare("other", CelType.of("no.such.Type")))
        .bind("other", 1L)
        .upstream(Upstream.rejects(Phase.CHECK))
        .type(CelException.Code.NO_OVERLOAD);
    c("unregistered-message-literal", "no.such.Type{}").staticType();
    c("dyn-plus-string", "dyn(1) + 'a'")
        .upstream(Upstream.folds("OVERLOAD_NOT_FOUND"))
        .strict(CelException.Code.NO_OVERLOAD)
        .error(CelException.Code.NO_OVERLOAD);
    c("dyn-select-on-int", "dynv.foo.bar")
        .error(CelException.Code.NO_OVERLOAD, "ATTRIBUTE_NOT_FOUND");
    c("dyn-comprehension-over-int", "dynv.all(i, true)")
        .error(CelException.Code.NO_OVERLOAD, "INTERNAL_ERROR");
    c("string-of-dyn-list", "string(dyn([1]))")
        .upstream(Upstream.folds("OVERLOAD_NOT_FOUND"))
        .strict(CelException.Code.NO_OVERLOAD)
        .error(CelException.Code.NO_OVERLOAD);
    c("binding-type-mismatch", "x + 1")
        .bind("x", "not an int")
        .error(CelException.Code.NO_OVERLOAD, "OVERLOAD_NOT_FOUND");
    c("unbound-variable", "x + 1")
        .bindings(Map.of())
        .error(CelException.Code.UNDECLARED, "ATTRIBUTE_NOT_FOUND");
    c("inferred-variable-unbound", "inferred + 1")
        .config(STANDARD.inferring())
        .upstream(Upstream.notApplicable("inferred dyn declarations"))
        .error(CelException.Code.UNDECLARED);
    c("host-illegal-argument", "boom(1)")
        .error(CelException.Code.INVALID_ARGUMENT, "INTERNAL_ERROR");
    c("host-cel-error", "missing('k')").error(CelException.Code.NO_SUCH_KEY, "INTERNAL_ERROR");
    c("host-error-absorbed", "true || boom(1) == 1").value(true);
    c("macros-disabled-all", "xs.all(i, i > 0)")
        .config(STANDARD.noMacros())
        .type(CelException.Code.UNDECLARED);
    c("macros-disabled-has", "has(m.a)")
        .config(STANDARD.noMacros())
        .type(CelException.Code.NO_OVERLOAD);
    c("container-unresolved", "nested.value")
        .config(STANDARD.container("a.b"))
        .type(CelException.Code.UNDECLARED);
    c("optional-equality-with-int", "optional.of(1) == 1").type(Outcome.value(false));
  }

  private static void unknowns() {
    current = Area.UNKNOWNS;
    var x = Unknown.of("x");
    var b = Unknown.of("b");
    var dyn = Unknown.of("dynv");
    c("unknown-plus", "x + 1").bind("x", x).unknown("x");
    c("unknown-merge", "x + y").bind("x", x).bind("y", Unknown.of("y")).unknown("x", "y");
    c("unknown-beats-left-error", "(1 / 0) + x").bind("x", x).unknown("x");
    c("unknown-beats-right-error", "x + (1 / 0)").bind("x", x).unknown("x");
    c("unknown-beats-division-by-zero", "x / 0").bind("x", x).unknown("x");
    c("unknown-and-false", "b && false").bind("b", b).upstream(UNKNOWNS).value(false);
    c("unknown-or-true", "b || true").bind("b", b).upstream(UNKNOWNS).value(true);
    c("unknown-and-true", "b && true").bind("b", b).unknown("b");
    c("unknown-and-error", "(x > 0) && (1 / 0 == 0)").bind("x", x).unknown("x");
    c("error-or-unknown", "(1 / 0 == 0) || (x > 0)").bind("x", x).unknown("x");
    c("unknown-condition", "x > 0 ? 1 : 2").bind("x", x).unknown("x");
    c("unknown-branch", "true ? x : 1 / 0").bind("x", x).unknown("x");
    c("unknown-untaken-branch", "false ? x : 1").bind("x", x).upstream(UNKNOWNS).value(1L);
    c("unknown-list-element", "[x, 1][1]").bind("x", x).unknown("x");
    c("unknown-list-size", "[x].size()").bind("x", x).unknown("x");
    c("unknown-map-value", "{'a': x}['a']").bind("x", x).unknown("x");
    c("unknown-map-key", "{x: 1}").bind("x", x).unknown("x");
    c("unknown-map-index", "m[s]").bind("s", Unknown.of("s")).unknown("s");
    c("unknown-range", "xs.all(i, i > 0)").bind("xs", Unknown.of("xs")).unknown("xs");
    c("unknown-in-all", "[1, 2].all(i, i > x)").bind("x", x).unknown("x");
    c("unknown-exists-short-circuit", "[1, 2].exists(i, i == x || i == 2)")
        .bind("x", x)
        .upstream(UNKNOWNS)
        .value(true);
    c("unknown-exists-one", "[1, 2].exists_one(i, i == x)").bind("x", x).unknown("x");
    c("unknown-map-transform", "[1].map(i, x)").bind("x", x).unknown("x");
    c("unknown-size", "size(dynv)").bind("dynv", dyn).unknown("dynv");
    c("unknown-select", "dynv.field").bind("dynv", dyn).unknown("dynv");
    c("unknown-presence", "has(dynv.field)").bind("dynv", dyn).unknown("dynv");
    c("unknown-index", "dynv[0]").bind("dynv", dyn).unknown("dynv");
    c("unknown-equality", "x == 1").bind("x", x).unknown("x");
    c("unknown-self-equality", "x == x").bind("x", x).unknown("x");
    c("unknown-in-list", "x in [1]").bind("x", x).unknown("x");
    c("unknown-container", "1 in dynv").bind("dynv", dyn).unknown("dynv");
    c("unknown-conversion", "int(x)").bind("x", x).unknown("x");
    c("unknown-string-concat", "string(x) + s").bind("x", x).unknown("x");
    c("unknown-not", "!b").bind("b", b).unknown("b");
    c("unknown-negate", "-x").bind("x", x).unknown("x");
    c("unknown-type", "type(x)").bind("x", x).unknown("x");
    c("unknown-binding", "cel.bind(v, x, v + 1)").bind("x", x).unknown("x");
    c("unknown-optional-of", "optional.of(x)").bind("x", x).unknown("x");
    c("unknown-optional-select", "dynv.?field").bind("dynv", dyn).unknown("dynv");
    c("unknown-optional-fallback-unused", "optional.of(1).orValue(x)")
        .bind("x", x)
        .upstream(UNKNOWNS)
        .value(1L);
    c("unknown-optional-fallback", "optional.none().orValue(x)").bind("x", x).unknown("x");
    c("unknown-host-function", "boom(x)").bind("x", x).unknown("x");
    c("unknown-proto-field", "msg.single_int64 + x").config(PROTO).bind("x", x).unknown("x");
    c("unknown-proto-message", "msg.single_int64")
        .config(PROTO)
        .bind("msg", Unknown.of("msg"))
        .unknown("msg");
    c("unknown-proto-presence", "has(msg.single_int64)")
        .config(PROTO)
        .bind("msg", Unknown.of("msg"))
        .unknown("msg");
    c("budget-beats-unknown", "x + y + 1")
        .config(STANDARD.budget(2))
        .bind("x", x)
        .bind("y", Unknown.of("y"))
        .limit();
  }

  private static void limits() {
    current = Area.LIMITS;
    c("budget-arithmetic", "1 + 2 + 3 + 4 + 5 + 6 + 7 + 8").config(STANDARD.budget(4)).limit();
    c("budget-comprehension", "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(i, i * 2)")
        .config(STANDARD.budget(10))
        .limit();
    c("budget-nested-comprehension", "xs.all(a, xs.all(b, a + b > 0))")
        .config(STANDARD.budget(50))
        .bind("xs", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L))
        .limit();
    c("budget-not-short-circuited", "false || (1 + 2 + 3 + 4 + 5 > 0)")
        .config(STANDARD.budget(3))
        .limit();
    c("budget-short-circuited", "true || (1 + 2 + 3 + 4 + 5 > 0)")
        .config(STANDARD.budget(3))
        .upstream(LIMIT)
        .value(true);
    c("budget-not-absorbed-by-exists", "[1, 2, 3].exists(i, i + 1 + 2 + 3 + 4 > 0)")
        .config(STANDARD.budget(5))
        .limit();
    c("budget-not-absorbed-by-optional", "optional.none().orValue(1 + 2 + 3 + 4 + 5)")
        .config(STANDARD.budget(3))
        .limit();
    c("depth-nested-lists", "[[[[[[[[[[1]]]]]]]]]]").config(STANDARD.depth(8)).parseLimit();
    c("depth-operator-chain", "1 + 2 + 3 + 4 + 5 + 6 + 7 + 8")
        .config(STANDARD.depth(4))
        .parseLimit();
    c("depth-nested-macros", "[1].map(a, [2].map(b, [3].map(c, [4].map(d, a + b + c + d))))")
        .config(STANDARD.depth(6))
        .parseLimit();
    c("source-length", "1 + 2 + 3 + 4").config(STANDARD.sourceLength(10)).parseLimit();
    c("deep-parentheses", "(".repeat(2000) + "1" + ")".repeat(2000))
        .upstream(Upstream.rejects(Phase.PARSE))
        .parseLimit();
    c("deep-negation", "-".repeat(2000) + "1").value(1L);
    c("deep-selects", "x" + ".a".repeat(2000)).upstream(Upstream.rejects(Phase.PARSE)).parseLimit();
    c("deep-lists", "[".repeat(2000) + "1" + "]".repeat(2000))
        .upstream(Upstream.rejects(Phase.PARSE))
        .parseLimit();
    c("deep-operator-chain", "1" + " + 1".repeat(5000))
        .upstream(Upstream.rejects(Phase.PARSE))
        .parseLimit();
    c("flatten-limit", "lists.range(1000000).map(i, [i]).flatten()").limit();
    c("range-cap", "lists.range(1000001)")
        .upstream(Upstream.notApplicable("TruffleCEL extension cap"))
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT);
  }

  private static void collections() {
    current = Area.COLLECTIONS;
    c("index-past-end", "xs[5]").error(CelException.Code.INDEX, "INDEX_OUT_OF_BOUNDS");
    c("index-negative", "xs[-1]").error(CelException.Code.INDEX, "INDEX_OUT_OF_BOUNDS");
    c("index-huge", "xs[9223372036854775807]")
        .error(CelException.Code.INDEX, "INDEX_OUT_OF_BOUNDS");
    c("index-beyond-int-range", "xs[2147483648]")
        .error(CelException.Code.INDEX, "INDEX_OUT_OF_BOUNDS");
    c("index-double", "xs[1.5]").type(CelException.Code.INDEX);
    c("index-empty-list", "[][0]")
        .upstream(Upstream.folds("INDEX_OUT_OF_BOUNDS"))
        .strict(CelException.Code.INDEX)
        .error(CelException.Code.INDEX);
    c("missing-key", "m['missing']").error(CelException.Code.NO_SUCH_KEY, "ATTRIBUTE_NOT_FOUND");
    c("missing-field-on-map", "m.missing")
        .error(CelException.Code.NO_SUCH_KEY, "ATTRIBUTE_NOT_FOUND");
    c("missing-key-variable", "m[s]")
        .bind("s", "missing")
        .error(CelException.Code.NO_SUCH_KEY, "ATTRIBUTE_NOT_FOUND");
    c("map-key-type", "{'a': 1}[1]").type(CelException.Code.NO_SUCH_KEY);
    c("numeric-key-equivalence", "{1: 'a'}[1.0]").type(Outcome.value("a"));
    c("numeric-key-inexact", "{1: 'a'}[1.5]").type(CelException.Code.NO_SUCH_KEY);
    // Strict validation rechecks after folding, so the dyn-erased index becomes a type error.
    c("dyn-string-list-index", "xs[dyn('a')]")
        .upstream(Upstream.folds("OVERLOAD_NOT_FOUND"))
        .strict(CelException.Code.TYPE)
        .error(CelException.Code.NO_OVERLOAD);
    c("double-map-key-literal", "{1.5: 1}")
        .upstream(
            Upstream.diverges(
                Upstream.rejects(Phase.EVALUATE),
                "cel-java defers map key type validation to evaluation"))
        .type(CelException.Code.INVALID_ARGUMENT);
    c("dyn-double-map-key", "{dyn(1.5): 1}")
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT, "INVALID_ARGUMENT");
    c("dyn-null-map-key", "{dyn(null): 1}")
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT, "INVALID_ARGUMENT");
    c("duplicate-map-key-literal", "{'a': 1, 'a': 2}")
        .upstream(Upstream.rejects(Phase.EVALUATE))
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT);
    c("duplicate-map-key-variable", "{s: 1, 'a': 2}")
        .upstream(Upstream.rejects(Phase.EVALUATE))
        .error(CelException.Code.INVALID_ARGUMENT);
    c("nested-missing-key", "{'a': {'b': 1}}.a.c")
        .upstream(Upstream.folds("ATTRIBUTE_NOT_FOUND"))
        .strict(CelException.Code.NO_SUCH_KEY)
        .error(CelException.Code.NO_SUCH_KEY);
    c("nested-index-past-end", "{'a': [1]}.a[1]")
        .upstream(Upstream.folds("INDEX_OUT_OF_BOUNDS"))
        .strict(CelException.Code.INDEX)
        .error(CelException.Code.INDEX);
    c("index-into-int", "{'a': 1}['a']['b']").type(CelException.Code.NO_OVERLOAD);
    c("deep-index-into-int", "[[1]][0][0][0]").type(CelException.Code.NO_OVERLOAD);
    c("select-on-map-value-int", "{'a': 1}.a.b").type(CelException.Code.NO_OVERLOAD);
    c("presence-on-list", "has(xs.a)").type(CelException.Code.NO_OVERLOAD);
    c("presence-missing-map-key", "has(m.missing)").value(false);
    c("element-division-by-zero", "xs[0] / 0")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("in-missing-key", "'missing' in m").value(false);
    c("size-of-map-key", "size(m['missing'])")
        .error(CelException.Code.NO_SUCH_KEY, "ATTRIBUTE_NOT_FOUND");
    c("dyn-index-on-int", "dynv[0]").error(CelException.Code.NO_OVERLOAD, "OVERLOAD_NOT_FOUND");
    c("dyn-list-select", "dyn([1]).size")
        .upstream(Upstream.folds("ATTRIBUTE_NOT_FOUND"))
        .strict(CelException.Code.NO_OVERLOAD)
        .error(CelException.Code.NO_OVERLOAD);
    c("list-index-uint", "xs[1u]").type(Outcome.value(2L));
    c("map-of-list-missing", "{'a': [1, 2]}['b'][0]")
        .upstream(Upstream.folds("ATTRIBUTE_NOT_FOUND"))
        .strict(CelException.Code.NO_SUCH_KEY)
        .error(CelException.Code.NO_SUCH_KEY);
    c("error-inside-list-literal", "[1 / 0].size()")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("error-inside-map-literal", "has({'a': 1 / 0}.a)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("error-inside-size", "size([1 / 0, 2])")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
  }

  private static void arithmetic() {
    current = Area.ARITHMETIC;
    c("int-add-overflow", "9223372036854775807 + 1")
        .upstream(Upstream.folds("NUMERIC_OVERFLOW"))
        .strict(CelException.Code.OVERFLOW)
        .error(CelException.Code.OVERFLOW);
    c("int-add-overflow-variable", "x + 1")
        .bind("x", Long.MAX_VALUE)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-subtract-overflow", "x - 1")
        .bind("x", Long.MIN_VALUE)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-multiply-overflow", "x * 2")
        .bind("x", Long.MAX_VALUE)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-divide-overflow", "x / -1")
        .bind("x", Long.MIN_VALUE)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-negate-overflow", "-x")
        .bind("x", Long.MIN_VALUE)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-min-modulo", "x % -1").bind("x", Long.MIN_VALUE).value(0L);
    c("uint-add-overflow", "18446744073709551615u + 1u")
        .upstream(Upstream.folds("NUMERIC_OVERFLOW"))
        .strict(CelException.Code.OVERFLOW)
        .error(CelException.Code.OVERFLOW);
    c("uint-subtract-underflow", "0u - 1u")
        .upstream(Upstream.folds("NUMERIC_OVERFLOW"))
        .strict(CelException.Code.OVERFLOW)
        .error(CelException.Code.OVERFLOW);
    c("uint-multiply-overflow", "u * 2u")
        .bind("u", new UInt(-1L))
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("uint-subtract-variable", "u - 4u").error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-divide-by-zero", "x / 0").error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("int-modulo-by-zero", "x % 0").error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("constant-divide-by-zero", "1 / 0")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("constant-modulo-by-zero", "1 % 0")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("uint-divide-by-zero", "u / 0u").error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("uint-modulo-by-zero", "u % 0u").error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("double-divide-by-zero", "d / 0.0").value(Double.POSITIVE_INFINITY);
    c("timestamp-plus-duration-overflow", "t + dur")
        .bind("t", Instant.parse("9999-12-31T23:59:59Z"))
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), TIME_RANGE))
        .error(CelException.Code.OVERFLOW);
    c("timestamp-minus-duration-underflow", "t - dur")
        .bind("t", Instant.parse("0001-01-01T00:00:00Z"))
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), TIME_RANGE))
        .error(CelException.Code.OVERFLOW);
    c("timestamp-difference-spans-range", "t - timestamp('0001-01-01T00:00:00Z')")
        .bind("t", Instant.parse("9999-12-31T23:59:59Z"))
        .upstream(
            Upstream.diverges(
                Upstream.rejects(Phase.EVALUATE),
                "cel-java's duration arithmetic overflows below the protobuf Duration range"))
        .value(ANY);
    c("duration-plus-duration-overflow", "dur + dur")
        .bind("dur", Duration.ofSeconds(315576000000L))
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), TIME_RANGE))
        .error(CelException.Code.OVERFLOW);
    c("duration-minus-duration-overflow", "dur - duration('1s')")
        .bind("dur", Duration.ofSeconds(-315576000000L))
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), TIME_RANGE))
        .error(CelException.Code.OVERFLOW);
    c("constant-timestamp-overflow", "timestamp('9999-12-31T23:59:59Z') + duration('1s')")
        .upstream(Upstream.diverges(Upstream.folds("INTERNAL_ERROR"), TIME_RANGE))
        .strict(CelException.Code.OVERFLOW)
        .error(CelException.Code.OVERFLOW);
    c("invalid-regex", "'abc'.matches('(')")
        .upstream(Upstream.folds("INVALID_ARGUMENT"))
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT);
    c("invalid-regex-variable", "s.matches('(')")
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT, "INVALID_ARGUMENT");
    c("regex-replacement-invalid-group", "regex.replace(s, '(.)', r'\\2')")
        .bind("s", "a")
        .upstream(
            Upstream.diverges(
                Upstream.rejects("INTERNAL_ERROR"),
                "cel-java reports regex extension validation failures as INTERNAL_ERROR"))
        .error(CelException.Code.INVALID_ARGUMENT);
    c("regex-extract-too-many-groups", "regex.extract(s, '(a)(b)')")
        .bind("s", "ab")
        .upstream(
            Upstream.diverges(
                Upstream.rejects("INTERNAL_ERROR"),
                "cel-java reports regex extension validation failures as INTERNAL_ERROR"))
        .error(CelException.Code.INVALID_ARGUMENT);
    c("regex-extract-all-too-many-groups", "regex.extractAll(s, '(a)(b)')")
        .bind("s", "z")
        .upstream(
            Upstream.diverges(
                Upstream.rejects("INTERNAL_ERROR"),
                "cel-java reports regex extension validation failures as INTERNAL_ERROR"))
        .error(CelException.Code.INVALID_ARGUMENT);
    c("regex-zero-count-invalid-pattern", "regex.replace(s, '[', 'x', 0)")
        .bind("s", "a")
        .value("a");
    c("invalid-time-zone", "t.getFullYear('Not/AZone')")
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("overflow-absorbed-by-or", "true || x + 1 > 0").bind("x", Long.MAX_VALUE).value(true);
    c("overflow-in-comparison", "x + 1 > 0")
        .bind("x", Long.MAX_VALUE)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("overflow-in-list", "[x + 1][0]")
        .bind("x", Long.MAX_VALUE)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
  }

  private static void conversions() {
    current = Area.CONVERSIONS;
    c("int-of-huge-double", "int(1e300)")
        .upstream(Upstream.folds("NUMERIC_OVERFLOW"))
        .strict(CelException.Code.OVERFLOW)
        .error(CelException.Code.OVERFLOW);
    c("int-of-huge-double-variable", "int(d)")
        .bind("d", 1e300)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-of-nan", "int(d)")
        .bind("d", Double.NaN)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-of-infinity", "int(d)")
        .bind("d", Double.POSITIVE_INFINITY)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-of-two-pow-63", "int(d)")
        .bind("d", 9223372036854775808.0)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("uint-of-negative-int", "uint(x)")
        .bind("x", -1L)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("uint-of-negative-double", "uint(d)")
        .bind("d", -1.0)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("uint-of-huge-double", "uint(d)")
        .bind("d", 1e20)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-of-large-uint", "int(u)")
        .bind("u", new UInt(-1L))
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("int-of-invalid-string", "int(s)")
        .bind("s", "abc")
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("int-of-out-of-range-string", "int(s)")
        .bind("s", "9223372036854775808")
        .upstream(Upstream.rejects("BAD_FORMAT"))
        .add(Outcome.error(Phase.EVALUATE, null));
    c("uint-of-invalid-string", "uint(s)")
        .bind("s", "abc")
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("uint-of-negative-string", "uint(s)")
        .bind("s", "-1")
        .upstream(Upstream.rejects("BAD_FORMAT"))
        .add(Outcome.error(Phase.EVALUATE, null));
    c("double-of-invalid-string", "double(s)")
        .bind("s", "abc")
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("bool-of-invalid-string", "bool(s)")
        .bind("s", "yes")
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("string-of-invalid-utf8", "string(bs)")
        .bind("bs", new Bytes(new byte[] {(byte) 0xff}))
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("timestamp-of-invalid-string", "timestamp(s)")
        .bind("s", "not a date")
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("timestamp-past-maximum", "timestamp(x)")
        .bind("x", 253402300800L)
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), TIME_RANGE))
        .error(CelException.Code.OVERFLOW);
    c("timestamp-before-minimum", "timestamp(x)")
        .bind("x", -62135596801L)
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), TIME_RANGE))
        .error(CelException.Code.OVERFLOW);
    c("timestamp-string-past-maximum", "timestamp(s)")
        .bind("s", "10000-01-01T00:00:00Z")
        .upstream(Upstream.rejects("BAD_FORMAT"))
        .add(Outcome.error(Phase.EVALUATE, null));
    c("duration-of-invalid-string", "duration(s)")
        .bind("s", "abc")
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("duration-of-huge-string", "duration(s)")
        .bind("s", "315576000001s")
        .upstream(Upstream.rejects("BAD_FORMAT"))
        .error(CelException.Code.OVERFLOW);
    c("duration-of-unitless-string", "duration(s)")
        .bind("s", "100")
        .error(CelException.Code.INVALID_ARGUMENT, "BAD_FORMAT");
    c("constant-invalid-int-string", "int('abc')")
        .upstream(Upstream.folds("BAD_FORMAT"))
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT);
    c("constant-invalid-timestamp", "timestamp('abc')")
        .upstream(Upstream.rejects("BAD_FORMAT"))
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT);
    c("int32-wrapper-overflow", "google.protobuf.Int32Value{value: 2147483648}")
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("uint32-wrapper-overflow", "google.protobuf.UInt32Value{value: 4294967296u}")
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("duration-message-out-of-range", "google.protobuf.Duration{seconds: 999999999999}")
        .upstream(WKT_UNVALIDATED)
        .error(CelException.Code.OVERFLOW);
    c("duration-message-sign-mismatch", "google.protobuf.Duration{seconds: 1, nanos: -1}")
        .upstream(WKT_UNVALIDATED)
        .error(CelException.Code.OVERFLOW);
    c("timestamp-message-negative-nanos", "google.protobuf.Timestamp{nanos: -1}")
        .upstream(WKT_UNVALIDATED)
        .error(CelException.Code.OVERFLOW);
    c("wrapper-unknown-field", "google.protobuf.Int32Value{no: 1}").staticType();
    c("wrapper-field-type", "google.protobuf.Int32Value{value: 'a'}")
        .type(CelException.Code.NO_OVERLOAD);
  }

  private static void shortCircuit() {
    current = Area.SHORT_CIRCUIT;
    c("false-and-error", "false && (1 / 0 == 0)").value(false);
    c("error-and-false", "(1 / 0 == 0) && false").value(false);
    c("true-or-error", "true || (1 / 0 == 0)").value(true);
    c("error-or-true", "(1 / 0 == 0) || true").value(true);
    c("ternary-skips-error-else", "true ? 7 : 1 / 0").value(7L);
    c("ternary-skips-error-then", "false ? 1 / 0 : 7").value(7L);
    c("variable-false-and-error", "b && (x / 0 == 0)").bind("b", false).value(false);
    c("variable-true-or-error", "b || (x / 0 == 0)").value(true);
    c("variable-ternary", "b ? x : x / 0").value(1L);
    c("error-or-false", "(x / 0 == 0) || false")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("error-and-true", "(x / 0 == 0) && true")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("error-condition", "(x / 0 == 0) ? 1 : 2")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("both-operands-error", "(x / 0 == 0) || (x % 0 == 0)")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("different-errors-unspecified-category", "(x / 0 == 0) || (xs[9] == 0)")
        .upstream(Upstream.rejects(Phase.EVALUATE))
        .add(Outcome.error(Phase.EVALUATE, null));
    c("exists-absorbs-error", "[1, 2].exists(i, 1 / (i - 1) == 1)").value(true);
    c("exists-propagates-error", "[1, 3].exists(i, 1 / (i - 1) == 1)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("all-propagates-error", "[1, 2].all(i, 1 / (i - 1) == 1)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("all-false-before-error", "[1, 2].all(i, 1 / (i - 2) == 1)").value(false);
    c("all-error-after-true", "[2, 1].all(i, 1 / (i - 1) == 1)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("all-error-with-false-disjunct", "[1, 0].all(i, i / 0 == 0 || false)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("exists-one-propagates-error", "[1, 2].exists_one(i, 1 / (i - 1) == 1)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("filter-propagates-error", "[1, 2].filter(i, 1 / (i - 1) == 1)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("map-propagates-error", "[1, 2].map(i, 1 / (i - 1))")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("variable-all-propagates-error", "xs.all(i, 1 / (i - 1) == 1)")
        .bind("xs", List.of(1L, 2L))
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("variable-all-false-wins-over-error", "xs.all(i, 1 / (i - 1) == 1)").value(false);
    c("variable-exists-absorbs-error", "xs.exists(i, 1 / (i - 1) == 1)").value(true);
    c("exists-or-inside-predicate", "[1, 2, 3].exists(i, i == 2 || 1 / 0 == 0)").value(true);
    c("missing-key-absorbed", "m['missing'] in ['x'] || true").value(true);
    c("optional-fallback-skipped", "optional.of(1).orValue(1 / 0)").value(1L);
    c("optional-fallback-evaluated", "optional.none().orValue(1 / 0)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("optional-chain-fallback-evaluated", "{'a': 1}.?b.orValue(1 / 0)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("variable-optional-fallback-evaluated", "m.?missing.orValue(string(x / 0))")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("binding-initializer-error", "cel.bind(v, 1 / 0, true)").value(true);
    c("block-unused-slot-error", "cel.block([1 / 0], true)")
        .upstream(
            Upstream.notApplicable("cel.block is produced by cel-java's optimizer, not parsed"))
        .value(true);
    c("block-used-slot-error", "cel.block([1 / 0], cel.index(0) == 1)")
        .upstream(
            Upstream.notApplicable("cel.block is produced by cel-java's optimizer, not parsed"))
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("not-strictly-false-absorbs", "[1].all(i, i / 0 == 0) || true").value(true);
    c("nested-ternary-error", "b ? (x / 0 == 0 ? 1 : 2) : 3")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("in-absorbs-error-right", "false && (1 in [1 / 0])").value(false);
  }

  private static void comprehensions() {
    current = Area.COMPREHENSIONS;
    c("map-division-by-zero", "[1, 2, 3].map(i, i / (i - 2))")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("filter-missing-key", "xs.filter(i, m[string(i)] == 'a')")
        .error(CelException.Code.NO_SUCH_KEY, "ATTRIBUTE_NOT_FOUND");
    c("predicate-field-on-int", "[1, 2].all(i, i.foo)").type(CelException.Code.NO_OVERLOAD);
    c("macro-extra-argument", "[1].all(i, true, false)").syntax();
    c("mixed-list-element-arithmetic", "[[1], ['a']].map(l, l[0] + 1)")
        .upstream(Upstream.folds("OVERLOAD_NOT_FOUND"))
        .strict(CelException.Code.NO_OVERLOAD)
        .error(CelException.Code.NO_OVERLOAD);
    c("exists-one-division", "[1, 2, 3].exists_one(i, i / 0 == 0)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("transform-map-division", "{'a': 1}.transformMap(k, v, v / 0)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("variable-transform-map-division", "m.transformMap(k, v, x / 0)")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("map-filter-form-undeclared", "{'a': 1}.map(k, v, v / 0)").type(CelException.Code.UNDECLARED);
    c("mapped-index-past-end", "[1].map(i, i)[1]")
        .upstream(Upstream.folds("INDEX_OUT_OF_BOUNDS"))
        .strict(CelException.Code.INDEX)
        .error(CelException.Code.INDEX);
    c("filter-then-index", "xs.filter(i, i > 10)[0]")
        .error(CelException.Code.INDEX, "INDEX_OUT_OF_BOUNDS");
    c("nested-comprehension-error", "xs.all(a, xs.all(b, a / (b - 2) >= -10))")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("map-filter-error", "xs.map(i, i > 1, i / (i - 2))")
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("map-filter-non-bool", "xs.map(i, i, i)").type(CelException.Code.NO_OVERLOAD);
    c("range-is-map-key-error", "{'a': 1}.map(k, k / 0)").type(CelException.Code.NO_OVERLOAD);
    c("overflow-in-accumulator", "[x, 1].map(i, i + 1)")
        .bind("x", Long.MAX_VALUE)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("string-range", "s.all(i, true)").type(CelException.Code.NO_OVERLOAD);
    c("exists-error-no-true", "[0, 0].exists(i, 1 / i == 1)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
  }

  private static void optionals() {
    current = Area.OPTIONALS;
    c("none-value", "optional.none().value()")
        .upstream(Upstream.diverges(Upstream.folds("INTERNAL_ERROR"), OPTIONAL_VALUE))
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT);
    c("missing-key-optional-value", "{'a': 1}.?b.value()")
        .upstream(Upstream.diverges(Upstream.folds("INTERNAL_ERROR"), OPTIONAL_VALUE))
        .strict(CelException.Code.INVALID_ARGUMENT)
        .error(CelException.Code.INVALID_ARGUMENT);
    c("optional-index-past-end-value", "xs[?5].value()")
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), OPTIONAL_VALUE))
        .error(CelException.Code.INVALID_ARGUMENT);
    c("or-value-type-mismatch", "optional.of(1).orValue('a')").type(Outcome.value(1L));
    c("or-non-optional", "optional.of(1).or(2)").type(Outcome.value(ANY));
    c("optional-list-entry-non-optional", "[?1]").type(CelException.Code.NO_OVERLOAD);
    c("optional-map-entry-non-optional", "{?'a': 1}").type(CelException.Code.NO_OVERLOAD);
    c("optional-plus-int", "optional.none() + 1").type(CelException.Code.NO_OVERLOAD);
    c("of-non-zero-error", "optional.ofNonZeroValue(1 / 0)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("optional-select-on-int", "x.?y").type(CelException.Code.NO_OVERLOAD);
    c("has-value-on-int", "x.hasValue()").type(CelException.Code.NO_OVERLOAD);
    c("optional-value-division", "optional.of(1).value() / 0")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("optional-of-error", "optional.of(1 / 0)")
        .upstream(Upstream.folds("DIVIDE_BY_ZERO"))
        .strict(CelException.Code.DIVIDE_BY_ZERO)
        .error(CelException.Code.DIVIDE_BY_ZERO);
    c("optional-none-select-propagates", "optional.none().foo.hasValue()").value(false);
    c("optional-chain-absent-value", "m.?missing.value()")
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), OPTIONAL_VALUE))
        .error(CelException.Code.INVALID_ARGUMENT);
    c("optional-chain-present", "m.?a.orValue('z')").value("b");
    c("optional-of-null-value", "optional.of(null).hasValue()").value(true);
    c("optional-index-string-list", "xs[?'a']").type(CelException.Code.NO_OVERLOAD);
  }

  private static void protobuf() {
    current = Area.PROTOBUF;
    c("proto-missing-field", "msg.no_such").config(PROTO).type(CelException.Code.NO_SUCH_FIELD);
    c("proto-nested-missing-field", "msg.single_nested_message.no_such")
        .config(PROTO)
        .type(CelException.Code.NO_SUCH_FIELD);
    c("proto-literal-unknown-field", PROTO3 + "{no_such: 1}").config(PROTO).staticType();
    c("proto-literal-field-type", PROTO3 + "{single_int32: 'a'}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-int32-overflow", PROTO3 + "{single_int32: 2147483648}")
        .config(PROTO)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("proto-literal-uint32-overflow", PROTO3 + "{single_uint32: 4294967296u}")
        .config(PROTO)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("proto-literal-int32-wrapper-overflow", PROTO3 + "{single_int32_wrapper: 2147483648}")
        .config(PROTO)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("proto-literal-enum-overflow", PROTO3 + "{standalone_enum: 2147483648}")
        .config(PROTO)
        .upstream(
            Upstream.diverges(
                Upstream.rejects("INTERNAL_ERROR"),
                "cel-java reports enum range failures as INTERNAL_ERROR"))
        .error(CelException.Code.OVERFLOW);
    c("proto-literal-enum-string", PROTO3 + "{standalone_enum: 'FOO'}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-timestamp-int", PROTO3 + "{single_timestamp: 1}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-duration-string", PROTO3 + "{single_duration: 'x'}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-repeated-scalar", PROTO3 + "{repeated_int32: 1}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-repeated-element-type", PROTO3 + "{repeated_int32: ['a']}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-map-key-type", PROTO3 + "{map_string_string: {1: 'a'}}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-uint-into-int64", PROTO3 + "{single_int64: 1u}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-int-into-double", PROTO3 + "{single_double: 1}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-struct-int-keys", PROTO3 + "{single_struct: {1: 1}}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-struct-list", PROTO3 + "{single_struct: [1]}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-list-value-int", PROTO3 + "{list_value: 1}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-wrapper-string", PROTO3 + "{single_int32_wrapper: 'a'}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-null-scalar", PROTO3 + "{single_int32: null}")
        .config(PROTO)
        .type(ILL_TYPED_CONSTRUCTION);
    c("proto-literal-null-wrapper", PROTO3 + "{single_bool_wrapper: null}.single_bool_wrapper")
        .config(PROTO)
        .value(null);
    c(
            "proto-literal-null-message",
            PROTO3 + "{single_nested_message: null}.single_nested_message.bb")
        .config(PROTO)
        .value(0L);
    c("proto-literal-duplicate-field", PROTO3 + "{single_int32: 1, single_int32: 2}")
        .config(PROTO)
        .upstream(
            Upstream.diverges(
                Upstream.accepts(), "cel-java accepts duplicate message literal fields"))
        .syntax();
    c("proto-unset-wrapper-arithmetic", "msg.single_int32_wrapper + 1")
        .config(PROTO)
        .error(CelException.Code.NO_OVERLOAD, "OVERLOAD_NOT_FOUND");
    c("proto-unset-wrapper-is-null", "msg.single_int32_wrapper == null").config(PROTO).value(true);
    c("proto-unset-nested-default", "msg.single_nested_message.bb").config(PROTO).value(0L);
    c("proto-unset-nested-presence", "has(msg.single_nested_message.bb)")
        .config(PROTO)
        .value(false);
    c("proto-unset-scalar-presence", "has(msg.single_int32)").config(PROTO).value(false);
    c("proto-unset-any-select", "msg.single_any.foo")
        .config(PROTO)
        .error(CelException.Code.NO_OVERLOAD, "INTERNAL_ERROR");
    c("proto-unset-value-select", "msg.single_value.foo")
        .config(PROTO)
        .error(CelException.Code.NO_OVERLOAD, "ATTRIBUTE_NOT_FOUND");
    c("proto-unset-struct-key", "msg.single_struct.foo")
        .config(PROTO)
        .error(CelException.Code.NO_SUCH_KEY, "ATTRIBUTE_NOT_FOUND");
    c("proto-unset-list-value-index", "msg.list_value[0]")
        .config(PROTO)
        .error(CelException.Code.INDEX, "INDEX_OUT_OF_BOUNDS");
    c("proto-empty-repeated-index", "msg.repeated_int64[0]")
        .config(PROTO)
        .error(CelException.Code.INDEX, "INDEX_OUT_OF_BOUNDS");
    c("proto-map-missing-key", "msg.map_string_string['x']")
        .config(PROTO)
        .error(CelException.Code.NO_SUCH_KEY, "ATTRIBUTE_NOT_FOUND");
    c("proto-map-missing-field-select", "msg.map_string_string.x")
        .config(PROTO)
        .error(CelException.Code.NO_SUCH_KEY, "ATTRIBUTE_NOT_FOUND");
    c("proto-field-plus-string", "msg.single_int32 + 'a'")
        .config(PROTO)
        .type(CelException.Code.NO_OVERLOAD);
    c("proto-message-equals-int", "msg == 1").config(PROTO).type(Outcome.value(false));
    c("proto-size-of-message", "size(msg)").config(PROTO).type(CelException.Code.NO_OVERLOAD);
    c("proto-index-message", "msg['single_int32']")
        .config(PROTO)
        .type(CelException.Code.NO_OVERLOAD);
    c("proto-unknown-enum-constant", PROTO3 + ".NestedEnum.NO_SUCH")
        .config(PROTO)
        .type(CelException.Code.NO_OVERLOAD);
    c("proto-field-overflow", "msg.single_int64 + 9223372036854775807")
        .config(PROTO)
        .error(CelException.Code.OVERFLOW, "NUMERIC_OVERFLOW");
    c("proto-dyn-missing-field", "dyn(msg).no_such")
        .config(PROTO)
        .error(CelException.Code.NO_SUCH_FIELD, "INTERNAL_ERROR");
    c("proto-dyn-missing-presence", "has(dyn(msg).no_such)")
        .config(PROTO)
        .error(CelException.Code.NO_SUCH_FIELD, "INTERNAL_ERROR");
    c("proto-wrapper-field-select", "msg.single_int32_wrapper.value")
        .config(PROTO)
        .type(CelException.Code.NO_OVERLOAD);
    c("proto-binding-type-mismatch", "msg.single_int64")
        .config(PROTO)
        .bind("msg", nested3(Map.of("bb", 1L)))
        .error(CelException.Code.NO_SUCH_FIELD, "INTERNAL_ERROR");
    c("proto2-default-value", "msg2.single_int32").config(PROTO).value(-32L);
    c("proto2-default-presence", "has(msg2.single_int32)").config(PROTO).value(false);
    c("proto2-missing-field", "msg2.no_such").config(PROTO).type(CelException.Code.NO_SUCH_FIELD);
    c(
            "proto2-unregistered-extension",
            "proto.getExt(msg2, cel.expr.conformance.proto2.no_such_ext)")
        .config(PROTO)
        .upstream(Upstream.notApplicable("cel-java extension registry differs"))
        .type(CelException.Code.NO_SUCH_FIELD);
    c(
            "proto-any-unknown-type-url",
            "google.protobuf.Any{type_url: 'type.googleapis.com/no.such.Type'}")
        .config(PROTO)
        .error(CelException.Code.INVALID_ARGUMENT, "INTERNAL_ERROR");
    c("proto-optional-unset-scalar-value", "msg.?single_int32.value()")
        .config(PROTO)
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), OPTIONAL_VALUE))
        .error(CelException.Code.INVALID_ARGUMENT);
    c("proto-literal-field-division", PROTO3 + "{single_int64: x / 0}")
        .config(PROTO)
        .error(CelException.Code.DIVIDE_BY_ZERO, "DIVIDE_BY_ZERO");
    c("proto-timestamp-overflow", "msg.single_timestamp - dur")
        .config(PROTO)
        .bind("msg", proto3(Map.of("single_timestamp", Instant.parse("0001-01-01T00:00:00Z"))))
        .upstream(Upstream.diverges(Upstream.rejects("INTERNAL_ERROR"), TIME_RANGE))
        .error(CelException.Code.OVERFLOW);
    c("proto-container-unknown-field", "TestAllTypes{no_such: 1}")
        .config(PROTO.container("cel.expr.conformance.proto3"))
        .staticType();
    c("proto-container-unknown-message", "NoSuch{}")
        .config(PROTO.container("cel.expr.conformance.proto3"))
        .staticType();
  }
}
