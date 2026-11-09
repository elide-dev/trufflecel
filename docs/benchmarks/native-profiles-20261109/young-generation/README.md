# Native young-generation bounds

This is a separate paired qualification of native launch profiles, using the same
TruffleCEL 0.10.0 production tree, GraalVM 25.5.5-elide.27 and TruffleData 0.10.0 as
the [initial compiler-isolation investigation](../README.md). Do not pool the runs.

| Profile | Extra native launch flags |
| --- | --- |
| `default` | None |
| `young-32m` | `-XX:MaxNewSize=32m` |
| `shared-young-32m` | `-XX:MaxNewSize=32m -XX:-CompileInIsolates` |

All profiles retain Serial GC and `-Xms512m -Xmx512m`: the young-generation cap
changes collection sizing, not the total heap limit. Each profile applies the same
flags to all four engines. Three forks, all six fixtures, full ordinary Map inputs
and materialized list results yield 216 fixed-work memory processes and 216 separate
timing processes. These are not the separate Guava ImmutableMap/prepared-input
controls described in the [comparison guide](../../../BENCHMARK_COMPARISON.md).
The `default` profile name denotes this explicit 512 MiB comparison baseline,
not a GraalVM process launched without heap options.

The [complete tables](tables.md) retain every engine, profile, allocation value,
current RSS/PSS, lifetime peak and forced guest-preparation cost. The
[summary](summary.json) preserves process medians and ranges. Charts compare only
TruffleCEL launch settings; matched cel-java rows remain in the tables. The
[chart gallery](../../../images/native-profiles-20261109/README.md) includes
evaluation time, current RSS and peak RSS with the same Elide fonts and tokens
as the release charts.

## Throughput tradeoffs

With compiler isolation retained, the cap changes interpreter time medians by
**−5.1% to +6.1%** and guest-JIT medians by **−4.9% to +1.9%** across the six
fixtures. Negative means less time. The interpreter's wide-activation regression
has disjoint observed process ranges: **33.55 [33.12–33.82] →
35.61 [34.06–35.94] ns/op**. This is not a universal no-cost memory reduction.

Materialized comprehension interpretation changes from **5,022.46 to 5,046.46 ns/op**
(0.5% more time, overlapping ranges). Guest JIT changes from **1,873.49 to
1,782.26 ns/op** (4.9% less time, disjoint observed ranges). These are hot evaluation
measurements; compilation/preparation time remains separate in the tables.

Adding shared compilation changes interpreter medians by **−1.7% to +5.5%** and
guest-JIT medians by **−5.2% to +5.7%**, relative to the same baseline. Its
wide-activation guest-JIT range is **24.02–39.25 ns/op**, versus the baseline's
**23.50–24.28 ns/op**. Three shared-host processes are insufficient to promise
equivalent throughput or tail latency. Choose this setting for its measured memory
tradeoff only after application-level qualification.

## Memory attribution

For materialized comprehension, the cap lowers interpreter current RSS from
**117.28 to 59.85 MiB**. Optimized cel-java with the same cap uses **65.33 MiB**.
The guest-JIT row falls from **194.09 to 139.46 MiB** with isolation retained, or
**86.56 MiB** with shared compilation. Evaluating-thread allocation is unchanged
across all profiles and workloads. This is reduced resident heap capacity and
runtime overhead, not fewer bytes allocated per evaluation.

The combined profile still exceeds optimized cel-java's **65.46 MiB** in that
same profile. The median private-clean mappings are **57.43 versus 38.25 MiB**,
while private-dirty mappings are **26.19 versus 24.59 MiB**. Mapping categories do
not identify an isolate or prove live-object sizes; independent medians also need
not sum exactly. They show why another reduction in per-evaluation allocation
cannot be assumed to eliminate the remaining process-memory difference.

## Applicability

These are opt-in application flags, not new library defaults. The experiment's
heap bounds and fixtures do not determine the right young-generation size for a
larger application or other guest languages. Smaller young generations can increase
GC frequency. Include compilation/warmup and the expected reuse lifetime when
choosing a configuration; the separate default-flag cumulative-cost run does not
establish payback for these profiles.

`young-32m` retains the default compiler-isolation setting. The combined profile
also has the reproduced non-heap management-counter defect tracked in
[elide-dev/graal#227](https://github.com/elide-dev/graal/issues/227). Applications
that require that counter need its upstream fix before adopting shared compilation.
The strict native guest-JIT/blocklist gate passes with default flags, shared
compilation, the cap alone, and the combined profile.

A separate [counter probe](counter-controls/counters.json) runs one process per
profile/engine on the small diagnostic comprehension. The combined guest-JIT
profile reproduces negative non-heap counters after evaluation and GC; the cap
alone, baseline, interpreter and cel-java controls do not fail in this probe.
This establishes the observed configuration boundary, not universal counter safety.
The [probe driver](counter-controls/counter_probe.py) and source/binary identities
are retained. Its explicit-GC checkpoints are not pooled with performance results.

## Reproduction and identities

The driver is `tools/bench/native_profiles.py` at `3f9d5ae`. Run both phases with
`--profiles default young-32m shared-young-32m`, using the [parent report's commands](../README.md#reproduce)
and new output directories. Complete source manifests, binary/driver hashes, command
arrays, CPU affinity, host/kernel and load observations are in the two metadata files.
The evaluation binaries are unchanged from the first investigation. Measurements
ran serially on the same shared Threadripper host, without exclusive CPU reservation
or thermal control. Ranges are observations, not confidence intervals.
The engine/workload/fork schedule is shuffled with a fixed seed. Profile order
alternates forward/reverse within each cell; with three profiles the middle
`young-32m` profile stays in the middle, so profile placement is not fully randomized.

Regenerate tables from retained records:

```sh
python3 tools/bench/native_profile_report.py \
  docs/benchmarks/native-profiles-20261109/young-generation --evaluation-only
```
