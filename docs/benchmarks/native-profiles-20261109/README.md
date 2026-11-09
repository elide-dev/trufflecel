# Native memory and first-result profiles, 2026-11-09

This investigation addresses [#42](https://github.com/elide-dev/trufflecel/issues/42)
using TruffleCEL 0.10.0 production code, TruffleData 0.10.0 and GraalVM
25.5.5-elide.27. It measures application launch configuration and Java embedding
choices. It does not change the evaluator or rewrite the release benchmark archive.

The [complete tables](tables.md) include every engine/fixture/profile cell,
allocation, current and peak RSS, forced guest preparation, and paired native
startup controls. [summary.json](summary.json) retains process values and ranges.
Ranges are observations across fresh processes, not confidence intervals or proof
of statistical equivalence.

The subsequent [young-generation qualification](young-generation/README.md) tests
a 32 MiB cap, with and without shared compilation, in a separate 432-process
matrix. It reduces native interpreter comprehension RSS to **59.85 MiB**, below
matched optimized cel-java's **65.33 MiB**, with 0.5% higher hot evaluation time
and overlapping timing ranges. Other fixtures have throughput tradeoffs. Its
[charts](../../images/native-profiles-20261109/README.md), raw data and full tables
are retained separately; do not pool the two runs.

## Compiler memory

Both profiles use the same combined timing/memory executables and
`-Xms512m -Xmx512m`. `default` retains GraalVM's compiler isolation;
`shared-compiler` adds `-XX:-CompileInIsolates` to **both engines**. Interpreter
comparisons remain separate from guest JIT. Both cel-java planner configurations,
all six fixtures, all four input variants, full 69-binding activations (except
the scalar control), and materialized repeated-list outputs are preserved.

Three fresh forks per cell give 144 fixed-work memory processes and 144 separate
timing processes. Memory uses five batches of 65,536 warmup evaluations and seven
measured batches, including calibrated evaluating-thread allocation. The parent
samples Linux RSS with the target and inputs live. Timing uses five one-second
warmup windows and seven half-second measurement windows. Guest JIT is forced
before hot timing, with its preparation duration reported separately. It is not
free work and does not establish natural-JIT payback.

Shared compilation reduces guest-JIT current RSS by **14.2–26.7%** across the six
fixtures. The materialized comprehension drops from **194.40 to 144.05 MiB**;
its peak drops from **240.65 to 189.66 MiB**. Allocation per evaluation is unchanged.
This does not eliminate the RSS deficit: optimized cel-java uses about **67 MiB**
for that workload in both profiles. Interpreter memory is essentially unaffected
by the compiler-isolation option.

RSS and unique physical memory are not interchangeable. The same comprehension
processes have median proportional set size (PSS) **175.08 → 141.24 MiB**, a smaller
delta than their RSS change. PSS apportions shared mappings; RSS counts resident
mappings in full. Both are retained in the tables and raw `smaps_rollup` snapshots.
Neither counter measures live Java objects or attributes pages to a particular isolate.

Throughput is not uniformly preserved. The shared-compiler guest-JIT medians range
from **4.1% lower time** for comprehension to **7.0% higher time** for optimizer-policy.
Most process ranges overlap; protobuf-policy is about **6.0% slower**, with disjoint
ranges in this run. These results support an explicit memory/throughput choice,
not changing the default or claiming a free improvement in every category.

The shared profile is opt-in. Compiler allocations share the application heap
and GC, so applications need compilation headroom and qualification with their
other guest languages. The native guest-JIT/blocklist gate now exercises both
compiler-isolation modes. A reproduced GraalVM management-counter defect is
tracked as [elide-dev/graal#227](https://github.com/elide-dev/graal/issues/227):
`getNonHeapMemoryUsage()` can throw after compilation in shared mode. The
[phase exploration](phase-exploration.json) retains that failure independently
of Linux RSS and application heap counters. It is a single-fork attribution
probe, not the three-fork performance matrix.

## First result and embedding

The native startup experiment pairs `context` and `direct` in the **same binary**
for each preparation mode. The direct Java API omits an unnecessary Polyglot
context for non-regex expressions; regex keeps the context and the same TruffleRegex
backend. Source-backed instrumentation and Polyglot binding applications still
need their context. Compiler mode/failure options are verified on the actual
standalone call target. The library does not change process-global settings.

Runtime and image-prepared ASTs use separate binaries. Image preparation embeds
checked ASTs and a protobuf adapter, never guest machine code. Both modes create
and decode inputs at runtime. The parent observes launch through the flushed,
verified first result; child phases include fixture/wire construction, adaptation,
decode, configuration, preparation, planning and evaluation/verification. JSON
reporting and teardown follow the readiness marker. Empty controls are separate
and are never subtracted. Eight forks rotate all four input variants, yielding
592 fresh native processes including controls.

For image-prepared scalar interpretation, the paired external median falls from
**1.259 to 1.133 ms** when the unnecessary context is omitted. Optimized cel-java's
direct-control median is **1.109 ms**. All three observed process ranges overlap.
The child `main`-to-result medians are **0.289 / 0.261 / 0.187 ms** respectively:
the remaining runtime/decode cost has not disappeared. Direct embedding narrows
the measured gap; it does not prove a faster first result in every workload.

The [initial context-based baseline](baseline-startup.json) separately preserves
four-fork JVM/runtime-native/image-native first-result measurements against current
main. Do not pool it with the later paired embedding controls or use either as
standalone application footprint: both engines are present in the artifacts.

## Natural compilation and program lifetime

The separate [cumulative-cost run](payback.json) uses default runtime flags and
three fresh forks for scalar-wide, protobuf-policy and materialized repeated-list
evaluation: 54 JVM/native runtime/native image-prepared processes in total. It
includes configuration, AST preparation/planning and all evaluations, with natural
synchronous compilation. Input generation, ingestion and process launch precede
this clock; do not add it to the startup measurements.

The [full checkpoint tables](tables.md#natural-jit-cumulative-cost) use powers of
ten through one million evaluations. A reported win requires every guest-JIT fork
to be last-tier compiled and faster than every interpreter fork at that and all
later sampled checkpoints. This is a bounded observation, not an exact crossover.

| Runtime | Scalar-wide | Protobuf policy | Materialized repeated list |
| --- | ---: | ---: | ---: |
| JVM | No observed payback through 1M | No observed payback through 1M | 1M |
| Native, runtime AST | 1M | 1M | 100K |
| Native, image AST | 1M | 1M | 100K |

For example, JVM protobuf-policy takes **375.33 ms** interpreted versus **587.56 ms**
with guest JIT through one million evaluations. Native runtime-prepared repeated-list
evaluation takes **5,008.22 versus 1,876.86 ms**. Interpretation remains appropriate
for low-reuse workloads. These measurements do not justify a universal new compiler
threshold, and they do not measure payback for the experimental heap profiles.

## Sources, host and limits

- Production Java tree: `06e399d1096d1d03731a2544fe932334b71b315d`, identical to
  `v0.10.0` and main revision `29269155d6c4b6470c74f55c5cbbe37b31fc3955`.
- Evaluation executables come from the retained release qualification at
  `cf0d51d85cd6041a93e2aed6178f88a0c3cdaa73`; their exact source manifests and hashes
  are embedded in both phase metadata files. The profile driver was `9abd196`.
- Startup artifacts and driver are identified by the `802b4b6` source manifest.
  Only documentation differed from committed artifact inputs during capture;
  the manifest records both that distinction and per-file/artifact hashes.
- The cumulative-cost artifacts have the `21db3c9` source manifest and the same
  released production Java tree. The driver and full runtime identities are in
  `payback.json`.
- Linux `unclemax`, AMD Ryzen Threadripper PRO 9965WX, CPUs 2–9, shared host.
  An atomic directory lock serialized workload benchmarks. Background services
  and short artifact transfers remained possible; CPUs and thermals were not
  exclusively reserved. Command-by-command load observations are retained.
- Current RSS includes heap capacity, compiler/runtime pages and other process
  memory; peak RSS includes setup and compilation. Neither is live retained heap.
  GC position affects a fixed-work endpoint, and the combined executables do not
  establish standalone deployment sizes.
- No blanket compiler-threshold change follows from hot-loop ratios. Natural-JIT
  cumulative cost needs its own measured reuse curve. JVM memory and remaining
  native RSS losses are not represented as resolved by a native launch option.

## Reproduce

Build with the pinned Bazel/GraalVM toolchains and preserve a tracked-input source
manifest without local credentials. Transfer the immutable executables, drivers
and manifests to a Linux amd64 measurement host. Serialize measurements there.

```sh
bazel build //benchmarks/representative:svm \
  //benchmarks/representative:evaluation_memory_svm \
  //benchmarks/representative:startup_svm \
  //benchmarks/representative:startup_prepared_svm --remote_download_toplevel

# Run memory and timing sequentially, preserving separate output directories.
python3 tools/bench/native_profiles.py memory \
  --native /artifacts/timing-native --memory-native /artifacts/memory-native \
  --source-manifest /artifacts/source.json --output /new/profile-memory \
  --host-conditions 'Describe actual reservation, load and thermals'
python3 tools/bench/native_profiles.py timing \
  --native /artifacts/timing-native --memory-native /artifacts/memory-native \
  --source-manifest /artifacts/source.json --output /new/profile-timing \
  --host-conditions 'Describe actual reservation, load and thermals'
python3 tools/bench/startup_embeddings.py \
  --native /artifacts/startup-native --native-prepared /artifacts/startup-prepared-native \
  --source-manifest /artifacts/startup-source.json --output /new/startup-embeddings \
  --host-conditions 'Describe actual reservation, load and thermals'

python3 tools/bench/native_profile_report.py docs/benchmarks/native-profiles-20261109
```

The report generator validates complete inventories, matched artifacts/provenance,
profile/heap flags and execution modes before writing its tables. Raw process
records and metadata are retained beside this report. Historical measurements
remain in their original directories.
