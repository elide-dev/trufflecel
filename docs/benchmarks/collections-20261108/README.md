# Compact host-map snapshot allocation probe — November 8, 2026

This focused comparison measures the unchanged `map-regex` fixture and the
single-binding `scalar` control before and after compact owned host-map snapshots.
It uses the released 0.9.0 source (`f47ffa6`) as its baseline, TruffleData 0.9.0,
the pinned GraalVM `25.5.5-elide.27` toolchain, and cel-java 0.15.0. It is evidence
for the collection-normalization part of [#24](https://github.com/elide-dev/trufflecel/issues/24),
not completion of that broader optimization backlog.

## Behavior and scope

Reading a host map still normalizes its entire nested value, checks the existing
100-level depth and one-million-node budget, and creates owned storage. Public
results remain owned immutable maps, with copied mutable byte arrays. Snapshot
activations remain isolated from later host collection mutations and reusable
across threads. There is no borrowed nested-container view or new host-reference
cache.

The common internal representation uses an insertion-ordered key/value array and
an integer hash index instead of one linked-map node per entry. Key normalization,
last-value/first-position behavior for normalized duplicate keys, and insertion
order are preserved. Hash chains are capped at eight entries; collision-heavy
maps fall back to `LinkedHashMap`. Reported host `Map.size()` is only a bounded
allocation hint (at most 1,024 entries); actual key/value traversal charges the
budget, including duplicates. A growing or underreporting source also falls back
when the initial storage fills. Maps with more than 1,024 distinct normalized
entries therefore use the existing map implementation after an extra bounded
copy. This probe does not establish a win for those large maps, adversarial
collisions, or all collection operations.

## Method

The host is an Apple M5 Max running Darwin 25.6.0 arm64. Other agents were running
correctness builds/tests on the shared host; benchmark runs were serialized, but
the host was not isolated. Treat timings as advisory. This is a focused allocation
investigation, not the dedicated Linux comparison required for release-wide
performance claims.

JMH 1.37 ran three forks, three 500 ms warmup iterations and three 500 ms measured
iterations, with the GC profiler. The harness checks all four fixture inputs and
forces/verifies last-tier guest compilation for `truffle-jit`. The launcher uses
the pinned Bazel GraalVM. Its recorded default JVMCI host compiler remains enabled;
these results are not labeled C2. The shorter warmup than the dedicated-host
methodology does not establish latency convergence. Ingestion is excluded.

The baseline artifact was built from clean `f47ffa6` before production edits.
The candidate was rebuilt after the production change. Both used identical
`Engines`, `Workloads`, `SteadyState`, dependency pins and benchmark flags. Raw
JSON preserves per-fork samples, confidence intervals and VM options; logs
preserve warmup and tier-check output. `source.json` records baseline revision,
candidate production/fixture hashes and host/toolchain identity. An independent
baseline binary hash was not captured, so these are local paired-probe artifacts,
not a fully packaged reproducibility bundle.

```sh
bazel build --config=local //benchmarks/representative:jmh
bazel-bin/benchmarks/representative/jmh \
  'dev.trufflecel.SteadyState.evaluate' \
  -p workload=map-regex,scalar \
  -p engine=truffle-interpreter,truffle-jit,cel-java-optimized \
  -f 3 -wi 3 -i 3 -w 500ms -r 500ms -prof gc \
  -rf json -rff /tmp/trufflecel-010-collections-baseline.json
```

The candidate repeats the same arguments with a fresh `candidate.json` output.
Because the parallel remote native build switched `bazel-bin` to Linux outputs,
it invokes the already-built host launcher at
`bazel-out/darwin_arm64-fastbuild/bin/benchmarks/representative/jmh`.

## Results

Allocated bytes per evaluation (mean ± JMH-reported score error):

| Workload | Engine | Baseline B/op | Candidate B/op | Change |
| --- | --- | ---: | ---: | ---: |
| map-regex | truffle-interpreter | 4,218.19 ± 0.42 | 1,874.16 ± 0.36 | -55.6% |
| scalar | truffle-interpreter | 128.00 ± 0.01 | 128.00 ± 0.01 | 0.0% |
| map-regex | truffle-jit | 4,054.18 ± 0.40 | 1,710.15 ± 0.33 | -57.8% |
| scalar | truffle-jit | 24.00 ± 0.00 | 24.00 ± 0.00 | 0.0% |
| map-regex | cel-java-optimized | 3,014.04 ± 0.01 | 3,014.05 ± 0.01 | 0.0% |
| scalar | cel-java-optimized | 96.00 ± 0.00 | 96.00 ± 0.00 | 0.0% |

The map/regex fixture allocates 55.6% less in the interpreter and 57.8% less in
guest JIT. Candidate allocations are also below the measured optimized cel-java
configuration for this fixture. The scalar control is unchanged; TruffleCEL's
interpreter still allocates more than optimized cel-java in that control. These
two workloads do not establish superiority in every category, and this change
does not address parsing/compilation, protobuf allocation, cold startup or native
throughput.

Advisory timings (mean ± JMH-reported score error):

| Workload | Engine | Baseline ns/op | Candidate ns/op |
| --- | --- | ---: | ---: |
| map-regex | truffle-interpreter | 1,357.94 ± 15.62 | 1,179.13 ± 78.33 |
| scalar | truffle-interpreter | 20.92 ± 3.43 | 20.31 ± 1.27 |
| map-regex | truffle-jit | 1,390.23 ± 674.18 | 1,119.28 ± 185.92 |
| scalar | truffle-jit | 18.98 ± 18.98 | 10.90 ± 0.85 |
| map-regex | cel-java-optimized | 2,872.72 ± 539.40 | 3,679.08 ± 902.75 |
| scalar | cel-java-optimized | 23.59 ± 2.40 | 30.55 ± 3.53 |

The unchanged cel-java timing and scalar guest-JIT timing drift substantially
between runs despite stable allocation counts. No latency speedup claim follows
from these shared-host measurements. A quiet Linux run using the full standard
warmup/measurement matrix remains necessary for release qualification.

Raw evidence: [baseline JSON](baseline.json.gz), [candidate JSON](candidate.json.gz),
[baseline log](baseline.log.gz), [candidate log](candidate.log.gz),
[source/artifact metadata](source.json). The archives preserve the exact JMH
output bytes (including the JSON trailing blank line).

## Validation

Regression coverage includes normalized key collisions/order, collision fallback,
lying size hints and source growth, full nested validation and actual node limits,
one-shot nested iterables, concurrent snapshot reuse, output ownership and entry
immutability. No production coverage exclusions, benchmark fixture changes or
allocation-ceiling changes were made.

- Gazelle target generation and the repository formatter completed.
- `bazel test //...`: all 41 targets passed on Linux RBE, including the full
  conformance corpus, compiled allocation checks and compiled negative matrix
  ([invocation](https://app.buildbuddy.io/invocation/283b0336-d25b-47a8-8b51-39d20f9f2e5c)).
- `bazel coverage --config=local //src/test/...`: all 24 source test targets passed.
  The unchanged 99.8% floors pass at 5,391/5,395 lines (99.93%) and 5,382/5,388
  branches (99.89%). `Values.java` is 193/193 lines and 236/236 branches.
- `bazel test //benchmarks:jit_smoke //benchmarks:native_smoke`: both Linux RBE
  gates passed, including the native guest-JIT/blocklist checks
  ([invocation](https://app.buildbuddy.io/invocation/aa8c2c34-83ea-458f-8fe0-dd287f5f1200)).

No native throughput measurement or complete six-workload dedicated-host rerun
is claimed. This work remains compatible with TruffleData 0.9.0; the next release
is separately gated on adopting and qualifying TruffleData 0.10.0.
