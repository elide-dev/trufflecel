# TruffleCEL

[![CI](https://github.com/elide-dev/trufflecel/actions/workflows/ci.yml/badge.svg)](https://github.com/elide-dev/trufflecel/actions/workflows/ci.yml)
[![CodSpeed](https://img.shields.io/endpoint?url=https://codspeed.io/badge.json%3Frepository%3Delide-dev/trufflecel)](https://codspeed.io/elide-dev/trufflecel)
[![Java 25](https://img.shields.io/badge/Java-25-blue)](https://www.graalvm.org/)

**CEL expressions, executed by Truffle.** An independent parser, gradual type checker,
and Truffle AST evaluator built for embedding in Elide. No CEL Java dependency or fallback.

```java
var policy = Cel.compile("user.age >= 18 && 'admin' in user.roles");
boolean allowed = policy.test(Map.of(
    "user", Map.of("age", 30, "roles", List.of("admin"))));
```

Compile once and reuse across threads. Evaluation has its own activation, comprehension
scopes and operation budget. Host collections are snapshotted, and failures carry stable
CEL error codes. Native TruffleData rows, protobuf messages and Oracle JSON objects have
explicit adapters; expressions cannot discover arbitrary Java methods.

## Choose an artifact

| Maven coordinate | Purpose |
| --- | --- |
| `dev.elide:trufflecel-core` | Compiler, evaluator, Polyglot language and native TruffleData rows |
| `dev.elide:trufflecel-protobuf` | Optional protobuf construction, ingestion and wire decoding |
| `dev.elide:trufflecel-json` | Optional Oracle Truffle JSON input conversion |

All three are thin JARs. The optional bridges depend on the matching core version; core
does not pull in protobuf, JSON, JDBC drivers or test libraries. The runtime baseline is
**Java 25**, **Elide GraalVM `25.5.5-elide.5`**, and **TruffleData `0.1.0-alpha.3`**.

Use a published version from [Releases](https://github.com/elide-dev/trufflecel/releases):

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.elide.dev")
    maven("https://maven.elide.dev/gvm-elide/25.5.5-elide.5")
}
dependencies {
    implementation("dev.elide:trufflecel-core:<version>")
    // Add trufflecel-protobuf or trufflecel-json as needed.
}
```

The release pipeline can also stage artifacts locally before a first public release.
See [publishing](docs/PUBLISHING.md) for Maven/Bazel consumption and release operations.

## Embed

`Cel.compile(source)` infers free variables as `dyn`. Declare a fixed environment when
you want compile-time validation:

```java
var env = Cel.newBuilder()
    .variable("balance", CelType.INT)
    .variable("enabled", CelType.BOOL)
    .evaluationBudget(10_000)
    .build();
var program = env.compile("enabled && balance > 0");
boolean allowed = program.test(Map.of("balance", 42L, "enabled", true));
```

`Program.evaluate` returns a value; `Program.test` requires a boolean. `source`, `variables`
and `resultType` describe the compiled program. Explicit `ObjectType` and function bindings
extend the environment. The registered Polyglot language ID is `cel`.

For TruffleData, bind a `DataType` to a schema and pass immutable batch/JDBC rows directly.
For protobuf, register a `ProtobufType` and ingest through `input`. `JsonValues.from` creates
bounded, immutable CEL snapshots of Oracle `JSONObject` and `JSONArray` trees.

[Integration notes](docs/INTEGRATION.md) cover Elide's Kotlin facade, numeric and presence
semantics, native rows, well-known protobuf mappings, and adapter limitations.

## Build and verify

Bazelisk selects the pinned Bazel version. Builds default to BuildBuddy Linux amd64 remote
execution and caching, following Komodo. Dependencies and toolchains are pinned. Configure
credentials outside Git, or append `--config=local` for host JVM execution.

```sh
bazel build //:trufflecel //:trufflecel-protobuf //:trufflecel-json
bazel test //...
bazel coverage //src/test/...
bazel run //tools:coverage --config=local
```

JARs are under `bazel-bin/src/main/java/dev/trufflecel/`. Gazelle owns the Java library and
test target source lists and dependency mappings. Run workspace tools locally on macOS:

```sh
bazel run //:gazelle --config=local
bazel run //tools:format --config=local
```

The reusable CI workflows check generated targets, formatting, tests, coverage, Maven
publications, and an independent JVM consumer of the publication JARs. External actions
are pinned by commit; runner hardening, limited permissions, fork-safe credentials and
serialized publishing are included. See [CI setup](docs/CI.md) and [Bazel setup](docs/BAZEL.md).

## Performance and releases

Every pull request and main update runs eight **CodSpeed simulation** cases: compilation
plus evaluation, cached arithmetic, short-circuiting, comprehensions, regex, native rows,
protobuf and JSON-backed predicates. Bazel builds a Native Image executable on RBE; the
Linux CI runner measures named regions with checksum-verified results. Startup, fixture
creation and warmup are outside the regions. These are native-AOT instruction measurements,
not JVM/JIT throughput claims. The benchmark explicitly uses Truffle’s fallback interpreter;
optimizing guest-JIT Native Image qualification remains unresolved. [Benchmark details](docs/BENCHMARKS.md).

After successful main checks, Release Please opens version/changelog PRs from conventional
commits. Merging a release PR creates a version tag and publishes its exact contents to
`maven.elide.dev`, with source JARs, POMs and checksums. Existing versions are immutable.

## Compatibility and scope

The conformance suite runs **1,905 cases across 20 upstream core CEL suites**, with no
per-case skips, pinned to cel-spec revision `21ec6f4e52e8d694687ff9ed7408d3eccbb955d7`.
It checks values, errors, namespaces, declarations, macros and inferred types.
[Corpus provenance](third_party/cel-spec/README.md).

CI requires **98% production line coverage and 90% branch coverage**, including optional
adapters. Additional tests cover resource limits, concurrency, interop, published TruffleData
artifacts and Maven consumption. The core corpus uses an independent test-only protobuf
fixture; adapter tests separately exercise the published protobuf implementation.

This does not certify every CEL extension. Full optional/two-variable macro and extended
string/list/math/network suites, canonical protobuf AST serialization, and a proto2 extension
registry are not yet provided. The protobuf bridge still retains its immutable source message
for semantics not exposed by native rows; [TruffleData #1](https://github.com/elide-dev/truffledata/issues/1)
tracks those APIs. Native benchmark qualification does not replace qualification of an Elide
Native Image embedding or establish production size, allocation or JIT performance budgets.
