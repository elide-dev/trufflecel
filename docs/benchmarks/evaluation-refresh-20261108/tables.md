## Evaluation time (ns/op)

| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |
| --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar | 20.81 | 8.94 | 27.54 | 27.15 |
| jvm | scalar-wide | 28.36 | 12.33 | 532.31 | 520.53 |
| jvm | proto-policy | 196.66 | 129.40 | 1,211.50 | 1,151.95 |
| jvm | proto-repeated | 2,822.42 | 1,718.16 | 7,544.58 | 7,867.68 |
| jvm | map-regex | 1,140.50 | 947.94 | 2,643.45 | 2,655.15 |
| jvm | optimizer-policy | 97.22 | 55.88 | 778.17 | 627.65 |
| svm | scalar | 28.61 | 18.83 | 81.95 | 79.28 |
| svm | scalar-wide | 33.06 | 24.79 | 1,296.99 | 1,264.91 |
| svm | proto-policy | 301.28 | 202.89 | 2,473.47 | 2,243.73 |
| svm | proto-repeated | 4,662.91 | 1,803.56 | 12,386.94 | 12,348.70 |
| svm | map-regex | 1,589.70 | 1,230.35 | 6,275.87 | 6,213.59 |
| svm | optimizer-policy | 151.54 | 87.27 | 1,686.24 | 1,566.44 |

## Current-thread allocation (B/op)

| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |
| --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar | 96.00 | 24.00 | 96.00 | 96.00 |
| jvm | scalar-wide | 120.00 | 48.00 | 2,936.00 | 2,936.00 |
| jvm | proto-policy | 548.00 | 526.00 | 4,158.00 | 4,062.00 |
| jvm | proto-repeated | 5,444.00 | 5,436.00 | 18,344.00 | 18,344.00 |
| jvm | map-regex | 1,822.00 | 1,726.00 | 3,054.00 | 3,054.00 |
| jvm | optimizer-policy | 280.00 | 264.00 | 3,336.00 | 3,272.00 |
| svm | scalar | 104.00 | 40.00 | 240.00 | 240.00 |
| svm | scalar-wide | 104.00 | 40.00 | 3,504.00 | 3,504.00 |
| svm | proto-policy | 360.00 | 340.00 | 4,794.00 | 4,598.00 |
| svm | proto-repeated | 4,752.00 | 4,720.00 | 15,704.00 | 15,704.00 |
| svm | map-regex | 1,740.00 | 1,660.00 | 6,176.00 | 6,176.00 |
| svm | optimizer-policy | 184.00 | 168.00 | 3,912.00 | 3,804.00 |

## Current RSS after fixed sampled work (MiB)

| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |
| --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar | 172.68 | 202.01 | 149.49 | 153.24 |
| jvm | scalar-wide | 189.33 | 202.01 | 385.39 | 387.28 |
| jvm | proto-policy | 335.25 | 421.49 | 396.70 | 398.07 |
| jvm | proto-repeated | 419.97 | 494.04 | 390.04 | 392.94 |
| jvm | map-regex | 419.20 | 490.48 | 387.46 | 390.40 |
| jvm | optimizer-policy | 311.61 | 385.03 | 390.97 | 398.71 |
| svm | scalar | 106.27 | 94.50 | 154.92 | 155.07 |
| svm | scalar-wide | 106.34 | 93.95 | 123.48 | 123.70 |
| svm | proto-policy | 156.97 | 239.03 | 109.23 | 114.18 |
| svm | proto-repeated | 106.53 | 176.08 | 56.20 | 56.88 |
| svm | map-regex | 142.43 | 196.58 | 82.38 | 82.61 |
| svm | optimizer-policy | 156.55 | 239.87 | 124.34 | 126.30 |

## Lifetime peak RSS through fixed sampled work (MiB)

| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |
| --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar | 172.68 | 202.01 | 149.49 | 153.24 |
| jvm | scalar-wide | 189.33 | 202.01 | 385.39 | 387.28 |
| jvm | proto-policy | 335.25 | 421.49 | 396.70 | 398.07 |
| jvm | proto-repeated | 419.97 | 494.04 | 390.04 | 392.94 |
| jvm | map-regex | 419.20 | 490.48 | 387.46 | 390.40 |
| jvm | optimizer-policy | 311.61 | 385.03 | 390.97 | 398.71 |
| svm | scalar | 106.27 | 94.50 | 154.92 | 155.07 |
| svm | scalar-wide | 106.34 | 93.95 | 151.98 | 152.74 |
| svm | proto-policy | 156.97 | 239.03 | 156.62 | 157.18 |
| svm | proto-repeated | 154.96 | 221.64 | 155.95 | 156.29 |
| svm | map-regex | 154.07 | 208.65 | 152.69 | 152.87 |
| svm | optimizer-policy | 156.55 | 239.87 | 155.71 | 155.90 |
