# Opt-in snapshots across the representative workloads

Activation snapshots remain an explicit API choice. The benchmark suite now covers all six
workloads with ordinary map evaluation, reused-snapshot evaluation, and preparation plus one
evaluation, for both the interpreter and guest JIT. Preparation is excluded only from the
reused-snapshot cell; ingestion is measured separately in the representative suite.

The [combined tables](suite-tables.md) and [combined numerical summary](suite-summary.json)
cover all 36 evaluation cells. This follow-up adds the single-binding scalar, materialized
protobuf list and optimizer-policy workloads to the [first activation report](../activation-20261105/README.md).
The reports retain separate raw samples; historical results are unchanged.

## Reading the results

Reused snapshots are faster in all **12** measured workload/runtime pairs. Preparing a new
snapshot for one evaluation is slower in all 12. The added guest-JIT cases are below, in
**ns/op**, using medians of three process means:

| Workload | Ordinary map | Reused snapshot | New snapshot plus one evaluation |
| --- | ---: | ---: | ---: |
| Scalar, one binding | 8.4 | 4.9 | 18.9 |
| Materialized protobuf list | 4,662 | 2,720 | 5,332 |
| Optimizer-policy | 1,918 | 160 | 2,616 |

These are within-TruffleCEL comparisons, not a new cross-engine ranking. Reuse moves
preparation into an explicit step; the one-shot column keeps that cost visible.

Each input has four variants with independently checked results. The repeated protobuf
workload returns a materialized list. Full-activation workloads retain all 69 bindings, including
unrelated maps, lists and protobuf data. The single-binding scalar remains an explicitly named
control, rather than replacing the full-activation scalar.

Reusing an activation is appropriate for several policies or repeated evaluations over the
same input. A new or changed input still incurs normalization and snapshot preparation.
These measurements do not establish that a snapshot should replace ordinary map entry for
one-shot evaluation. They also do not establish cold JIT payback or complete request latency.

The API requires explicit ownership. Repeated map identity does not establish that its contents,
including nested collections or adapter views, are unchanged. Automatic reuse remains deferred;
it would need a reliable immutability or mutation-tracking contract. Existing map behavior is
unchanged. See [activation ownership](../../INTEGRATION.md#reusing-input-snapshots).

## Provenance and validation

Both measurement runs use the **same JAR**, SHA-256
`9c1496946e2abc0beb17c3e8e40a12ac14e54c35d67e718e5b2e56ae89030229`.

The exact artifact digest, source hashes and driver revision are retained in [metadata](metadata.json).
The runtime/JMH source is `9e28a45b3f7f788f796d12f61bbe5e54d8f3ac8a`; the expanded driver is
`e457bf087a5f2e463368368401f1d668bcea2e4e`.
The combined report rejects different artifact/JVM hashes, hosts, kernels, CPU affinities, fork JVM
arguments, duplicate cells and missing workloads. Per-cell run attribution and process ranges
remain in the combined JSON.

The follow-up retains 18 evaluation cells and six repeated preparation controls, each with three
forks, five one-second warmups and five one-second measurements. JMH 1.37 uses explicit C2 host
compilation, a 512 MiB heap and its GC profiler on the dedicated Threadripper PRO 9965WX host,
with affinity CPUs 2–9. Background services remain running; the CPUs are not isolated.
Guest-JIT targets must have valid last-tier code before and after timing.
All 24 follow-up cells and 72 forks completed. No cell exceeded 10% first-two versus last-two
measurement drift in any fork. This audit does not prove that host compilation has ceased.

Runtime source and the measured artifact have not changed since the activation validation:
29 JVM test targets, 99.91% line/branch coverage, six paths through the complete pinned corpus,
and both Linux native gates passed. The expanded benchmark-tool tests pass. This is a JVM
measurement; native correctness gates do not imply a native performance result.

## Remaining work

1. Expand differential negative tests beyond the current syntax/type, arithmetic/index/map and
   lazy-suppression cases, especially unknown/error interactions and adapter failure categories.
2. Measure longer cold curves with snapshots and include process launch, schema setup and
   ingestion in a complete startup measurement. Snapshot reuse does not establish JIT amortization.
3. Integrate the upcoming TruffleData/protobuf changes, then rerun the cross-engine JVM and native
   comparisons with explicitly labeled snapshot reuse and preparation costs.
4. Review the accumulated local changes, run the required CI before merge, and release the
   reviewed result. These measurements are not a claim that the changes are on main or released.

## Reproduction

The benchmark driver now defaults to all six workloads in `activation` mode. This follow-up
selects only the three workloads missing from the first report using
`--activation-workloads scalar,proto-repeated,optimizer-policy`; the selection is recorded and
validated explicitly. See [benchmark commands](../../BENCHMARKS.md#activation-preparation-and-reuse).

```sh
python3 tools/bench/activation_report.py docs/benchmarks/activation-suite-20261105
python3 tools/bench/activation_suite_report.py \
  docs/benchmarks/activation-20261105 docs/benchmarks/activation-suite-20261105 \
  --output docs/benchmarks/activation-suite-20261105
```

`tables.md` and `summary.json` describe this follow-up alone. The `suite-*` files combine the
disjoint evaluation runs. `SHA256SUMS` covers raw JMH results, metadata and compressed logs.
