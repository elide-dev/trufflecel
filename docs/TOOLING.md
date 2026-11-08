# Configuration and policy tooling

These APIs are part of the core artifact. AST protobuf interchange is in
`trufflecel-protobuf`; neither artifact depends on cel-java.

## Environment YAML

`EnvironmentYaml.read(yaml)` imports data-only declarations. To supply application
object types, pass an explicitly configured builder as the second argument.
`EnvironmentYaml.write(environment)` exports declarations and limits; host function
implementations must be supplied again as per-evaluation bindings when importing.
Callbacks, custom macros, validators and application constants are not serialized.
Export rejects configurations containing those nonportable values instead of
silently dropping them. Object adapters remain explicit host registrations.

```java
Environment environment = EnvironmentYaml.read("""
    variables:
      - {name: request, type: 'map<string, dyn>'}
    functions:
      - name: permitted
        overloads:
          - {id: permitted_string, args: [string], return: bool}
    """);
var options = EvaluationOptions.newBuilder()
    .function("permitted_string", arguments -> arguments.getFirst().equals("reader"))
    .build();
boolean allowed = (Boolean) environment.compile("permitted(request.role)")
    .evaluate(Map.of("request", Map.of("role", "reader")), options);
```

Types accept both `type_name`/`params` mappings and shorthand such as
`map<string, list<int>>`, `optional<int>`, `$T`, and qualified object names.
`TypeSyntax` exposes the same bounded parser and formatter. Environments support
container aliases/abbreviations, variable declarations, global and receiver
function signatures, generic type parameters, library versions, selected standard
macros, function-name allowlists, expected result types and TruffleCEL limits.
`context_variable` exposes fields of an explicitly registered adapter as root
variables; use `ProtobufType.context` to supply the corresponding owned activation.
The official `two-var-comprehensions` library name maps to `comprehensions`.
Standard macro selections preserve separately enabled extension macros.

TruffleCEL-specific feature keys are `trufflecel.macros`,
`trufflecel.infer_variables`, `trufflecel.homogeneous_literals`,
`trufflecel.strict_validation`, and `trufflecel.common_subexpressions`.
Macro-call tracking is always retained. Unsupported feature names, settings and
schema fields fail explicitly. Configuration parsing has a one-million-character
cap, bounded nesting and work, rejects duplicate keys and object-construction tags,
and does not load files, classes or network resources.

## CEL Policy

`PolicyCompiler.prepare(environment, yaml)` returns a reusable `PreparedProgram`;
`compile` instantiates it immediately. Policies support ordered lazy variables,
lexical shadowing, imported registered type names, first-match rules, aggregate
rules and nested first-match rules. Conditions must be boolean, explanations must
be strings, output types must agree, and unreachable/static-false choices are
rejected. A variable initializer runs at most once when reached during evaluation.

```java
var program = PolicyCompiler.compile(
    Cel.newBuilder().variable("score", CelType.INT).build(), """
    name: risk-labels
    rule:
      aggregate:
        - {condition: 'score > 10', output: "'review'"}
        - {condition: 'score > 100', output: "'escalate'"}
    """);
// ["review", "escalate"]
Object labels = program.evaluate(Map.of("score", 150));
```

A matching output is returned directly. An unmatched first-match rule returns
`OptionalValue.none()`, so a non-exhaustive policy can have a dynamic result type.
An aggregate always returns a list. Nested absence is pruned; explicit list or
optional outputs remain values. An unconditional incomplete subrule can fall
through to later choices, while a selected conditional subrule returns its result,
including absence. Aggregates cannot nest inside aggregates. Generated lazy and
optional helpers do not enlarge the functions allowed in user expressions.

Policy block scalars preserve original newlines and leading spaces, including
CEL line comments. Runtime and compile diagnostics retain policy-document source
locations. For quoted YAML scalars, columns after an escape refer to the decoded
expression; use block scalars when exact document columns are needed. Each embedded
expression and the combined AST retain configured size,
nesting and evaluation limits. Invariant declarations can be read as data with
`PolicyCompiler.invariants`; compilation does not prove them. Formal verification
is outside the 0.10.0 scope.

The complete pinned [CEL Policy corpus](../third_party/cel-policy/UPSTREAM.md) runs
in `//src/test/java/dev/trufflecel:policyConformance`. Its Kubernetes-shaped example
uses the upstream test-only tag transformation in the harness; the core compiler
accepts CEL Policy documents and does not implement Kubernetes admission matching.

## Cost bounds and fuzzing

`CostEstimator.estimate(environment, source, sizeBounds)` reports conservative
bounds on charged CEL operations. Collection-size bounds are explicit caller
guarantees. An absent maximum names the work that could not be bounded; it is
never a finite estimate of host callback time, CPU time or allocation. Execution
budgets and cancellation remain necessary independently of static estimation.

`//tools/fuzz:regression` exercises the seed corpus and deterministic mutations.
On Linux x86-64, `//tools/fuzz:smoke` runs Jazzer against parsing, checking,
canonical AST imports, JSON and YAML decoding. It requires nonzero instrumented
coverage and preserves its coverage report, log and findings as test outputs.
For a longer campaign use `--test_env=TRUFFLECEL_FUZZ_RUNS=100000`.
Jazzer is an isolated test-only Maven resolution and is absent from publications.

## Platform scope

TruffleCEL uses the repository-pinned Java 25/GraalVM toolchain. Java 8 and Android
compatibility would require a separate runtime and build architecture; they are
not provided by these APIs. JVM qualification does not establish Native Image
compatibility: the native JIT/blocklist gate exercises the shipped paths too.
