# Reading the cel-java comparison

The useful question is which engine and execution mode fit an application's
inputs, expression lifetime and memory budget. Begin with interpreter execution
on the same runtime, then consider guest compilation with its preparation cost.
The [chart gallery](images/benchmark-0.11.0/README.md) and
[release report](benchmarks/release-0.11.0-20261110/README.md) retain the raw values,
source identities, fork ranges and reproduction commands.

This presentation follows the methodology concerns in the November 4
[CEL Java discussion](https://groups.google.com/g/cel-java-discuss/c/nu9DE8BTClE).
The table below distinguishes evidence now available from questions still open.
It is a guide to evaluating the results, not a claim that all concerns are resolved.

| Concern | What to inspect | Remaining limit |
| --- | --- | --- |
| Guest machine code compared with an interpreter | [Interpreter-first chart](images/benchmark-0.11.0/interpreter-time-light.svg); [both execution modes](images/benchmark-0.11.0/evaluation-time-light.svg), with separate counts | Guest JIT is forced before hot timing. It excludes preparation and warmup. Neither mode's warm timing is first-request latency. |
| Three tiny expressions, pruned activations, scalar fast paths and list-count rewrites | Six fixtures include nested protobuf/presence, fully materialized repeated-field results, maps/regex, optimizer opportunities and full 69-binding activations except the single-binding scalar control | Six fixtures are still a bounded sample, not a workload distribution. Input ingestion is outside evaluation timing. |
| Tiny-source compile ratios dominated by fixed checker overhead | [Phase-separated compilation and Pratt-parser measurements](benchmarks/representative-20261105/README.md#compilation-and-optimization) | Those phase measurements are historical. The current README makes no headline compile-speed claim. Current-release phase-separated measurements and alternate HotSpot distributions are not established by the release timing chart. |
| cel-java optimizers disabled | Default and constant-folding/common-subexpression-elimination planner modes remain visible; each TruffleCEL comparison uses the lower cel-java aggregate for the metric | The release hot-evaluation suite does not include a separate Pratt-parser variant. The older phase report includes Pratt plus optimizers. Compile, optimize and evaluate must not be collapsed into one speedup. |
| Short manual warmup and changing shared CI hosts | Release JVM timing uses JMH: three forks, five one-second warmups and five one-second measurements. Native timing uses fresh processes, five warmup and seven measurement windows. CPU affinity and exact host/runtime/source identities are recorded | The AMD Threadripper host is shared. A five-second process monitor guards this round; it does not establish exclusive reservation or absence of all interference. JVM warmup is time-based, not a convergence proof. Native timing is not JMH. The separate CI suite uses a different host/protocol and must not be pooled. |
| Corpus success presented as exhaustive conformance; test-only protobuf path | [Compatibility evidence](COMPATIBILITY.md): shipped protobuf adapter, canonical AST interchange, negative/error-phase tests, custom-function/resource-limit/unknown regression tests | Corpus evaluation-error cases assert failure, not a category or message. The additional edge table has explicit scope and documented divergences; neither suite proves every possible behavior. |

## Input preparation belongs beside the headline

cel-java copies ordinary maps and reuses Guava ImmutableMaps. Both are real
embedding scenarios. The [prepared-input time](images/benchmark-0.11.0/prepared-time-light.svg)
and [allocation controls](images/benchmark-0.11.0/prepared-allocation-light.svg)
show the same full 69-binding inputs and both planner modes. They are a separate
experiment, with caller-side preparation excluded, covering only scalar-wide
and protobuf-policy. Do not pool their values with the main suite or extend
ordinary-map win counts to prepared APIs.

For example, cel-java's JVM scalar-wide allocation drops from 2,936 to **72 B/op**.
TruffleCEL's interpreter uses **120 B/op** in the main suite. This changes the
allocation ordering; hiding the prepared control would misrepresent the choice.
The same cel-java control reduces time from roughly 517–524 to 25–26 ns/op.
The [complete control tables](benchmarks/release-0.11.0-20261110/prepared-inputs.md)
provide all eight runtime/fixture/planner pairs.

## Four different memory questions

| Measurement | What it answers | What it cannot establish |
| --- | --- | --- |
| [Allocation per evaluation](images/benchmark-0.11.0/evaluation-allocation-light.svg), B/op | How many heap bytes the evaluating thread allocates | Live retained heap, compiler/background allocation or native memory |
| [Current process RSS](images/benchmark-0.11.0/evaluation-rss-light.svg), MiB | Resident pages for the entire process after the sampled work, with program and inputs live | A controlled post-GC heap size, standalone deployment size, or equal work from process launch |
| [Lifetime peak RSS](images/benchmark-0.11.0/evaluation-peak-rss-light.svg), MiB | High-water resident memory including setup, calibration, warmup and compilation | The amount still resident after evaluation, or a sum of independent memory categories |
| [Historical retained-heap experiment](benchmarks/retained-20261108/README.md) | Incremental live Java heap for cached programs, excluding a shared environment/runtime | Current 0.11.0 retained heap, native RSS or deployment footprint |

Current and peak RSS already cover the whole process; adding them would double
count memory. Both engines share the comparison artifact, with one engine active
per process. Separate production images, compiler/runtime baselines, GC position,
and input ownership must be controlled before claiming a standalone footprint win.
Native guest-JIT RSS is higher on the protobuf and map/regex fixtures despite its
lower per-evaluation allocation. Both outcomes belong in a performance discussion.

## Startup and cumulative cost

The [historical native-profile investigation](benchmarks/native-profiles-20261109/README.md)
includes TruffleData **0.10.0** first-result and bounded natural-JIT reuse measurements.
It separates the context/direct Java embedding controls from native memory profiles;
neither is a new evaluator implementation. The native scalar first-result ranges
overlap after omitting an unnecessary context, while JVM scalar-wide and protobuf-policy
guest JIT still do not recover their compilation cost through one million evaluations.
The report retains all checkpoints and cases without observed payback. Its hot-profile
memory savings also include measured throughput tradeoffs and a known GraalVM counter bug.

The [November 8 launch-to-first-result and natural-JIT report](benchmarks/startup-payback-20261108/README.md)
uses **TruffleData 0.9.0**, before the final release dependency and regex changes.
It includes fresh-process first results and cumulative reuse through one million
evaluations, including engine preparation and natural compilation. It records a
slower JVM scalar startup and no sampled JVM scalar-wide/policy JIT payback in
that interval. It is historical evidence, not a current-release guarantee.

Before publishing an application end-to-end speedup, measure its complete request
path and expected reuse using the exact deployed artifacts, retained full inputs,
recommended cel-java configurations, and matched preparation contracts. The historical
cumulative-cost curve excludes process launch and ingestion. Do not extrapolate
an unobserved crossover from a warmed nanoseconds-per-operation ratio.

## Reproduction and reporting rules

Preserve all fixtures and failed cells. Record engine versions, parser/planner
options, CPU/runtime identity, heap/GC options, host sharing, warmup, compilation
state, input ownership and output materialization. Check outputs outside the timed
region with multiple input variants; do not prune unused bindings to recover a
headline. Keep raw samples and fork ranges with the chart, and state the aggregation
rule. The observed ranges are not confidence intervals or significance tests.

The [release report](benchmarks/release-0.11.0-20261110/README.md#protocol)
and [benchmark methodology](BENCHMARKS.md) give the actual protocols. The separate
CodSpeed simulation suite counts native-AOT instructions; it is a regression gate,
not a substitute for JVM throughput, startup, or memory measurement.
