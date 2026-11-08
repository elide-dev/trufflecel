# Paired TruffleRegex RE2 qualification, 2026-11-08

This report compares production `67ab33c` (RE2/J) with qualified candidate `9cdc037`
(scoped TruffleRegex RE2 for eligible constant boolean matches). It retains the
same full-input `map-regex` fixture and an unrelated `scalar-wide` control on the
JVM and Native Image, in interpreter and guest-JIT modes. It measures a bounded
production change, not a new six-fixture ranking against cel-java.

The candidate reduces `map-regex` JIT evaluation time by **10.92% on JVM and
11.20% in Native Image**, while increasing allocation and process footprint. This
is a throughput/footprint tradeoff, not a memory optimization. Interpreter changes
are smaller: JVM time falls 2.44%; native time rises 1.14%, with overlapping fork
ranges.

| Regex mode | Time, baseline → candidate (ns/op) | Allocation (B/op) | Current RSS (MiB) |
| --- | ---: | ---: | ---: |
| JVM interpreter | 1,126.72 → 1,099.23 | 1,822 → 1,904 | 419.20 → 434.72 |
| JVM JIT | 926.93 → 825.68 | 1,726 → 1,732 | 486.83 → 536.88 |
| Native interpreter | 1,564.94 → 1,582.80 | 1,740 → 1,804 | 139.68 → 144.87 |
| Native JIT | 1,166.24 → 1,035.67 | 1,660 → 1,664 | 192.54 → 224.39 |

Native JIT lifetime peak RSS rises from 204.81 to 237.00 MiB. The unrelated
`scalar-wide` control has unchanged measured allocation in every mode, but timing
point estimates rise 1.96–4.24%. Its JVM and native-interpreter fork ranges overlap;
its native-JIT ranges do not (23.32–23.92 versus 24.12–25.43 ns/op). Current RSS
also rises 0.93–2.56 MiB in that control. The original evidence is retained without
rerunning or selecting favorable cells.

[Evaluation tables](tables.md) show every matched timing, allocation, current-RSS
and peak-RSS cell with all three fork ranges. [summary.json](summary.json) retains
individual fork values and measurement-window drift. Startup uses four first-result
processes per configuration, one for each original input variant; its observations
are separate from evaluation throughput and fixed-work memory.

## Sources and backend proof

- RE2/J production tree: `4391fa7a0c1b17959c02a9f372e53014b05260df`.
  Final clean measured source: `5eb191aa1796c74c4d7e7a00245a278be86186b0`.
- Candidate production tree: `246c6c7f3e39c0ee95733fd6621f2a91b0f31ae7`.
  Final clean measured source: `3fe435cb1c25b8104692a48dc53a69b9e03dc45f`.
- The original artifact-build commits were `ce4bb1b` and `198a43a`. A Python-only
  startup-provenance fix was then applied. Cached builds on the final sources
  reproduced all 12 native/JAR digests exactly; both original and final identities
  are recorded in the source manifests.
- Both revisions use pinned GraalVM `25.5.5-elide.27` and TruffleData `0.9.0`.
  Candidate regex/ICU/XZ dependencies and lockfiles belong to the production change.
  The final TruffleData `0.10.0` adoption remains the release gate.
- Source manifests contain 146 tracked-file hashes per revision. Compressed source
  patches reconstruct each complete measured tree from published base `377ab51`.
  The timed loops, fixtures, counts, flags and controller source are identical.

The candidate overlay changes exactly four benchmark probe methods: backend label,
expected match-node count, node type predicate, and the `Regex.MatchNode.isAccelerated()`
query. The benchmark keeps its owning CEL context entered through evaluation and
qualification; the candidate may use its RE2/J fallback outside that context.
The baseline retains the existing `Regex.java` RE2/J seam but has no MatchNode.
Both probes walk the evaluated AST outside timing and allocation windows. The
candidate requires exactly one accelerated node for `map-regex` and no match nodes
for `scalar-wide`. A fallback result fails qualification even if its CEL value is
correct. Guest-JIT validity and strict compiler warning/failure gates also remain.

Every memory/native timing result includes its backend/node proof. Each JMH fork
emits a final proof after measurement and before context close; setup also checks
the path before guest compilation. The driver validates those proofs before setting
`complete=true`. Both that flag and `regex_path_verified=true` are required by the
report generator. No unverified or fallback measurement counts as a completed cell.

The fixture's literal pattern is `^[a-z]+@example[.]com$`, inside the original map,
email and list policy. All four variants and all 69 bindings remain. All four variants
are checked independently; every measured result escapes through JMH or a volatile
consumer. This fixture exercises the accelerated ASCII subset; it does not establish acceleration
for dynamic patterns, Unicode/escape-sensitive patterns or regex extension calls
that retain RE2/J behavior.

## Process layout and host conditions

There are 96 evaluation measurement processes: 24 JVM JMH forks, 24 native timing
processes and 48 separate allocation/RSS processes. Sixteen earlier smoke processes
verify complete outputs, calibrated counters and actual backend selection. A further
32 fresh processes measure scalar-wide launch-to-first-result: four input variants
× two modes × two runtimes × two revisions.

One atomic host lock serializes the whole window. After smoke checks and startup,
memory completes before timing begins. Each phase shuffles engine/workload cells
with a recorded seed and runs adjacent baseline/candidate blocks, alternating which
revision goes first. A JVM cell contains three fresh JMH forks; memory and native
cells contain three fresh processes per runtime. This balances block order without
running profilers or other benchmarks during timing.

The host is the existing `unclemax` Linux x86_64 Threadripper PRO 9965WX machine,
with logical CPU affinity `2–9`. It is an observed low-load shared host: CPUs are
not isolated, background services remain, and no governor, THP, NUMA or other global
setting is changed. OS file caches are not flushed; source/artifact hashing and
smokes precede startup. These are fresh processes, not cold-machine launches.

The locked window began at 18:37:30 UTC. Recorded one-minute load rose from 0.09
to 1.13, including the serial benchmark's own CPU use. Observed governors were
`performance`; the controller did not change them. The largest absolute movement
between the first two and last two measured timing windows was **6.44%**. No fork
crossed the descriptive 10% drift screen, but that does not prove steady state or
exclude shared-host interference.

JVM processes explicitly use C2 (`-XX:-UseJVMCICompiler`) with
`-Xms512m -Xmx512m --enable-native-access=ALL-UNNAMED`. JVM allocation additionally
uses `-Xbatch`. Native processes use `-Xms512m -Xmx512m`; the images use Serial GC.
Effective flags, runtime-library hashes, source hashes, binary hashes, command order,
CPU/kernel data, governors and observed load are retained with each cell.

## Timing, allocation and RSS boundaries

JVM timing uses JMH with five one-second warmup iterations and five one-second
measurement iterations. Native timing uses five one-second warmup windows and seven
half-second measurement windows. Both are unprofiled. Tables use the median of three
fork means; each range shows the smallest and largest fork mean. These ranges are
observations, not confidence intervals or proof of peak convergence.

Memory processes run five warmup batches and seven measured batches of 65,536
evaluations. Each current-thread allocation counter must pass an escaping 1 MiB
array calibration. Allocation excludes setup, warmup, backend probes and
compiler/background-thread work. Tables use the median and range of three fork
means, each averaging seven allocation windows.

The parent reads `/proc/PID/smaps_rollup` and `status` while the engine, program and
inputs remain strongly reachable. Current RSS is `smaps_rollup.Rss`; lifetime peak
RSS is `status.VmHWM`. **These are process footprints at uncontrolled GC positions,
not retained heap.** They include setup, prepared inputs, calibration, compilation,
warmup, sampled work, serialization and untimed backend/AST diagnostics. Guest JIT
adds 2,000 preparation evaluations and forced compilation. The original native
evaluation images permit fixture statics at image build time; ASTs are prepared at
runtime for these timing cells.

The two probe implementations and changed AST sizes need not allocate identically,
even though both are outside the counters and clocks. Their overhead remains part
of process RSS. Fixed common sample counts also do not make total work since launch
equal between interpreter and JIT modes. Do not infer retained-heap changes or
standalone deployment memory from these snapshots.

## Unrelated startup and artifact size

The unrelated scalar-wide launch-to-first-result medians are 256.54 → 258.24 ms
for JVM interpreter and 255.79 → 258.76 ms for JVM JIT; native interpreter is
1.638 → 1.733 ms and native JIT 1.665 → 1.574 ms. All four observed ranges overlap.
The median differences within the same input variant are +2.876 ms, +2.969 ms,
+0.093 ms and −0.091 ms respectively. Every native-interpreter pair is slower;
three of four native-JIT pairs are faster. These four launches per configuration
do not establish a general startup improvement or regression.

[Startup tables](startup-tables.md) retain every paired difference;
[startup-summary.json](startup-summary.json) also retains internal phases. No first
result had valid last-tier guest code. Because scalar-wide contains no regex call,
this control measures unrelated startup effects, not first-use regex compilation.

Startup reuses the existing observer clock from immediately before `Popen` through
receipt of the flushed, independently verified first-result marker. Runtime AST and
adapter preparation are included; no image-prepared-AST artifact is used. Guest
compilation is natural, not forced. All four variants appear once per configuration,
and revision-first order alternates within adjacent launch pairs. No empty-engine
subtraction or precise amortization point is inferred from this selected control.

| Combined artifact | Baseline (MiB) | Candidate (MiB) | Increase (MiB) |
| --- | ---: | ---: | ---: |
| `jmh_deploy.jar` | 19.90 | 39.49 | 19.58 |
| `evaluation_memory_deploy.jar` | 17.02 | 36.60 | 19.58 |
| `startup_deploy.jar` | 17.02 | 36.61 | 19.58 |
| `representative` | 73.13 | 82.44 | 9.31 |
| `evaluation-memory` | 73.06 | 82.50 | 9.44 |
| `startup-representative` | 72.94 | 82.56 | 9.62 |

The native executables and deploy JARs contain the comparison harness, both CEL
engines and benchmark metadata. Their sizes describe these combined artifacts,
not the standalone TruffleCEL library, an application's dependency closure, or a
production native executable.

## Qualification and reproduction

Production qualification is recorded separately:

- [71 test targets, including native JIT/blocklist and representative/image-prepared checks](https://app.buildbuddy.io/invocation/250662b0-9e13-49e4-b0ff-b059f6868bcb)
- [Compiled accelerator and post-compilation weak-owner checks](https://app.buildbuddy.io/invocation/5b19c3d0-9a8f-415a-96c9-f0c2f0c73c83)
- [Full-source coverage](https://app.buildbuddy.io/invocation/883c9321-e8f5-48db-8a12-1233239f2991): 8,175/8,191 lines and 7,500/7,515 branches; both 99.8% floors pass.
- [Baseline native harness build/check](https://app.buildbuddy.io/invocation/67601277-2ad4-4bac-be2b-eae38adb44a9) and [candidate JVM/Python/native harness checks](https://app.buildbuddy.io/invocation/38c37e1c-87f1-4109-9972-5cda4c2a6071)
- [Baseline cached driver-fix build](https://app.buildbuddy.io/invocation/0a687b15-ada9-4bb8-b305-bbf0655a7983) and [candidate cached build](https://app.buildbuddy.io/invocation/429d582f-81ab-4ad5-8cdc-d69740e2ab24), with identical artifact digests before and after the Python fix.

An initial attempt passed its 16 backend-proof smokes and then stopped before any
measured launch because startup-provenance code passed a string to a Path-based
hash helper. The corrected driver has a regression exercising actual provenance
construction. The [failed attempt](failed-before-measurement.tar.gz) is retained separately; the complete matrix was
run in a fresh output directory. No failed timing cell was silently omitted.

The raw archive retains per-process JSON/logs, memory checkpoints, JMH warmups,
backend proofs, source manifests/patches, artifact sizes/hashes, all driver sources
and the exact locked orchestration. Source reconstruction and report generation use
only that immutable evidence. The complete archive SHA256 is
`fa884a684a25e08f257dfce682f119b1cceef817aba5a427d6ab9c2e50c0fb13`.
Large native/JAR binaries are identified by SHA256 and are not checked into the
report.

```sh
mkdir -p /tmp/trufflecel-regex-raw
tar -xzf docs/benchmarks/regex-re2-20261108/raw.tar.gz -C /tmp/trufflecel-regex-raw
python3 docs/benchmarks/regex-re2-20261108/analyze.py \
  --raw /tmp/trufflecel-regex-raw --output /tmp/trufflecel-regex-summary
python3 docs/benchmarks/regex-re2-20261108/startup-analysis.py \
  --raw /tmp/trufflecel-regex-raw --output /tmp/trufflecel-regex-summary
python3 docs/benchmarks/regex-re2-20261108/verify_source.py \
  --raw /tmp/trufflecel-regex-raw
```
