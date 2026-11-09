## Evaluation time (ns/op)

| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |
| --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar | 21.24 | 9.07 | 27.37 | 27.37 |
| jvm | scalar-wide | 23.07 | 12.57 | 527.66 | 527.06 |
| jvm | proto-policy | 186.65 | 135.64 | 1,241.14 | 1,216.58 |
| jvm | proto-repeated | 2,837.97 | 1,712.94 | 7,659.03 | 7,533.60 |
| jvm | map-regex | 1,084.45 | 804.42 | 2,659.37 | 2,755.53 |
| jvm | optimizer-policy | 96.97 | 55.52 | 735.06 | 717.18 |
| svm | scalar | 31.23 | 18.27 | 79.99 | 79.48 |
| svm | scalar-wide | 34.01 | 23.73 | 1,323.06 | 1,347.83 |
| svm | proto-policy | 329.64 | 192.89 | 2,528.09 | 2,368.41 |
| svm | proto-repeated | 5,191.58 | 1,826.50 | 11,924.84 | 11,982.68 |
| svm | map-regex | 1,560.09 | 995.84 | 6,345.31 | 6,148.05 |
| svm | optimizer-policy | 158.98 | 83.16 | 1,697.03 | 1,633.31 |

## Current-thread allocation (B/op)

| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |
| --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar | 96.00 | 24.00 | 96.00 | 96.00 |
| jvm | scalar-wide | 120.00 | 48.00 | 2,936.00 | 2,936.00 |
| jvm | proto-policy | 548.00 | 526.00 | 4,158.00 | 4,062.00 |
| jvm | proto-repeated | 5,444.00 | 5,436.00 | 18,344.00 | 18,344.00 |
| jvm | map-regex | 1,904.00 | 1,732.00 | 3,054.00 | 3,054.00 |
| jvm | optimizer-policy | 280.00 | 264.00 | 3,336.00 | 3,272.00 |
| svm | scalar | 104.00 | 40.00 | 240.00 | 240.00 |
| svm | scalar-wide | 104.00 | 40.00 | 3,504.00 | 3,504.00 |
| svm | proto-policy | 360.00 | 340.00 | 4,794.00 | 4,598.00 |
| svm | proto-repeated | 4,752.00 | 4,720.00 | 15,704.00 | 15,704.00 |
| svm | map-regex | 1,804.00 | 1,664.00 | 6,176.00 | 6,176.00 |
| svm | optimizer-policy | 184.00 | 168.00 | 3,912.00 | 3,804.00 |

## Current RSS after fixed sampled work (MiB)

| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |
| --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar | 173.67 | 202.90 | 150.30 | 153.95 |
| jvm | scalar-wide | 190.29 | 202.98 | 386.25 | 388.11 |
| jvm | proto-policy | 336.24 | 422.46 | 397.46 | 399.20 |
| jvm | proto-repeated | 420.95 | 494.96 | 390.98 | 393.93 |
| jvm | map-regex | 434.84 | 537.30 | 388.40 | 391.19 |
| jvm | optimizer-policy | 312.65 | 386.04 | 391.89 | 399.64 |
| svm | scalar | 117.75 | 114.09 | 164.79 | 165.70 |
| svm | scalar-wide | 118.53 | 113.93 | 133.22 | 134.33 |
| svm | proto-policy | 168.32 | 257.48 | 119.27 | 124.22 |
| svm | proto-repeated | 116.82 | 190.16 | 66.91 | 67.44 |
| svm | map-regex | 159.28 | 245.43 | 92.56 | 93.05 |
| svm | optimizer-policy | 167.30 | 258.59 | 134.68 | 136.23 |

## Lifetime peak RSS through fixed sampled work (MiB)

| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |
| --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar | 173.67 | 202.90 | 150.30 | 153.95 |
| jvm | scalar-wide | 190.29 | 202.98 | 386.25 | 388.11 |
| jvm | proto-policy | 336.24 | 422.46 | 397.46 | 399.20 |
| jvm | proto-repeated | 420.95 | 494.96 | 390.98 | 393.93 |
| jvm | map-regex | 434.84 | 537.30 | 388.40 | 391.19 |
| jvm | optimizer-policy | 312.65 | 386.04 | 391.89 | 399.64 |
| svm | scalar | 117.75 | 114.09 | 164.79 | 165.70 |
| svm | scalar-wide | 118.53 | 113.93 | 160.96 | 162.02 |
| svm | proto-policy | 168.32 | 257.48 | 167.50 | 168.78 |
| svm | proto-repeated | 165.11 | 239.71 | 166.99 | 167.84 |
| svm | map-regex | 170.22 | 257.41 | 163.12 | 162.84 |
| svm | optimizer-policy | 167.30 | 258.59 | 166.37 | 167.33 |
