# TruffleCEL 0.9.0 evidence and remaining comparison gaps

TruffleCEL is not established as optimal against cel-java in every category. The
released three-fixture comparison has lower time and allocated-byte medians in all
24 cells, but higher fixed-work peak RSS in 14. Broader historical measurements
also retain collection-allocation and ingestion gaps. The six-fixture suite has
not been fully refreshed against the released TruffleData 0.9.0 dependency.

## Released three-fixture comparison

This is the unmodified `cel-comparison/comparison.json` artifact from the successful
[0.9.0 release CI run](https://github.com/elide-dev/trufflecel/actions/runs/42432054271),
source `f47ffa6f82ee43f1e12b1882a3d4472ddb4798c1`, captured November 8, 2026 UTC.
It uses TruffleData 0.9.0, cel-java 0.15.0 and GraalVM 25.5.5-elide.27, on a Linux
x86_64 Intel Xeon Platinum 8573C CI worker with four logical CPUs. This is a CI
observation; it does not establish an exclusively reserved or quiet host.

| Metric | Lower TruffleCEL median | Equal | Higher TruffleCEL median | Overlapping process ranges |
| --- | ---: | ---: | ---: | ---: |
| Time/op | 24 | 0 | 0 | 1 |
| Current-thread allocated heap bytes/op | 24 | 0 | 0 | 0 |
| Fixed-work process peak RSS | 10 | 0 | 14 | 0 |

The time overlap is JVM interpreter/scalar. These are rankings of three process
medians, not statistical significance tests or guaranteed improvements. Three
fixtures (`scalar`, `policy`, `comprehension`) across eight runtime/stage panels
produce 24 paired cells. The artifact holds 144 records, each combining a timing
process with a separate fixed-operation-count memory process. Allocation excludes
compiler/background threads. RSS covers the whole process, including guest
compilation and a shared executable containing both engines. It does not measure
standalone deployment footprint, retained heap or native memory attribution.

Every RSS loss is retained in the [complete tables](tables.md):

- Native compilation of `comprehension`: 81.28 versus 76.00 MiB.
- JVM guest-JIT evaluation of `comprehension`: 307.83 versus 280.19 MiB.
- All six native guest-JIT cells (runtime/image AST, all three fixtures):
  TruffleCEL 81.26–141.34 MiB versus cel-java 73.01–78.23 MiB.
- All six native interpreter cells (runtime/image AST, all three fixtures):
  TruffleCEL 79.80–81.14 MiB versus cel-java 72.98–78.71 MiB.

The first value in each pair is TruffleCEL. Native guest-JIT comprehension is
141.04/141.34 MiB versus 75.00/73.01 MiB in runtime/image-AST modes. Lower allocation
per evaluation does not imply a smaller resident process.

## Broader evidence by category

Historical rows below describe their recorded sources and dependencies, not new
0.9.0 measurements. The [November 5 baseline](../representative-20261105/README.md)
had wide-activation and protobuf-policy throughput losses. The later
[borrowed-input comparison](../borrowed-20261105/README.md) improved those paths,
using unchanged six-fixture sources but an explicit stable-input API contract.
It used TruffleData 0.6.0 and GraalVM 25.5.5-elide.24. Retain both reports when
explaining the improvement; neither alone establishes current release performance.

The separate [0.10 collection investigation](../collections-20261108/README.md)
retains a current `f47ffa6` / TruffleData 0.9.0 allocation baseline: maps/regex
interpreter 4,218 and guest JIT 4,054 versus optimized cel-java 3,014 B/op; scalar
interpreter 128 and guest JIT 24 versus cel-java 96 B/op. This shared macOS ARM
probe uses the default JVMCI host compiler, not C2, and does not establish a
quiet-host timing result. Its fixture, host and method differ from the historical
figures below. The released 24-cell suite does not contain the maps/regex fixture.

| Category | Available evidence | Remaining work before broad claims |
| --- | --- | --- |
| Tiny-source compilation | Released suite: all six JVM/native cells favor TruffleCEL in time and allocation; native comprehension loses RSS. | Larger expressions/schema graphs, compile concurrency and frontend-stage costs need matched measurements. |
| Scalar and wide activation evaluation | Historical six-fixture JVM comparison favors TruffleCEL interpreter and guest JIT; targeted native wide-activation comparison also wins. | Refresh on the released dependency and retain all 69 bindings. Historical JVM single-binding interpreter allocation loses: 112 versus 96 B/op. |
| Protobuf policy and repeated-list evaluation | Historical six-fixture JVM comparison favors both TruffleCEL modes; targeted native policy also wins. | Refresh all six native fixtures, including repeated-list materialization. Preserve four inputs and full public results. |
| Maps/regex and optimizer-policy evaluation | Historical JVM throughput favors both TruffleCEL modes. Targeted native optimizer-policy also wins. | Maps/regex allocation loses: interpreter 6,710 and guest JIT 6,586 versus cel-java 2,926 B/op. Snapshot reuse changes request preparation costs and is a separate API path. |
| Input ingestion | [November 7 normalization follow-up](../ingestion-fastpath-20261107/README.md) reduces CEL snapshot allocation substantially. CEL decode still allocates about 5,366 versus stock protobuf 4,284 B/op. | Shared-host timings are advisory; that report used TruffleData 0.8.0. Rerun released/final-candidate wire decode, generated-message snapshot and decode-plus-evaluate on a reserved host. Bound-snapshot compatibility remained restricted. |
| First result / adapter preparation | New external startup harness includes process launch, fixture construction, adapter creation, ingestion, configuration, preparation, planning and checked first evaluation. | Correctness validation is not timing evidence. Collect JVM and separate native runtime/image-prepared artifacts on a reserved Linux host. |
| Guest compilation payback | Historical [prepared-input curves](../nested-20261105/README.md) measure selected entry paths and finite checkpoints. | No universal payback count. Refresh relevant modes, expose cases with no crossing, include startup where applicable. |
| Memory and deployment footprint | Released small-suite allocation wins coexist with 14 RSS losses; broader historical collection allocation still loses. | Representative RSS/allocation, retained heap (including many prepared programs), compiler/code-cache/native memory and standalone artifact sizes remain unmeasured here. |
| Scaling and embeddings | Focused historical size/selectivity and snapshot diagnostics exist. | Multicore throughput, contention, large schema/expression scaling and polyglot bridge costs need separate evidence. |
| Semantics / feature parity | Conformance and native gates validate their explicit cases. | Feature issues and differential/fuzzing coverage are separate work; timing wins do not prove full CEL parity or exhaustive correctness. |

The next release remains gated on final TruffleData 0.10.0 adoption and qualification.
This report supplies a baseline and identifies unknowns; it does not clear that gate.

## Reproduction and preservation

[Raw artifact](comparison.json), [digest](SHA256SUMS), [derived medians/ranges](summary.json)
and [all 72 metric comparisons](tables.md) are retained. Existing reports are unchanged.
The artifact's source digest, native executable digest, runtime versions, host details
and exact allocation/RSS scopes remain in its metadata. The CI artifact does not
contain the complete raw command/stdout provenance of a dedicated-host run; do not
invent it retrospectively. The corresponding harness is `tools/bench/compare.py`
at the release commit.

```sh
gh run download 42432054271 --repo elide-dev/trufflecel \
  --name cel-comparison --dir /new/release-artifact
python3 docs/benchmarks/release-0.9.0-20261108/summarize.py
```

The summarizer first validates the full runtime/stage/fixture/fork matrix, then
computes the same median-of-process-medians convention as the original renderer.
No new performance timing was collected for this report.
