[![TruffleCEL — Common Expression Language on GraalVM Truffle](docs/images/trufflecel-banner.png)](docs/images/trufflecel.svg)

[![CI](https://github.com/elide-dev/trufflecel/actions/workflows/ci.yml/badge.svg)](https://github.com/elide-dev/trufflecel/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/elide-dev/trufflecel/graph/badge.svg?token=LH1rsnWmBY)](https://codecov.io/gh/elide-dev/trufflecel)
[![CodSpeed](https://img.shields.io/endpoint?url=https://codspeed.io/badge.json%3Frepository%3Delide-dev/trufflecel)](https://codspeed.io/elide-dev/trufflecel)
[![Release](https://img.shields.io/github/v/release/elide-dev/trufflecel?include_prereleases)](https://github.com/elide-dev/trufflecel/releases)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/elide-dev/trufflecel/badge)](https://scorecard.dev/viewer/?uri=github.com/elide-dev/trufflecel)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16472/badge)](https://www.bestpractices.dev/en/projects/16472)
[![Java 25](https://img.shields.io/badge/Java-25-blue)](https://www.graalvm.org/)

**CEL expressions, executed by Truffle.** TruffleCEL is an independent
[Common Expression Language](https://cel.dev/) parser, type checker, and evaluator
for Java and Elide. Compile reusable policies, validation rules, and filters for
GraalVM JVM or Native Image, with optional guest compilation for hot expressions.
Production code has no cel-java dependency.

[Get started](#get-started) · [Documentation](docs/README.md) ·
[Benchmarks](#performance-and-memory) · [Contribute](CONTRIBUTING.md) ·
[Releases](https://github.com/elide-dev/trufflecel/releases)

## Get started

Use Java 25 and Elide GraalVM `25.5.5-elide.27`. TruffleCEL **0.10.0** uses
TruffleData **0.10.0**. Add the thin core JAR and the matching runtime repository:

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.elide.dev")
    maven("https://maven.elide.dev/gvm-elide/25.5.5-elide.27")
}
dependencies {
    implementation("dev.elide:trufflecel-core:0.10.0")
}
```

```java
import dev.trufflecel.Cel;
import java.util.List;
import java.util.Map;

var policy = Cel.compile("user.age >= 18 && 'admin' in user.roles");
boolean allowed = policy.test(Map.of(
    "user", Map.of("age", 30, "roles", List.of("admin"))));
```

Compile once and reuse across threads. Each evaluation has its own activation,
scopes, and operation budget. Map inputs are borrowed: keep their contents stable
until evaluation returns. Use `Activation.snapshot(bindings)` for reusable owned
inputs. Declare an environment with `Cel.newBuilder()` for typed variables,
custom functions, and explicit resource limits.

RE2/J is optional. For compatibility-mode regex support, add
`com.google.re2j:re2j:1.8`. Applications can omit it by selecting
[TruffleRegex-only mode](docs/INTEGRATION.md#omitting-re2j), subject to its explicit
context and supported-pattern contract.

### Choose an artifact

| Maven coordinate | Purpose |
| --- | --- |
| `dev.elide:trufflecel-core` | Compiler, evaluator, Polyglot language and native TruffleData rows |
| `dev.elide:trufflecel-protobuf` | Protobuf inputs, wire decoding and canonical CEL AST interchange |
| `dev.elide:trufflecel-json` | Oracle Truffle JSON input conversion |
| `dev.elide:trufflecel-processor` | Build-time generation of reflection-free POJO, JavaBean and record adapters |

Use matching versions. Put the processor on the annotation processor path; generated
adapters depend on core. The direct Java API does not require a Polyglot context;
the registered language ID is `cel`. See [integration](docs/INTEGRATION.md) for
ownership, numeric and presence semantics, and [publishing](docs/PUBLISHING.md)
for Maven/Bazel consumption and runtime dependencies.

## Performance and memory

Start with **interpreter-to-interpreter** results under the same host runtime.
Guest JIT is a separate choice for hot, reused expressions; warmed evaluation
excludes compilation and warmup. The [comparison guide](docs/BENCHMARK_COMPARISON.md)
explains the evidence, comparator settings, and remaining limits in response to
upstream methodology feedback.

The [0.10.0 report](docs/benchmarks/release-0.10.0-20261109/README.md) covers six
fixtures, including protobuf policy, materialized repeated fields, maps/regex,
wide activations, and optimizer opportunities. cel-java 0.15.0 runs with both
default and constant-folding/CSE planners. Every non-scalar fixture retains all
69 bindings. Figures use ordinary Map inputs and three forks per cell on the
recorded Linux host; they do not establish universal rankings.

| Metric | TruffleCEL interpreter: lower / tied / higher | Guest JIT: lower / tied / higher |
| --- | ---: | ---: |
| Hot evaluation time | 12 / 0 / 0 | 12 / 0 / 0 |
| Current-thread allocation per evaluation | 11 / 1 / 0 | 12 / 0 / 0 |
| Current whole-process RSS | 5 / 0 / 7 | 4 / 0 / 8 |
| Lifetime peak whole-process RSS | 6 / 0 / 6 | 4 / 0 / 8 |

Each mode is compared with the lower cel-java planner aggregate for that metric,
fixture and runtime. Counts are descriptive, not significance tests. Three of
the interpreter peak-RSS comparisons have overlapping fork ranges.

**Open a chart for readable values and observed ranges. Shorter bars are better.**

| Interpreter evaluation | Allocation per evaluation |
| --- | --- |
| [![Interpreter evaluation: 12 lower ordinary-map times, guest JIT excluded.](docs/images/benchmark-0.10.0-elide/interpreter-time.svg)](docs/images/benchmark-0.10.0-elide/interpreter-time.svg) | [![Allocation: interpreter has 11 lower and one tied result; guest JIT has 12 lower results.](docs/images/benchmark-0.10.0-elide/evaluation-allocation.svg)](docs/images/benchmark-0.10.0-elide/evaluation-allocation.svg) |
| **Current whole-process RSS** | **Peak whole-process RSS** |
| [![Current RSS: interpreter is higher in 7 cases, guest JIT in 8.](docs/images/benchmark-0.10.0-elide/evaluation-rss.svg)](docs/images/benchmark-0.10.0-elide/evaluation-rss.svg) | [![Peak RSS: interpreter is higher in 6 cases, guest JIT in 8.](docs/images/benchmark-0.10.0-elide/evaluation-peak-rss.svg)](docs/images/benchmark-0.10.0-elide/evaluation-peak-rss.svg) |

[Full gallery and PNG downloads](docs/images/benchmark-0.10.0-elide/README.md) ·
[Guest-JIT timing alongside interpreters](docs/images/benchmark-0.10.0-elide/evaluation-time.svg) ·
[All values and ranges](docs/benchmarks/release-0.10.0-20261109/tables.md)

**Input preparation changes the comparison.** cel-java copies ordinary maps but
reuses Guava ImmutableMaps. In the [separate prepared-input controls](docs/images/benchmark-0.10.0-elide/README.md#prepared-input-controls),
JVM scalar-wide cel-java allocation falls from 2,936 to 72 B/op, below TruffleCEL
interpreter's 120 B/op. Caller preparation is excluded. Only two fixtures have
these controls; ordinary-map counts do not apply to every input API.

**Memory and startup remain tradeoffs.** Allocation is current-thread bytes/op,
not retained heap. RSS covers the whole process, including setup and compilation;
the shared comparison binary is not a standalone deployment footprint.
[Startup/payback](docs/benchmarks/startup-payback-20261108/README.md) and
[retained-heap](docs/benchmarks/retained-20261108/README.md) measurements use earlier
TruffleData 0.9.0 artifacts, and are explicitly historical. Current-release
startup/payback and separate deployment-size measurements remain open evidence gaps.

CI also runs [CodSpeed simulation](https://codspeed.io/elide-dev/trufflecel),
allocation limits and native guest-compilation gates. CodSpeed instruction counts
are distinct from JVM/JIT throughput. See [methodology and historical reports](docs/BENCHMARKS.md).

## Compatibility and safety

All **2,771 cases in the pinned 32-file cel-spec corpus** run without skips, through
both a test fixture and the shipped protobuf adapter, and through frontend and
canonical AST paths. An additional 431-case negative/edge table checks rejection
phase and defined error categories; 368 cases also run against cel-java with both
parsers and optimizer settings, with 22 documented divergences.

This is evidence for the tested contracts, not exhaustive CEL specification
compliance. CI enforces **99.8% line and branch coverage**, resource-limit and
concurrency tests, and coverage-guided frontend/decoder fuzzing. Read
[compatibility and limitations](docs/COMPATIBILITY.md), [validation](docs/VALIDATION.md),
and the [threat model](docs/THREAT_MODEL.md) before exposing expressions to untrusted input.

## Build and contribute

Bazelisk selects the pinned Bazel and GraalVM toolchains. Local development needs
no BuildBuddy credentials:

```sh
bazel run --config=local //bazel/gazelle:update
bazel run --config=local //tools:format
bazel test --config=local //...
bazel coverage --config=local //src/test/...
bazel run --config=local //tools:coverage
```

The default configuration uses BuildBuddy RBE; `--config=local` uses the host.
Native guest-JIT qualification runs on Linux. See [Bazel setup](docs/BAZEL.md),
[contributor guidance](CONTRIBUTING.md), [agent guidance](AGENTS.md), and
[CI](docs/CI.md). Contributions of code, tests, documentation, and reproducible
performance investigations are welcome.

## Community, security and licensing

[Support](SUPPORT.md) · [Governance](GOVERNANCE.md) ·
[Code of conduct](CODE_OF_CONDUCT.md) · [Security policy](SECURITY.md) ·
[OpenSSF evidence and remaining work](docs/OPENSSF.md)

First-party TruffleCEL is dual-licensed under [Apache-2.0](LICENSE-APACHE) or
[MIT](LICENSE-MIT), at your option (`Apache-2.0 OR MIT`). Contributors offer their
work under both alternatives and retain copyright; see the
[contributor agreement](CONTRIBUTOR_AGREEMENT.md). [Third-party components](THIRD_PARTY.md)
retain their own terms and notices. Report vulnerabilities privately using the
[security process](SECURITY.md).
