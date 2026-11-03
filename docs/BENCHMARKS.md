# Continuous simulation benchmarks

CodSpeed measures 14 fixed native-AOT cases on every PR and main push. The integration
uses the same pinned CodSpeed action and instrument-hooks implementation as Komodo and
TruffleData. It counts instructions within explicit C callback regions; it does not time
Java startup, fixture creation, compilation warmup or shutdown.

The image explicitly sets `truffle.UseFallbackRuntime=true`: it measures the native-compiled
interpreter, with no guest compiler included. Guest compilation has separate mandatory
JVM and Native Image gates below; simulation must not be read as guest-JIT throughput evidence.

| Case | Measured work per operation |
| --- | --- |
| compile-evaluate | Parse, check, plan and evaluate a scalar expression |
| evaluate-scalar | Evaluate a previously compiled arithmetic expression |
| short-circuit | Evaluate boolean error-suppression/short-circuit behavior |
| comprehension | Filter/map a fixed list and evaluate its size |
| regex | Match a bounded regular expression through CEL |
| native-row | Read a TruffleData row and evaluate arithmetic |
| protobuf | Evaluate a predicate over a protobuf-backed value |
| json | Evaluate a predicate over an already converted Oracle JSON object |
| optional-chain | Read nested optional fields and apply a lazy fallback |
| two-variable-transform | Transform a list using index and value bindings |
| string-format | Format numeric/string values and compute the result size |
| list-sort | Sort by a computed key and select the first item |
| math-extrema | Find the maximum of a numeric list |
| network-cidr | Parse a numeric CIDR/IP and test containment |

Each case executes 256 operations. Checksums in `benchmarks/cases.json` are checked after
the measurement; a volatile result prevents dead-code elimination. Preparation and 16 warmup
operations run outside each region. Native-AOT is the only measured tier; no JVM/JIT claim
is made from these results.

```sh
# Hermetic Native Image and hooks build on Linux RBE; stages executable and debug symbols:
python3 tools/bench/bench.py prepare
# On Linux amd64:
python3 tools/bench/bench.py smoke
# Inside the CodSpeed simulation action:
python3 tools/bench/bench.py run
```

`//benchmarks:smoke` runs all workload correctness checks on the JVM as part of `//...`.
`bazel test //benchmarks:native_smoke` executes the built Linux image on RBE, including the
negative check that requiring instrumentation fails when hooks are not active. The native
image and smoke target are manual targets, so ordinary JVM development does not build them.

Preparation fingerprints sources, the case manifest and executable. Measurement rejects
stale input fingerprints, missing/duplicate regions, zero instruction counts, unexpected
results and uninstrumented execution. Failure cannot become a passing zero-cost benchmark.
Code and symbol files are preserved under `build/simulation/`, along with per-case logs,
raw profiles and derived instruction counts per operation. Unsupported DWARF is stripped
for Valgrind while preserving symbols and checking that `.text` is byte-identical.

The CI workflow builds on RBE but measures on the hosted Ubuntu 24.04 runner under the
CodSpeed action; measurements are not remote-cache hits. CodSpeed supplies PR comparisons
and history after the repository's GitHub integration is enabled. The driver fails if its
simulation runner environment or named-region profile output is missing.

No production library dependency on CodSpeed, its native hooks, or the benchmark SDK is
introduced. These targets qualify this executable and workloads, not every possible native
embedding of the library.

## Guest compilation and allocation gates

`bazel test //benchmarks:compiled` runs on the pinned Elide GraalVM JDK, as part of
`bazel test //...`. It requires an optimizing runtime and valid last-tier guest code before
and after measurement. Background compilation is disabled, compilation failures throw,
all Truffle performance warnings are errors, and partial-evaluation graphs are capped at
10,000 nodes. Each tested AST is limited to 128 nodes.

The test covers scalar arithmetic, lazy logic, optionals, comprehensions, list/map literals,
conditionals, unknown propagation, native rows, protobuf, JSON, and network operations.
Changed inputs check that compiled expressions remain dynamic; a resource-budget regression
checks that compiled execution still enforces evaluation limits.

Allocation budgets measure the complete `Program.evaluate` call, including activation
creation and host-data snapshots. Fixture creation and warmup are excluded. The test requires
JVM thread-allocation accounting; unsupported accounting or an interpreter-only runtime
fails rather than skips. Scalar evaluation is capped at 512 bytes/op, logical/optional/row
paths at 640, list construction at 768, protobuf/JSON at 1,024, comprehensions/maps at 1,536,
and CIDR parsing/containment at 20,000. These are bounded regression budgets for the fixed
fixtures, not zero-allocation claims or limits for arbitrary inputs.

`bazel test //benchmarks:jit_smoke` builds a separate Linux amd64 Native Image containing
the optimizing guest compiler. `TruffleCheckBlockListMethods` and
`TruffleCheckNeverPartOfCompilation` are explicitly enabled. The smoke executable runs all
14 workload correctness cases, then requires actual last-tier guest compilation and changed
input correctness for scalar, lazy, comprehension, map, and optional expressions. It never
sets `truffle.UseFallbackRuntime`. This manual Bazel target is explicitly required by CI,
and release creation depends on its success.

Generic Java collections, extension libraries, host bindings, and data adapters use explicit
Truffle boundaries. Arithmetic, lazy evaluation, AST child evaluation, and comprehension
control flow remain eligible for partial evaluation. These gates qualify the tested JVM and
native workloads; they do not establish Elide embedding throughput or a production image-size
budget. Production JARs do not depend on the Truffle runtime or compiler test APIs.
