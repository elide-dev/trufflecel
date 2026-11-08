# Paired regex evaluation

Values are median [minimum, maximum] across three independent forks. Lower is better.

## Evaluation time (ns/op)

| Runtime | Mode | Fixture | RE2/J baseline | TruffleRegex candidate | Change |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | scalar-wide | 22.94 [22.70, 23.25] | 23.39 [22.98, 24.22] | +1.96% |
| jvm | truffle-interpreter | map-regex | 1,126.72 [1,125.49, 1,130.28] | 1,099.23 [1,081.37, 1,110.63] | -2.44% |
| jvm | truffle-jit | scalar-wide | 12.15 [12.15, 12.41] | 12.46 [12.00, 12.78] | +2.56% |
| jvm | truffle-jit | map-regex | 926.93 [893.91, 927.44] | 825.68 [817.57, 829.37] | -10.92% |
| svm | truffle-interpreter | scalar-wide | 33.43 [32.97, 34.54] | 34.85 [33.90, 34.90] | +4.24% |
| svm | truffle-interpreter | map-regex | 1,564.94 [1,549.05, 1,594.56] | 1,582.80 [1,568.72, 1,634.51] | +1.14% |
| svm | truffle-jit | scalar-wide | 23.33 [23.32, 23.92] | 24.23 [24.12, 25.43] | +3.84% |
| svm | truffle-jit | map-regex | 1,166.24 [1,153.08, 1,208.33] | 1,035.67 [1,030.99, 1,045.86] | -11.20% |

## Current-thread allocation (B/op)

| Runtime | Mode | Fixture | RE2/J baseline | TruffleRegex candidate | Change |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | scalar-wide | 120.00 [120.00, 120.00] | 120.00 [120.00, 120.00] | +0.00% |
| jvm | truffle-interpreter | map-regex | 1,822.00 [1,822.00, 1,822.00] | 1,904.00 [1,904.00, 1,904.00] | +4.50% |
| jvm | truffle-jit | scalar-wide | 48.00 [48.00, 48.00] | 48.00 [48.00, 48.00] | +0.00% |
| jvm | truffle-jit | map-regex | 1,726.00 [1,726.00, 1,726.00] | 1,732.00 [1,732.00, 1,732.00] | +0.35% |
| svm | truffle-interpreter | scalar-wide | 104.00 [104.00, 104.00] | 104.00 [104.00, 104.00] | +0.00% |
| svm | truffle-interpreter | map-regex | 1,740.00 [1,740.00, 1,740.00] | 1,804.00 [1,804.00, 1,804.00] | +3.68% |
| svm | truffle-jit | scalar-wide | 40.00 [40.00, 40.00] | 40.00 [40.00, 40.00] | +0.00% |
| svm | truffle-jit | map-regex | 1,660.00 [1,660.00, 1,660.00] | 1,664.00 [1,664.00, 1,664.00] | +0.24% |

## Current process RSS (MiB)

| Runtime | Mode | Fixture | RE2/J baseline | TruffleRegex candidate | Change |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | scalar-wide | 189.57 [189.52, 189.62] | 191.08 [191.07, 191.08] | +0.80% |
| jvm | truffle-interpreter | map-regex | 419.20 [419.19, 419.30] | 434.72 [434.70, 434.82] | +3.70% |
| jvm | truffle-jit | scalar-wide | 202.02 [201.96, 202.10] | 202.95 [202.91, 202.96] | +0.46% |
| jvm | truffle-jit | map-regex | 486.83 [486.81, 486.84] | 536.88 [536.84, 537.14] | +10.28% |
| svm | truffle-interpreter | scalar-wide | 103.51 [103.49, 103.52] | 104.59 [104.48, 104.61] | +1.05% |
| svm | truffle-interpreter | map-regex | 139.68 [139.67, 139.69] | 144.87 [144.83, 144.89] | +3.72% |
| svm | truffle-jit | scalar-wide | 90.12 [90.10, 90.16] | 92.68 [92.67, 92.70] | +2.84% |
| svm | truffle-jit | map-regex | 192.54 [192.54, 192.57] | 224.39 [224.36, 224.48] | +16.54% |

## Lifetime peak process RSS (MiB)

| Runtime | Mode | Fixture | RE2/J baseline | TruffleRegex candidate | Change |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | scalar-wide | 189.57 [189.52, 189.62] | 191.08 [191.07, 191.08] | +0.80% |
| jvm | truffle-interpreter | map-regex | 419.20 [419.19, 419.30] | 434.72 [434.70, 434.82] | +3.70% |
| jvm | truffle-jit | scalar-wide | 202.02 [201.96, 202.10] | 202.95 [202.91, 202.96] | +0.46% |
| jvm | truffle-jit | map-regex | 486.83 [486.81, 486.84] | 536.88 [536.84, 537.14] | +10.28% |
| svm | truffle-interpreter | scalar-wide | 103.51 [103.49, 103.52] | 104.59 [104.48, 104.61] | +1.05% |
| svm | truffle-interpreter | map-regex | 151.00 [150.61, 151.01] | 156.80 [156.77, 157.20] | +3.85% |
| svm | truffle-jit | scalar-wide | 90.12 [90.10, 90.16] | 92.68 [92.67, 92.70] | +2.84% |
| svm | truffle-jit | map-regex | 204.81 [204.75, 205.18] | 237.00 [235.59, 237.02] | +15.72% |
