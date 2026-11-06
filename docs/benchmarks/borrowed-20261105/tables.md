# Measured evaluation

ns/op: median of three process means [minimum–maximum]. JVM uses JMH; native uses
five warmup windows and seven measured windows. Preparation and ingestion are excluded.

## JVM borrowed map comparison

| Workload | Engine | ns/op [process range] | B/op |
| --- | --- | ---: | ---: |
| scalar | truffle-interpreter | 18.6 [18.1–18.7] | 112.0 |
| scalar | truffle-jit | 8.9 [8.5–9.6] | 24.0 |
| scalar | cel-java-optimized | 27.2 [27.2–28.0] | 96.0 |
| scalar-wide | truffle-interpreter | 24.3 [23.6–24.6] | 136.0 |
| scalar-wide | truffle-jit | 15.1 [14.8–15.1] | 48.0 |
| scalar-wide | cel-java-optimized | 516.7 [511.4–521.7] | 2,872.0 |
| proto-policy | truffle-interpreter | 312.6 [311.8–319.4] | 838.0 |
| proto-policy | truffle-jit | 265.2 [261.4–267.4] | 808.0 |
| proto-policy | cel-java-optimized | 1,146.6 [1,096.4–1,209.6] | 3,956.0 |
| proto-repeated | truffle-interpreter | 2,485.0 [2,483.8–2,510.8] | 5,388.1 |
| proto-repeated | truffle-jit | 1,191.6 [1,179.8–1,211.5] | 5,404.1 |
| proto-repeated | cel-java-optimized | 7,387.4 [7,350.0–7,408.1] | 18,280.1 |
| map-regex | truffle-interpreter | 1,800.5 [1,727.7–1,819.2] | 6,710.1 |
| map-regex | truffle-jit | 1,590.0 [1,581.4–1,595.5] | 6,586.1 |
| map-regex | cel-java-optimized | 2,544.8 [2,418.6–2,702.2] | 2,926.0 |
| optimizer-policy | truffle-interpreter | 86.0 [85.3–87.8] | 248.0 |
| optimizer-policy | truffle-jit | 54.2 [53.9–55.6] | 208.0 |
| optimizer-policy | cel-java-optimized | 708.8 [703.9–710.7] | 3,208.0 |

Drift audit (>10%, first two versus last two measured windows):

No flagged process.

## JVM explicit snapshot controls

| Workload | Engine | ns/op [process range] | B/op |
| --- | --- | ---: | ---: |
| scalar | truffle-interpreter | 15.0 [15.0–15.2] | 112.0 |
| scalar | truffle-jit | 5.0 [4.9–5.0] | 24.0 |
| scalar-wide | truffle-interpreter | 16.9 [16.7–16.9] | 112.0 |
| scalar-wide | truffle-jit | 7.7 [7.7–7.9] | 24.0 |
| proto-policy | truffle-interpreter | 296.3 [296.2–296.6] | 814.0 |
| proto-policy | truffle-jit | 248.2 [246.6–249.0] | 784.0 |
| proto-repeated | truffle-interpreter | 2,485.0 [2,468.9–2,496.6] | 5,364.1 |
| proto-repeated | truffle-jit | 1,202.8 [1,194.2–1,222.3] | 5,380.1 |
| map-regex | truffle-interpreter | 740.7 [714.5–761.0] | 2,722.0 |
| map-regex | truffle-jit | 500.5 [495.4–511.2] | 2,610.0 |
| optimizer-policy | truffle-interpreter | 81.6 [80.7–86.5] | 224.0 |
| optimizer-policy | truffle-jit | 47.7 [46.9–47.9] | 184.0 |

Drift audit (>10%, first two versus last two measured windows):

- truffle-interpreter/optimizer-policy: [{'fork': 2, 'fraction': 0.1435539760732587}]

## Native borrowed map comparison (targeted workloads)

| Workload | Engine | ns/op [process range] |
| --- | --- | ---: |
| scalar-wide | truffle-interpreter | 35.7 [35.3–37.1] |
| scalar-wide | truffle-jit | 27.8 [27.6–28.3] |
| scalar-wide | cel-java-optimized | 1,291.9 [1,264.0–1,312.4] |
| proto-policy | truffle-interpreter | 530.5 [530.3–531.4] |
| proto-policy | truffle-jit | 384.3 [384.2–393.1] |
| proto-policy | cel-java-optimized | 2,285.1 [2,225.3–2,291.5] |
| optimizer-policy | truffle-interpreter | 169.6 [169.6–174.2] |
| optimizer-policy | truffle-jit | 84.9 [84.6–85.0] |
| optimizer-policy | cel-java-optimized | 1,638.5 [1,597.7–1,723.6] |

Drift audit (>10%, first two versus last two measured windows):

No flagged process.

## Snapshot control follow-up (15 warmup iterations)

| Workload | Engine | ns/op [process range] | B/op |
| --- | --- | ---: | ---: |
| optimizer-policy | truffle-interpreter | 80.6 [80.0–81.2] | 224.0 |

Drift audit (>10%, first two versus last two measured windows):

No flagged process.
