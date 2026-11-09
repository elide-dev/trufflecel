# Benchmark methodology and results

Start with the [comparison guide](BENCHMARK_COMPARISON.md) for interpreter-first
results, prepared-input controls, allocation/current/peak RSS definitions, and an
explicit accounting of upstream methodology feedback and remaining evidence gaps.
The [chart gallery](images/benchmark-0.10.0-elide/README.md) collects the current
release's readable figures and downloadable SVG/PNG files.

## Final 0.10.0 dependency qualification

The [November 9 release report](benchmarks/release-0.10.0-20261109/README.md)
measures the completed production code with published TruffleData 0.10.0 on JVM
and Native Image. All six ordinary-map fixtures, both TruffleCEL modes and both
cel-java planners are refreshed, alongside caller-prepared input controls and
separate ingestion allocation samples. Source/runtime/artifact hashes, raw samples,
observed fork ranges, summaries and reproduction scripts are preserved.

The ordinary-map matrix records 24/24 lower TruffleCEL evaluation times, 23/24 lower
allocations with one tie, and 9/24 lower current RSS values. Prepared immutable
cel-java inputs reduce adaptation costs; process footprint remains a gap.
[Time](images/benchmark-0.10.0-elide/evaluation-time.svg),
[allocation](images/benchmark-0.10.0-elide/evaluation-allocation.svg) and
[RSS](images/benchmark-0.10.0-elide/evaluation-rss.svg) charts describe the measured cases.
The [chart gallery](images/benchmark-0.10.0-elide/README.md) uses Elide's fonts and
colors, zero-based linear bars, direct values, and explicit comparisons against
the best cel-java planner for each metric. Each card has its own scale; compare
lengths within a card. These are new presentations of the preserved measurements.
The separate three-expression CI comparison records 24/24 time and allocation wins
and 12/24 peak-RSS wins using its own protocol and host. Neither suite establishes
universal optimality, and they must not be pooled as one experiment.

Reports below retain their original source revisions, dependencies and observations.
They are historical evidence, not isolated before/after measurements of this upgrade.

## November 8 memory reductions

The [paired evaluation allocation report](benchmarks/evaluation-allocation-20261108/README.md)
measures the combined 0.10.0 candidate against the completed functionality baseline.
The ordinary interpreted scalar falls from 128 to 96 bytes per call. A separate
fixture reading one converted binding falls from 4,192 to 168 bytes with 1,000
declarations; the final allocation stays at 168 bytes with 1, 69, or 1,000 declarations.
Controlled calls remain at their baseline allocation in the matched comparison.
Compile-and-evaluate controls also allocate less; neither diagnostic measures throughput.

The final combined section of the
[retained Program report](benchmarks/retained-20261108/README.md#combined-production-follow-up)
measures a 51.9% reduction for 10,000 distinct scalar Programs sharing a 69-declaration
Environment, and 56.4% for Programs instantiated from one shared PreparedProgram.
These incremental live-Java-heap results exclude the shared environment and runtime,
which are already present before caching. They do not measure native process RSS.
Raw samples, unchanged intermediate results, reproduction patches, and limitations
are preserved in the reports.

The [paired ingestion report](benchmarks/ingestion-20261108/README.md) removes one
redundant wire-array copy from protobuf decoding. Ordinary decode allocation falls
from 5,366 to 5,152 B/op on the JVM and 4,834 to 4,628 B/op in Native Image. Adding a
64 KiB unknown payload increases the saving to about 65.7 KB per decode on either
runtime. Unprofiled ordinary decode timing ranges overlap; this establishes an
allocation saving without a throughput claim. Bound generated-message snapshots
remain a tradeoff: slower on the measured JVM fixture and more allocation for the
ordinary native fixture. Remaining unknown-byte staging is tracked in
[TruffleData #38](https://github.com/elide-dev/truffledata/issues/38), with separate
qualification requested for its unreleased 0.10.0 slab decoder.

The [CodSpeed check](https://github.com/elide-dev/trufflecel/runs/128794753405)
classified all 21 benchmarks as unchanged for `07a3fe4` against the actual
functionality base `547c9fc`. It detected no timing regression in that suite; it
does not turn the allocation reductions into a measured throughput improvement.
The [native phase investigation](#native-memory-by-execution-phase-graalvm-27)
separately evaluates compiler and heap settings, with matched cel-java controls.
Its production baseline is unchanged; no embedding defaults are changed by the
library memory optimizations.

The [optional RE2/J qualification](benchmarks/optional-re2j-20261108/README.md)
compares main, new compatibility mode and opt-in TruffleRegex-only execution.
Compatibility retains unchanged allocation with overlapping timing ranges in all
12 cells. Opt-in constant matching retains unchanged allocation and overlapping
ranges. Dynamic guest-JIT matching is 8.17% faster on JVM and 16.68% faster on native
than compatibility in the same artifact, with current process RSS increasing
11.25% and 16.50%. The complete scalar controls, balanced confirmation, interpreter
costs and raw evidence remain in the report. Performance artifacts include RE2/J;
separate packaged JVM/native consumers prove execution and guest compilation with
the optional JAR physically absent.

## Representative comparisons

The [November 8 six-fixture report](benchmarks/evaluation-refresh-20261108/README.md)
records production candidate `67ab33c`, GraalVM 25.5.5-elide.27, TruffleData 0.9.0 and
the existing RE2/J path. It contains all six fixtures, four input variants and full
69-binding activations except the explicitly single-binding scalar. The repeated
fixture materializes 77–80 output elements from 128 input elements. Comparators
include cel-java's default planner and constant-folding/CSE planner on both JVM and
Native Image.

Using the lower of cel-java's two modes separately for each metric, the ordinary-Map
protocol records lower TruffleCEL time in all 24 runtime/mode/fixture comparisons,
lower current-thread allocation in 23 with one tie, lower current RSS in 9, and lower
lifetime peak RSS in 10. The 24 JMH
cells each use three forks; 72 native timing processes and 144 separate allocation/RSS
processes complete the matrix. These are descriptive point estimates from an observed
low-load shared host with fixed affinity, not universal engine rankings. RSS includes
setup, calibration, compilation and fixed sampled work at an uncontrolled GC point;
guest JIT also has 2,000 additional preparation evaluations and forced compilation.
It is not retained heap or equal total work since process launch.

The full-input maps are JDK unmodifiable maps. cel-java's public `eval(Map)` copies
their entries on each evaluation, while its Guava `ImmutableMap` path reuses the
caller-prepared map. The [paired input-representation controls](benchmarks/cel-java-inputs-20261108/README.md)
isolate this distinction for scalar-wide and protobuf policy on both runtimes, with
default and optimized planners. Both control forms capture four complete maps in the
same array and use the same evaluation lambda; caller-side preparation is outside
the timing/allocation intervals. The original matrix is preserved rather than
reinterpreted as a comparison of every optimized embedding path.

Across the 96 supplemental control processes, immutable inputs reduce cel-java's
allocation by 2,864 B/op on the JVM and 3,384 B/op on native in all eight matched
runtime/workload/planner cells. Scalar-wide allocation falls from 2,936 to 72 B/op
on the JVM and 3,504 to 120 B/op on native; its paired evaluation speedup is about
20–22×. Protobuf-policy speedup is 1.78–1.85× on the JVM and 2.01–2.37× on native.
These compare the two cel-java input forms inside one artifact, not separate-report
measurements of a revision change or a matched ranking against every TruffleCEL path.

Both reports preserve this RE2/J baseline. The separate
[paired TruffleRegex RE2 qualification](benchmarks/regex-re2-20261108/README.md)
compares that production code with the scoped constant-match candidate on the same
regex workload and an unrelated scalar-wide control. It includes 24 JVM JMH forks,
24 native timing processes, 48 allocation/RSS processes and 32 first-result launches.
Every candidate regex cell must prove actual acceleration before being marked complete.

Regex guest-JIT time improves 10.92% on JVM and 11.20% on native, while allocation
increases by 6 and 4 B/op respectively. Current JIT RSS rises 10.28% on JVM and
16.54% on native. Interpreter allocation also increases; native interpreter time has
overlapping fork ranges and a 1.14% higher point estimate. Scalar-wide allocation
is unchanged, but its timing point estimates rise 1.96–4.24%, with disjoint native-JIT
ranges. The combined native benchmark executables grow 9.3–9.6 MiB. These results
describe a speed/footprint tradeoff, not a memory optimization or a new ranking
against cel-java. The report preserves all controls and costs.

A [separate cached-dispatch experiment](benchmarks/regex-dispatch-20261108/README.md)
was rejected. All eight timing-pair ranges overlap; native regex JIT RSS rises
7.11% despite small JIT allocation savings. The prototype and its extra dispatch
root were removed. Its complete negative evidence is preserved, and the simpler
initial adapter remains the selected implementation.

The [November 5 report](benchmarks/representative-20261105/README.md) remains historical
evidence with its original dependencies, raw data and methodology.

`//benchmarks/representative` is the response to the methodology gaps in the historical
three-expression suite. It compares the same CEL sources and four independently checked
activations across five explicit configurations: TruffleCEL interpreter, TruffleCEL guest
JIT, cel-java's default planner, planner plus constant folding/CSE, and the optimized planner
with the opt-in Pratt parser. cel-java remains an isolated test/benchmark dependency.

The six workloads cover a scalar with one binding, the same expression with a 69-binding
activation (including unrelated maps, lists, and protobuf data), nested protobuf
presence/wrapper/timestamp/map fields, a 128-item protobuf repeated
field producing an actual list, maps/regexes, and a policy with repeated subexpressions and
constant arithmetic. Input activations are not trimmed except in the explicitly named
single-binding scalar control. Generated protobuf messages and native snapshots contain
the same data. Evaluation excludes ingestion; `Lifecycle ... ingest` measures wire ingestion
separately. These are broader fixtures, not a claim to represent every application's workload.

JVM steady-state and compilation measurements use JMH 1.37, three forks, five one-second
warmup iterations, and five one-second measured iterations. Every result escapes through
JMH's result consumer. Compilation includes parse/check/optional optimization/program creation
with reused environment objects. `FrontendStages` separately measures upstream parse, check,
optimize, and plan on `true` and a small arithmetic expression to investigate fixed overhead.
Its stage timings are diagnostic and should not be added into a claimed engine speedup.

The dedicated-host driver explicitly disables the JVMCI *host* compiler to select C2 while
retaining Truffle guest compilation. The pinned GraalVM enables the JVMCI host compiler by
default; historical JVM results must not be labeled C2 without checking their VM flags.
Each run preserves effective VM flags, CPU model/affinity, kernel, load, artifact hashes,
source manifest, exact commands, per-fork samples, and warmup logs. Five warmup iterations
do not prove peak convergence: inspect trends and rerun longer if performance is still changing.
Do not run a profiler or another benchmark concurrently with timing.

Fresh processes also measure cumulative preparation plus the first 1, 10, 100, 1,000 and
10,000 evaluations without forced guest compilation. Fixture preparation precedes that clock;
VM launch is outside it. Native `image-cold` uses checked/optimized ASTs prepared during image
construction, while `cold` prepares expressions at runtime. Neither embeds guest machine code.
Native steady-state uses an explicitly recorded five-window warmup and seven samples; these
are separate from JMH. Compare cold curves to identify an observed amortization point, and
report when the sampled range does not show one.

The native comparison registers reflection metadata for the generated protobuf accessors
used by cel-java. This configuration belongs to the benchmark executable; it adds no
reflection requirement to TruffleCEL's production adapter. The native correctness gate
runs every engine/workload pair with both runtime and image-build AST preparation.

```sh
bazel test --config=local //benchmarks/representative:smoke //benchmarks/representative:semantics
bazel build --config=local //benchmarks/representative:jmh_deploy.jar //benchmarks/representative:lifecycle_deploy.jar
bazel build //benchmarks/representative:svm --remote_download_toplevel
# Run prebuilt artifacts on quiet Linux x86_64; use the pinned GraalVM, not a system JDK:
python3 tools/bench/representative.py jmh --java /path/to/pinned-graalvm/bin/java \
  --jar /path/to/jmh_deploy.jar --source-manifest /path/to/source.json --output /new/results/jmh
python3 tools/bench/representative.py lifecycle --java /path/to/pinned-graalvm/bin/java \
  --jar /path/to/lifecycle_deploy.jar --native /path/to/representative \
  --source-manifest /path/to/source.json --output /new/results/lifecycle
```

Existing measurements below are historical evidence. Their raw files are immutable and use
their original workload definitions, host configuration, and methodology.

### Complete process startup and first verified result

The [released 0.9.0 evidence matrix](benchmarks/release-0.9.0-20261108/README.md)
separates small-suite wins, memory losses, historical representative results and
unmeasured categories. `Startup` and `tools/bench/startup.py` address the previously
missing launch interval; adding the harness is not a startup performance result.

The [2026-11-08 candidate startup/payback report](benchmarks/startup-payback-20261108/README.md)
now records 285 launch-to-first-result processes and 54 natural-compilation reuse
processes on pinned GraalVM 25.5.5-elide.27. Native runtime-prepared startup medians
favor TruffleCEL across the six fixtures, with overlapping scalar ranges; JVM scalar
startup remains slower. Native repeated-list guest JIT first wins at the 100,000
sample, native scalar-wide/policy and JVM repeated-list at one million, while JVM
scalar-wide/policy show no payback through one million. Those are post-compilation sampled
process-range comparisons, not precise crossover points. The report preserves raw
outputs, runtime/source hashes, controls and preparation boundaries.

The parent process starts a monotonic clock immediately before `Popen`. The child
flushes one readiness marker only after its first evaluation matches an independent
fixture expectation. The parent records receipt of that marker separately from
process exit, excluding JSON serialization and context teardown from the readiness
endpoint. Parent scheduling and pipe delivery remain included. Each process runs
one expression once; guest compilation is natural, unforced, and its last-tier state
is recorded. A false last-tier flag does not exclude first-tier code.

All six existing sources, full activations and four input variants remain unchanged.
The startup fixture constructs the shared raw inputs, serializes one request and
decodes it through the selected engine's representation, even for the scalar control.
The internal phase partition includes fixture/wire preparation, adapter construction,
decode, engine configuration, checked/optimized AST preparation, planning, and first
evaluation plus verification. These phases are diagnostic, not independently timed
benchmarks or attribution of all VM initialization. The external interval also includes
launch, class loading and work preceding `main`.

Runtime-prepared Native Image and image-prepared Native Image use **separate binaries**.
The runtime artifact leaves the prepared holder uninitialized so embedded ASTs/adapters
do not prewarm its schema caches. The image artifact embeds checked/optimized ASTs and
the TruffleCEL protobuf adapter; generated descriptors follow the benchmark's existing
build-time protobuf policy in both artifacts. Raw inputs and decoded snapshots are
created at runtime in both. Native runtime preparation therefore means runtime
expression and adapter construction with image-initialized generated schema classes;
it is not equivalent to JVM schema class loading. Neither image embeds guest machine
code. Each native artifact rejects the wrong preparation mode.

An empty-engine control for the JVM and each native binary follows the same entry
point and emits the marker without fixture or engine initialization. Report controls
separately; never subtract them to claim isolated engine startup. The shared benchmark
artifacts contain both engines, so they do not establish standalone deployment size
or application startup. OS file caches are not flushed; these are fresh processes,
not a cold-disk experiment.

```sh
bazel test --config=local //benchmarks/representative:startup_smoke //tools/bench:test
bazel build --config=local //benchmarks/representative:startup_deploy.jar
bazel test //benchmarks/representative:startup_native_runtime_smoke \
  //benchmarks/representative:startup_native_image_smoke
bazel build //benchmarks/representative:startup_svm \
  //benchmarks/representative:startup_prepared_svm --remote_download_toplevel
# Transfer prebuilt artifacts and their source manifest to a reserved Linux x86_64 host.
python3 tools/bench/startup.py --java /path/to/pinned-graalvm/bin/java \
  --jar /path/to/startup_deploy.jar --native /path/to/startup-representative \
  --native-prepared /path/to/startup-prepared-representative \
  --source-manifest /path/to/source.json --output /new/results/startup \
  --cpus 2,3,4,5,6,7,8,9 --host-conditions 'Describe actual reservation, load and thermals'
```

The pinned distribution is GraalVM 25.5.5-elide.27 from `MODULE.bazel`; its Java banner
uses the JVM build number rather than that distribution filename. Preserve the source
manifest's commit, dirty state, per-file hashes and exact artifact-build commands,
including `MODULE.bazel`, lockfiles, `.bazelversion`, `.bazelrc`, `bazel/`, `src/main/`,
`benchmarks/representative/` and both measurement drivers. Use an explicit tracked-file
allowlist; do not read or copy `user.bazelrc` or credentials. Preserve build logs separately.
The driver adds command arrays, source-manifest/driver/artifact hashes, Java banner,
runtime release metadata, module/libjvm hashes, effective VM flags, CPU affinity,
kernel, load and raw per-process stdout/stderr. JVM uses C2; both runtimes use explicit
512 MiB initial/maximum heaps. Native uses serial GC; JVM effective flags identify its GC.

The default full run is 285 fresh processes: five per cell for three engines
(TruffleCEL interpreter, TruffleCEL guest JIT, optimized cel-java), six workloads,
JVM runtime preparation and both native preparation modes, plus three controls.
Forks rotate input index modulo four, paired across engines. Omitting both native
arguments gives an explicitly JVM-only matrix. Validation rejects incomplete,
repeated or mislabeled cells, missing/duplicate readiness markers, unverified results,
invalid phase partitions, timeouts and failed processes. `complete` stays false until
the entire requested matrix passes. Report per-process samples and ranges; a correct
smoke run on a shared build host is not a quiet-host timing comparison.

### Borrowed map inputs

The [borrowed-input report](benchmarks/borrowed-20261105/README.md) records the current
stable-input contract and matched JVM comparison with optimized cel-java, plus targeted
native timings. Ordinary map evaluation borrows bindings and normalizes values when read;
callers must keep inputs stable until evaluation returns. Explicit activation snapshots
remain eager and isolated. Earlier reports timed the previous eager map-entry behavior;
the new result is an intentional API-contract change, not equivalent eager validation made
faster. All original workload bindings remain present in the comparisons.

### Activation preparation and reuse

See the [all-six-workload snapshot report](benchmarks/activation-suite-20261105/README.md) and
[initial activation investigation](benchmarks/activation-20261105/README.md) for measured
preparation/reuse tradeoffs, raw samples and validation.

`ActivationPreparation` separates shallow map copying, value normalization (with every result
consumed), and complete `Activation.snapshot` preparation. These independent diagnostic stages
must not be summed to claim an end-to-end cost. `ActivationEvaluation` compares ordinary
map entry, evaluation of a reusable snapshot, and snapshot preparation plus one evaluation.
All paths retain the full input and check all four fixture variants before measurement.
The guest-JIT configurations require last-tier code before and after timing.

```sh
python3 tools/bench/representative.py activation --java /path/to/pinned-graalvm/bin/java \
  --jar /path/to/jmh_deploy.jar --source-manifest /path/to/source.json --output /new/results/activation
```

The default matrix contains six preparation cells (scalar/full activation) and thirty-six
evaluation cells (all six representative workloads; interpreter/guest JIT; three entry modes).
An explicit `--activation-workloads scalar,proto-repeated,optimizer-policy` selection can measure
a subset; the exact selection is recorded and validated, so partial runs cannot be mistaken for
complete six-workload results. The original focused report retains its three-workload inventory.
Each cell runs three forks, five one-second warmups and five one-second samples,
with C2, a 512 MiB heap and JMH's GC profiler for allocated bytes/op. Preparation is explicitly
excluded only from the reusable-snapshot evaluation cell. This measures reuse of an existing
input; ingestion and changed-input preparation remain application costs.

### Structured-data preparation and scaling

The [first prepared-field/owned-collection report](benchmarks/structured-20261105/README.md)
retains the full activation comparison, matched size/selectivity/preparation
diagnostics, baseline profiles and native qualification. The
[nested-field follow-up](benchmarks/nested-20261105/README.md) adds nested/map and
common well-known reads, empty-input allocation checks, native entry-path measurements
and natural compilation-payback curves, including selected snapshot cases through
ten million evaluations.

`StructuredAccess` isolates prepared repeated-field evaluation with a materialized
filter/map result. Its parameters vary list length and selected percentage. Separate
methods measure `Environment.prepare` and `PreparedProgram.instantiate`; schema setup,
ingestion and activation preparation happen in trial setup and are not timed here.
The fixture is a focused single-request diagnostic, distinct from the four-input,
full-activation comparison above.

Build the same benchmark sources against reference and candidate runtime revisions.
The paired driver runs list sizes 0, 16, 128 and 1024 at 50% selection, 128 elements at
0%/100% selection, and preparation/instantiation for 128 elements. It alternates artifact
order by scenario, records source/artifact/toolchain identity, and rejects incomplete
forks or cells. Both artifacts must use the same `Engines`, `Workloads`, `StructuredAccess`
and benchmark build configuration. Compilation warnings and oversized guest graphs are
errors in the current representative engine harness.

```sh
python3 tools/bench/structured.py --java /path/to/pinned-graalvm/bin/java \
  --reference-jar /path/to/reference.jar --candidate-jar /path/to/candidate.jar \
  --reference-source /path/to/reference-source.json \
  --candidate-source /path/to/candidate-source.json --output /new/results/structured
```

Run this after other timed jobs on the dedicated host have completed. It uses three
forks, five one-second warmups and five one-second measurements, explicit C2 host
compilation and JMH's GC profiler. A peak evaluation improvement does not establish
startup amortization or a complete-request improvement.

### Protobuf zero checks

The [native protobuf zero-check diagnostic](benchmarks/protobuf-zero-20261108/README.md)
compares native presence checks with the previous message-export implementation on identical
snapshots, including allocation samples and semantic edge cases. It is a direct adapter
measurement on a shared host; equality, hashing and ingestion remain separate follow-ups.

### Prepared-input cold curves and native throughput

`tools/bench/focused_payback.py` supplies the bounded full-map subset used in the
[2026-11-08 report](benchmarks/startup-payback-20261108/README.md): scalar-wide,
protobuf policy and materialized repeated-list workloads across JVM runtime, native
runtime and native image preparation, with three forks per execution mode and
checkpoints through one million evaluations. Its inventory and tier validation are
independent of whether a favorable crossing is observed. The report's `run.sh`
preserves the exact invocation; profiling runs remain separate from these timings.

`PreparedLifecycle` measures ordinary map entry, explicit snapshot reuse and creation
of a fresh snapshot for every evaluation. Its cold modes include runtime configuration,
expression preparation/instantiation and creation of four reusable snapshots when that
entry path is selected. Fixture generation/ingestion and VM launch remain outside the
timer; this is expression-lifecycle payback in a preloaded process, not application
startup or whole-request latency. No evaluation warms the expression before the first
checkpoint, and correctness is checked after timing.

Natural guest compilation is synchronous and unforced for cold curves. Checkpoints
extend through one million evaluations by default; `--maximum 10000000` allows a
separate longer investigation. Every checkpoint records whether last-tier guest code
is valid. Native steady-state measurements separately force and verify guest compilation,
use five one-second warmups, and retain seven half-second measured windows per process.
Results always escape through a volatile consumer, including materialized list outputs.

```sh
bazel build --config=local //benchmarks/representative:prepared_lifecycle_deploy.jar
bazel test //benchmarks/representative:prepared_native_smoke
python3 tools/bench/prepared_lifecycle.py all --java /path/to/pinned-graalvm/bin/java \
  --jar /path/to/prepared_lifecycle_deploy.jar --native /path/to/prepared-representative \
  --source-manifest /path/to/source.json --output /new/results/lifecycle
```

The complete driver runs 228 fresh processes: three forks for all six native steady-state
workloads across both execution modes and all three entry paths; JVM and native cold
curves for full-activation scalar, protobuf policy and repeated-field policy across those
entry paths; and image-build AST cold curves for the two protobuf snapshot workloads.
Image preparation includes the AST, with activation snapshots still prepared at runtime.
`cold` and `native` subcommands allow separately retained runs. All processes run sequentially
on the dedicated host, in a fixed shuffled order, with explicit C2 host compilation for
JVM runs. The validator rejects missing/duplicate cells, invalid checkpoint/tier states
and incomplete measurement windows. Report sampled median crossings alongside process
ranges and cases with no observed payback; peak throughput alone cannot establish one.

Use time/op and allocated heap bytes/op as the primary comparison metrics. Keep compilation,
interpretation, and guest-JIT evaluation separate, including image-build AST preparation.
Use CodSpeed to locate CPU regressions and existing allocation budgets to enforce limits;
use fixed-work peak RSS as a secondary process-footprint diagnostic. A lower allocation
rate does not imply a smaller resident process, especially when guest compilation is enabled.

CodSpeed measures 21 fixed native-AOT cases on every PR and main push. The integration
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
| folded-constant | Evaluate a constant comprehension that compile-time folding reduced to its value |

Each case executes 256 operations. The historical literal fixtures keep their original
expressions and benchmark names; constant folding may legitimately reduce their runtime work.
The six `-input` variants of comprehension, two-variable-transform, string-format, list-sort,
math-extrema and network-cidr read activations prepared outside the measured region. These
have distinct benchmark identities because host binding lookup and normalization change the
measured work. Never compare a literal fixture's history with its runtime-input variant.
`folded-constant` explicitly exercises folding. Checksums in `benchmarks/cases.json` are
checked after measurement; a volatile result prevents dead-code elimination. Preparation and
16 warmup
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
for same-type numeric equality. The combined change (`c7b6b6a`,
[run 41707420064](https://github.com/elide-dev/trufflecel/actions/runs/41707420064)) reduced
instruction counts in all 14 cases relative to baseline:

| Case | Combined instructions/op | Change from baseline |
| --- | ---: | ---: |
| Compile/evaluate | 21,977.63 | −52.9% |
| Scalar evaluation | 2,133.65 | −17.0% |
| Comprehension | 26,620.54 | −51.0% |
| Native row | 2,642.30 | −40.6% |
| Protobuf | 4,222.81 | −60.2% |
| JSON | 8,208.55 | −55.0% |

[Saved CodSpeed measurements](benchmarks/codspeed-history.json) include all 14 cases for
each recorded run, correctness checksums, source fingerprints, and binary hashes.
Profile self-cost totals were reconciled with every saved instruction count. These results
compare TruffleCEL revisions; the separate paired timing suite compares against cel-java.

Adding instrumentation (`76f2851`, [run 41714577130](https://github.com/elide-dev/trufflecel/actions/runs/41714577130))
increased scalar instructions by 1.7% and comprehension instructions by 4.3%. The next
change (`94f39d7`, [run 41716755365](https://github.com/elide-dev/trufflecel/actions/runs/41716755365))
removes temporary scalar argument arrays and repeated operator dispatch, specializes literal
execution, and stores a single normalized activation binding directly. Relative to the tagged
implementation:

| Case | Instructions/op | Change |
| --- | ---: | ---: |
| Scalar evaluation | 1,267.34 | −41.6% |
| Comprehension | 25,665.44 | −7.6% |
| Native row | 2,239.30 | −15.9% |
| Two-variable transform | 7,561.44 | −19.1% |
| Compile/evaluate | 21,632.09 | −3.1% |
| Short-circuit | 2,069.87 | +2.6% |
| Optional chain | 3,782.21 | +1.7% |
| Protobuf | 4,293.60 | +1.1% |

The other cases changed by between −1.1% and +0.6%. This is a mixed result, with the largest
improvement in the targeted scalar path. Local guest-JIT allocation checks report 64 bytes
per scalar evaluation (previously 104) and 472 bytes for the comprehension fixture
(previously 512). Neither these local allocation checks nor CodSpeed instruction counts
prove a particular peak-RSS or wall-clock speedup; the paired suite measures those separately.

The next change (`300a638`, [run 41723855384](https://github.com/elide-dev/trufflecel/actions/runs/41723855384))
adds integer division, remainder, and comparison fast paths, and normalizes host lists into
a single owned snapshot. Relative to `94f39d7`, CodSpeed reports 13.2% fewer comprehension
instructions and 2.6% fewer scalar-evaluation instructions. Compile/evaluate increases by
2.9%, and protobuf and JSON each increase by 3.7%; the other cases change by at most 1.2%.
All 14 profile self-cost totals reconcile with the saved measurements. The local compiled
comprehension gate drops from 472 to 336 bytes/evaluation, with its limit tightened to 448.

Arithmetic-chain lowering (`a44e445`, [run 41727711499](https://github.com/elide-dev/trufflecel/actions/runs/41727711499))
reduces scalar-evaluation instructions by another 19.0%, to 1,000.33 instructions/op, and
compile/evaluate instructions by 3.4%. Math extrema increases by 2.4%; the other cases
change by at most 1.3%. Every saved profile total is reconciled. Source-based compilation
retains the original expression nodes for instrumentation; the ordinary Java API combines
eligible integer arithmetic while preserving budgets, unknowns, and intermediate overflow.

Allocating only the containers each macro needs (`456e1bc`,
[run 41729137565](https://github.com/elide-dev/trufflecel/actions/runs/41729137565)) reduces
comprehension instructions by 4.7%, two-variable transforms by 5.1%, and JSON by 3.9%.
Other cases change by less than 0.8%; all 14 totals reconcile. The compiled `all` fixture
uses 224 bytes/evaluation locally, down from 336, with a 320-byte limit. The paired suite
also records lower allocation for comprehensions on both JVM and SVM.

Skipping constructor-only adapters during runtime dispatch and specializing integer
equality (`b4a0150`, [run 41731020047](https://github.com/elide-dev/trufflecel/actions/runs/41731020047))
reduces comprehension instructions by 29.9%, short-circuit evaluation by 39.9%, JSON by
23.9%, protobuf by 17.0%, and native-row access by 7.6%. Other cases change by less than
1%; all 14 profile totals reconcile. Custom object equality overrides retain precedence.

Moving unobservable evaluation contexts into the guest target (`78a4c2c`,
[run 41737027259](https://github.com/elide-dev/trufflecel/actions/runs/41737027259))
reduces comprehension instructions by 15.1% and scalar instructions by 5.5% relative to
`d415645`. Its paired suite retains 24/24 timing and allocation wins, but only 9/24 RSS
wins. Native comprehension peak RSS reaches about 139 MiB: expanding context construction
inside the guest compiler has a process-memory cost despite lower evaluation allocations.

Keeping escaping local contexts outside the guest target (`325e7cb`,
[simulation run 41740534860](https://github.com/elide-dev/trufflecel/actions/runs/41740534860))
retains 14.1% fewer comprehension instructions and 12.6% fewer two-variable-transform
instructions relative to `d415645`. Scalar instructions increase 4.5%, short-circuit
instructions increase 2.6%, and compile/evaluate increases 1.0%; these are tradeoffs, not
universal improvements. All 14 profile totals reconcile for both runs. Separate native
diagnostics place comprehension peak RSS near 120 MiB after the change. The complete paired
suite confirms approximately 119.8 MiB and retains 24/24 timing and allocation wins, with
9/24 RSS wins, on an AMD EPYC 7763 runner.

Using a checked identifier as a single-binding lookup hint (`65c9089`) avoids allocating
map-entry iterators when an environment declares more variables than the activation supplies.
Unmatched keys retain the existing lookup behavior. A [paired native diagnostic](benchmarks/binding-lookup-diagnostic.json)
on an Intel Xeon 2.70 GHz worker records the following medians across three fresh processes:

| Guest-JIT evaluation | TruffleCEL bytes/op | cel-java bytes/op | TruffleCEL peak RSS | cel-java peak RSS |
| --- | ---: | ---: | ---: | ---: |
| Scalar | 16 | 216 | 62.2 MiB | 68.5 MiB |
| Policy | 116 | 412 | 108.1 MiB | 66.6 MiB |
| Comprehension | 384 | 648 | 119.3 MiB | 66.0 MiB |

This is a scalar RSS win in the diagnostic, with policy and comprehension still behind.
Both engines use default runtime options and the unchanged fixed-work harness. The saved
report identifies the base revision and applied patch: this measurement predates inclusion
of the lexer optimization. It covers native guest-JIT evaluation only; the README charts
remain the explicitly dated full-suite snapshot until the next complete matrix is available.

The lexer and binding-lookup changes (`31f6702`,
[run 41741898254](https://github.com/elide-dev/trufflecel/actions/runs/41741898254))
reduce CodSpeed compile/evaluate instructions by 12.3%, to 19,123 instructions/op, relative
to `325e7cb`. Other cases change by at most 1.1%; all 14 profile totals reconcile. The
comprehension profile still attributes about 13% of instructions to scope-name lookup,
which motivates resolving local reads to lexical slots during program creation.

Two [independent lookup diagnostics](benchmarks/lookup-diagnostics.json) apply separate patches
to `31f6702`. Validating small immutable scalar activations through declared names
(`04d83d6`) records 28 bytes/op for native policy evaluation, versus the earlier 116.
Its paired policy RSS is 75.2 MiB for TruffleCEL and 67.1 MiB for cel-java, so the allocation
reduction does not yet establish a policy RSS win. Extra and unmatched inputs still pass
through normalization; nulls and mutable inputs retain snapshot behavior.

Resolving local reads to lexical slots (`252466e`) removes repeated scope-name searches.
Its native comprehension diagnostic records 384 bytes/op and 118.6 MiB RSS, versus cel-java's
648 bytes/op and 66.4 MiB. This does not close the comprehension footprint gap. Both candidates
pass the native JIT/blocklist and comparison checks. The saved report includes all 72 process
measurements, binary hashes, worker CPU models, and independently reconstructed source hashes.
These runs do not measure the combined implementation; its full CI matrix remains separate.

The combined lookup changes (`717f203`,
[run 41743793856](https://github.com/elide-dev/trufflecel/actions/runs/41743793856)) retain
24/24 timing and allocation wins and 14/24 RSS wins. CodSpeed reports 15.5% fewer
comprehension instructions, 20.4% fewer two-variable-transform instructions, and 4.0% fewer
JSON instructions relative to `31f6702`; all 14 profile totals reconcile. Native comprehension
compilation remains a footprint gap: 69.0 MiB versus cel-java's 67.5 MiB in this full run.

Avoiding argument arrays for string predicates (`5bdd9be`) reduces native policy allocation
from 28 to 16 bytes/op. Its [paired diagnostic](benchmarks/string-predicate-diagnostic.json)
records 68.7 MiB policy RSS for TruffleCEL versus 67.4 MiB for cel-java. This is still an
RSS loss. Predicate recognition runs after function-name resolution, preserving qualified
custom functions alongside unknown/error precedence and evaluation budgets. The native JIT,
blocklist, and comparison checks pass for this change.

Its [CodSpeed run](https://github.com/elide-dev/trufflecel/actions/runs/41745899048) records
1.7% more compile/evaluate instructions, 2.0% more JSON instructions, and 1.5% more protobuf
instructions relative to `717f203`; the remaining cases change by at most 1.0%. All 14 totals
reconcile. These existing cases do not isolate the string-predicate path, so the paired policy
measurements remain the direct evidence for its allocation reduction.

The completed paired suite for `5bdd9be` retains 24/24 timing and allocation wins and
records 17/24 fixed-work RSS wins on an AMD EPYC 9V45 runner. The README charts use this
full snapshot. Remaining RSS losses are native comprehension compilation, JVM and native
guest-JIT comprehension evaluation, native comprehension interpretation, and image-build
policy evaluation. Runner hardware differs from the earlier full runs, so the win counts
describe paired comparisons within each run, not a controlled measurement of revision gains.

Reusing immutable compiler declarations (`385d755`) copies the declaration map only when
inference adds a name. A [paired native compilation diagnostic](benchmarks/declaration-reuse-diagnostic.json)
records lower time, allocation, and fixed-work RSS for all three fixtures:

| Compilation | TruffleCEL bytes/op | cel-java bytes/op | TruffleCEL peak RSS | cel-java peak RSS |
| --- | ---: | ---: | ---: | ---: |
| Scalar | 1,968 | 230,184 | 36.9 MiB | 66.2 MiB |
| Policy | 3,096 | 237,856 | 46.1 MiB | 66.2 MiB |
| Comprehension | 5,320 | 285,384 | 63.5 MiB | 66.8 MiB |

This closes the comprehension compile-RSS gap in the diagnostic. Native JIT/blocklist and
comparison checks pass; the final full-suite snapshot must verify it across execution modes.
The measured revision includes the string-predicate change and excludes frame-storage experiments.
Its [CodSpeed run](https://github.com/elide-dev/trufflecel/actions/runs/41747068746)
records 0.9% fewer compile/evaluate instructions relative to `5bdd9be`; all 14 totals
reconcile and the other cases change by less than 0.9%.

Auxiliary frame locals and exploded binding cleanup (`83d8ffc`) let guest compilation
eliminate scope storage for eligible comprehensions. Source-backed programs retain named
scopes for debugging. The [paired native diagnostic](benchmarks/frame-locals-diagnostic.json)
records 304 bytes/op for comprehension evaluation versus cel-java's 648, but peak RSS
remains 119.4 MiB versus 66.6 MiB. Policy evaluation records 16 bytes/op versus 412,
with RSS of 68.9 MiB versus 67.2 MiB. All three compilation fixtures retain lower paired
time, allocation, and RSS; comprehension compilation records 64.4 MiB versus 66.7 MiB.
These measurements include declaration reuse and string predicates, with 72 fresh processes.
They do not establish a full-matrix RSS win.

The change passes 21 JVM test targets, the native JIT/blocklist and comparison checks,
and tightened allocation gates. Production coverage is 98.63% of lines and 92.87% of
branches. Reentrant evaluation, nested ranges, shadowed bindings, and instrumented scopes
remain covered.

Counting the final comprehension result (`63e1139`) avoids retaining a list consumed only
by `size()`. The [native diagnostic](benchmarks/comprehension-count-diagnostic.json) records
196 bytes/op for guest-JIT comprehension evaluation versus cel-java's 648, and 284 bytes/op
for interpretation versus 648. Paired median times are lower in all nine native comparisons.
Comprehension RSS remains higher: 117.4 MiB versus 66.3 MiB under guest JIT, and 69.4 MiB
versus 66.2 MiB under interpretation. Compilation retains lower paired RSS for all fixtures.
The 108-process report predates the subsequent reuse of owned input snapshots during iteration.

Internal iteration can also reuse normalized list storage instead of cloning it. Public array
conversion still returns a copy, and host mutation cannot alter the evaluation snapshot.
With both changes, the JVM allocation gate records 104 bytes/evaluation for `all` and
256 for filter/map/size, with limits of 160 and 320 respectively. All 21 JVM targets pass;
production coverage is 98.64% of lines and 93.04% of branches.

The [0.2.0 release profile](https://github.com/elide-dev/trufflecel/actions/runs/41754803857)
records 3.7% fewer comprehension instructions and 11.8% fewer two-variable-transform
instructions relative to `385d755`. Sorting records 3.3% more instructions and native-row
access 2.3% more; the remaining cases change by at most 1.7%. All 14 region totals reconcile
with their saved profiles. These are native-AOT interpreter measurements; guest-JIT timing
and allocation remain separate measurements in the paired suite.

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
fails rather than skips. Scalar, logical, conditional, and addition fixtures are capped at
64 bytes/op; `all` at 160; optional evaluation at 192; list construction and filter/map at
320; maps at 512; row access at 640; protobuf/JSON at 1,024; and CIDR parsing/containment
at 20,000. These are bounded regression budgets for the fixed
fixtures, not zero-allocation claims or limits for arbitrary inputs.

`bazel test //benchmarks:jit_smoke` builds a separate Linux amd64 Native Image containing
the optimizing guest compiler. `TruffleCheckBlockListMethods` and
`TruffleCheckNeverPartOfCompilation` are explicitly enabled. The smoke executable runs all
21 workload correctness cases, then requires actual last-tier guest compilation and changed
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
activations with checked results. There are eight paired scenarios (48 engine/workload cells, 144 timing processes and 144 separate memory processes):

| Runtime | Scenario | Timed operation |
| --- | --- | --- |
| GraalVM JVM | Compile | Parse, check, and create an executable program |
| GraalVM JVM | Evaluate | Reuse a program compiled during process setup |
| Native Image / SVM | Compile | Parse, check, and create an executable program |
| Native Image / SVM | Evaluate | Reuse a program compiled during process setup |
| Native Image / SVM | Image-build AST | Evaluate a checked AST prepared during image construction |
| GraalVM JVM | Interpret | Evaluate with Truffle guest compilation disabled |
| Native Image / SVM | Interpret | Evaluate with Truffle guest compilation disabled |
| Native Image / SVM | Image-build interpret | Interpret a checked AST prepared during image construction |

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
not treated as steady-state compilation benchmarks. Each compiler environment is initialized
before its frontend timers start. The observations belong to the image build (which may be cached or
remote), not necessarily to the host that later runs the timing suite.

TruffleCEL compiled-evaluation cases require a valid last-tier `OptimizedCallTarget` before and after
measurement. Guest compilation, context initialization, input fixture creation, and program
setup are outside evaluation timing. The timed public API includes activation normalization,
argument dispatch, result conversion, and checksum accumulation. Compilation returns a
program to a volatile sink without evaluating it; compiler environments are reused. There
is no claim that moving frontend work into image construction improves warmed evaluation:
it removes that frontend work from runtime startup, which is a different benefit.

Interpreter cases set `engine.Compilation=false` and assert that their targets have no
valid guest machine code before and after measurement. Host JVM JIT remains enabled for
both engines; SVM executes native-AOT interpreter code. These cases use the same
compiler-capable native executable, not a smaller fallback-only deployment image.

Schemas 2 and 3 initialize and execute only the selected engine in each process. In particular,
cel-java does not create a Truffle context, and JVM runs do not initialize the other engine's
compiler or image-AST holder. Both engines remain on the classpath/in the common native
executable, whose image heap contains both sets of checked ASTs. This measures the shared
harness, not the footprint of independently linked production distributions. Historical
schema-1 timing snapshots predate this setup isolation and contain no RSS samples.

Schema 3 separates timing from memory measurement. Every process is wrapped by Linux
`/usr/bin/time -f %M`. The timing process retains its whole-lifetime RSS as
`timed_peak_rss_kib`, preserving the earlier time-bounded measurement. Faster engines may
execute more work in those runs, so that number mixes work volume with memory behavior.

Each separate memory process performs one warmup batch and seven measured batches:
1,024 compilations per batch, or 65,536 evaluations per batch. Thus both engines perform
8,192 compilations or 524,288 evaluations, plus the same four correctness checks and
2,000 preparation evaluations in the guest-JIT scenarios. Guest compilation itself is
required for TruffleCEL's JIT scenarios and included in whole-process RSS. These are bounded
workloads with fixed warmup, not claims that all host compiler activity has ceased.

`allocated_bytes_per_op` records seven samples from
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` on the benchmark thread.
The counter must be supported, enabled, and pass a calibration that allocates an escaped
1 MiB array. That common calibration is excluded from bytes/op and included in RSS.
Allocation measurement covers the public API, normalization, and checksum handling; it
excludes setup, warmup, and allocations on compiler/background threads. It measures heap
allocation, not retained objects, native memory, or code-cache size. Unsupported or broken
accounting fails the run. Zero allocations are valid; negative counters are not.

`peak_rss_kib` now belongs to the fixed-work memory process. It includes startup, compiler
setup, calibration, warmup, guest compilation, execution, and shutdown. It remains a
whole-process high-water mark, not retained heap or a standalone deployment footprint.
Schema-2 RSS uses a different workload and must not be treated as a controlled before/after
comparison with schema 3.

TruffleCEL compilation and interpretation use the context-free Java API. Interpreter
processes set `polyglot.engine.Compilation=false` before creating any target and verify
that no guest machine code appears. JIT cases still create a context to configure and
require synchronous guest compilation. Neither engine performs unnecessary Polyglot
initialization for compilation or interpretation.

The charts are `comparison.svg` (ns/op), `allocations.svg` (heap bytes/op), and
`peak-rss.svg` (fixed-work peak RSS in MiB). Each uses medians and min/max whiskers across
three independent processes. Historical schemas 1 and 2 remain renderable; missing
allocation data is never synthesized. The checked-in README snapshot is schema 3;
earlier raw datasets are retained.

Each timing cell runs in three fresh processes, in a deterministic shuffled order. Each process
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
python3 tools/bench/compare.py render build/comparison/comparison.json build/comparison/peak-rss.svg --metric peak-rss
python3 tools/bench/compare.py render build/comparison/comparison.json build/comparison/allocations.svg --metric allocations
```

Bazel builds both executables with pinned toolchains; measurement runs on the same local
host for both runtimes. `comparison.json` records all samples, source commit and digest,
machine details, engine versions, and image-build preparation observations. `comparison.svg`
is standalone and suitable for a README. CI runs the reusable `comparison.yml` workflow
alongside CodSpeed and uploads the JSON and all three SVG files as the `cel-comparison` artifact. The workflow
also supports manual dispatch. Check in an explicitly dated result snapshot when updating
the README; CI does not silently rewrite the published chart.

The optimized Linux run (`c7b6b6a`, [CI 41707420064](https://github.com/elide-dev/trufflecel/actions/runs/41707420064))
has lower TruffleCEL median time in all 15 paired comparisons. Each TruffleCEL process
median is below every cel-java process median within its cell. Across these fixtures,
compilation speedups range from 45.67× to 190.98×, and evaluation speedups from 1.35× to
2.65×. The prior losses changed as follows:

| Scenario | Baseline TruffleCEL | Optimized TruffleCEL | cel-java in optimized run |
| --- | ---: | ---: | ---: |
| JVM scalar evaluation | 64.64 ns | 31.36 ns | 42.46 ns |
| SVM comprehension evaluation | 1,105.15 ns | 431.18 ns | 919.32 ns |
| SVM image-build AST comprehension | 1,107.36 ns | 429.09 ns | 915.66 ns |

The [original baseline samples](benchmarks/comparison-baseline.json) and [optimized schema-1 samples](benchmarks/comparison-optimized-v1.json)
come from separate shared runners; use each run's paired cel-java measurements for the
comparison. The workload definitions, timing harness, warmup, and sample counts were
unchanged. These measurements support the stated fixture results, not a claim about every
CEL expression or every machine.

The expanded schema-2 run (`ad3fdcb`, [CI 41711452800](https://github.com/elide-dev/trufflecel/actions/runs/41711452800))
adds interpreter execution and whole-process peak RSS. TruffleCEL leads in 23/24 timing
cells; JVM scalar interpretation is about 1.92× slower. Peak RSS is higher in 23/24 cells.
On SVM, interpreter RSS is approximately 3–9% higher; guest-JIT evaluation RSS is about
54–84% higher. These are the baseline gaps for further optimization. Schema-1 and schema-2
runs use different shared runner CPUs and setup isolation, so compare engines within each
run rather than treating their absolute times as a controlled before/after experiment.

The schema-3 run (`a44e273`, [CI 41717938644](https://github.com/elide-dev/trufflecel/actions/runs/41717938644))
records 23/24 timing wins, 16/24 allocation wins, and 8/24 fixed-work RSS wins for TruffleCEL.
JVM scalar interpretation remains slower. Native guest-JIT RSS is roughly 103–117 MiB,
versus 64–69 MiB for cel-java, despite lower scalar allocations. This demonstrates the
importance of reporting allocation and deployment footprint separately. The
[earlier schema-2 RSS snapshot](benchmarks/comparison-throughput-rss-v2.json) uses timed,
unequal-work processes and must not be used to claim an RSS improvement from schema 3.

The normalization run (`300a638`, [CI 41723855384](https://github.com/elide-dev/trufflecel/actions/runs/41723855384))
records 23/24 timing wins, 17/24 allocation wins, and 7/24 fixed-work RSS wins. JVM scalar
interpretation still trails cel-java. Native interpretation is 1.3–2.0× faster for these
fixtures, while guest-JIT RSS remains about 103–118 MiB versus cel-java's 63–69 MiB.
This run uses an AMD EPYC 9V74, whereas the [first allocation snapshot](benchmarks/comparison-allocation-baseline-v3.json)
uses an EPYC 7763; these are paired engine comparisons, not a controlled measurement of
the change between commits. Small RSS differences also need repeated measurements before
being treated as regressions or improvements.

The arithmetic-chain run (`a44e445`, [CI 41727711499](https://github.com/elide-dev/trufflecel/actions/runs/41727711499))
records **24/24 timing wins**, including every interpreter case, alongside 17/24 allocation
wins and 7/24 fixed-work RSS wins. JVM scalar interpretation is 1.16× faster than cel-java
in this run. Native guest-JIT RSS remains about 100–117 MiB versus 64–69 MiB for cel-java.
The runner is an AMD EPYC 7763; the results support these paired fixture comparisons and
do not establish universal speedups or a lower native process footprint.

The macro-container snapshot (`456e1bc`, [CI 41729137565](https://github.com/elide-dev/trufflecel/actions/runs/41729137565))
retains 24/24 timing wins and increases allocation wins to **22/24**. Only the JVM policy
cases still allocate more than cel-java. Fixed-work RSS remains lower in 7/24 comparisons.
This run uses an AMD EPYC 9V74; the same shared-runner and whole-process memory caveats apply.

The current snapshot (`d415645`, [CI 41733144626](https://github.com/elide-dev/trufflecel/actions/runs/41733144626))
records **24/24 timing wins and 24/24 allocation wins**. Reusing immutable scalar binding
snapshots closes the two JVM policy allocation gaps, with mutable host inputs still copied
before evaluation. Fixed-work RSS is lower in **9/24** comparisons; native guest-JIT process
memory remains higher. This run uses an Intel Xeon Platinum 8370C. The engine comparisons
are paired within this run; absolute timings across different runner CPUs are not controlled.

The context-placement snapshot (`325e7cb`,
[run 41740534860](https://github.com/elide-dev/trufflecel/actions/runs/41740534860))
retains **24/24 timing and allocation wins and 9/24 RSS wins**. Native guest-JIT RSS is
87.2 MiB for scalar evaluation, 108.7 MiB for policy evaluation, and 119.8 MiB for
comprehensions. This run uses an AMD EPYC 7763; comparison with earlier commits on different
runner hardware is not a controlled before/after measurement.

The lexer and binding-lookup snapshot (`31f6702`,
[run 41741898254](https://github.com/elide-dev/trufflecel/actions/runs/41741898254))
retains **24/24 timing and allocation wins**, with **14/24 RSS wins**. Native scalar
guest-JIT RSS is approximately 62.2 MiB versus cel-java's 68.4 MiB; image-prepared scalar
evaluation also wins. The other additional RSS wins are JVM comprehension interpretation
and both native scalar interpreter modes. Some differences are small: these are measured
medians from three processes, not guarantees across machines. The runner is an AMD EPYC 7763.

The README snapshot is checked in as [raw JSON](benchmarks/comparison.json) and a
[standalone SVG](images/cel-comparison.svg). To regenerate that chart from its saved samples:

```sh
python3 tools/bench/compare.py render docs/benchmarks/comparison.json docs/images/cel-comparison.svg
python3 tools/bench/compare.py render docs/benchmarks/comparison.json docs/images/cel-peak-rss.svg --metric peak-rss
python3 tools/bench/compare.py render docs/benchmarks/comparison.json docs/images/cel-allocations.svg --metric allocations
```

For fast correctness checks, use `bazel test //benchmarks/comparison:smoke --config=local`.
The Linux native check is `bazel test //benchmarks/comparison:native_smoke`; it additionally
requires successful image-build AST initialization and the Truffle blocklist gates.

### Native compiler memory diagnostic

A separate [three-fork diagnostic](benchmarks/compiler-isolate-diagnostic.json) at
`d415645` compares Native Image's default compiler isolate with compilation in the
application isolate (`-XX:-CompileInIsolates`) and a 16 MiB compiler-isolate heap
(`-XX:CompilationIsolateOptions=-Xmx16m`). Both engines receive the same options.
Each uses the existing fixed-work evaluation harness; timing runs in separate processes.
All 108 processes pass correctness checks, including last-tier guest compilation for
TruffleCEL. Median fixed-work peak RSS for TruffleCEL is:

| Configuration | Scalar | Policy | Comprehension |
| --- | ---: | ---: | ---: |
| Default | 99.0 MiB | 103.4 MiB | 118.9 MiB |
| Compiler in application isolate | 88.3 MiB | 89.0 MiB | 93.0 MiB |
| Separate compiler heap capped at 16 MiB | 99.1 MiB | 101.9 MiB | 107.5 MiB |

cel-java remains around 66–69 MiB across these configurations. Sharing the application
isolate reduces the gap but does not close it. These are exploratory measurements from
one BuildBuddy action; its CPU model was not captured, and timing variance prevents a
controlled claim about latency improvement. The README charts retain the default settings.

On Linux, reproduce one cell after building `//benchmarks/comparison:svm`:

```sh
/usr/bin/time -f '%M KiB peak RSS' bazel-bin/benchmarks/comparison/trufflecel-comparison \
  -XX:-CompileInIsolates trufflecel evaluate scalar --memory
```

Replace the engine with `cel-java` for the paired measurement, repeat in fresh processes,
and omit the runtime option for the default. An application compiling in its application
isolate must also assess GC interference under its own concurrency and workload.

### Native memory by execution phase (GraalVM .27)

The [phase attribution report](benchmarks/native-memory-phases-010.json) preserves
three fresh forks of every scalar/policy/comprehension configuration, with paired
TruffleCEL and cel-java processes in one RBE action. Profiling pauses the child at
startup, context creation, preparation, first results, warmup, last-tier compilation,
fixed evaluation, application GC, idle, and context close. Separate fresh processes
measure timing. The report retains all phase counters and timing samples, the exact
commands, binary/source identities, and links/digests for compressed Linux mapping
snapshots. This is an embedding-configuration experiment on production baseline
`67921ff`, not a measurement of the subsequent library optimizations.

Both engines use the same combined executable, Serial GC and an explicit
`-Xmx512m` budget on the same reported `AMD EPYC` host (Linux 5.15, seven available
logical CPUs). These absolute numbers are not interchangeable with the default
comparison charts or standalone executable footprints. Each table cell is
**TruffleCEL / cel-java**; RSS is current `smaps_rollup.Rss` immediately after the
fixed evaluation workload, and times are medians of per-fork medians.

| Configuration | Scalar RSS (MiB) | Policy RSS (MiB) | Comprehension RSS (MiB) | Comprehension time (ns) |
| --- | ---: | ---: | ---: | ---: |
| Default | 66.51 / 70.43 | 83.70 / 70.36 | 130.41 / 65.99 | 86.67 / 559.87 |
| Compiler in application isolate | 57.65 / 70.59 | 74.79 / 70.64 | 93.91 / 65.77 | 88.00 / 557.32 |
| Separate compiler heap capped at 16 MiB | 65.64 / 70.51 | 79.02 / 70.63 | 113.16 / 65.77 | 86.55 / 561.50 |
| Compiler in application isolate, `MaxHeapFree=32m` | 57.66 / 54.14 | 74.88 / 53.77 | 77.22 / 54.11 | 96.25 / 605.37 |

Sharing the compiler keeps the three TruffleCEL median evaluation times within
1.6% of their defaults while reducing current RSS. The 16 MiB compiler cap also
preserves steady evaluation time in these fixtures, but comprehension warmup plus
last-tier compilation increases from 60.7 to 71.0 ms. Limiting retained free chunks
to 32 MiB reduces current resident memory further, with an approximately 11%
comprehension slowdown relative to default. Its Linux lifetime high-water counter
remains about 93.1 MiB, the same as the shared-compiler configuration without the
cap. Current memory reclamation and startup peak reduction are different outcomes.
No embedding option or benchmark threshold is changed by this investigation.

Application heap accounting explains another part of the gap. After application
GC, comprehension reports only 0.81 MiB used but 47.31 MiB committed in the default
application isolate; RSS is still 127.98 MiB. With the compiler in that isolate,
the corresponding figures are 1.81 MiB used, 49.31 MiB committed and 91.42 MiB RSS.
The compiler cap leaves application heap commitment at 47.31 MiB while lowering
RSS to 110.73 MiB. These observations distinguish application free-chunk retention
from the additional footprint of separate compilation. They do not assign every
anonymous mapping to a particular isolate or measure retained bytes per program.

A second action repeats comprehension with three forks and a 12-second idle period,
exceeding the effective 10-second compiler and graph-cache idle delays recorded from
each target. It does not reclaim the remaining footprint: default RSS is 127.98 MiB
after application GC and 128.04 MiB after idle plus another GC. The shared-compiler
figures are 91.53 and 91.58 MiB; the compiler-cap figures are 110.78 and 110.84 MiB.
Closing the Polyglot context also leaves these resident pages in place. This context
does not own the Java API target's standalone engine, so context closure is not a
test of compiler-engine disposal or proof that all target references were released.
The excess is still resident at the measured idle
point, rather than disappearing at the configured idle delay.

`MemoryMXBean` supplies the heap counters: the pinned Native Image Serial GC
implementation's `Runtime.totalMemory()` returns its maximum heap budget, not
committed memory. `System.gc()` operates on the application isolate, so it does
not collect a separate compiler isolate. The report also keeps PSS, file/anonymous
mapping classes and Linux `VmHWM`; the latter is accounted separately and can lag
the current `smaps` RSS value.

The probe uncovered a [GraalVM .27 non-heap accounting defect](benchmarks/graalvm-nonheap-counter-issue.md).
With a shared compiler, the management bean can return implausibly small positive
values and subsequently throw for negative runtime-code sizes after GC. Such
getter failures are explicitly `null` with their error text, rather than zero or
omitted phases; suspect positive readings remain recorded but are unreliable.
Independent executable anonymous mappings occupy 20 KiB after
comprehension evaluation in all three compiler configurations; file-backed compiler
and runtime code is accounted separately. The upstream report remains a draft.
Correctness, checksums, effective target options and last-tier validity remain
mandatory throughout the diagnostic.

All 45 engine comparisons completed across the two actions, using 90 profiled
processes and 90 fresh timing processes. The focused counter tests,
effective-option smoke, profiler-driver tests,
native comparison smoke and Linux native JIT/blocklist gate also pass. The raw
report retains 93 explicitly unavailable non-heap readings; they are a limitation
of the upstream counter, not missing RSS/heap samples or waived CEL checks.

To reproduce the three-fork matrix, use the recorded command in the report or:

```sh
bazel test //benchmarks/comparison:memory_diagnostic \
  --test_env=MEMORY_SOURCE_REVISION="$(git rev-parse HEAD)" \
  --test_env=MEMORY_FORKS=3 --test_env=MEMORY_IDLE_MS=0 \
  --test_env=MEMORY_VARIANTS=default,shared-compiler,bounded-compiler,shared-free-32m
```

The original harness commits `d51d535`, `8d87446` and `aa70c27` correspond to the
reachable PR commits `9427bb1`, `171cc15` and `092d3b1`, respectively. Applying those
harness changes to baseline `67921ff` reproduces the recorded production inputs.
The historical compiler-isolate measurements above remain unchanged.


## Explicit Java host entry

During the TruffleData 0.6.0 upgrade and source-boundary fix, CodSpeed flagged scalar
and short-circuit fallback-interpreter regressions. Retained profiles attribute about
187 extra instructions per evaluation to Truffle frame-stack thread-local map probes.
The images embed different thread-local hash values; the CEL scalar operation counts
are unchanged. A fresh evaluation thread does not remove the difference.

A [controlled five-image diagnostic](https://github.com/elide-dev/trufflecel/actions/runs/41972037029)
uses the same harness and one Linux runner to compare the release baseline, boundary-only,
dependency-only, combined, and explicit-host-entry variants. Each runs on both the setup
thread and a fresh evaluation thread. [Raw counts, checksums and image identities](benchmarks/host-entry-diagnostic.json)
record the evidence. On the setup thread:

| Variant | Scalar instructions/op | Short-circuit instructions/op |
| --- | ---: | ---: |
| Release baseline | 1,113.77 | 1,316.80 |
| Boundary only | 1,300.77 | 1,503.80 |
| Dependencies only | 1,300.77 | 1,503.80 |
| Boundary and dependencies | 1,300.77 | 1,503.80 |
| Explicit Java host entry | 936.02 | 1,140.65 |

`Program.evaluate` now calls Truffle's explicit-location overload with no guest call site
for the Java entry. This avoids the ambient call-location lookup while retaining the
call target, guest frame tracking, and compilation heuristics. The internal Polyglot
entry retains its ambient location lookup. Nested-call tests verify frame presence and
cleanup after success and failure; existing instrumentation, concurrency, allocation,
and native-JIT gates qualify the remaining contracts.

The production benchmark harness, workload, iteration count, and thresholds are unchanged.
The fresh-thread experiment remains diagnostic only. These instruction measurements do
not establish wall-clock throughput or memory improvements across arbitrary embeddings.
