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

## Finding instruction hotspots

Download the `codspeed-simulation` artifact from a completed CI run and inspect its named
regions with the checked-in analyzer:

```sh
gh run download RUN_ID --name codspeed-simulation --dir /tmp/cel-profile
python3 tools/bench/codspeed_profile.py /tmp/cel-profile/profiles --case comprehension
```

The report ranks **self instructions**, excluding call-edge costs to avoid double counting,
and verifies their sum against the measured region total. Native compiler inlining can
attribute work to callers, so interpret the symbols alongside source and the CodSpeed
call graph. These are fallback-interpreter costs; confirm improvements in the paired
JVM/SVM timing suite before making compiled-throughput claims.

The November 3 baseline ([run 41703453231](https://github.com/elide-dev/trufflecel/actions/runs/41703453231),
source `f2437800a29d`) measured 2,570.63 instructions/op for scalar evaluation and
54,359.79 for comprehension. In the comprehension profile, immutable-map entry iteration,
value iteration, iterator `hasNext`, entry `getValue`, and value-iterator construction together
accounted for about 37% of instructions. Equality traversed registered object types on each
comparison. The first optimization precomputes that traversal as an array in the immutable
environment, retaining the same adapter order and custom equality behavior. This baseline
identifies the target.

The first array-dispatch change (`4e9d35a`, [run 41706099612](https://github.com/elide-dev/trufflecel/actions/runs/41706099612))
produced these CodSpeed results with unchanged workloads:

| Case | Baseline instructions/op | Array dispatch | Change |
| --- | ---: | ---: | ---: |
| Comprehension | 54,359.79 | 29,905.04 | −45.0% |
| Short-circuit | 5,467.48 | 2,406.77 | −56.0% |
| Protobuf | 10,605.37 | 7,470.19 | −29.6% |
| JSON | 18,255.39 | 9,087.50 | −50.2% |
| Compile/evaluate | 46,701.28 | 50,701.77 | +8.6% |

The compile/evaluate regression exposed per-call default-environment construction in
`Cel.compile`. Follow-up changes reuse the immutable default environment, use compact
single-binding activations, defer/reuse local scope storage, and avoid decimal conversion
for same-type numeric equality. Those changes require their own measured verification;
the table above reports only the first array-dispatch change.

## Guest compilation and allocation gates

`bazel test //benchmarks:compiled` runs on the pinned Elide GraalVM JDK, as part of
`bazel test //...`. It requires an optimizing runtime and valid last-tier guest code before
and after measurement. Background compilation is disabled, compilation failures throw,
all Truffle performance warnings are errors, and the partial-evaluation graph-size budget
is 10,000. Each tested AST is limited to 128 nodes.

The test covers scalar arithmetic, lazy logic, optionals, comprehensions, list/map literals,
conditionals, unknown propagation, native rows, protobuf, JSON, and network operations.
Changed inputs check that compiled expressions remain dynamic; a resource-budget regression
checks that compiled execution still enforces evaluation limits.

Allocation budgets measure the complete `Program.evaluate` call, including activation
creation and host-data snapshots. Fixture creation and warmup are excluded. The test requires
JVM thread-allocation accounting, synchronous C2 host compilation (`-Xbatch` and
`-XX:-UseJVMCICompiler`), and 100,000 warmup evaluations before each measurement phase.
Pinning C2 for the host harness avoids a synchronous Graal host-compiler/class-loader
deadlock observed on macOS; the guest compiler is still Graal, with mandatory last-tier checks. Unsupported accounting or an interpreter-only runtime
fails rather than skips. Scalar evaluation is capped at 256 bytes/op, logical/optional/row
paths at 640, list construction at 768, protobuf/JSON and comprehensions at 1,024, maps at 1,536,
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

## Comparison with upstream cel-java

The bounded comparison uses upstream [cel-java 0.15.0](https://github.com/cel-expr/cel-java/tree/v0.15.0)
and its recommended planner runtime. Its Maven graph is pinned separately in
`cel_java_install.json` and is referenced only by test/benchmark targets. It does not change
TruffleCEL's production dependencies or published POMs.

Three expressions exercise arithmetic, a string/boolean policy, and a list comprehension.
Both engines receive identical explicit declarations and four alternating, workload-specific
activations with checked results. There are five paired scenarios (30 engine/workload cells):

| Runtime | Scenario | Timed operation |
| --- | --- | --- |
| GraalVM JVM | Compile | Parse, check, and create an executable program |
| GraalVM JVM | Evaluate | Reuse a program compiled during process setup |
| Native Image / SVM | Compile | Parse, check, and create an executable program |
| Native Image / SVM | Evaluate | Reuse a program compiled during process setup |
| Native Image / SVM | Image-build AST | Evaluate a checked AST prepared during image construction |

`Environment.prepare` creates a checked TruffleCEL AST without a call target.
`PreparedProgram.instantiate` clones that tree and creates its runtime target. The comparison
image initializes `ImageExpressions` at image build time, stores both engines' checked ASTs,
and asserts that provenance at runtime. Truffle targets and cel-java executable plans are
created after process startup. The image-build template uses only standard declarations;
application-provided constants and function bindings must themselves be safe for the image heap.
The comparison-specific initialization settings also cover the immutable frontend dependencies
retained by cel-java; they are not added to production library configuration. This is **build-time CEL frontend compilation**, not storage
of Graal guest machine code in the image. The image-build holder also records each frontend's
preparation time; those cold, order-sensitive observations are retained in the raw results,
not treated as steady-state compilation benchmarks. Both compiler environments are initialized
before those timers start. The observations belong to the image build (which may be cached or
remote), not necessarily to the host that later runs the timing suite.

TruffleCEL evaluation cases require a valid last-tier `OptimizedCallTarget` before and after
measurement. Guest compilation, context initialization, input fixture creation, and program
setup are outside evaluation timing. The timed public API includes activation normalization,
argument dispatch, result conversion, and checksum accumulation. Compilation returns a
program to a volatile sink without evaluating it; compiler environments are reused. There
is no claim that moving frontend work into image construction improves warmed evaluation:
it removes that frontend work from runtime startup, which is a different benefit.

Each cell runs in three fresh processes, in a deterministic shuffled order. Each process
warms for 500 ms, then records seven batches of at least 150 ms each. Input selection cycles
through four activations, and every timed batch verifies its result checksum. The SVG shows
the median of process medians and min/max process medians as whiskers. These are diagnostic
wall-clock measurements, not confidence intervals or CodSpeed instruction counts. Shared CI
machines, GC, host compilation, and timer/checksum overhead can affect small differences.
No timing threshold gates CI; incorrect results or missing guest compilation do fail it.

Run the complete paired suite on Linux x86_64:

```sh
python3 tools/bench/compare.py run
# Regenerate the SVG without rerunning any benchmarks:
python3 tools/bench/compare.py render build/comparison/comparison.json build/comparison/comparison.svg
```

Bazel builds both executables with pinned toolchains; measurement runs on the same local
host for both runtimes. `comparison.json` records all samples, source commit and digest,
machine details, engine versions, and image-build preparation observations. `comparison.svg`
is standalone and suitable for a README. CI runs the reusable `comparison.yml` workflow
alongside CodSpeed and uploads both files as the `cel-comparison` artifact. The workflow
also supports manual dispatch. Check in an explicitly dated result snapshot when updating
the README; CI does not silently rewrite the published chart.

The README snapshot is checked in as [raw JSON](benchmarks/comparison.json) and a
[standalone SVG](images/cel-comparison.svg). To regenerate that chart from its saved samples:

```sh
python3 tools/bench/compare.py render docs/benchmarks/comparison.json docs/images/cel-comparison.svg
```

For fast correctness checks, use `bazel test //benchmarks/comparison:smoke --config=local`.
The Linux native check is `bazel test //benchmarks/comparison:native_smoke`; it additionally
requires successful image-build AST initialization and the Truffle blocklist gates.
