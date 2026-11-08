# Retained Program heap diagnostic — November 8, 2026

Cached Programs retain less Java heap after releasing planning annotations, sharing empty
child arrays, and compacting arithmetic-chain provenance. Across these five fixtures,
the AST change reduces the ordinary cached Program footprint by 2.3–19.4%. Sharing
declaration layouts additionally removes the per-Program cost of a wide Environment:
10,000 distinct scalar Programs with 69 declarations retain about 10.56 MiB after both
changes, versus 22.60 MiB before them. Repeated instantiation of one PreparedProgram
falls from 21.09 MiB to 9.19 MiB for the same cache size.

These are shared-host, interpreter-only **live Java heap diagnostics**, not RSS,
allocation-per-evaluation, throughput, guest compiler memory, or a cel-java comparison.
They do not establish that TruffleCEL has the lowest memory use in every category.

## Revisions and method

- Production baseline: `67921ff`.
- AST changes: `4ffe7ea`; harness: `4df7775`.
- AST plus shared layouts: `3ea7c78`, the cherry-pick of `f35e2f0` onto those commits.
- Host: Apple M5 Max, 48 GiB RAM, macOS 26.6.2, arm64; Bazel 9.3.2.
- Pinned GraalVM `25.5.5-elide.27`, reporting Java `25.0.4.1.1+1-jvmci-25.5-b24`.
- Optimizing Truffle runtime in an entered CEL context, guest compilation disabled;
  C2 host compilation (`-XX:-UseJVMCICompiler`), Serial GC, fixed 512 MiB Java heap.
- One fresh JVM per case/mode/sharing/declaration combination. The local measurement
  lock prevented another benchmark from running concurrently. Normal shared-host
  activity remains; every round records the observed load average.

Each process first creates and evaluates 2,000 distinct-source Programs and 2,000
instantiations of a shared template, including the selected trace/partial path. The
Environment, PreparedProgram, inputs and runtime are therefore live before the baseline.
Three rounds then cache 10,000 evaluated Programs. Each round records five explicit-GC
heap samples, 50 ms apart, before construction, while the cache is held by a volatile
root, and after verifying and releasing the entire cache. Measurement uses the memory
MXBean's used Java heap. Population and release methods have explicit `dontinline`
directives so temporary loop locals cannot keep the released cache alive in the
sampling frame. An initial unpublished probe detected that trap through its large
post-release delta; those invalid observations are excluded.

Weak-target bookkeeping in the runtime disappears asynchronously between collections.
For each five-sample batch, the analysis uses the minimum, not a transient larger
sample. All samples remain in the [raw logs](raw/). Tables average
`(min(live) - min(before)) / 10000` over rounds 1 and 2; round 0 remains in the raw
evidence as additional cache priming. Later-round post-release drift and both individual
round estimates are retained in [summary.json](summary.json). Baseline logs' original
printed `retainedBytes`/`bytesPerProgram`/`baselineDrift` fields used batch medians;
the table and summary recompute them from the raw arrays using the same minimum-based
rule for every revision. Candidate harness reporting was corrected to that rule.

Two repeated rounds in one process are not independent JVM forks or a confidence
interval. Most later-round drift is under 18 bytes per cached Program. The measured
reductions exceed that residual drift; smaller differences between trace and partial
rows should not be interpreted as separate performance effects. The manifest records
source revisions, host details and source hashes; the summary hashes each raw log.

## Fixtures and results

Inputs are `x = 42` and `xs = [40, 41, 42, 43, 44]`, with a shared Environment declaring
both variables. A unique trailing source comment makes every `distinct` Program source
different. The fixtures independently assert their result on every Program before
measurement and again after collection:

| Fixture | CEL source | Expected result |
| --- | --- | ---: |
| chain | `x * 2 + 1` | 85 |
| folded | `[1, 2, 3, 4].map(n, n * 2).filter(n, n > 4).size()` | 2 |
| comprehension | `xs.filter(n, n > x).map(n, n * 2).size()` | 2 |
| cse | `((x + 1) * (x + 1)) + ((x + 1) * (x + 1))` | 3698 |
| macro | `twice(x + 1)`, a custom macro expanding to argument + argument | 86 |

Only the `cse` fixture enables common-subexpression elimination. `trace` evaluates with
a non-retaining listener, then retains the Program and its memoized trace target.
`partial` evaluates with `x` unknown, asserts the unknown result (or a complete result
for the closed folded fixture), then retains the Program and its memoized partial/trace
target. Trace wrappers, events, and PartialResults are not cached by this experiment.

Bytes per cached Program, including the cache's reference-array slot:

| Fixture | Mode | Baseline | AST change | Reduction |
| --- | --- | ---: | ---: | ---: |
| chain | default | 1291.7 | 1187.7 | 8.1% |
| chain | trace | 2575.0 | 2439.0 | 5.3% |
| chain | partial | 2575.0 | 2439.0 | 5.3% |
| folded | default | 3723.6 | 3402.6 | 8.6% |
| folded | trace | 6324.0 | 6004.6 | 5.1% |
| folded | partial | 6323.7 | 6005.7 | 5.0% |
| comprehension | default | 3035.7 | 2858.7 | 5.8% |
| comprehension | trace | 5060.1 | 4883.2 | 3.5% |
| comprehension | partial | 5059.7 | 4883.4 | 3.5% |
| cse | default | 6921.5 | 6761.3 | 2.3% |
| cse | trace | 9554.9 | 9395.6 | 1.7% |
| cse | partial | 9555.5 | 9393.7 | 1.7% |
| macro | default | 2148.0 | 1732.2 | 19.4% |
| macro | trace | 3605.5 | 3189.4 | 11.5% |
| macro | partial | 3605.8 | 3189.1 | 11.6% |

The separate layout experiment adds 67 unused int declarations to the shared Environment.
`template` creates all Programs from one PreparedProgram instantiated repeatedly;
`distinct` compiles the individually suffixed source texts. This measures retained
Programs rather than 10,000 distinct Environments or retained input objects.

| Declarations | Source sharing | Baseline | AST change | AST + shared layouts | Total reduction |
| ---: | --- | ---: | ---: | ---: | ---: |
| 2 | distinct | 1291.7 | 1187.7 | 1107.7 | 14.2% |
| 69 | distinct | 2370.3 | 2266.6 | 1107.7 | 53.3% |
| 69 | template | 2211.4 | 2123.4 | 963.8 | 56.4% |

## Implementation and limits

Runtime-owned copies release `fold`, `checkedType`, and custom `macroCall` planning
annotations after lowering. Fold replacements already store the value, error and
original cost required at evaluation. Their preserved source trees keep source IDs,
locations, literals, error encodings and executable semantics for lazy restoration.
The cleanup also reaches source trees held outside Truffle child edges by fold/CSE
nodes. It runs once during initial Program construction, before those source trees can
be shared by later lazy-target clones; PreparedProgram templates remain unchanged.
Empty child arrays are shared after construction and target cloning. Arithmetic chains
retain the same full-width IDs and locations in three arrays instead of seven.

The retained source copies required by tracing, partial evaluation and source-accurate
errors still cost memory. CSE retains its unoptimized source tree as well as optimized
runtime nodes. This change does not remove that contract or establish the theoretical
minimum representation. Additional lazy trace targets also legitimately increase a
cache's retained heap; callers that never request tracing or partial evaluation avoid
those targets.

The focused prepared-field, CSE, trace, partial-evaluation and harness smoke tests passed.
The new regression concurrently instantiates shared templates, checks values, budgets,
error/trace provenance and partial unknowns, and checks the original template syntax,
types and fold annotations after use. Formatting and Gazelle were run. Combined full
source coverage and native guest-JIT qualification belong to the integrating change;
this report does not claim those gates from a JVM heap measurement.

## Reproduction

```sh
bazel test --config=local //benchmarks/retained:smoke
bazel build --config=local //benchmarks/retained:heap
bazel-bin/benchmarks/retained/heap chain default distinct 2 10000
bazel-bin/benchmarks/retained/heap folded trace distinct 2 10000
bazel-bin/benchmarks/retained/heap chain default distinct 69 10000
bazel-bin/benchmarks/retained/heap chain default template 69 10000
```

The full AST matrix takes each fixture above through `default`, `trace`, and `partial`
with `distinct 2 10000`, then adds the two 69-declaration rows. Repeat in separate fresh
processes on each production revision. The shared-layout variant uses the three chain
rows in its table. Acquire `/tmp/trufflecel-010-local-measurement.lock` with atomic
`mkdir` and release it in `finally` when running alongside the local diagnostics.
Preserve new output separately; do not replace this historical evidence.
