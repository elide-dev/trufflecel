# Activation measurements

Medians of three JMH process means; brackets show the minimum and maximum process means.
All cells use the GC profiler. Allocation is Java heap bytes/op, not RSS or native memory.

## Preparation diagnostics

| Input | Operation | ns/op [process range] | bytes/op |
| --- | --- | ---: | ---: |
| scalar | copyBindings | 9.0 [9.0–9.0] | 160.0 |
| scalar-wide | copyBindings | 479.6 [470.4–493.7] | 3,280.0 |
| scalar | normalizeValues | 1.0 [1.0–1.0] | 0.0 |
| scalar-wide | normalizeValues | 1,460.8 [1,441.9–1,480.4] | 4,368.0 |
| scalar | snapshot | 13.5 [13.5–13.5] | 200.0 |
| scalar-wide | snapshot | 2,420.4 [2,397.7–2,449.0] | 9,704.0 |

These isolated stages are not additive. The scalar copy diagnostic is a generic
map copy; ordinary single-binding evaluation has its own compact fast path.

## Evaluation

| Runtime | Workload | Entry | ns/op [process range] | bytes/op |
| --- | --- | --- | ---: | ---: |
| truffle-interpreter | scalar | evaluateMap | 18.1 [17.9–18.5] | 112.0 |
| truffle-interpreter | scalar-wide | evaluateMap | 1,726.5 [1,714.0–1,727.7] | 7,760.1 |
| truffle-interpreter | proto-policy | evaluateMap | 2,187.1 [2,076.9–2,215.7] | 8,462.1 |
| truffle-interpreter | proto-repeated | evaluateMap | 4,273.4 [4,202.4–4,425.7] | 13,012.2 |
| truffle-interpreter | map-regex | evaluateMap | 2,487.9 [2,479.3–2,516.3] | 10,382.1 |
| truffle-interpreter | optimizer-policy | evaluateMap | 1,905.4 [1,796.0–1,915.1] | 7,872.1 |
| truffle-jit | scalar | evaluateMap | 8.3 [8.3–8.5] | 24.0 |
| truffle-jit | scalar-wide | evaluateMap | 1,712.8 [1,706.9–1,736.9] | 7,672.1 |
| truffle-jit | proto-policy | evaluateMap | 2,232.3 [2,017.6–2,287.6] | 8,432.1 |
| truffle-jit | proto-repeated | evaluateMap | 3,091.3 [3,062.1–3,125.3] | 13,028.2 |
| truffle-jit | map-regex | evaluateMap | 2,274.0 [2,268.3–2,285.6] | 10,258.1 |
| truffle-jit | optimizer-policy | evaluateMap | 1,925.7 [1,786.3–1,928.0] | 7,832.1 |
| truffle-interpreter | scalar | evaluateSnapshot | 14.9 [14.7–15.5] | 112.0 |
| truffle-interpreter | scalar-wide | evaluateSnapshot | 17.2 [17.1–17.4] | 112.0 |
| truffle-interpreter | proto-policy | evaluateSnapshot | 305.2 [302.9–318.3] | 814.0 |
| truffle-interpreter | proto-repeated | evaluateSnapshot | 2,512.5 [2,490.1–2,558.7] | 5,364.1 |
| truffle-interpreter | map-regex | evaluateSnapshot | 717.4 [715.5–722.9] | 2,734.0 |
| truffle-interpreter | optimizer-policy | evaluateSnapshot | 80.9 [80.6–82.0] | 224.0 |
| truffle-jit | scalar | evaluateSnapshot | 5.0 [4.9–5.0] | 24.0 |
| truffle-jit | scalar-wide | evaluateSnapshot | 8.2 [8.1–8.4] | 24.0 |
| truffle-jit | proto-policy | evaluateSnapshot | 250.2 [249.6–251.7] | 784.0 |
| truffle-jit | proto-repeated | evaluateSnapshot | 1,199.4 [1,182.1–1,209.6] | 5,380.1 |
| truffle-jit | map-regex | evaluateSnapshot | 501.4 [500.5–510.7] | 2,610.0 |
| truffle-jit | optimizer-policy | evaluateSnapshot | 48.0 [47.8–51.1] | 184.0 |
| truffle-interpreter | scalar | snapshotAndEvaluate | 29.8 [29.1–30.2] | 288.0 |
| truffle-interpreter | scalar-wide | snapshotAndEvaluate | 2,321.7 [2,310.0–2,371.9] | 9,816.1 |
| truffle-interpreter | proto-policy | snapshotAndEvaluate | 2,872.8 [2,701.8–3,055.6] | 10,518.1 |
| truffle-interpreter | proto-repeated | snapshotAndEvaluate | 4,915.4 [4,891.8–5,067.6] | 15,044.2 |
| truffle-interpreter | map-regex | snapshotAndEvaluate | 3,093.3 [3,057.9–3,132.3] | 12,402.2 |
| truffle-interpreter | optimizer-policy | snapshotAndEvaluate | 2,443.5 [2,395.4–2,585.5] | 9,904.1 |
| truffle-jit | scalar | snapshotAndEvaluate | 19.3 [18.9–19.4] | 200.0 |
| truffle-jit | scalar-wide | snapshotAndEvaluate | 2,383.8 [2,323.6–2,461.0] | 9,728.1 |
| truffle-jit | proto-policy | snapshotAndEvaluate | 2,822.1 [2,755.0–3,007.8] | 10,488.1 |
| truffle-jit | proto-repeated | snapshotAndEvaluate | 3,586.8 [3,582.8–3,720.8] | 15,084.2 |
| truffle-jit | map-regex | snapshotAndEvaluate | 2,945.4 [2,944.6–3,056.1] | 12,326.1 |
| truffle-jit | optimizer-policy | snapshotAndEvaluate | 2,464.5 [2,409.9–2,581.8] | 9,888.1 |

Snapshot reuse excludes its one-time preparation; `snapshotAndEvaluate` includes it.
All entries retain the full activation, including unused bindings. No ingestion is timed.

## Warmup audit

No cell exceeds 10% first-two versus last-two measurement drift in any fork.

This audit does not prove that host compilation has ceased or establish a peak rate.
