# Six-fixture evaluation refresh, 2026-11-08

This report measures TruffleCEL candidate `67ab33c` against cel-java's default and
optimized planners using all six representative evaluation fixtures and the existing
public Map-entry path. It separates steady evaluation timing from allocation and
Linux process-memory observations. Caller-prepared cel-java activation forms require
separate controls; this is not a comparison of every optimized embedding path.

The ordinary-Map matrix favors TruffleCEL evaluation time in all 24 matched
runtime/mode/fixture comparisons. Allocation is lower in 23 and tied in the JVM
scalar interpreter control. Process memory remains a gap:

| Metric | Lower TruffleCEL point estimate | Equal | Higher |
| --- | ---: | ---: | ---: |
| Evaluation time | 24 | 0 | 0 |
| Current-thread allocation | 23 | 1 | 0 |
| Current RSS at the checkpoint | 9 | 0 | 15 |
| Lifetime peak RSS through the checkpoint | 10 | 0 | 14 |

Native guest-JIT current RSS is 239.03 MiB for protobuf policy, 176.08 MiB for the
materialized repeated-list result, and 196.58 MiB for maps/regexes, versus the lower
cel-java mode's 109.23, 56.20 and 82.38 MiB. Lower allocation has therefore not closed
those process-footprint gaps. The wide-activation timing/allocation advantage also
includes cel-java's ordinary-map copying, explained below.

The [complete tables](tables.md) contain every runtime, fixture and engine. The
[machine-readable summary](summary.json) retains all three per-fork point estimates
and measurement drift. Counts compare each TruffleCEL mode with the lower result of
cel-java's two modes for that particular metric; they are descriptive point estimates,
not significance tests or proof of universal optimality.

## Scope and provenance

- Production candidate: `67ab33c`, after published baseline `4c5df30`.
- Measured clean source: `755e9bae6adac0e5360f27c02f808da3f604d265`.
  The production `src/main/java` tree is `4391fa7a0c1b17959c02a9f372e53014b05260df`.
- Dependencies: GraalVM `25.5.5-elide.27`, TruffleData `0.9.0`, cel-java `0.15.0`,
  JMH `1.37`. TruffleData `0.10.0` remains the next release gate.
- This snapshot still uses RE2/J. A subsequent TruffleRegex integration requires
  its own paired measurements; its results must not replace this baseline.
- [source.json](source.json) records 100 source hashes. [source.patch.gz](source.patch.gz)
  reconstructs the complete measured change from `4c5df30`, including production
  and benchmark changes. This preserves provenance after branch commits are
  cherry-picked. [verify_source.py](verify_source.py) verifies reconstruction.
- RBE-built artifacts were executed serially on the existing `unclemax` Linux host:
  AMD Ryzen Threadripper PRO 9965WX, 24 cores/48 threads, Linux `7.0.0-43-generic`.
  CPU affinity was `2–9`. The shared benchmark lock excluded our other benchmark
  jobs, but these CPUs were not isolated and the host was not reserved exclusively.
  No governor, THP, NUMA or other global host settings were changed.
- Each phase records observed load, effective VM flags, governors, CPU/kernel data,
  exact commands, artifact hashes and source hashes. Observed one-minute load was
  0.31 at the start, at most 1.43 before a measured command, and 1.11 at the end
  on this 48-thread host; recorded governors were `performance`. Timing phases have
  no allocation profiler, and memory work completed before timing began.

The production change avoids an internal wire-buffer clone in `decode(Bytes)`.
These prepared evaluation fixtures use `snapshot(Message)`, whose implementation
is unchanged. This report therefore establishes the current comparison, **not a
before/after speedup caused by that ingestion fix**. It also must not be compared as
an isolated code regression or improvement with older runs on different dependency
versions, JVM flags, machines or memory protocols.

## Matched workload and compiler checks

All engines evaluate the same CEL source and four independently checked input
variants. `scalar` has one binding; every other fixture retains the full 69-binding
activation, including unrelated maps, lists and protobuf data. The six fixtures are:

| Fixture | Evaluated work |
| --- | --- |
| `scalar` | One-binding scalar control |
| `scalar-wide` | Same scalar expression with all 69 bindings |
| `proto-policy` | Nested protobuf presence, wrapper, timestamp and map policy |
| `proto-repeated` | 128-element repeated input; filter/map materializes 77–80 output elements across the four variants |
| `map-regex` | Map lookups and regular expressions |
| `optimizer-policy` | Repeated subexpressions and constant arithmetic |

TruffleCEL receives native protobuf snapshots and cel-java receives generated
protobuf messages containing the same data. Fixture creation and ingestion precede
the evaluation measurement; ingestion costs must be assessed separately.

The full activation is a JDK unmodifiable map backed by a `LinkedHashMap`.
cel-java 0.15.0's planner `eval(Map)` calls `Activation.copyOf`, which copies this
map's entries on each evaluation. Its fast path reuses a Guava `ImmutableMap`;
callers can also use a variable resolver. This follows the pinned
[runtime sources](https://repo.maven.apache.org/maven2/dev/cel/runtime/0.15.0/runtime-0.15.0-sources.jar),
and is consistent with the scalar versus wide-scalar allocation difference
(96 versus 2,936 B/op on the JVM). Thus the wide-activation result includes public
Map-entry adaptation cost. It does not establish the same margin for applications
that supply cel-java's reusable activation forms. The optimized comparator here
changes AST planning, not input-map representation.

Results escape to JMH's result consumer or a volatile sink. The repeated fixture
returns and verifies the actual list; it does not replace the result with a size.
The memory harness verifies complete expected outputs before and after measurement.

TruffleCEL uses the default frontend in interpreter and guest-JIT modes. cel-java
uses its default planner or planner plus constant folding/common-subexpression
elimination. The optional Pratt parser is omitted because this is evaluation-only;
parse/check/plan time is outside the timed loops. JVM processes explicitly disable
the JVMCI **host** compiler (`-XX:-UseJVMCICompiler`) to select C2 while retaining
Truffle guest compilation. The pinned GraalVM system default is not assumed.

For every TruffleCEL process, target options require the requested compilation mode,
background compilation disabled, compilation failures set to `Throw`, and all
compiler performance warnings treated as errors. Guest-JIT measurements force and
verify a valid last-tier target; interpreter measurements reject guest compilation.
No compiler gate or correctness check was relaxed.

## Timing protocol

JVM: 24 JMH cells, each with three fresh forks, five one-second warmup iterations
and five one-second measurement iterations. The table uses the mean of the three
fork means, matching the JMH score. Native: 72 fresh processes, three per cell,
using the existing representative lifecycle harness with five one-second warmup
windows and seven half-second measurement windows. The native table uses the
median of the three per-fork medians. Native ASTs are prepared at process runtime;
these are not image-prepared-AST startup measurements.

The existing native images configure fixture statics for build-time initialization;
the AST mode does not imply that fixture creation or ingestion occurs at runtime.

The engine/fixture order is shuffled deterministically with seed `20261108`.
All JVM processes use `-Xms512m -Xmx512m --enable-native-access=ALL-UNNAMED` in addition
to the C2 host flag. Native processes also use `-Xms512m -Xmx512m`; the native images
use Serial GC. Heap policy is matched within each runtime, not across runtimes.

Every timing cell completed all three forks. The greatest absolute change between
the first two and last two measurement samples within a fork was 5.03% on the JVM
and 8.49% on native. No cell crossed the 10% diagnostic screen used to identify a
longer-warmup rerun. These are measurement-window drift checks, not proof of peak
convergence; the raw archive retains warmup and measured samples. The observed
three-fork timing ranges do not overlap the faster cel-java mode's range in any of
the 24 TruffleCEL comparisons, but three forks on a shared host do not establish
statistical significance or generalize beyond these inputs.

## Allocation and process-memory protocol

144 separate fresh processes cover two runtimes × four engines × six fixtures ×
three forks. They run five batches of 65,536 warmup evaluations and seven measured
batches of 65,536 evaluations. JVM memory processes additionally use `-Xbatch`.
Current-thread allocated bytes are read only around the measured batches and are
calibrated in every process with an escaping 1 MiB array. Unsupported or invalid
counters fail the run. The table uses the median of seven allocation windows per
fork, then the median of the three forks. Compiler/background-thread allocations
are outside this per-thread evaluation metric.

While the engine, program and prepared inputs remain strongly reachable, the
parent reads `/proc/PID/smaps_rollup` and `/proc/PID/status`, then acknowledges the
checkpoint so the child can close. Current RSS is `smaps_rollup.Rss`; peak RSS is
`status.VmHWM`. Both tables report medians of the three process observations.

**RSS is whole-process footprint at an uncontrolled point in the GC cycle, not
retained heap.** It includes setup, prepared inputs, correctness validation,
1 MiB allocation calibration, warmup, compilation and the fixed sampled work.
Guest JIT adds 2,000 preparation evaluations and forced compilation before the
common warmup/sample counts. Consequently, total work since process launch is
not equal between interpreter, guest JIT and cel-java. The current checkpoint
and lifetime peak are distinct metrics; neither is derived from timing-process
RSS or normalized by unequal timed throughput.

Both native binaries contain the comparison engines and generated protobuf
reflection metadata required by cel-java. Their RSS is not the standalone
deployment size of either production engine. A minimum heap of 512 MiB also makes
these process values incomparable with older maximum-only heap experiments.

## Reproduction and retained evidence

Build the artifacts with the repository's pinned Bazel/GraalVM toolchains:

```sh
bazel build //benchmarks/representative:jmh_deploy.jar \
  //benchmarks/representative:evaluation_memory_deploy.jar \
  //benchmarks/representative:svm \
  //benchmarks/representative:evaluation_memory_svm --remote_download_toplevel
```

The exact driver invocations are in the raw archive's run script and phase
metadata. Each phase uses `tools/bench/evaluation_refresh.py` with separate new
output directories, the recorded source manifest, fixed CPU affinity and the
pinned Linux Java executable. Use an otherwise quiet host and serialize with
other measurements; reproduce all comparator modes with identical artifacts and
flags. Do not overwrite these measurements.

```sh
python3 docs/benchmarks/evaluation-refresh-20261108/verify_source.py
mkdir -p /tmp/trufflecel-evaluation-refresh-raw
tar -xzf docs/benchmarks/evaluation-refresh-20261108/raw.tar.gz \
  -C /tmp/trufflecel-evaluation-refresh-raw
python3 docs/benchmarks/evaluation-refresh-20261108/summarize.py \
  --raw /tmp/trufflecel-evaluation-refresh-raw --output /tmp/trufflecel-evaluation-refresh-summary
```

[raw.tar.gz](raw.tar.gz) retains all memory samples and `/proc` snapshots, JMH JSON
and warmup logs, native sample arrays and stderr, phase metadata, source manifest,
run script, driver and native memory smoke output. [SHA256SUMS](SHA256SUMS) records
digests for the report inputs. Artifact hashes in the metadata identify the measured
deploy jars and native executables; large build binaries are not checked into the report.

Validation: representative JVM smoke and memory smoke, benchmark-driver tests,
native memory repeated-list guest-JIT smoke, complete matrix inventories and strict
compiler gates passed. Source-patch reconstruction verifies all 100 manifest
hashes. Separate release qualification covers production conformance, ownership,
concurrency and native guest-JIT gates.

- [Representative option assertions](https://app.buildbuddy.io/invocation/7b95a2c3-0cd3-4a86-a2a8-efd939b449f7)
- [JVM smoke and driver tests](https://app.buildbuddy.io/invocation/b7cb89d3-78b8-4690-baca-829ca5a00d43)
- [Measured artifact builds](https://app.buildbuddy.io/invocation/e2da5d85-26af-4d34-aaeb-519f94e1dcd5)

The current evidence covers six prepared evaluation fixtures. Launch and preparation
costs and reuse payback are measured in the separate
[startup/payback refresh](../startup-payback-20261108/README.md). Ingestion, larger
policies, concurrent workloads and standalone deployment size need their own
measurements. Lower evaluation allocation alone does not establish lower process
memory or an end-to-end application win.
