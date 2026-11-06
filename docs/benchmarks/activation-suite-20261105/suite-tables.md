# Snapshot reuse across all six workloads

These disjoint runs use the same benchmark JAR, JVM arguments, host, kernel and CPU affinity.
Values are medians of three process means; ranges and raw samples remain in each run.
Preparation is excluded only from reused-snapshot evaluation. No ingestion is timed.

## Time

| Runtime | Workload | Map (ns/op) | Reused snapshot (ns/op) | New snapshot + evaluation (ns/op) |
| --- | --- | ---: | ---: | ---: |
| truffle-interpreter | scalar | 18.4 | 15.1 | 29.7 |
| truffle-interpreter | scalar-wide | 1,701.7 | 16.9 | 2,299.6 |
| truffle-interpreter | proto-policy | 2,396.6 | 541.8 | 3,016.7 |
| truffle-interpreter | proto-repeated | 5,891.0 | 3,922.4 | 6,485.4 |
| truffle-interpreter | map-regex | 2,476.7 | 711.2 | 3,213.0 |
| truffle-interpreter | optimizer-policy | 1,930.2 | 182.4 | 2,593.0 |
| truffle-jit | scalar | 8.4 | 4.9 | 18.9 |
| truffle-jit | scalar-wide | 1,704.0 | 8.2 | 2,338.8 |
| truffle-jit | proto-policy | 2,298.1 | 492.1 | 3,083.6 |
| truffle-jit | proto-repeated | 4,661.5 | 2,720.0 | 5,331.9 |
| truffle-jit | map-regex | 2,272.0 | 503.7 | 3,067.4 |
| truffle-jit | optimizer-policy | 1,917.5 | 160.3 | 2,616.2 |

## Allocated Java heap

| Runtime | Workload | Map (bytes/op) | Reused snapshot (bytes/op) | New snapshot + evaluation (bytes/op) |
| --- | --- | ---: | ---: | ---: |
| truffle-interpreter | scalar | 112.0 | 112.0 | 288.0 |
| truffle-interpreter | scalar-wide | 7,760.1 | 112.0 | 9,816.1 |
| truffle-interpreter | proto-policy | 8,944.1 | 1,296.0 | 11,000.2 |
| truffle-interpreter | proto-repeated | 18,984.3 | 11,336.2 | 21,016.3 |
| truffle-interpreter | map-regex | 10,382.1 | 2,734.0 | 12,426.2 |
| truffle-interpreter | optimizer-policy | 8,024.1 | 376.0 | 10,080.1 |
| truffle-jit | scalar | 24.0 | 24.0 | 200.0 |
| truffle-jit | scalar-wide | 7,672.1 | 24.0 | 9,728.1 |
| truffle-jit | proto-policy | 8,920.1 | 1,272.0 | 10,976.2 |
| truffle-jit | proto-repeated | 18,992.2 | 11,344.1 | 21,048.3 |
| truffle-jit | map-regex | 10,258.1 | 2,610.0 | 12,314.2 |
| truffle-jit | optimizer-policy | 8,020.1 | 336.0 | 10,040.1 |

Allocation is measured with JMH’s GC profiler; it is not RSS or native memory.

## Runs

- `activation-20261105`: map-regex, proto-policy, scalar-wide.
- `activation-suite-20261105`: optimizer-policy, proto-repeated, scalar.
