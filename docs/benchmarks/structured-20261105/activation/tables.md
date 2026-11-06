# Activation measurements

Medians of three JMH process means; brackets show the minimum and maximum process means.
All cells use the GC profiler. Allocation is Java heap bytes/op, not RSS or native memory.

## Preparation diagnostics

| Input | Operation | ns/op [process range] | bytes/op |
| --- | --- | ---: | ---: |
| scalar | copyBindings | 8.9 [8.8–8.9] | 160.0 |
| scalar-wide | copyBindings | 475.2 [472.8–476.0] | 3,280.0 |
| scalar | normalizeValues | 1.0 [1.0–1.1] | 0.0 |
| scalar-wide | normalizeValues | 1,454.3 [1,453.2–1,481.5] | 4,368.0 |
| scalar | snapshot | 13.4 [13.4–13.5] | 200.0 |
| scalar-wide | snapshot | 2,440.6 [2,427.9–2,476.2] | 9,704.0 |

These isolated stages are not additive. The scalar copy diagnostic is a generic
map copy; ordinary single-binding evaluation has its own compact fast path.

## Evaluation

| Runtime | Workload | Entry | ns/op [process range] | bytes/op |
| --- | --- | --- | ---: | ---: |
| truffle-interpreter | scalar | evaluateMap | 18.0 [18.0–18.2] | 112.0 |
| truffle-interpreter | scalar-wide | evaluateMap | 1,730.3 [1,729.9–1,754.8] | 7,760.1 |
| truffle-interpreter | proto-policy | evaluateMap | 2,290.8 [2,283.4–2,387.0] | 8,944.1 |
| truffle-interpreter | proto-repeated | evaluateMap | 4,371.4 [4,230.2–4,425.6] | 13,012.2 |
| truffle-interpreter | map-regex | evaluateMap | 2,500.1 [2,485.4–2,637.4] | 10,382.1 |
| truffle-interpreter | optimizer-policy | evaluateMap | 1,938.9 [1,918.3–1,943.4] | 8,024.1 |
| truffle-jit | scalar | evaluateMap | 8.5 [8.2–8.9] | 24.0 |
| truffle-jit | scalar-wide | evaluateMap | 1,717.5 [1,705.6–1,718.3] | 7,672.1 |
| truffle-jit | proto-policy | evaluateMap | 2,338.6 [2,237.0–2,345.5] | 8,920.1 |
| truffle-jit | proto-repeated | evaluateMap | 3,091.2 [2,904.4–3,103.5] | 13,028.2 |
| truffle-jit | map-regex | evaluateMap | 2,277.5 [2,269.2–2,303.4] | 10,258.1 |
| truffle-jit | optimizer-policy | evaluateMap | 1,837.4 [1,817.5–1,890.9] | 7,984.1 |
| truffle-interpreter | scalar | evaluateSnapshot | 15.3 [15.0–15.8] | 112.0 |
| truffle-interpreter | scalar-wide | evaluateSnapshot | 16.9 [16.7–16.9] | 112.0 |
| truffle-interpreter | proto-policy | evaluateSnapshot | 527.8 [511.8–545.9] | 1,296.0 |
| truffle-interpreter | proto-repeated | evaluateSnapshot | 2,492.5 [2,485.1–2,537.3] | 5,364.1 |
| truffle-interpreter | map-regex | evaluateSnapshot | 718.1 [708.3–725.7] | 2,722.0 |
| truffle-interpreter | optimizer-policy | evaluateSnapshot | 161.8 [156.2–166.2] | 376.0 |
| truffle-jit | scalar | evaluateSnapshot | 5.0 [4.9–5.0] | 24.0 |
| truffle-jit | scalar-wide | evaluateSnapshot | 8.1 [8.0–8.2] | 24.0 |
| truffle-jit | proto-policy | evaluateSnapshot | 475.2 [473.4–483.9] | 1,272.0 |
| truffle-jit | proto-repeated | evaluateSnapshot | 1,195.7 [1,189.0–1,197.3] | 5,380.1 |
| truffle-jit | map-regex | evaluateSnapshot | 507.7 [501.4–515.4] | 2,610.0 |
| truffle-jit | optimizer-policy | evaluateSnapshot | 119.5 [111.9–126.4] | 336.0 |
| truffle-interpreter | scalar | snapshotAndEvaluate | 29.6 [29.3–29.7] | 288.0 |
| truffle-interpreter | scalar-wide | snapshotAndEvaluate | 2,389.8 [2,330.6–2,410.2] | 9,816.1 |
| truffle-interpreter | proto-policy | snapshotAndEvaluate | 2,935.6 [2,934.8–2,946.4] | 10,976.1 |
| truffle-interpreter | proto-repeated | snapshotAndEvaluate | 5,075.1 [5,028.9–5,203.3] | 15,068.3 |
| truffle-interpreter | map-regex | snapshotAndEvaluate | 3,106.9 [3,083.5–3,127.1] | 12,402.2 |
| truffle-interpreter | optimizer-policy | snapshotAndEvaluate | 2,572.5 [2,512.2–2,767.8] | 10,056.1 |
| truffle-jit | scalar | snapshotAndEvaluate | 19.1 [18.9–19.5] | 200.0 |
| truffle-jit | scalar-wide | snapshotAndEvaluate | 2,308.1 [2,307.5–2,316.5] | 9,728.1 |
| truffle-jit | proto-policy | snapshotAndEvaluate | 3,139.2 [3,104.5–3,153.3] | 10,976.2 |
| truffle-jit | proto-repeated | snapshotAndEvaluate | 3,570.6 [3,554.3–3,631.5] | 15,084.2 |
| truffle-jit | map-regex | snapshotAndEvaluate | 2,930.0 [2,929.8–3,084.2] | 12,314.1 |
| truffle-jit | optimizer-policy | snapshotAndEvaluate | 2,563.6 [2,559.7–2,605.8] | 10,040.1 |

Snapshot reuse excludes its one-time preparation; `snapshotAndEvaluate` includes it.
All entries retain the full activation, including unused bindings. No ingestion is timed.

## Warmup audit

No cell exceeds 10% first-two versus last-two measurement drift in any fork.

This audit does not prove that host compilation has ceased or establish a peak rate.
