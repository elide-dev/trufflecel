# Borrowed inputs: fresh-request overhead, November 5, 2026

Ordinary map evaluation now borrows stable caller-owned inputs and normalizes bindings on
read. It no longer constructs a second activation map or traverses unused bindings.
TruffleData remains at 0.6.0; this change targets work inside TruffleCEL.

## Input contract and implementation

Callers must keep the input map and reachable data unchanged until evaluation returns,
including during host functions and adapter callbacks. Changes between calls are observed.
`Activation.snapshot` remains the explicit eager-validation and isolation API. This is a
behavior change: unused invalid inputs no longer fail ordinary map evaluation, and an input
mutation during evaluation is unsupported. It is documented in the changelog and
[integration guide](../../INTEGRATION.md#borrowed-input-bindings).

Each program prepares an immutable name-to-slot layout. Each evaluation borrows its map;
values requiring conversion are cached in evaluation-local slots, allocated only when
needed. Canonical scalar and adapter values need no conversion cache. No request values
are retained in the program. Truffle assumptions are not used to pretend that arbitrary
writes to Java collections can be detected.

A read binding still receives the existing complete per-value normalization and depth/size
checks, including its nested contents. Repeated reads reuse converted values, which also
supports one-shot iterables. Public collection/byte results retain owned storage. Debugger
inspection can read additional bindings; scopes, operation budgets and conversion caches
remain isolated between evaluations. Explicit snapshots and their eager validation are
unchanged. Nested host-map/list normalization has not been replaced with borrowed views.

## Measurements

The complete matched JVM comparison has 18 cells and 54 fresh processes. Both TruffleCEL
execution modes are faster than optimized cel-java on all six fixtures. No measured process
exceeds the 10% drift threshold. Median ns/op:

| Workload | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java folding/CSE | Guest-JIT advantage |
| --- | ---: | ---: | ---: | ---: |
| Scalar, one binding | 18.6 | 8.9 | 27.2 | 3.07× |
| Scalar, full activation | 24.3 | 15.1 | 516.7 | 34.26× |
| Protobuf policy | 312.6 | 265.2 | 1,146.6 | 4.32× |
| Repeated protobuf list | 2,485.0 | 1,191.6 | 7,387.4 | 6.20× |
| Maps/regex | 1,800.5 | 1,590.0 | 2,544.8 | 1.60× |
| Optimizer-policy | 86.0 | 54.2 | 708.8 | 13.07× |

These are fresh **borrowed-map** calls, not reused activations. Interpreter advantages range
from 1.41× to 21.27×, so the fresh-input benefit does not depend on guest compilation.
For guest-JIT calls, allocated Java heap is 48 B/op for full-activation scalar, 808 B/op for
protobuf policy and 208 B/op for optimizer-policy, versus cel-java's 2,872, 3,956 and 3,208 B/op.

Maps/regex remains an allocation gap: guest JIT allocates 6,586 B/op versus cel-java's
2,926 B/op, despite being faster. This workload reads host collections that still undergo
normalization. The single-binding interpreter scalar also allocates 112 B/op versus 96 B/op
for cel-java. These throughput wins are not a universal memory or end-to-end win.
The twelve JVM snapshot-control cells retain their earlier performance level: guest-JIT
protobuf policy is 248.2 ns and the repeated list is 1,202.8 ns. One interpreter
optimizer-policy snapshot fork exceeds the drift threshold (+14.36%); the original data
is retained. A separate three-fork, fifteen-warmup follow-up measures 80.6 ns
[80.0–81.2], 224 B/op, with no drift flag. The original median is 81.6 ns. The follow-up
supports the same control level without discarding the flagged original process.

### Native fresh-map comparison

All 27 targeted native processes completed with no >10% drift flag. Both TruffleCEL
execution modes beat optimized cel-java on all three previously losing fresh-map fixtures.
Median ns/op:

| Workload | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java folding/CSE |
| --- | ---: | ---: | ---: |
| Scalar, full activation | 35.7 | 27.8 | 1,291.9 |
| Protobuf policy | 530.5 | 384.4 | 2,285.1 |
| Optimizer-policy | 169.6 | 84.9 | 1,638.5 |

This native timing claim is limited to these three workloads. The all-six-workload native
correctness gates are not a substitute for native throughput measurements on the remaining
fixtures. Java heap allocation is measured in the JVM run, not inferred for Native Image.
See [complete process ranges, allocation and drift tables](tables.md).

## Method and scope

The fresh-map JVM comparison runs all six existing workloads with TruffleCEL interpreter,
TruffleCEL guest JIT and cel-java 0.15.0 with constant folding/CSE. All engines run from the
same artifact and use the unchanged `SteadyState.evaluate` harness. An additional twelve
cells exercise explicit snapshot reuse in both TruffleCEL modes. Each cell uses three fresh
JMH processes, five one-second warmups and five one-second measurements with the GC profiler.

The targeted native comparison covers the three previously losing fresh-map workloads:
full-activation scalar, protobuf policy and optimizer-policy. It compares the same three
engines, three fresh processes each, five one-second warmups and seven half-second samples.
AST preparation happens at runtime before steady-state timing; guest compilation is forced
and checked. These custom native timings are separate from JMH.

Full activations still contain all 69 bindings. Inputs are not trimmed; runtimes now decide
which bindings to read. Every workload cycles four independently checked inputs and returns
its complete public result. Ingestion, expression preparation and guest compilation are
excluded from steady-state timing. This is not a measurement of complete request latency,
cold payback, RSS or the forthcoming TruffleData ingestion improvements.

All timed processes run sequentially on the dedicated Threadripper PRO 9965WX, CPUs 2–9,
pinned GraalVM 25.5.5-elide.24, with a 512 MiB heap. JVM host compilation uses explicit C2.
Native measurements start only after JVM measurements finish. Background services remain
running; CPUs are not isolated. Process ranges and 10% first-two/last-two drift audits are
retained. Raw measurements are not silently replaced by follow-ups.

## Validation

All 31 JVM targets pass, including 2,771 conformance cases through six execution paths.
New tests cover no enumeration of unused bindings, conversion reuse, mutation between
calls, one-shot iterables, read-time bounds and short-circuiting, null/missing bindings,
public output ownership, debugger-visible bindings, reentrant evaluations and concurrent
cache isolation.
Existing mutation-isolation tests now explicitly use `Activation.snapshot`.
Coverage is 99.91% lines (4,476/4,480), 99.89% branches (4,574/4,579).
Allocation/AST limits are unchanged. All three [Linux native gates pass](https://app.buildbuddy.io/invocation/caa9c8c9-fa02-4552-8091-13ae9617bc14),
including the JIT/blocklist gate and both representative matrices.
Runtime source is local commit `4b24ef8`; source hashes establish equivalence with the
artifacts built before that commit.

## Reproduction and provenance

Report-local drivers retain the exact dedicated-host commands and output paths. JVM and
native metadata record the host/toolchain, source manifest, artifact digests, process
commands and completion flags. The short one-fork pilot is diagnostic only and is not
used in these comparisons. Historical reports are unchanged. All work remains local.

Regenerate and audit the retained comparisons with:

```sh
python3 docs/benchmarks/borrowed-20261105/summarize.py
```

`source-equivalence.json` checks the measured production sources against the local runtime
commit. `SHA256SUMS` covers raw JSON, manifests, compressed logs and the exact runners.
Derived tables and summaries are reproducible from those files.

## Remaining gaps

- Maps/regex still allocates more Java heap than cel-java; read host collections still normalize
  into owned CEL values. Faster native TruffleData inputs may reduce a different part of this cost.
- This report does not rerun ingestion or cold payback. The previous ingestion disadvantage and
  the need to amortize guest compilation are not declared fixed by warmed evaluation wins.
- The comparison covers these synthetic fixtures and optimized cel-java 0.15.0, not every CEL
  workload, library version or embedding API. Polyglot input-bridge costs are outside this run.
- The stable-input contract is an intentional compatibility change. Existing users needing
  isolation or eager validation must use `Activation.snapshot` explicitly.

No push, merge or release accompanies this local implementation and report.
