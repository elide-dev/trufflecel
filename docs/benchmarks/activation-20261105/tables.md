# Activation measurements

Medians of three JMH process means; brackets show the minimum and maximum process means.
All cells use the GC profiler. Allocation is Java heap bytes/op, not RSS or native memory.

## Preparation diagnostics

| Input | Operation | ns/op [process range] | bytes/op |
| --- | --- | ---: | ---: |
| scalar | copyBindings | 8.8 [8.8–8.8] | 160.0 |
| scalar-wide | copyBindings | 472.0 [465.2–482.3] | 3,280.0 |
| scalar | normalizeValues | 1.0 [1.0–1.0] | 0.0 |
| scalar-wide | normalizeValues | 1,483.3 [1,480.9–1,486.8] | 4,368.0 |
| scalar | snapshot | 13.4 [13.3–13.6] | 200.0 |
| scalar-wide | snapshot | 2,400.8 [2,328.3–2,558.2] | 9,704.0 |

These isolated stages are not additive. The scalar copy diagnostic is a generic
map copy; ordinary single-binding evaluation has its own compact fast path.

## Evaluation

| Runtime | Workload | Entry | ns/op [process range] | bytes/op |
| --- | --- | --- | ---: | ---: |
| truffle-interpreter | scalar-wide | evaluateMap | 1,701.7 [1,643.2–1,753.9] | 7,760.1 |
| truffle-interpreter | proto-policy | evaluateMap | 2,396.6 [2,300.2–2,408.9] | 8,944.1 |
| truffle-interpreter | map-regex | evaluateMap | 2,476.7 [2,430.5–2,507.6] | 10,382.1 |
| truffle-jit | scalar-wide | evaluateMap | 1,704.0 [1,700.0–1,722.0] | 7,672.1 |
| truffle-jit | proto-policy | evaluateMap | 2,298.1 [2,275.0–2,336.3] | 8,920.1 |
| truffle-jit | map-regex | evaluateMap | 2,272.0 [2,249.4–2,374.1] | 10,258.1 |
| truffle-interpreter | scalar-wide | evaluateSnapshot | 16.9 [16.9–17.9] | 112.0 |
| truffle-interpreter | proto-policy | evaluateSnapshot | 541.8 [530.9–549.7] | 1,296.0 |
| truffle-interpreter | map-regex | evaluateSnapshot | 711.2 [689.0–712.1] | 2,734.0 |
| truffle-jit | scalar-wide | evaluateSnapshot | 8.2 [8.2–8.5] | 24.0 |
| truffle-jit | proto-policy | evaluateSnapshot | 492.1 [491.2–508.4] | 1,272.0 |
| truffle-jit | map-regex | evaluateSnapshot | 503.7 [503.3–523.5] | 2,610.0 |
| truffle-interpreter | scalar-wide | snapshotAndEvaluate | 2,299.6 [2,295.0–2,325.3] | 9,816.1 |
| truffle-interpreter | proto-policy | snapshotAndEvaluate | 3,016.7 [3,008.5–3,023.3] | 11,000.2 |
| truffle-interpreter | map-regex | snapshotAndEvaluate | 3,213.0 [3,159.8–3,368.4] | 12,426.2 |
| truffle-jit | scalar-wide | snapshotAndEvaluate | 2,338.8 [2,334.8–2,342.1] | 9,728.1 |
| truffle-jit | proto-policy | snapshotAndEvaluate | 3,083.6 [3,057.1–3,093.1] | 10,976.2 |
| truffle-jit | map-regex | snapshotAndEvaluate | 3,067.4 [2,889.2–3,078.2] | 12,314.2 |

Snapshot reuse excludes its one-time preparation; `snapshotAndEvaluate` includes it.
All entries retain the full activation, including unused bindings. No ingestion is timed.

## Warmup audit

No cell exceeds 10% first-two versus last-two measurement drift in any fork.

This audit does not prove that host compilation has ceased or establish a peak rate.
