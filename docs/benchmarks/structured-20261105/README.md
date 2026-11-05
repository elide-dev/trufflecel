# Prepared protobuf reads and owned collections, November 5, 2026

This investigation measures the first implementation of preparation-time field
access plans and owned collection storage. It compares TruffleCEL with its prior
runtime, with TruffleData pinned to 0.6.0 in both artifacts. It is not a new
comparison against cel-java.

## Architectural change

Preparation now resolves supported protobuf fields to immutable metadata. Each
program instance creates its own adopted read nodes and bounded data-library
caches. Execution guards exact adapter identity before using a cached ordinal;
other representations retain generic lookup and conversion. No request values
are cached. Custom adapter precedence, unknowns, optional lifting, maps, nested
messages and unsupported scalar conversions retain their existing paths.

Qualified repeated scalar fields expose immutable canonical storage directly to
collection execution. Evaluation-local builders transfer ownership of result
storage, removing intermediate list copies. Filter and map remain separate
passes, preserving callback order, failures and operation charging. Public
results are still materialized, and arbitrary host collections still undergo
normalization. Activation snapshots remain opt-in.

## Measurements

The full activation matrix confirms the initial target for prepared repeated
protobuf filter/map evaluation: **2.27× faster guest-JIT evaluation and 52.6%
less allocated heap**. Interpreter evaluation improves 1.57× with 52.7% less
allocation. Guest-JIT figures below are medians of three process means:

| Repeated-field entry | Reference ns/op | Candidate ns/op | Speedup | Reference B/op | Candidate B/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ordinary map | 4,661.5 | 3,091.2 | 1.51× | 18,992.2 | 13,028.2 |
| Reused snapshot | 2,720.0 | 1,195.7 | 2.27× | 11,344.1 | 5,380.1 |
| Fresh snapshot plus evaluation | 5,331.9 | 3,570.6 | 1.49× | 21,048.3 | 15,084.2 |

Reused-snapshot process ranges are 2,711.1–2,797.2 ns before and
1,189.0–1,197.3 ns after. Input preparation still limits one-shot performance;
the evaluation gain does not eliminate ingestion or full activation copying.

The protobuf policy workload changes only modestly (492.1 → 475.2 ns for
guest-JIT snapshot reuse), with unchanged 1,272 B/op. Its nested messages, maps
and conversions remain outside this slice. Optimizer-policy snapshot evaluation
improves 160.3 → 119.5 ns, but this secondary result is not the architectural
acceptance target and needs further attribution before a general claim.

All 42 full-matrix cells completed: six input-preparation diagnostics and 36
evaluation cells, totaling 126 processes. None exceeds 10% first-two versus
last-two measurement drift in any fork. No unaffected evaluation cell has a
median slowdown above 5%; the largest is 3.9% for interpreter full-activation
scalar evaluation after constructing a fresh snapshot. This audit is not a
statistical equivalence test or proof that host compilation has ceased.

The matched single-request diagnostic confirms scaling at 50% selection:

| Elements | Reference ns/op | Candidate ns/op | Speedup | Reference B/op | Candidate B/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0 | 44.5 | 15.7 | 2.84× | 216.0 | 240.0 |
| 16 | 359.9 | 161.4 | 2.23× | 1,240.0 | 440.0 |
| 128 | 2,424.6 | 995.2 | 2.44× | 9,640.1 | 3,832.1 |
| 1,024 | 18,572.3 | 7,524.0 | 2.47× | 70,528.9 | 27,664.4 |

At 128 elements, selecting nothing improves 3.61× and selecting everything
improves 2.13×. Empty input is faster but allocates 24 extra bytes; allocation
does not improve universally. The diagnostic uses one request and a generated
cutoff, so its absolute times are not interchangeable with the four-input full
activation fixture above. All 16 paired cells and 48 processes completed, with
no fork exceeding the 10% measurement-drift check.

Expression preparation changes from 1,534.7 to 1,575.7 ns (5,672.1 → 5,768.1
B/op); instantiation changes from 902.6 to 904.8 ns (1,567.9 → 1,799.9 B/op).
The preparation/instantiation process ranges overlap. These are steady-state
setup diagnostics, not cold-start measurements or a measured payback curve.

## Profiling evidence

The separate baseline JFR diagnostic uses one fork, five one-second warmups and
five one-second measurements for reused-snapshot guest-JIT evaluation. Exported
allocation and execution samples are retained in `profile/`; raw recordings and
unrelated environment events are excluded. The profiled timing is diagnostic
and is not used as the before/after reference.

Repeated-field samples include allocation stacks through `ProtobufType.get`,
`Values.in`, `Values.listRange` and `HostCollections.freezeList`. Execution
samples also include builder append and comprehension scope management. Policy
samples include generic field lookup, normalization and canonical conversion.
These observations support removing conversion/copy stages. Sampling and
compiled/inlined stack attribution do not establish exact per-method CPU or
allocation percentages; some boxed-number samples are attributed to budget
charging frames rather than a unique allocation site.

## Validation

All 30 JVM Bazel test targets pass, including the complete 2,771-case upstream
corpus through six execution paths. New differential tests compare planned and
generic reads, mismatched adapters/schemas, optional and unknown handling, small
budgets and error order, callback ordering, mutation isolation, canonical imports,
multiple program instances, concurrent reuse and late debugger attachment.

Production coverage is 99.91% lines (4,403/4,407) and branches (4,499/4,503).
Gazelle, formatting and all eleven benchmark-tool tests pass. Existing allocation and compilation gates
pass without exclusions or relaxed limits.

Both [Linux native gates pass](https://app.buildbuddy.io/invocation/fc6eed64-2105-4f96-8e1f-ba9e4771bc64).
The representative native matrix also
[passes with all compilation warnings treated as errors and a 10,000-node graph limit](https://app.buildbuddy.io/invocation/3dc60b76-0f9b-448f-b490-c3a2f47c39cd).
These qualify compatibility and compilation, not native throughput.

## Method and provenance

Runtime implementation: `5dd051f9bbe071ff88a6b92b3b1b5a1286e7198b`.
Timed artifacts were built before that local commit; their manifests record
base revision `2bd3e3b` plus the exact source hashes. Production Java sources in
the candidate artifacts match the committed implementation.

The full activation matrix uses the same benchmark sources as the retained
baseline in [activation-20261105](../activation-20261105/README.md) and
[activation-suite-20261105](../activation-suite-20261105/README.md). It preserves
all six workloads, both interpreter and guest-JIT execution, and ordinary map,
reused snapshot and fresh-snapshot entry. Preparation diagnostics are separate
and are not additive.

Paired size/selectivity/preparation diagnostics build the reference runtime at
`2bd3e3b` with the same benchmark overlay as the candidate: `StructuredAccess`,
`Engines` and its build configuration. These runs enforce compiler warnings and
graph size limits for both artifacts. The full activation run retains the older
engine harness to match its historical reference exactly.
The historical manifests contain only selected production hashes;
`reference-equivalence.json` additionally records verification of every reference
production Java source against the historical baseline's Git objects.

All timed comparisons run sequentially on the dedicated Threadripper PRO 9965WX,
Linux x86_64, CPUs 2–9, pinned GraalVM `25.5.5-elide.24`, explicit C2 host
compilation, a 512 MiB heap, and JMH's GC profiler. Each cell has three fresh
processes, five one-second warmups and five one-second measurements. Tables use
the median of process means and retain their minimum/maximum. Allocation is Java
heap bytes/op, not RSS or native memory. Background services remain running;
CPUs are not isolated.

Only snapshot reuse excludes activation preparation. No timed evaluation cell
includes ingestion, schema setup, expression preparation or guest compilation.
Separate prepare/instantiate diagnostics measure those operations on an already
configured environment, not VM startup or first-evaluation latency. Cold payback,
whole-request gains and Native Image performance require separate measurements.

The next architectural scope is nested message/map access and conversion,
followed by qualification against the forthcoming TruffleData changes. Neither
is included in this first scalar/repeated-scalar slice.

Raw JMH JSON, compressed logs, source manifests and artifact hashes are retained
under `activation/` and `paired/`. `comparison-tables.md` includes every measured
comparison with process ranges; `comparison-summary.json` retains derived values
and drift audits. `SHA256SUMS` covers raw evidence and provenance, excluding
derived summaries and prose.

Reproduce the measurements using the
[activation and structured drivers](../../BENCHMARKS.md#structured-data-preparation-and-scaling).
Regenerate the report from retained measurements:

```sh
python3 tools/bench/activation_report.py docs/benchmarks/structured-20261105/activation
python3 tools/bench/structured_report.py \
  --activation docs/benchmarks/structured-20261105/activation \
  --baseline docs/benchmarks/activation-20261105 docs/benchmarks/activation-suite-20261105 \
  --paired docs/benchmarks/structured-20261105/paired \
  --output docs/benchmarks/structured-20261105
```
