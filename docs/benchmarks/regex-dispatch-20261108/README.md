# Rejected cached regex dispatch experiment, 2026-11-08

**Decision: reject the cached adapter and retain the initial qualified TruffleRegex
implementation.** This report preserves the complete negative result; its candidate
is not the selected production implementation.

This experiment compares the initial TruffleRegex integration with the cached,
context-owned `invokeMember` adapter. Both revisions use TruffleRegex RE2 and the
same dependency closure. It preserves the original full-input `map-regex` fixture
and unrelated `scalar-wide` control on JVM and Native Image, in interpreter and
guest-JIT modes. It does not replace the earlier [RE2/J versus TruffleRegex
report](../regex-re2-20261108/README.md).

The cached adapter does **not demonstrate a throughput improvement** over the
initial TruffleRegex implementation in this matrix. All eight timing pairs have
overlapping observed fork ranges. Regex JIT point estimates are effectively flat
on JVM (+0.05%) and 1.68% slower in Native Image. This is not an equivalence test;
three forks cannot establish identical performance.

| Regex mode | Time, initial → cached (ns/op) | Allocation (B/op) | Current RSS (MiB) |
| --- | ---: | ---: | ---: |
| JVM interpreter | 1,096.25 → 1,102.99 | 1,904 → 1,924 | 434.70 → 434.93 |
| JVM JIT | 814.36 → 814.81 | 1,732 → 1,696 | 537.08 → 515.19 |
| Native interpreter | 1,605.65 → 1,579.48 | 1,804 → 1,820 | 144.86 → 151.79 |
| Native JIT | 1,001.70 → 1,018.51 | 1,664 → 1,656 | 224.32 → 240.26 |

JIT allocation falls by 36 B/op on JVM and 8 B/op natively, while interpreter
allocation rises by 20 and 16 B/op. JVM JIT regex RSS falls 4.08%, but native JIT
RSS rises 7.11% and lifetime peak rises from 236.83 to 253.07 MiB. The unrelated
scalar control has unchanged allocation in every mode, yet native RSS rises
5.56% in interpreter mode and 11.66% in JIT mode. These whole-process observations
must not be interpreted as retained-heap measurements or attributed entirely to
per-match allocation.

The evidence does not support selecting this prototype as a general performance
optimization: its small JIT allocation benefit comes with higher native footprint
and no demonstrated matching-speed benefit. [All evaluation tables](tables.md)
and [individual fork values](summary.json) are retained, including slower cells.
The initial TruffleRegex comparison remains a separate historical experiment.

## Sources and qualification

The baseline is measured source `3fe435cb1c25b8104692a48dc53a69b9e03dc45f`, with
production revision `9cdc03748256b9751fdcefba11be7be273236c9e` and production tree
`246c6c7f3e39c0ee95733fd6621f2a91b0f31ae7`. Its four already verified artifacts are
reused read-only from the previous experiment, under a new baseline directory;
historical manifests and outputs remain unchanged.

The candidate is source `f8b9eab1c4a300245890bef56e13071d0bd15ee7`, production tree
`99d9093f877b0b56cf61ad3deb226a564c22604e`. The production change is confined to
`Regex.java`; associated tests exercise scoped ownership and collection of evicted regex state
while the reusable Program remains live.
Both revisions use pinned GraalVM `25.5.5-elide.27` and TruffleData `0.9.0`.
TruffleData `0.10.0` adoption remains the release gate.

The focused JVM semantics, instrumentation, post-compilation collection of evicted
context-owned regex state, and native JIT/blocklist checks pass on the frozen
candidate:
[qualification invocation](https://app.buildbuddy.io/invocation/5cdd6e26-daf3-4cce-8733-d89ca40a3ebc).
The ownership review found no blocker. The real-GC test keeps the Context entered
and live while clearing its regex cache; it proves the evicted regex dispatch state
can be collected with the Program still live. It does not prove Context collection.
These gates check the weak ownership contract separately from benchmark RSS.

The integrated candidate also passes [all 72 test targets](https://app.buildbuddy.io/invocation/4cdd253e-e0be-441d-9e99-38ada6389a0f),
including the complete conformance corpus, publication consumer, JVM/native
compiled gates and representative/image-prepared native checks.
[Full-source coverage](https://app.buildbuddy.io/invocation/40a992d5-972d-4e53-9088-5092a7315780)
is 8,184/8,200 lines and 7,500/7,515 branches; both 99.8% floors pass.
The [exact benchmark artifact build](https://app.buildbuddy.io/invocation/b1329806-fbdb-4cbb-ad03-a0c6ec8ef986)
and JVM representative/memory plus native representative smoke tests pass.

Both source manifests include 146 tracked-file hashes. Compressed patches
reconstruct the complete measured source trees from published base `377ab51`.
`Engines`, `Workloads`, JMH/native timed loops, allocation protocol and the
`RegexQualification` probe are byte-identical. The common scripts also match both
source manifests. Artifact SHA256s are checked before and after each phase.

The benchmark keeps its owning CEL context entered. Both revisions must report
backend `truffle-regex-re2`, exactly one live accelerated match node for `map-regex`
and none for `scalar-wide`. Backend fallback fails qualification even when CEL
results agree. All four input variants are checked independently; every measured
result escapes through JMH or a volatile consumer. All 69 bindings remain present.
The regex literal is `^[a-z]+@example[.]com$`; this experiment does not expand
support to dynamic, Unicode-sensitive or extension patterns.

## Separate preflight and complete matrix

Preflight runs 16 output/backend/counter smoke processes, followed by eight
allocation processes: one per revision, runtime and mode for `map-regex`. Those
allocation processes use the full warmup/sample counts and are archived separately.
They help identify regressions before spending time on the matrix; they are not
substituted into the three-fork results.

Preflight verified all 16 smoke processes and eight full-count allocation
processes without backend fallback or tier failure. `map-regex` allocation was
1,904 → 1,924 B/op for JVM interpreter, 1,732 → 1,696 for JVM JIT,
1,804 → 1,820 for native interpreter and 1,664 → 1,656 for native JIT.
These single-process allocation checks are preliminary; the full matrix supplies
three independent forks per cell.

The full matrix runs in a separate invocation and requires a complete, matching
preflight. It contains 96 measurement processes: 48 allocation/RSS processes,
24 JVM timing forks and 24 native timing processes. Memory completes before timing
begins. Each phase shuffles the four engine/workload cells using a recorded seed,
runs adjacent baseline/candidate blocks and alternates which revision goes first.
Every cell remains in the report once the full matrix starts; there is no selective
omission or replacement of slower cells.

An atomic host lock covers each measurement window. The existing Linux x86_64
Threadripper PRO 9965WX host uses logical CPU affinity `2–9`; CPUs are not reserved
or isolated. Background services and global governor/THP/NUMA settings remain
unchanged. No profilers, transfers or other benchmark agents run during timing.

The matrix began at 19:13:49 UTC. One-minute load was 0.42 at entry and 1.27 at
completion; recorded command-boundary samples reached 1.93, including the serial
benchmark's own execution and compilation. The maximum absolute movement from
the first two to the last two measured timing windows was **5.57%**. No fork
crossed the descriptive 10% drift screen, which does not prove steady state or
exclude host interference.

## Measurement boundaries

JVM timing uses C2 (`-XX:-UseJVMCICompiler`), five one-second JMH warmup iterations
and five one-second measured iterations. Native timing uses five one-second warmup
windows and seven half-second measurement windows. Both use three fresh forks,
without allocation profiling. Tables show the median and observed range of fork
means; these are descriptive observations, not confidence intervals or proof of
steady state.

Memory processes run five warmup batches and seven measured batches of 65,536
evaluations. Current-thread allocation must pass an escaping 1 MiB array
calibration, and excludes setup, warmup, backend probes and compiler/background
thread work. Both runtimes use `-Xms512m -Xmx512m`; JVM allocation additionally uses
`-Xbatch`, and native images use Serial GC. Strict compiler warning/failure gates
and actual guest-tier checks remain enabled.

RSS is sampled while engine, program and inputs remain strongly reachable:
`smaps_rollup.Rss` is current process RSS and `status.VmHWM` is lifetime peak RSS.
These are process footprints at uncontrolled GC positions, not retained heap.
They include setup, compilation, calibration, warmup, fixed measured work and
untimed serialization/probe traversal. JIT mode adds 2,000 preparation evaluations
and forced compilation. Even with identical probe source, changed AST structure
and runtime caches can change its untimed allocation; that remains part of RSS.

No startup rerun is included: this bounded change preserves dependencies and the
non-regex setup path. This does not establish unchanged first-use regex compilation
cost. Combined benchmark executable/JAR sizes are recorded but are not standalone
TruffleCEL deployment sizes.

| Combined artifact | Initial TruffleRegex (MiB) | Cached dispatch (MiB) | Difference (bytes) |
| --- | ---: | ---: | ---: |
| `jmh_deploy.jar` | 39.49 | 39.49 | +1,254 |
| `evaluation_memory_deploy.jar` | 36.60 | 36.60 | +1,254 |
| `representative` | 82.44 | 82.50 | +65,536 |
| `evaluation-memory` | 82.50 | 82.44 | -65,536 |

## Reproduction and evidence

The immutable [raw archive](raw.tar.gz) has SHA256
`a32e5f8d222d4b12cbdbef837384172a11884fb7e728cbc408f180a64f0d30ee`.
The exact [controller](run.py) and [atomic-lock wrapper](run-locked.sh) are included
both here and in the archive. The preflight and full matrix completed successfully;
no failed or unfavorable measured cell was omitted.

The archive retains all raw process outputs, `/proc` checkpoints, JMH logs/JSON,
source manifests and compressed patches, hashes, sizes, common driver sources,
preflight evidence and the exact controller. Reports require both `complete=true`
and `regex_path_verified=true`, replay every fork's backend proof, and reject
incomplete or duplicate cell inventories. Copied result rows are reconciled with
raw child/JMH JSON and raw `/proc` snapshots before aggregation. Source reconstruction verifies both
complete Git trees, production trees and every listed source hash.

```sh
mkdir -p /tmp/trufflecel-regex-dispatch-raw
tar -xzf docs/benchmarks/regex-dispatch-20261108/raw.tar.gz -C /tmp/trufflecel-regex-dispatch-raw
python3 docs/benchmarks/regex-dispatch-20261108/analyze.py \
  --raw /tmp/trufflecel-regex-dispatch-raw --output /tmp/trufflecel-regex-dispatch-summary
python3 docs/benchmarks/regex-dispatch-20261108/verify_source.py \
  --raw /tmp/trufflecel-regex-dispatch-raw
```
