# Nested protobuf access, native throughput and cold payback, November 5, 2026

This follow-up extends the [first prepared-field implementation](../structured-20261105/README.md)
to nested message/map reads and common well-known conversions, then measures full-input
JVM evaluation, native throughput and natural guest-JIT payback. TruffleData remains
pinned to 0.6.0. These measurements do not compare against cel-java.

## Implementation

Prepared metadata now resolves ordinary nested messages, repeated messages, and maps
with qualified scalar keys and scalar or ordinary message values. Message identity and
schema/adapter guards remain exact. Repeated messages and maps materialize canonical
owned values once; the generic conversion/normalization pass no longer copies them
again. Map ordering, duplicate wire-key handling, size bounds and public output ownership
remain unchanged. This is not a lazy native-map view or filter/map fusion.

Common singular Bool/Int32/Int64/Float/Double/String wrappers, Timestamp and Duration
also use prepared reads when their descriptors are the standard protobuf-java instances.
Wrapper and time validation shares the existing implementations behind narrow host
boundaries. This keeps validation outside the guest compiler graph without weakening
its graph-size or blocklist gates. Missing ordinary messages construct their default
only when read, preserving required-field failures and short-circuit behavior.

Nested rows retain the adapter's existing `ProtoRow` representation contract. A generated
TruffleData message does not silently become eligible for a specialized read. A regression
test verifies the same rejection as generic conversion without invoking a generated reader.
Plans retain schema metadata and independent runtime caches, never request values.
Empty owned lists now share immutable storage to remove the previous small-input penalty.

Custom/overlapping adapters, optional lifting, extensions, unsupported scalar conversions,
reconstructed well-known descriptors, and unqualified map/message types retain generic
handling. Activation snapshots remain opt-in. No dependency upgrade is part of this change.

## Results

The complete matched JVM matrix has 42 cells and 126 fresh processes. Reused-snapshot
protobuf policy improves **1.90×** with guest JIT (475.2 → 250.2 ns, 1,272 → 784 B/op)
and **1.73×** in the interpreter (527.8 → 305.2 ns, 1,296 → 814 B/op). The wrapper-heavy
optimizer-policy workload improves **2.49×** with guest JIT (119.5 → 48.0 ns,
336 → 184 B/op) and **2.00×** in the interpreter (161.8 → 80.9 ns, 376 → 224 B/op).
These are TruffleCEL before/after comparisons; “optimizer-policy” is a workload name,
not a comparison with cel-java's optimizers.

The repeated-field snapshot workload remains essentially unchanged (guest-JIT
1,195.7 → 1,199.4 ns, 5,380 B/op), retaining the first slice's architectural gain.
No full-matrix cell exceeds the 5% slowdown investigation threshold, and no process
exceeds 10% measured-window drift. This does not prove statistical equivalence.

One-shot entry still pays for full activation normalization. Guest-JIT protobuf policy
improves only 1.05× with ordinary maps and 1.11× with fresh snapshots. Optimizer-policy
ordinary-map median is 4.8% slower (1,837.4 → 1,925.7 ns), with overlapping process
ranges. The prepared-input gains do not imply a blanket fresh-request win.
See [all JVM cells and process ranges](activation-comparison.md).

### Empty and repeated-field diagnostics

The empty filter/map input now allocates **168 B/op**, down from the first slice's
240 B/op and the original 216 B/op. Its current median is 13.2 ns. This removes the
previous +24 B small-input penalty without relaxing bounds or changing output ownership.

The isolated 128-element, 50%-selected diagnostic initially measured 1,065.6 ns against
the first slice's 995.2 ns, triggering investigation. A fresh alternating paired check
measured 1,013.6 ns [1,009.6–1,021.2] for the first slice and 1,060.6 ns
[1,038.2–1,100.3] for this implementation: **4.6% slower**, with separated process
ranges and unchanged 3,832 B/op. This small diagnostic slowdown remains unresolved;
it must not be described as equivalence or hidden by the full-workload result.

A further isolated experiment removed empty-range sharing for native arrays, leaving
empty result-builder sharing intact. It measured 1,064.9 ns [1,059.9–1,108.3] for the
current implementation versus 1,046.4 ns [1,024.5–1,094.6] for the variant. The 1.7%
median improvement has overlapping process ranges and does not establish the cause
or a reliable fix. The variant was **not adopted**. Its exact patch, artifact/source
hashes and all six process results are retained separately in `empty-variant/`.
Neither diagnostic follow-up exceeds 10% within-process drift. All individual process
means are in [the diagnostic summary](diagnostics-summary.json).

### Native throughput and cold payback

The complete 228-process matrix passes inventory, artifact/source-equivalence and tier
audits. No native process exceeds 10% measured-window drift. Native reused-snapshot
medians are:

| Workload | Interpreter ns/op | Guest JIT ns/op |
| --- | ---: | ---: |
| Scalar, full activation | 24.3 | 14.8 |
| Protobuf policy | 492.5 | 358.9 |
| Repeated protobuf filter/map | 4,043.2 | 1,590.7 |
| Wrapper-heavy optimizer-policy | 142.3 | 69.2 |

These compare execution modes of the **same new runtime**, not native before/after
performance. Full-input scalar map/fresh entry does not show a guest-JIT advantage;
normalization still dominates. See [all native entry paths and cold curves](lifecycle-tables.md).

Natural guest JIT first shows a sustained crossing with disjoint process ranges at the
sampled **100,000-evaluation checkpoint for repeated fields** on both JVM and Native
Image, across all three entry paths. Native protobuf policy crosses at **1,000,000**
across all three entries; native full-activation scalar crosses there with snapshots.
Image-build AST preparation retains the same sampled crossings for the two protobuf
snapshot cases. All cold JIT processes reach last-tier code by the final checkpoint.

JVM protobuf policy and scalar snapshots have **no crossing through 1,000,000**:
protobuf policy totals 637.4 ms with guest JIT versus 510.5 ms interpreted, and scalar
totals 167.4 versus 130.2 ms. Thus peak guest-JIT gains are not sufficient evidence of
lower lifecycle cost. A separate twelve-process extension with the **same artifacts**
finds a sustained crossing with disjoint process ranges at the sampled **10,000,000**
checkpoint for both JVM snapshot cases:

| JVM snapshot, 10 million evaluations | Interpreter total ms [process range] | Guest-JIT total ms [process range] |
| --- | ---: | ---: |
| Scalar, full activation | 331.1 [329.4–345.1] | 278.1 [277.4–283.0] |
| Protobuf policy | 3,179.4 [3,157.7–3,238.1] | 2,944.0 [2,919.6–3,018.5] |

The extension preserves all earlier checkpoints in each fresh process and is reported
separately; it does not replace the original matrix. It covers snapshot entry only,
so it establishes no longer-range payback for ordinary maps or fresh snapshots.
See [the extended curves](extended-cold-summary.json). These curves include expression
setup but exclude ingestion and VM launch; their sampled crossings are not exact
break-even counts or promises for other applications.

## Method

The JVM activation matrix retains the previous report's exact benchmark sources,
JVM options, host and CPU affinity. The candidate runtime is overlaid on the historical
benchmark checkout. All six workloads retain their full activations and four input
variants. Every entry path materializes its public result. Three fresh JMH processes,
five one-second warmups and five one-second measurements use explicit C2 host compilation
and the GC profiler. Allocated bytes measure Java heap allocation, not native memory.

Native throughput uses the current strict engine harness, five one-second warmups and
seven half-second windows in each of three fresh processes. All six workloads cover
interpreter and forced last-tier guest-JIT execution across ordinary maps, reused snapshots
and fresh snapshots. These are absolute measurements of the new runtime; they do not
establish a before/after native speedup over the historical implementation. Runtime AST
preparation and compilation are excluded from steady-state timing.

Cold curves use fresh processes and natural, synchronous guest compilation. Configuration,
expression preparation/instantiation and creation of four reusable input snapshots are
inside the cumulative timer. No evaluation occurs before the first checkpoint. Fixture
creation/ingestion, class loading caused by that fixture setup, and VM launch are outside
it. This measures expression-lifecycle payback in a preloaded process, not whole-application
startup. Image-cold cases embed checked ASTs but create activations at runtime; no guest
machine code is embedded during image construction.

The cold matrix includes full-activation scalar, protobuf policy and repeated-field policy
across both execution modes and all three entry paths on JVM and Native Image. Image-build
AST cold cases additionally cover the two protobuf workloads with reused snapshots.
The primary matrix checkpoints are 1, 10, 100, 1,000, 10,000, 100,000 and 1,000,000
evaluations; the targeted JVM snapshot extension adds 10,000,000. Each records
cumulative time and last-tier validity. The first sampled sustained median crossing is
reported alongside the stricter disjoint-process-range crossing; neither is an exact
crossover count or a guarantee beyond the measured range.

All timed jobs run sequentially on the dedicated Threadripper PRO 9965WX, Linux x86_64,
CPUs 2–9, pinned GraalVM 25.5.5-elide.24 and a 512 MiB heap. Background services remain
running and CPUs are not isolated. Lifecycle jobs use a fixed shuffled order. Raw results,
commands, source/artifact hashes, source-equivalence checks and drift audits are retained.
Two paired diagnostic experiments paused only the lifecycle parent driver between timed
processes. Each existing timed child finished before diagnostics started; no timed child
was suspended and no measurements overlapped. The parent resumed afterward. Both pauses
and all commands are retained in the lifecycle metadata directory.

## Validation

All 30 JVM targets pass, including the complete 2,771-case corpus through six execution
paths. Differential tests cover nested/default/repeated/message-map reads, optional and
unknown values, wrong adapters, numeric key lookup, duplicate wire keys, budget/error
behavior and public ownership. Standard wrapper/time tests include absent values and
invalid native values. Production coverage is 99.91% lines (4,478/4,482) and 99.89%
branches (4,568/4,573), without exclusions or relaxed thresholds.

All three [Linux native gates pass](https://app.buildbuddy.io/invocation/e26ff8e1-b7a3-4d2a-b0e5-c030fdeb401d):
the existing JIT/blocklist gate, representative matrix, and the new prepared-input matrix
with runtime/image-build AST preparation. Compilation warnings remain errors and the
10,000-node graph limit is unchanged. Gazelle, formatting and fifteen benchmark-tool
tests pass. Six JVM cold entry/engine combinations additionally pass harness validation.

Runtime source: `c669099`. Artifacts were built before the local commit; manifests record
base revision `0a48e49` (or `2bd3e3b` for the historical benchmark overlay), dirty state and
all production Java hashes. Both measured harnesses use identical production sources.

## Reproduction

See [prepared-input lifecycle methodology](../../BENCHMARKS.md#prepared-input-cold-curves-and-native-throughput)
for build/run commands. Regenerate derived tables from retained data:

```sh
python3 docs/benchmarks/nested-20261105/summarize-diagnostics.py
python3 tools/bench/activation_report.py docs/benchmarks/nested-20261105/activation
python3 tools/bench/prepared_report.py \
  --activation docs/benchmarks/nested-20261105/activation \
  --baseline docs/benchmarks/structured-20261105/activation \
  --lifecycle docs/benchmarks/nested-20261105/lifecycle \
  --output docs/benchmarks/nested-20261105
```

The report-local diagnostic script also regenerates the extended-cold summary and checks
its inventory and artifact identity against the main lifecycle matrix. The exact targeted
runner is retained in `extended-cold-driver.py`; its paths refer to the dedicated host.
Raw logs are gzip-compressed, and `SHA256SUMS` covers the retained measurements, manifests,
logs, experimental patch and runner. Derived tables/summaries are regenerated by the commands
above. Historical measurements remain unchanged.

## Remaining limits

The focused 128-element slowdown remains an open diagnostic issue. Qualified maps/repeated
messages still materialize bounded canonical collections, and unsupported/custom adapters
retain generic conversion. These measurements do not establish a universal gain across all
message shapes, ingestion, whole-request latency, RSS, or a new cross-engine ranking.
The forthcoming TruffleData upgrade and a fresh cel-java comparison remain separately
attributable work. No merge, push, release or upstream reply accompanies this local report.
