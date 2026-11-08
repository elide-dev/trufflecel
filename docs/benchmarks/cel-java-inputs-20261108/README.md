# cel-java full-input representation controls, 2026-11-08

This supplemental control measures how caller-prepared Guava `ImmutableMap` inputs
change cel-java evaluation for the same four full 69-binding fixtures. The original
six-workload matrix remains valid for its ordinary-map API path, but its allocation
and timing include cel-java's repeated input copying. These controls qualify that
comparison; they do not rewrite the original measurements or establish a universal
advantage for either evaluator.

Caller-prepared immutable inputs remove **2,864 B/op on the JVM** and **3,384 B/op
in native execution** in every measured default/optimized scalar-wide and protobuf
policy cell. Scalar-wide evaluation improves by about 20–22×; protobuf policy improves
by 1.78–2.37× across the two runtimes and planner configurations. This is a large
qualification of any cross-engine comparison using only the ordinary full-map path.
It does not establish that either evaluator wins every category.

| Example | Ordinary captured map | Caller-prepared immutable map |
|---|---:|---:|
| JVM scalar-wide, default planner | 563.50 ns/op; 2,936 B/op | 25.17 ns/op; 72 B/op |
| JVM protobuf policy, optimized planner | 1,170.68 ns/op; 4,046 B/op | 632.65 ns/op; 1,182 B/op |
| Native scalar-wide, default planner | 1,275.51 ns/op; 3,504 B/op | 63.61 ns/op; 120 B/op |
| Native protobuf policy, optimized planner | 2,444.35 ns/op; 4,598 B/op | 1,030.83 ns/op; 1,214 B/op |

These are medians of three fork means. [tables.md](tables.md) contains every cell and
its observed range; [summary.json](summary.json) preserves individual fork means and
measurement-drift checks. All paired timing ranges are disjoint in this run. Maximum
within-fork measurement drift is 7.58%, below the stated 10% inspection screen; that
screen is not a proof of convergence. Caller-side preparation cost remains outside
both measured intervals.

The practical follow-up is to include caller-prepared inputs when assessing cel-java's
best reusable evaluation path, while retaining ordinary-map results as a separate API
usage scenario. These two workloads do not establish prepared-input results for the
other four fixtures, and no complete-request or input-preparation payback claim follows
from the steady-state improvement.

## Why this control exists

The pinned [cel-java 0.15.0 runtime sources](https://repo.maven.apache.org/maven2/dev/cel/runtime/0.15.0/runtime-0.15.0-sources.jar)
show `PlannedProgram.eval(Map)` calling `Activation.copyOf` (`PlannedProgram.java`,
lines 105–111). `Activation.copyOf` reuses a Guava immutable map, but constructs a
new immutable copy for other map implementations, filtering null keys and values
(`Activation.java`, lines 104–141). The representative fixture supplies a
`Collections.unmodifiableMap`, which therefore takes the copy path. Unmodifiable
and Guava immutable are materially different input representations here.

The new benchmark modes capture four maps into a `Map[4]` in setup. Both forms use
exactly `program.eval(inputs[i & 3])` in the evaluation loop. The ordinary control
captures the existing maps; the immutable control calls `ImmutableMap.copyOf` once
for each input before the measured interval. Setup checks all 69 bindings, map type,
full map equality and identical bound value references. No unused bindings are
trimmed, no fixture values change, and no production code or global engine options
change. The original `cel-java` and `cel-java-optimized` modes still use their
original `Workloads.input` lookup path.

| Control | Planner configuration | Captured input representation |
|---|---|---|
| `cel-java-prepared-map` | Default | Existing unmodifiable map |
| `cel-java-immutable` | Default | Caller-prepared Guava immutable map |
| `cel-java-optimized-prepared-map` | Constant folding and common subexpression optimization | Existing unmodifiable map |
| `cel-java-optimized-immutable` | Same optimizers | Caller-prepared Guava immutable map |

`scalar-wide` and `proto-policy` preserve all four input variants and their independent
expected outputs. The inputs contain no null bindings; constructing Guava immutable
maps is not a drop-in recommendation for arbitrary nullable bindings. The setup-copy
cost is excluded, so this result describes caller-prepared input reuse. It does not
measure the cost of preparing a new map for every request. Results from the ordinary
captured-map control belong to this new artifact/path; do not claim a regression or
improvement against the original matrix from differences between those separate runs.

## Measurement and limits

Measured source is `a3027e1765f758c8c000bba72d8abd0d192a4ce6`, a benchmark-only extension of the broad
comparison harness `755e9ba`. Its production tree `4391fa7a0c1b17959c02a9f372e53014b05260df` matches
production candidate `67ab33cf89e0f06ad71b0b3c49d32fab53adf2d1` exactly. This retains the candidate's
RE2/J path and TruffleData 0.9.0; subsequent production changes require their own
qualification, and the 0.10.0 release remains gated on TruffleData 0.10.0.

All timings and allocations run on the same Linux host and pinned GraalVM
25.5.5-elide.27 distribution as the broad matrix. The host uses logical CPUs 2–9,
512 MiB initial/maximum heaps, C2 for JVM execution and SerialGC for native execution.
An atomic directory lock serializes the known benchmark windows. Background services
remain, CPUs are not reserved by the OS, and no governor, boost or thermal setting is
changed. Raw metadata records affinity, CPU/kernel details, load before each process,
VM flags, exact commands, source hashes, binary hashes and runtime library hashes.
This is a low-load shared host, not a claim of complete isolation.

There are 96 measured processes: 24 JVM JMH forks, 24 native timing forks and 48
allocation processes. Each of the eight workload/optimizer/representation cells uses
three independent forks per runtime. JVM timing uses five one-second warmup and five
one-second measured iterations with JMH result consumption. Native timing uses five
one-second warmup and seven half-second measured windows with a volatile consumer.
Both verify all four independent expected outputs outside the measurement windows.
The native artifacts configure fixture/schema statics for image-build initialization;
expression ASTs and the four captured control inputs are prepared at runtime before
steady-state measurement. These are evaluation controls, not runtime-ingestion or
image-prepared-AST comparisons.

Allocation is measured in separate fixed-work processes, with five warmup rounds
and seven measured rounds of 65,536 evaluations each. The thread-allocation counter
must pass an escaping one-MiB array calibration; JVM allocation runs additionally
use `-Xbatch`. No allocation profiler or counter sampling is added to the timing
processes. The raw memory process also retains Linux RSS snapshots, but these include
setup, calibration and warmup and are not retained-heap or deployment-size measures.

Tables report the median and observed range of three fork means. JMH fork means
average five measured samples; native and allocation fork means average seven.
Ranges are not confidence intervals. The paired controls use matching captured-array
lookup and the same artifact for both representations. Cross-artifact startup,
compilation payback, and caller-side input construction remain separate questions.
The analyzer also records each fork's absolute difference between its first-two and
last-two measurement means, divided by its whole-fork mean. A 10% screen identifies
cells needing further inspection; passing that screen is not proof of convergence.

## Evidence and reproduction

`raw.tar.gz` retains all three driver phases, individual process logs/results, source
manifest, exact invocation, artifact hashes and archived driver sources. `source.json`
uses a tracked-file allowlist, excluding credentials and `user.bazelrc`. `source.patch.gz`
reconstructs the measured tracked tree from published baseline `4c5df30`, independently
of whether the isolated worktree commits remain reachable after integration.

```sh
git worktree add --detach /tmp/cel-java-input-source 4c5df30
gzip -dc /absolute/path/to/source.patch.gz > /tmp/cel-java-input-source.patch
git -C /tmp/cel-java-input-source apply --index /tmp/cel-java-input-source.patch
git -C /tmp/cel-java-input-source write-tree
```

The resulting complete tree must be `c5cd1a2020acb5decad25d132544962d3f595bfd`; reconstruction
was verified using a temporary Git index. The exact build commands are retained in
`source.json` and the measurement commands in `run.sh`.

The final source passed JVM and native paired-input smoke gates plus the benchmark
Python suite (25 tests) in [BuildBuddy](https://app.buildbuddy.io/invocation/c9a55c72-8bbd-4ef3-a25c-9e49fcdf6b05).
Both native timing and allocation executables were exercised across all eight control
cells; setup assertions and independent results were checked in each. The
[final artifact build](https://app.buildbuddy.io/invocation/85b16ab4-9f5f-4345-a25e-0ee4ab798e85)
produced both deploy jars and both native binaries. Local and remote SHA256 digests
matched before measurement. Formatting and Gazelle completed successfully; no
production target changes resulted.

Regenerate the summaries without executing any benchmark:

```sh
python3 docs/benchmarks/cel-java-inputs-20261108/analyze.py --output /tmp/cel-java-input-summary
diff docs/benchmarks/cel-java-inputs-20261108/summary.json /tmp/cel-java-input-summary/summary.json
diff docs/benchmarks/cel-java-inputs-20261108/tables.md /tmp/cel-java-input-summary/tables.md
```

The analyzer verifies the archived driver/source hashes, complete fork inventories,
matching artifact/runtime hashes across phases and the fixed-work/tier contracts
before computing summaries. `SHA256SUMS` covers all report files except itself.
