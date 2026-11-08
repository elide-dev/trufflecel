# Paired cached regex dispatch evaluation

Values are median [minimum, maximum] across three independent forks. Lower is better.

## Evaluation time (ns/op)

| Runtime | Mode | Fixture | Initial TruffleRegex | Cached dispatch candidate | Change |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | scalar-wide | 22.94 [21.67, 22.99] | 22.76 [21.76, 23.60] | -0.79% |
| jvm | truffle-interpreter | map-regex | 1,096.25 [1,095.05, 1,106.12] | 1,102.99 [1,033.51, 1,107.15] | +0.61% |
| jvm | truffle-jit | scalar-wide | 12.50 [12.13, 12.87] | 12.53 [12.51, 12.61] | +0.25% |
| jvm | truffle-jit | map-regex | 814.36 [812.62, 828.42] | 814.81 [812.93, 898.90] | +0.05% |
| svm | truffle-interpreter | scalar-wide | 35.73 [33.94, 35.92] | 33.98 [33.77, 35.70] | -4.89% |
| svm | truffle-interpreter | map-regex | 1,605.65 [1,599.09, 1,606.33] | 1,579.48 [1,578.29, 1,682.92] | -1.63% |
| svm | truffle-jit | scalar-wide | 24.48 [23.97, 25.14] | 24.13 [23.82, 25.56] | -1.43% |
| svm | truffle-jit | map-regex | 1,001.70 [997.49, 1,044.49] | 1,018.51 [962.46, 1,039.65] | +1.68% |

## Current-thread allocation (B/op)

| Runtime | Mode | Fixture | Initial TruffleRegex | Cached dispatch candidate | Change |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | scalar-wide | 120.00 [120.00, 120.00] | 120.00 [120.00, 120.00] | +0.00% |
| jvm | truffle-interpreter | map-regex | 1,904.00 [1,904.00, 1,904.00] | 1,924.00 [1,924.00, 1,924.00] | +1.05% |
| jvm | truffle-jit | scalar-wide | 48.00 [48.00, 48.00] | 48.00 [48.00, 48.00] | +0.00% |
| jvm | truffle-jit | map-regex | 1,732.00 [1,732.00, 1,732.00] | 1,696.00 [1,696.00, 1,696.00] | -2.08% |
| svm | truffle-interpreter | scalar-wide | 104.00 [104.00, 104.00] | 104.00 [104.00, 104.00] | +0.00% |
| svm | truffle-interpreter | map-regex | 1,804.00 [1,804.00, 1,804.00] | 1,820.00 [1,820.00, 1,820.00] | +0.89% |
| svm | truffle-jit | scalar-wide | 40.00 [40.00, 40.00] | 40.00 [40.00, 40.00] | +0.00% |
| svm | truffle-jit | map-regex | 1,664.00 [1,664.00, 1,664.00] | 1,656.00 [1,656.00, 1,656.00] | -0.48% |

## Current process RSS (MiB)

| Runtime | Mode | Fixture | Initial TruffleRegex | Cached dispatch candidate | Change |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | scalar-wide | 191.11 [191.05, 191.12] | 191.11 [191.07, 191.14] | +0.00% |
| jvm | truffle-interpreter | map-regex | 434.70 [434.67, 434.73] | 434.93 [434.84, 434.98] | +0.05% |
| jvm | truffle-jit | scalar-wide | 202.93 [202.93, 202.94] | 202.93 [202.91, 202.93] | +0.00% |
| jvm | truffle-jit | map-regex | 537.08 [536.61, 537.15] | 515.19 [515.14, 515.25] | -4.08% |
| svm | truffle-interpreter | scalar-wide | 104.61 [104.60, 104.62] | 110.43 [110.34, 110.50] | +5.56% |
| svm | truffle-interpreter | map-regex | 144.86 [144.82, 144.87] | 151.79 [151.59, 151.80] | +4.78% |
| svm | truffle-jit | scalar-wide | 92.69 [92.63, 92.70] | 103.50 [103.35, 103.63] | +11.66% |
| svm | truffle-jit | map-regex | 224.32 [224.24, 224.39] | 240.26 [240.20, 240.62] | +7.11% |

## Lifetime peak process RSS (MiB)

| Runtime | Mode | Fixture | Initial TruffleRegex | Cached dispatch candidate | Change |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | scalar-wide | 191.11 [191.05, 191.12] | 191.11 [191.07, 191.14] | +0.00% |
| jvm | truffle-interpreter | map-regex | 434.70 [434.67, 434.73] | 434.93 [434.84, 434.98] | +0.05% |
| jvm | truffle-jit | scalar-wide | 202.93 [202.93, 202.94] | 202.93 [202.91, 202.93] | +0.00% |
| jvm | truffle-jit | map-regex | 537.08 [536.61, 537.15] | 515.19 [515.14, 515.25] | -4.08% |
| svm | truffle-interpreter | scalar-wide | 104.61 [104.60, 104.62] | 110.43 [110.34, 110.50] | +5.56% |
| svm | truffle-interpreter | map-regex | 156.68 [156.52, 157.43] | 164.37 [163.95, 164.69] | +4.91% |
| svm | truffle-jit | scalar-wide | 92.69 [92.63, 92.70] | 103.50 [103.35, 103.63] | +11.66% |
| svm | truffle-jit | map-regex | 236.83 [236.71, 236.89] | 253.07 [252.89, 253.95] | +6.86% |
