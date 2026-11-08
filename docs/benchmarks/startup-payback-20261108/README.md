# Startup and natural compilation payback, 2026-11-08

This run closes the missing launch-to-first-result measurement for the current
TruffleCEL candidate and samples guest-JIT payback through one million evaluations.
It does **not** establish a win over cel-java in every category. Native runtime
preparation favors TruffleCEL's median first-result time in all six fixtures, with
overlapping scalar process ranges. JVM scalar startup remains slower, and JVM scalar
and protobuf-policy guest JIT do not recover their compilation cost within this run.

Production source is `67ab33cf89e0f06ad71b0b3c49d32fab53adf2d1`, the owned protobuf
input-copy removal candidate. The measured clean checkout is
`619c4dd606a84e2ea2e0dc6a7f6de4ef04f0f731`; its production tree
`4391fa7a0c1b17959c02a9f372e53014b05260df` matches that candidate. Additional benchmark
commits assert effective compiler options (`c336b15`, cherry-picked as `9871b8f`) and
add the bounded payback driver (`543256f`). TruffleData remains **0.9.0**; release
qualification against TruffleData 0.10.0 is still required. This is current-candidate
evidence, not a paired attribution of startup improvements to the copy removal.

## Results and remaining work

The [complete tables](tables.md) retain all startup cells, controls, process ranges,
and every cumulative reuse checkpoint. [summary.json](summary.json) retains the
underlying fork values, phase timings, launch-to-exit interval, and sampled criterion.

| Observation | Result on this host | Implication |
|---|---|---|
| JVM scalar first verified result | Interpreter 257.231 ms; guest JIT 256.935 ms; optimized cel-java 245.665 ms | A current startup gap; observed ranges are disjoint |
| Native runtime-prepared first result | TruffleCEL medians 1.581–2.191 ms for these cells; optimized cel-java 2.223–4.471 ms | Favorable medians across all six fixtures, with overlapping scalar ranges |
| Native image-prepared scalar first result | Interpreter 1.899 ms; guest JIT 1.871 ms; optimized cel-java 1.499 ms | No all-category native startup win; process ranges overlap |
| Native repeated-list guest-JIT payback | First disjoint post-compilation sampled win at 100,000 evaluations, runtime and image preparation | Reuse can amortize compilation for this 128-element input and materialized filter/map output |
| Native scalar-wide / protobuf policy | First disjoint post-compilation sampled win at 1,000,000 evaluations | Benefits require substantial reuse in these fixtures |
| JVM repeated-list guest-JIT payback | First disjoint post-compilation sampled win at 1,000,000 evaluations | Reuse eventually pays here within the measured bound |
| JVM scalar-wide / protobuf policy | No payback observed through 1,000,000 evaluations | Do not recommend guest JIT unconditionally |

The startup phase timers identify a concrete follow-up: first-use initialization in
the protobuf adapter/decode path. For the JVM scalar interpreter, the adapter phase
median is 18.039 ms and the first decode phase is 70.350 ms; optimized cel-java's
generated-message parse phase is 0.325 ms. These are **class-initialization-inclusive
first-use phases**, not warm decoder throughput or an allocation profile. Later
configuration/preparation phases offset much of that difference (75.198 + 6.255 ms
for TruffleCEL versus 115.210 + 40.968 ms for cel-java). Separate class-initialization
profiling can attribute the cold cost before choosing a change. Phase medians need
not sum to the median total; the raw per-process phases partition it exactly.

For native image preparation, the scalar `main`-to-verified medians are approximately
0.546 ms for the interpreter and 0.372 ms for optimized cel-java. Context/engine
configuration and runtime decoding remain even when AST construction moves to image
build time. The harness enters a Polyglot context to configure the Truffle engine;
this is the chosen benchmark configuration, not a required minimum startup cost for
every use of the standalone Java API. This run does not justify changing global
compiler defaults or introducing another tuning profile.

## Host, runtime, and process boundaries

Measurements ran on Linux `unclemax`, AMD Ryzen Threadripper PRO 9965WX, kernel
`7.0.0-43-generic`, pinned to logical CPUs 2–9. An atomic directory lock serialized
the ingestion, startup/payback and broad-comparison measurement windows. There was
no concurrent benchmark, transfer, profiler or build in this window. Background
monitoring services remained; CPUs were not reserved by the OS and thermal state
was not controlled. [Host snapshots](host-before.json) record the existing performance
governor and enabled boost; neither was changed. The startup driver's load average
rose from 0.195/0.244/0.182 to 1.046/0.448/0.252; payback ended at
1.118/0.597/0.320. Low initial load is not a claim of complete machine isolation.

Both JVM and native artifacts use pinned GraalVM **25.5.5-elide.27**. The downloaded
Linux toolchain archive digest is
`b6d316ed0b81354949704afe632e1e2a1551d637736df5b6b03088cc5b09c852`.
Its Java banner uses build `25.0.4.1.1+1-jvmci-25.5-b24`, so the banner alone is not
the distribution pin. The drivers retain Java, runtime modules, `libjvm`, executable,
source and harness hashes. JVM evaluation uses C2 (`-XX:-UseJVMCICompiler`); both
runtimes have explicit 512 MiB initial and maximum heaps; native uses SerialGC.
Effective target compiler options and tier states are checked by the harness.
No allocation profiler ran during these timing measurements.

Startup uses **285 fresh processes**: five forks for three engines, six unchanged
fixtures, JVM runtime preparation, native runtime preparation and native image
preparation, plus five empty-engine controls for each runtime/artifact. Forks use
input index modulo four. The parent starts its monotonic clock immediately before
process creation and ends at receipt of the child's flushed readiness marker after
independent verification of its first result. Parent scheduling and pipe delivery
are included. JSON emission and context teardown are outside that endpoint; the
separate launch-to-exit interval remains available. No startup result had valid
last-tier guest code; this flag does not exclude first-tier compilation.

Every startup fixture constructs shared raw inputs and serializes/decodes a protobuf
request, including the scalar fixture. Only after ingestion does `scalar` trim its
activation to the single `x` binding; `scalar-wide` retains all 69 bindings. These
numbers therefore describe that application-shaped fixture, not a minimal scalar-only
process. The internal phases
cover fixture/wire preparation, adapter, decode, engine configuration, AST preparation,
planning, and evaluation/verification. Runtime-prepared and image-prepared native
startup use separate binaries, preventing embedded adapter/AST state from prewarming
the runtime artifact. Generated protobuf schema classes initialize at image build in
both binaries; native runtime preparation is not JVM class initialization. Neither
binary embeds guest machine code. Both engines are present in each artifact, so
these data do not establish standalone deployment size. Empty-engine controls are
reported separately and never subtracted. File caches are not flushed.

Payback uses **54 fresh processes**: three forks for two TruffleCEL modes, three
fixtures (`scalar-wide`, `proto-policy`, `proto-repeated`) and JVM/cold, native/cold,
native/image-cold preparation. It retains the full 69-binding map, rotates four input
variants and filters/maps the 128-element repeated input into a materialized list
(77–80 elements across the variants). A volatile consumer
receives every result; all four independent expected results are checked after
timing. No evaluation warms the program before the clock. Natural guest compilation
is synchronous and unforced. Every guest-JIT process reaches valid last-tier code
by the 10,000-evaluation checkpoint.

The cumulative payback clock starts at engine configuration and includes preparation,
instantiation and evaluations; VM launch, fixture creation and ingestion occur before
it. Do not add it to the startup interval or present it as complete-request payback.
Checkpoints are powers of ten from one through one million. The reported first win
requires all JIT forks to be last-tier compiled and the slowest JIT fork to beat the
fastest interpreter fork at that and every later sampled checkpoint. This excludes
small favorable setup fluctuations before compilation. Minimum/maximum process
ranges are not confidence intervals; a sampled checkpoint does not identify an exact
or universal crossover. No extrapolation beyond one million is made.

## Evidence and reproduction

[raw.tar.gz](raw.tar.gz) is the original measurement archive. It includes both raw
JSON files, all process stdout/stderr, source manifest, exact run script, host
snapshots and the Python driver sources. It contains no executable benchmark
artifacts or credentials. [source.json](source.json) uses an explicit tracked-file
allowlist and records the clean source revision, per-file hashes, distribution pin
and exact build commands. [artifact-sha256.txt](artifact-sha256.txt) records the five
deployed binaries/jars, checked before transfer and measurement. [run.sh](run.sh)
records the exact invocation and lock boundaries. These paths are historical host
locations, not a portable one-command deployment.

[source.patch](source.patch) preserves every tracked change from the published
baseline `4c5df30` through measured checkout `619c4dd`; it excludes the subsequent
report. This makes the measured source reproducible even when the worktree's commits
are not retained on the final branch. Apply it to a separate checkout of that baseline:

```sh
git worktree add --detach /tmp/trufflecel-startup-source 4c5df30
git -C /tmp/trufflecel-startup-source apply --index /absolute/path/to/source.patch
git -C /tmp/trufflecel-startup-source write-tree
```

The resulting complete tree must be
`7753a87b283c804fa48d108ad41d6eab4147d829`. Reconstruction was checked with a temporary
Git index against the measured commit. Build from that tree using the commands in
`source.json`; preserve the recorded pins and do not copy local credentials.

The candidate passed the two startup native artifact smoke gates, the prepared
native smoke gate and benchmark Python tests in
[BuildBuddy](https://app.buildbuddy.io/invocation/d21fa6d2-3533-4436-b22f-608cc4aa7b3c).
The Python suite passed 22 tests, including incomplete/duplicate payback inventory
and invalid checkpoint/tier cases. Gazelle and formatting completed without changes.
The final [deploy jar build](https://app.buildbuddy.io/invocation/d7feb503-1ae9-42a2-a5ec-1e044f6229a1)
and [native artifact download](https://app.buildbuddy.io/invocation/a89e5bde-de57-4707-b9f3-b0e647864588)
identify the produced artifacts. Full production qualification is tracked separately;
these benchmark gates are not a substitute for it.

Regenerate the summaries without running any benchmark:

```sh
python3 docs/benchmarks/startup-payback-20261108/analyze.py --output /tmp/startup-payback-summary
diff docs/benchmarks/startup-payback-20261108/summary.json /tmp/startup-payback-summary/summary.json
diff docs/benchmarks/startup-payback-20261108/tables.md /tmp/startup-payback-summary/tables.md
```

The analyzer imports the four archived Python source files into a temporary directory,
validates their hashes and both complete inventories, checks matching source/runtime
and artifact digests, then computes the tables. `SHA256SUMS` covers every report file
except itself. Historical measurements are unchanged; no timing comparison against
older host/toolchain/dependency runs is implied.
