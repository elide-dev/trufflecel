# TruffleCEL 0.10.0 benchmark charts

Shorter bars are better. Each card shows the same fixture on the same runtime,
with a linear scale starting at zero. Scales differ between cards. Every value
is printed beside its bar; the interpreter and guest-JIT comparisons each use
the lower cel-java default/optimized aggregate for that metric. Green text means
faster or less; amber means slower or more. The words carry the same information
without relying on color. White ticks show observed three-fork ranges, not
confidence intervals or significance tests.

These charts restyle the [November 9 release measurements](../../benchmarks/release-0.10.0-20261109/README.md).
They do not change or rerun the experiment. The original release charts, data,
checksums, and release assets remain preserved. The separate three-expression
CI suite is not pooled into these six-fixture charts.

| Metric | TruffleCEL lower | Tied | Higher | Downloads |
| --- | ---: | ---: | ---: | --- |
| Evaluation time | 24 | 0 | 0 | [SVG](evaluation-time.svg) · [PNG](evaluation-time.png) |
| Allocation per evaluation | 23 | 1 | 0 | [SVG](evaluation-allocation.svg) · [PNG](evaluation-allocation.png) |
| Current process RSS | 9 | 0 | 15 | [SVG](evaluation-rss.svg) · [PNG](evaluation-rss.png) |

Counts compare both TruffleCEL modes independently across six fixtures and two
runtimes. This is not a universal engine ranking. In particular, these ordinary
Map inputs include cel-java's map-copying cost. The separate
[prepared-input controls](../../benchmarks/release-0.10.0-20261109/prepared-inputs.md)
reduce that cost substantially: JVM scalar-wide cel-java allocation is 72 B/op
with prepared immutable inputs, below TruffleCEL interpreter's 120 B/op.
Process RSS includes setup and compilation and is neither retained heap nor a
standalone deployment-footprint measurement. Hot timing excludes preparation and
warmup, so it does not describe first-evaluation latency.

The [complete tables](../../benchmarks/release-0.10.0-20261109/tables.md) provide a
text alternative with all values and ranges. The [renderer and brand provenance](../../../tools/bench/charts/README.md)
document the exact fonts, tokens, source data and reproduction command.

## Evaluation time

![TruffleCEL evaluation time is lower in all 24 ordinary-map comparisons. Both engine modes and both cel-java planners are shown with exact values and observed ranges.](evaluation-time.svg)

## Allocation per evaluation

![TruffleCEL allocates less in 23 of 24 ordinary-map comparisons; JVM scalar interpreter ties at 96 bytes per operation.](evaluation-allocation.svg)

## Process memory

![TruffleCEL current process RSS is lower in 9 of 24 comparisons and higher in 15. Amber labels explicitly identify memory increases, including native guest-JIT protobuf and regex workloads.](evaluation-rss.svg)
