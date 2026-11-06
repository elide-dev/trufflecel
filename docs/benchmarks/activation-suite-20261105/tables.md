# Activation measurements

Medians of three JMH process means; brackets show the minimum and maximum process means.
All cells use the GC profiler. Allocation is Java heap bytes/op, not RSS or native memory.

## Preparation diagnostics

| Input | Operation | ns/op [process range] | bytes/op |
| --- | --- | ---: | ---: |
| scalar | copyBindings | 8.8 [8.8–9.0] | 160.0 |
| scalar-wide | copyBindings | 474.9 [471.0–481.2] | 3,280.0 |
| scalar | normalizeValues | 1.0 [1.0–1.0] | 0.0 |
| scalar-wide | normalizeValues | 1,485.1 [1,484.7–1,485.5] | 4,368.0 |
| scalar | snapshot | 13.4 [13.3–13.5] | 200.0 |
| scalar-wide | snapshot | 2,446.7 [2,429.1–2,461.7] | 9,704.0 |

These isolated stages are not additive. The scalar copy diagnostic is a generic
map copy; ordinary single-binding evaluation has its own compact fast path.

## Evaluation

| Runtime | Workload | Entry | ns/op [process range] | bytes/op |
| --- | --- | --- | ---: | ---: |
| truffle-interpreter | scalar | evaluateMap | 18.4 [18.2–18.9] | 112.0 |
| truffle-interpreter | proto-repeated | evaluateMap | 5,891.0 [5,886.4–5,900.4] | 18,984.3 |
| truffle-interpreter | optimizer-policy | evaluateMap | 1,930.2 [1,922.7–1,979.6] | 8,024.1 |
| truffle-jit | scalar | evaluateMap | 8.4 [8.4–8.5] | 24.0 |
| truffle-jit | proto-repeated | evaluateMap | 4,661.5 [4,521.8–4,730.7] | 18,992.2 |
| truffle-jit | optimizer-policy | evaluateMap | 1,917.5 [1,906.5–1,923.8] | 8,020.1 |
| truffle-interpreter | scalar | evaluateSnapshot | 15.1 [15.0–15.1] | 112.0 |
| truffle-interpreter | proto-repeated | evaluateSnapshot | 3,922.4 [3,921.2–3,942.9] | 11,336.2 |
| truffle-interpreter | optimizer-policy | evaluateSnapshot | 182.4 [182.4–194.6] | 376.0 |
| truffle-jit | scalar | evaluateSnapshot | 4.9 [4.9–5.0] | 24.0 |
| truffle-jit | proto-repeated | evaluateSnapshot | 2,720.0 [2,711.1–2,797.2] | 11,344.1 |
| truffle-jit | optimizer-policy | evaluateSnapshot | 160.3 [157.1–162.1] | 336.0 |
| truffle-interpreter | scalar | snapshotAndEvaluate | 29.7 [29.5–29.9] | 288.0 |
| truffle-interpreter | proto-repeated | snapshotAndEvaluate | 6,485.4 [6,451.2–6,500.5] | 21,016.3 |
| truffle-interpreter | optimizer-policy | snapshotAndEvaluate | 2,593.0 [2,588.8–2,639.0] | 10,080.1 |
| truffle-jit | scalar | snapshotAndEvaluate | 18.9 [18.9–19.0] | 200.0 |
| truffle-jit | proto-repeated | snapshotAndEvaluate | 5,331.9 [5,225.7–5,363.6] | 21,048.3 |
| truffle-jit | optimizer-policy | snapshotAndEvaluate | 2,616.2 [2,602.2–2,618.4] | 10,040.1 |

Snapshot reuse excludes its one-time preparation; `snapshotAndEvaluate` includes it.
All entries retain the full activation, including unused bindings. No ingestion is timed.

## Warmup audit

No cell exceeds 10% first-two versus last-two measurement drift in any fork.

This audit does not prove that host compilation has ceased or establish a peak rate.
