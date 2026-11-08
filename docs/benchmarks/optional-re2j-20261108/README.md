# Optional RE2/J qualification, 2026-11-08

**Decision: retain compatibility as the default and offer TruffleRegex-only as an
explicit mode with optional RE2/J.** The full matrix distinguishes no matching slowdown from making RE2/J optional.
All twelve old-main versus new-compatibility timing pairs have overlapping observed
fork ranges, with unchanged allocation in every cell. These are descriptive ranges,
not statistical equivalence or a guarantee for arbitrary workloads.

The opt-in retains the original constant-match hot path. All four constant-match
comparisons against new compatibility have overlapping timing ranges and identical
allocation. Dynamic guest-JIT matching improves **8.17% on JVM and 16.68% on native**
against compatibility in the same candidate artifact, with disjoint observed ranges.

| JIT matching | Compatibility → opt-in (ns/op) | Allocation (B/op) | Current RSS (MiB) |
| --- | ---: | ---: | ---: |
| JVM constant | 833.96 → 819.47 | 1,732 → 1,732 | 537.20 → 532.45 |
| Native constant | 1,002.22 → 1,026.65 | 1,664 → 1,664 | 245.57 → 245.65 |
| JVM dynamic | 1,137.71 → 1,044.80 | 1,888 → 1,864 | 491.72 → 547.06 |
| Native dynamic | 1,443.47 → 1,202.71 | 1,812 → 1,812 | 216.16 → 251.84 |

Dynamic interpreter matching is 2.69% faster on JVM and 2.50% faster on native,
while allocating 76 and 80 more bytes per call. Dynamic JIT allocation falls by
24 B/op on JVM and is unchanged on native. The opt-in dynamic JIT path increases
current process RSS by **11.25% on JVM and 16.50% on native**. This is the scoped
TruffleRegex speed/footprint tradeoff, not a memory reduction. Compatibility-mode
regex RSS changes remain within 0.71%; this option does not impose that dynamic
TruffleRegex footprint on compatibility consumers.

The original JVM scalar-JIT control is 12.09 → 12.65 ns/op against old main
(+4.62%, disjoint fork ranges), while native scalar-JIT is 7.43% faster with
overlapping ranges. Within the candidate artifact, opting in changes scalar-JIT
by +0.87% on JVM and −0.73% on native, with overlapping ranges and unchanged
allocation. A separate balanced confirmation runs all six permutations of the three variants,
with one independent fork per variant per block: 18 additional processes with the
same five one-second warmup and five one-second measured iterations. Old main is
12.38 [12.04, 12.79] ns/op, new compatibility 12.51 [12.05, 12.96], and opt-in
12.52 [12.43, 12.74]. All ranges overlap. The opt-in difference within the candidate
is +0.05%; against old main it is +1.12%. This follow-up does not reproduce the
original +4.62% point estimate, and does not prove identical performance. It
supplements rather than replaces the complete original scalar cells.

[All comparisons and observed ranges](tables.md), [every fork mean](summary.json),
raw child results, source reconstruction patches and exact commands are preserved.
Earlier regex reports remain unchanged. This experiment uses GraalVM .27 and
TruffleData 0.9.0; stable TruffleData 0.10.0 still gates release.

The baseline is `dc1dbd1`, with production tree
`246c6c7f3e39c0ee95733fd6621f2a91b0f31ae7`, identical to main `ecb07bf`.
The candidate source is `66e9150`, with production tree
`baea5a15a62907c6f888fa5b60c7566a8e3453c0`. Its artifacts were built at `3b61b5b`;
the only subsequent change in the measured source is README documentation.
[Baseline artifact build](https://app.buildbuddy.io/invocation/3138e5b5-e6c4-43b7-9196-c69e6d198545)
and [candidate artifact build](https://app.buildbuddy.io/invocation/d2f63dc0-a835-4f82-8254-8f08f932b67a)
use the same pinned GraalVM 25.5.5-elide.27 and TruffleData 0.9.0.

Both source revisions contain the common benchmark instrumentation. Timed loops,
fixtures, input inventory and qualification observer are byte-identical. Two
untimed helpers in `Engines.java` differ: the baseline rejects the opt-in property
and reports compatibility policy, while the candidate configures and reports its
selected policy. `harness-overlay.diff` records exactly those changes. All tracked
compiled Java sources, build inputs and benchmark scripts have recorded hashes;
patches reconstruct the entire measured trees from published main `ecb07bf`.

Tables retain all three comparisons: each candidate policy against old main, and
TruffleRegex-only against compatibility in the same candidate artifact.
There are three variants: old main compatibility, candidate compatibility and
candidate TruffleRegex-only. The last two use the exact same artifacts, with a
single policy system-property difference. The opt-in benchmark uses a 100,000-node
parser-tree bound, matching the compatibility accelerator; compatibility retains its
separate 10,000-RE2-instruction limit. Both candidate artifacts still include
RE2/J in their dependency closure. This experiment measures execution-policy costs;
it does not measure deployment-size or RSS savings from omitting the JAR. Separate
packaged JVM and Native Image consumers physically omit RE2/J and force guest
compilation as functional gates.

The unchanged constant `map-regex` fixture uses
`^[a-z]+@example[.]com$`. The additional dynamic fixture evaluates
`labels['env']=='prod' && email.matches(email) && xs.exists(v,v>100)`, with independently
checked outputs true/true/false/false for the four existing inputs. It reuses the
existing inputs without adding a binding or copying maps in the timed loop.
`scalar-wide` is the unrelated control. All fixtures retain four input maps with
69 bindings. These bounded workloads do not establish universal regex performance.

The backend probe must verify actual policy, execution backend, accelerated match
nodes and scoped cache entries. Constant matching uses one TruffleRegex match node
in all variants. Dynamic compatibility uses RE2/J with no scoped regex entries;
dynamic opt-in uses TruffleRegex with two scoped entries. Scalar uses neither.
Every JMH fork emits its proof; every memory/native result includes it. Correct CEL
results alone cannot qualify accidental fallback or the wrong policy.

The atomic host lock covers preflight and the entire matrix. Preflight contains
36 output/backend/allocation-counter smokes and 12 full-count constant allocation
processes. The full matrix contains 216 independent processes: 108 allocation/RSS,
54 JVM timing forks and 54 native timing forks. Allocation finishes before timing.
Each phase shuffles six engine/workload cells with a recorded seed and rotates
which of the three variants runs first. All completed cells remain in the report.

The Linux x86_64 Threadripper PRO 9965WX host uses CPU affinity 2–9, without CPU
reservation, isolation or global governor/THP/NUMA changes. No profilers or artifact
transfers run during timing. Small progress/result summaries are read without running
other measured work; supplemental result summaries use CPU 0. This is a shared observed host, not an isolated lab.

JVM timing uses C2 (`-XX:-UseJVMCICompiler`) and three fresh JMH forks, each with
five one-second warmup and five one-second measured iterations. Native timing uses
three fresh processes with five one-second warmup and seven half-second measured
windows. Tables describe median and observed ranges of fork means, not confidence
intervals or statistical equivalence.

Allocation/RSS uses separate processes, five warmup batches and seven measured
batches of 65,536 evaluations. Current-thread allocation must pass an escaping
1 MiB array calibration and excludes setup, warmup, observer and compiler-thread
work. Both runtimes use 512 MiB initial/maximum heaps; JVM allocation additionally
uses `-Xbatch`, native uses Serial GC. Guest JIT has 2,000 preparation evaluations
and forced last-tier compilation, with strict warning/failure gates enabled.

RSS is sampled while inputs, engine and program remain live. It includes setup,
calibration, compilation, warmup, measured evaluation and untimed observer traversal.
Current `smaps_rollup.Rss` and lifetime `status.VmHWM` are process observations at
uncontrolled GC positions, not retained heap. Changes in AST/cache/probe allocation
can affect RSS even when per-call allocation is unchanged. Combined benchmark
binary sizes are recorded separately from standalone deployment size. This
experiment does not measure startup or first-use regex compilation time.

The full 76-target test suite, complete conformance corpus, packaged consumers and
native JIT/blocklist gates pass in the
[functional qualification](https://app.buildbuddy.io/invocation/945e3264-8b35-47c7-883a-a47515c8f3e7).
Standard source coverage passes the unchanged 99.8% line and branch floors without
exclusions. Stable TruffleData 0.10.0 adoption and final qualification still gate
release; these measurements use the currently pinned 0.9.0 dependency.

The primary matrix began at 21:46:54 UTC. One-minute load was 0.53 at entry and
1.02 at completion; command-boundary samples reached 1.57, including the benchmark's
own execution and guest compilation. Maximum absolute movement from the first two
to last two measured timing windows was **8.20%**. This descriptive screen does not
prove steady state or exclude host interference.

The common deploy JARs grow by 7,249 bytes. The combined native timing executable
shrinks by 64 KiB and the memory executable grows by 64 KiB. Those images contain
all comparison engines and RE2/J; these are not deployment-size savings from
omitting the optional dependency.

After the measurement freeze, `72d05c0` only corrects builder Javadoc placement and
preserves the new native consumer's CI logs. It changes no matching behavior.
[Implementation CI](https://github.com/elide-dev/trufflecel/actions/runs/42527225293)
is green, including native/JIT and cross-engine comparisons.

## Replaying the evidence

`raw.tar.gz` contains all primary and supplementary raw results, JMH logs, memory
checkpoints, exact commands, frozen source manifests, reconstruction patches,
artifact digests/sizes, executed controllers and common benchmark scripts. Large
executables/JARs are omitted; their SHA256s are retained and reconciled against
every measured cell. Staged inputs are checked before and after both experiments.

```sh
sha256sum -c SHA256SUMS
mkdir /tmp/optional-re2j-evidence
tar -xzf raw.tar.gz -C /tmp/optional-re2j-evidence
python3 verify_source.py --raw /tmp/optional-re2j-evidence
python3 analyze.py --raw /tmp/optional-re2j-evidence --output /tmp/optional-re2j-replayed
cmp summary.json /tmp/optional-re2j-replayed/summary.json
cmp tables.md /tmp/optional-re2j-replayed/tables.md
```

Rebuilding requires published base main `ecb07bf` and the archived source patches;
no unpublished benchmark commit is required. The analyzer replays backend/policy
proofs, checks all process inventories, reconciles aggregated samples against raw
JMH/child output and `/proc` snapshots, and verifies source, artifact, runtime,
flag and affinity identities. The supplementary confirmation never replaces or
filters primary measurements.
