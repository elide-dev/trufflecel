# Evaluation allocation diagnosis — November 8, 2026

The combined candidate reduces the OptimizedRuntime interpreter's single-binding
scalar allocation from **128 to 96 bytes per evaluation**, a 25% reduction. A separate
fixture that converts one `Integer` binding now allocates **168 bytes** whether its
environment declares 1, 69, or 1,000 variables; the baseline allocates 200, 472, and
4,192 bytes respectively. These are paired JVM allocation observations on a shared
developer machine. They do not establish throughput, RSS, retained-heap, or cel-java
rankings.

Three production sources ran the identical harness:

- Baseline: `67921ff`, the completed 0.10.0 functionality source.
- Context only: `c8555b3`, with compact evaluation contexts.
- Combined: `a0b28c9`, including compact contexts, retained-AST metadata reductions,
  immutable binding-layout reuse, and normalization slots for referenced roots.

The harness is `8f9760c`, SHA-256
`3190d17db65ce7241063d8b302f72ac2331d4f7934f7ecd6b6dbad293de7c622`.
[Evidence metadata](evidence.json) records full revisions, production-tree IDs,
artifact and dependency hashes, exact raw windows, checksums, and qualifications.
The [complete tables](tables.md) preserve both interpreter modes and the intermediate
candidate, including unchanged and non-monotonic cells.

## Observations

OptimizedRuntime interpreter medians, in allocated bytes per operation:

| Fixture | Baseline | Context only | Combined |
| --- | ---: | ---: | ---: |
| Scalar, one canonical binding | 128 | 96 | 96 |
| Scalar, additional unused list binding | 152 | 120 | 120 |
| Scalar, controlled map | 216 | 216 | 216 |
| Scalar, controlled snapshot | 160 | 160 | 160 |
| Simple lazy binding | 184 | 152 | 152 |
| Captured lazy binding | 480 | 440 | 440 |
| Filter/map comprehension | 376 | 344 | 344 |
| Common subexpression elimination | 304 | 264 | 264 |
| Converted binding, 1 declaration | 200 | 168 | 168 |
| Converted binding, 69 declarations | 472 | 440 | 168 |
| Converted binding, 1,000 declarations | 4,192 | 4,160 | 168 |

The scalar's ordinary context no longer stores lexical scope fields or a second
copy of its call arguments. Lexical scopes and parent-budget references live in a
scoped subtype; forks still share the correct budget owner and preserve captured
bindings. Per-call initial budgets live in the optional evaluation state. Controlled,
traced, partial, and source-instrumented calls retain scoped contexts.

The declaration fixtures have the same one-entry input map and expression `x + x`.
An `Integer` value deliberately requires normalization, while unused declarations
increase from zero to 999. The combined layout sizes the conversion cache by
referenced roots. These fixtures are distinct from the two-entry wide-map control
and from the full 69-binding representative workload.

Fallback compilation-plus-evaluation allocation decreases from 3,504 to 3,112 bytes
with an inferred environment and from 3,184 to 2,760 with a reused environment.
Every fallback compilation window reports those exact values. OptimizedRuntime
medians decrease from 3,784.71 to 3,383.73 and from 3,468.50 to 3,036.43 respectively;
those windows vary, and their ranges remain in the evidence. These controls include
parsing, checking, planning, target creation, and evaluation. They are not isolated
frontend-stage measurements, and their harness differs from the earlier standalone
compile-allocation report; do not compare those reports as a matched pair.

| OptimizedRuntime compile control | Baseline window range | Combined window range |
| --- | ---: | ---: |
| Inferred environment | 3,772.69–3,801.48 | 3,376.00–3,405.61 |
| Reused environment | 3,453.12–3,474.28 | 3,026.83–3,056.47 |

The fallback controlled-snapshot cell is 224, 192, and 224 bytes across the three
sources. The combined candidate equals the baseline there. The OptimizedRuntime
controlled-snapshot cell remains 160 bytes throughout. No result is dropped because
it does not follow the primary improvement.

## Method and limits

Bazel 9.3.2 selected the pinned GraalVM 25.5.5-elide.27 on an Apple M5 Max running
macOS 26.6.2, with 18 physical cores and 48 GiB RAM. Each process used a 512 MiB heap,
`-Xbatch`, and `-XX:-UseJVMCICompiler` to select the C2 host compiler. The optimizing
Truffle runtime ran with `engine.Compilation=false`; fallback used
`-Dtruffle.UseFallbackRuntime=true`. The raw output identifies the actual call-target
class and VM version.

Each source/runtime pair used one fresh process. Each fixture warmed 100,000 calls,
then sampled seven windows of 50,000 calls through `ThreadMXBean`. Every return value
escaped through a volatile sink; the expected value was checked after warmup and
every window. Programs, inputs, options, and snapshots were prepared before the
loop, except for the two explicitly named compile-and-evaluate fixtures. Snapshot
creation and ingestion are excluded. Input ownership and validation contracts are
unchanged between revisions.

All builds completed before the final six-process matrix. Measurements ran
sequentially under an atomic directory lock shared with the retained-heap probe.
The host was not reserved, CPU affinity was not set, and other host activity was
not controlled. No durations were recorded. Seven windows in one process do not
provide independent-process uncertainty or establish the behavior of every input.
Background-thread allocation, whole-process memory, native allocation, and other
engines were not measured by this diagnostic.

## Qualification and reproduction

The context-only production change passed all 42 source test targets, including the
complete conformance corpus, and the JVM compiled gate. Its new reentrant-block
regression covers map and snapshot inputs, controlled and tracing calls, and exact
independent budgets. The [compiled log](context-only-compiled.txt) retains the scalar
at 24.00 bytes per evaluation. The native last-tier gate also
[passed](https://app.buildbuddy.io/invocation/ef22397b-4098-46be-822a-19f9b3b569e7);
its [raw output](context-only-native.txt) is retained. The combined source `a0b28c9`
subsequently passed [68 test targets](https://app.buildbuddy.io/invocation/3c17e2b3-d2b9-4a85-a946-357fbe3bef3e),
including the full suite, native guest-JIT gate, native comparison, representative
native tests, and prepared-native tests. Test success is distinct from the allocation
observations above; no benchmark ceiling was relaxed.

For the current harness:

```sh
bazel build --config=local //benchmarks:evaluation_allocation
bazel-bin/benchmarks/evaluation_allocation
bazel-bin/benchmarks/evaluation_allocation --jvm_flag=-Dtruffle.UseFallbackRuntime=true
```

For exact historical reproduction, create a detached worktree at `67921ff`. Apply
[compact-context.patch](compact-context.patch) or [combined.patch](combined.patch)
for the desired candidate, leaving the baseline unchanged. Copy
[EvaluationAllocation.java.txt](EvaluationAllocation.java.txt) to
`benchmarks/EvaluationAllocation.java` and append the preserved
[target](target.bazel.txt) to `benchmarks/BUILD.bazel`. Build with the pinned local
toolchain, then run each interpreter mode in a separate process. The patches include
production changes only; the committed test changes supply qualification coverage.
