# TruffleCEL 0.11.0 guarded measurements on unclemax

This is a fresh cross-engine comparison of the published TruffleCEL 0.11.0 source,
TruffleData 0.11.0, Elide GraalVM 25.5.5-elide.28 and cel-java 0.15.0. It is not a
controlled before/after experiment attributing changes to one dependency. The
[0.10.0 report](../release-0.10.0-20261109/README.md) and its charts remain unchanged.

## Results

This guarded round ran from 22:59:12 to 23:35:55 UTC and completed every phase.
The guard made 439 measurement-stage observations at five-second intervals; none
recorded competing compute work, and the highest one-minute load was 1.97.
No timing cell has a fork with more than 10% first-two/last-two window drift.
[host-audit.json](host-audit.json) retains per-phase load ranges and the guard
summary. These observations do not prove the absence of all interference.

[Complete tables](tables.md) and [all fork values](summary.json) retain every cell.
The following counts compare each TruffleCEL mode against the lower cel-java
planner aggregate separately for each fixture, runtime and metric.

| Metric | Interpreter lower / tied / higher | Guest JIT lower / tied / higher |
| --- | ---: | ---: |
| Hot evaluation time | 12 / 0 / 0 | 12 / 0 / 0 |
| Current-thread allocation per evaluation | 11 / 1 / 0 | 12 / 0 / 0 |
| Current whole-process RSS | 5 / 0 / 7 | 3 / 0 / 9 |
| Lifetime peak whole-process RSS | 6 / 0 / 6 | 3 / 0 / 9 |

Observed overlapping fork ranges (out of 12 per mode): allocation, interpreter 1
(the JVM scalar tie at 96 B/op); peak RSS, interpreter 2. No time or current-RSS
comparison has overlapping ranges.

The counts are identical to the [shared-host attempt](../release-0.11.0-20261110-shared/README.md);
individual values differ, and the two rounds are not pooled.

JVM scalar-wide cel-java allocation falls from 2,936 to 72 B/op with prepared
immutable inputs, versus TruffleCEL interpreter's 120 B/op in the main suite;
time falls from 517–524 to 25–26 ns/op. Caller preparation is excluded; both
planners and both runtimes are retained in the separate control tables.

[Prepared-input controls](prepared-inputs.md) · [Ingestion allocation](ingestion.md) ·
[Benchmark cards](../../images/benchmark-0.11.0/README.md)

## Source and host

The measured source is release tag `v0.11.0`, commit
`620a961b4020dd1ea7585eda98778949113cbc70`. [source.json](source.json) records all
158 tracked production, dependency, build and harness input hashes, the exact
Bazel command, and the GraalVM archive checksum. [build.json](build.json) records
the successful BuildBuddy invocation and the six artifact hashes and sizes.
Artifacts were built on Linux RBE and copied to unclemax; all six transfer hashes
and the downloaded runtime archive checksum were verified before execution.

Unclemax is a Linux x86-64 AMD Ryzen Threadripper PRO 9965WX host with 48 logical
CPUs. Measurements run serially with affinity restricted to CPUs 2–9. The first
round encountered later competing work and is retained in the
[shared-host attempt](../release-0.11.0-20261110-shared/README.md), with all samples
and its host audit. Three later [interrupted attempts](../release-0.11.0-20261110-interrupted/README.md)
are also retained separately: two stopped by the host guard and one terminated by
an unattributed SIGTERM. This full confirmation round waited for 180 seconds
without detected competing compute processes, then
sampled process and load information every five seconds. The guard aborts only
our entire round if competing work appears. Its script and observations are
retained in the raw archive. No CPU isolation, exclusive machine reservation, governor changes,
NUMA tuning, or THP changes are claimed. The existing shared TruffleCEL measurement
lock prevents overlapping runs that honor that lock.

Each phase records CPU/kernel/governor information, observed load, exact commands,
Java banner and flags, runtime-file hashes, source identity, and artifact hashes.
The validator requires the same host, kernel, CPU affinity, runtime and artifacts
across the six evaluation/control phases. A low observed load does not prove
absence of all interference.

## Protocol

The ordinary-map matrix covers six fixtures: scalar, wide activation, protobuf
policy, materialized repeated protobuf lists, maps/regex, and optimizer policy.
Every fixture checks four input variants. Scalar uses one binding; the other five
retain all 69 bindings. The repeated-list result contains all 77–80 output elements
from 128 inputs. Input construction and protobuf ingestion are outside hot timing.

TruffleCEL interpreter and guest JIT run against both cel-java default and
constant-folding/CSE planners. Interpreter comparisons lead the presentation.
Guest compilation is forced before timing and its cost is excluded. Every relevant
fork validates the actual regex backend, compilation mode and last-tier state;
compiler failures and compiler performance warnings fail qualification.

JVM timing uses JMH 1.37, three forks, five one-second warmups and five one-second
measurements per cell. The aggregate is the mean of three fork means. JVM host
compilation uses C2 (`-XX:-UseJVMCICompiler`); Truffle guest compilation remains
available for the explicitly separate guest-JIT mode. Native timing uses three
fresh processes, five warmup and seven measurement windows; the aggregate is the
median of three process medians. Timing has no allocation profiler attached.
Both runtimes use 512 MiB initial/maximum heaps; native uses Serial GC.

Memory runs use separate fresh processes with five warmup and seven measured
batches of 65,536 evaluations. A calibrated per-thread allocation counter excludes
compiler/background threads. Current RSS comes from `smaps_rollup.Rss`; lifetime
peak RSS comes from `status.VmHWM`, with program and inputs still live. Guest JIT
performs 2,000 additional preparation calls and forced compilation. RSS includes
setup, compilation, calibration and sampled work at an uncontrolled GC position.
It is neither retained heap nor equal total work from process launch. The combined
comparison artifacts do not establish standalone deployment footprint.

The separate [prepared-input controls](prepared-inputs.md) compare ordinary maps
and caller-prepared Guava ImmutableMaps for scalar-wide and protobuf-policy,
with both cel-java planners and both runtimes. All 69 bindings remain present,
the captured-array lookup is matched, and caller-side preparation is excluded.
These controls do not contribute to the ordinary-map comparison counts.

The separate [ingestion allocation table](ingestion.md) covers ordinary protobuf
messages, a 64 KiB unknown payload and MessageSet normalization. Each cell has
three fresh processes and seven fixed-work windows per operation. Correctness and
allocation-counter calibration are required. These are allocation observations,
not ingestion throughput or retained-memory claims.

## Limits and reproduction

The complete round requests 288 primary evaluation processes, 96 prepared-input
control processes, and 18 ingestion processes. Three-fork ranges and point estimates
are descriptive, not significance tests. Time-based warmup is not convergence
proof. No alternate HotSpot distribution, fresh startup/payback measurement,
retained-heap study or separate production-image size comparison is included.
Historical reports retain their original dependency versions and evidence gaps.
The separate CI comparison uses another host and protocol and is not pooled here.

[raw.tar.gz](raw.tar.gz) retains all raw windows, logs, process-memory snapshots,
metadata, run scripts, driver copies, the guard script, its status and every
five-second host observation. [measured-source.tar.gz](measured-source.tar.gz)
preserves the allowlisted source inputs without credentials or unrelated files.
[SHA256SUMS](SHA256SUMS) covers the retained report. Large benchmark executables and
the GraalVM archive are omitted; their verified hashes and build command are retained.

```sh
report=docs/benchmarks/release-0.11.0-20261110
mkdir /tmp/trufflecel-011-raw /tmp/trufflecel-011-source
tar -xzf "$report/raw.tar.gz" -C /tmp/trufflecel-011-raw
tar -xzf "$report/measured-source.tar.gz" -C /tmp/trufflecel-011-source
python3 "$report/verify.py" /tmp/trufflecel-011-raw --source-root /tmp/trufflecel-011-source
python3 "$report/summarize_evaluation.py" --raw /tmp/trufflecel-011-raw/evaluation --output "$report"
python3 "$report/summarize_controls.py" /tmp/trufflecel-011-raw "$report"
python3 "$report/audit_host.py" /tmp/trufflecel-011-raw "$report"
# With tools/bench/charts/requirements.txt installed in an isolated environment:
python3 tools/bench/charts/render_cards.py --report "$report" \
  --output /tmp/trufflecel-011-cards --measured 2026-11-10
```
