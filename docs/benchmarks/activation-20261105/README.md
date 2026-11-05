# Reusable activation investigation, November 5, 2026

This investigation isolates work owned by TruffleCEL while TruffleData and protobuf ingestion
improvements are developed separately. The change is opt-in: `Activation.snapshot(bindings)`
prepares inputs for repeated `Program.evaluate(activation)` or `Program.test(activation)` calls.
Ordinary map evaluation retains its existing normalization behavior.

## Preparation costs

The 69-binding fixture contains the same scalar, list, map and protobuf inputs as the
[representative comparison](../representative-20261105/README.md). No bindings are trimmed.
On the dedicated Threadripper host, isolated shallow copying takes about **0.47 µs**, value
normalization about **1.48 µs**, and complete snapshot preparation about **2.40 µs**.
These are independent benchmark operations and must not be added into an end-to-end estimate.
The snapshot operation includes constructing an immutable binding map, beyond a shallow copy.

The preparation result is consistent with substantial costs in TruffleCEL's entry path:
improving protobuf field reads alone will not remove repeated normalization of unrelated maps
and lists. It does not establish the fraction of total application CPU spent in each method.

## Evaluation results

Reuse removes most of the repeated input-preparation cost. The following guest-JIT numbers
are medians of three process means, in **ns/op**:

| Workload | Ordinary map entry | Reused snapshot | New snapshot plus one evaluation |
| --- | ---: | ---: | ---: |
| Scalar, full activation | 1,704 | 8.2 | 2,339 |
| Protobuf policy | 2,298 | 492 | 3,084 |
| Maps/regex | 2,272 | 504 | 3,067 |

The two policy fixtures improve about **4.5–4.7×** when the same prepared input is reused.
The scalar fixture exposes how much its earlier total was dominated by input handling.
Its full activation is still present; only preparation has moved to an explicit reusable step.
Interpreter evaluation also improves: about 1,702 → 17 ns for the full-activation scalar,
2,397 → 542 ns for protobuf policy, and 2,477 → 711 ns for maps/regex.

Allocated heap per guest-JIT evaluation falls from about **8,920 to 1,272 bytes** for protobuf
policy and **10,258 to 2,610 bytes** for maps/regex. Snapshot preparation itself allocates
about **9,704 bytes** for the full activation; the caller pays that whenever inputs change.

Preparing a fresh snapshot for **one** evaluation is **26–37% slower** than ordinary map entry
across these interpreter and guest-JIT cases. Use the existing map API for one-shot inputs.
The new API is useful when several policies or repeated calls share an unchanged input;
it does not make the existing map path faster. These within-TruffleCEL measurements are not
a new engine-to-engine comparison or evidence of an overall application speedup.

The [complete tables](tables.md) retain time and allocated heap bytes for ordinary map entry,
snapshot reuse, and preparing a new snapshot for one evaluation. Process ranges and JMH's
own means/errors remain in [the numerical summary](summary.json).

Only the reusable-snapshot evaluation excludes preparation. That cell represents repeated
policies/evaluations over an unchanged input, not a new input on each call. Wire decoding,
schema setup, expression compilation and guest compilation are outside all these timed cells.
There is no claim here about cold JIT payback, whole-request latency or native throughput.

## API and ownership

```java
Activation input = Activation.snapshot(bindings);
boolean allowed = accessPolicy.test(input);
Object classification = classificationPolicy.evaluate(input);
```

Preparation snapshots all host collections and bytes, including unused bindings, with the
existing per-value size/depth bounds. Normalization errors therefore surface during preparation.
Later host collection mutations do not change the prepared activation. Each evaluation owns
fresh execution scopes and an independent budget; an activation can be reused across programs.

Adapter and opaque values retain their existing contracts. Preparation does not freeze live
TruffleData views or make arbitrary objects safe to share across threads. The
[integration guide](../../INTEGRATION.md#reusing-input-snapshots) describes this boundary.
The library does not cache mutable input maps by identity or silently discard unused inputs.

## Validation

All 29 Bazel JVM test targets pass. The full 2,771-case upstream corpus now has six paths:
the existing independent and production adapters with direct/canonical ASTs, plus prepared
activations with the production adapter and direct/canonical ASTs. Expected-error assertions
retain the previously documented corpus limitations.

Additional tests cover mutation isolation (including returned bytes), null/missing/unknown
bindings, unused invalid inputs, cyclic and oversized data, concurrent and reentrant reuse,
budget exhaustion/recovery, and debugger visibility of bindings and comprehension locals.
Production coverage is 99.91% lines (4,338/4,342) and branches (4,441/4,445).

Both [Linux native gates pass](https://app.buildbuddy.io/invocation/5dc9e2d2-9c5e-419b-95a1-a08847628495),
including compiled evaluation of prepared activations and the existing representative native
matrix. Gazelle, formatting and all ten benchmark-tool Python tests pass. These are correctness
and compatibility gates; no Native Image performance claim follows from them.

## Method and provenance

Source: `9e28a45b3f7f788f796d12f61bbe5e54d8f3ac8a`. The measured JAR is retained on the dedicated
host; metadata records its SHA-256, source hashes, commands, effective VM flags and host details.
The source changes and results remain local to the working branch and have not been pushed.

- AMD Ryzen Threadripper PRO 9965WX, Linux x86_64, affinity CPUs 2–9; background services
  remain running and the CPUs are not isolated.
- Pinned GraalVM `25.5.5-elide.24`, explicit C2 host compilation (`-XX:-UseJVMCICompiler`),
  512 MiB initial/maximum heap, JMH 1.37.
- Three forks per cell, five one-second warmups and five one-second measurements. All cells
  use JMH's GC profiler. Allocated bytes measure Java heap allocation, not RSS or native memory.
- All 24 cells and 72 forks completed. No cell exceeds 10% first-two versus last-two
  measurement drift in any fork; all original samples are retained.
- Six preparation cells and eighteen evaluation cells. The latter cover full-activation scalar,
  protobuf policy and maps/regex, with interpreter and forced last-tier guest-JIT execution.
  All four input variants are checked independently before measurement; tier validity is
  checked again afterward. The JVM smoke test additionally checks prepared inputs on all six
  representative workloads.
- Tables use medians of three process means and retain minimum/maximum process means.
  The warmup audit compares the first two and last two measured iterations within each fork;
  it does not establish that all host compilation has ceased.

Reproduce the run with the `activation` mode in
[the benchmark driver](../../BENCHMARKS.md#activation-preparation-and-reuse). Regenerate the
derived tables without additional dependencies:

```sh
python3 tools/bench/activation_report.py docs/benchmarks/activation-20261105
```

Raw JMH results, compressed logs and metadata are retained alongside this report. Historical
representative measurements are unchanged.
`SHA256SUMS` covers the raw files; `summary.json` and `tables.md` are derived outputs.
