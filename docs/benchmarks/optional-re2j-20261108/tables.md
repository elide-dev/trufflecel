# Optional RE2/J policy evaluation

Values are median [minimum, maximum] across three independent forks. Lower is better.

## Evaluation time (ns/op)

| Comparison | Runtime | Mode | Fixture | Reference | Result | Change |
| --- | --- | --- | --- | ---: | ---: | ---: |
| baseline → candidate | jvm | truffle-interpreter | scalar-wide | 22.85 [21.74, 23.23] | 22.84 [22.60, 23.13] | -0.04% |
| baseline → candidate | jvm | truffle-interpreter | map-regex | 1,121.09 [1,050.14, 1,151.57] | 1,114.80 [1,046.15, 1,118.46] | -0.56% |
| baseline → candidate | jvm | truffle-interpreter | map-regex-dynamic | 1,542.85 [1,512.79, 1,545.81] | 1,524.68 [1,506.62, 1,552.51] | -1.18% |
| baseline → candidate | jvm | truffle-jit | scalar-wide | 12.09 [12.06, 12.52] | 12.54 [12.07, 13.07] | +3.73% |
| baseline → candidate | jvm | truffle-jit | map-regex | 824.19 [816.88, 838.02] | 833.96 [824.12, 845.07] | +1.19% |
| baseline → candidate | jvm | truffle-jit | map-regex-dynamic | 1,149.59 [1,142.78, 1,160.48] | 1,137.71 [1,116.51, 1,162.28] | -1.03% |
| baseline → candidate | svm | truffle-interpreter | scalar-wide | 35.59 [34.14, 35.75] | 34.96 [33.59, 35.21] | -1.79% |
| baseline → candidate | svm | truffle-interpreter | map-regex | 1,667.64 [1,566.86, 1,669.64] | 1,608.64 [1,574.78, 1,674.53] | -3.54% |
| baseline → candidate | svm | truffle-interpreter | map-regex-dynamic | 2,389.85 [2,311.27, 2,416.63] | 2,326.42 [2,325.09, 2,445.47] | -2.65% |
| baseline → candidate | svm | truffle-jit | scalar-wide | 25.32 [23.95, 25.38] | 23.61 [23.45, 25.28] | -6.75% |
| baseline → candidate | svm | truffle-jit | map-regex | 1,025.29 [987.64, 1,027.05] | 1,002.22 [985.86, 1,029.94] | -2.25% |
| baseline → candidate | svm | truffle-jit | map-regex-dynamic | 1,509.55 [1,446.46, 1,514.26] | 1,443.47 [1,431.49, 1,514.13] | -4.38% |
| baseline → only | jvm | truffle-interpreter | scalar-wide | 22.85 [21.74, 23.23] | 23.00 [21.69, 23.24] | +0.68% |
| baseline → only | jvm | truffle-interpreter | map-regex | 1,121.09 [1,050.14, 1,151.57] | 1,108.83 [1,088.63, 1,110.33] | -1.09% |
| baseline → only | jvm | truffle-interpreter | map-regex-dynamic | 1,542.85 [1,512.79, 1,545.81] | 1,483.68 [1,465.46, 1,535.17] | -3.84% |
| baseline → only | jvm | truffle-jit | scalar-wide | 12.09 [12.06, 12.52] | 12.65 [12.56, 13.19] | +4.62% |
| baseline → only | jvm | truffle-jit | map-regex | 824.19 [816.88, 838.02] | 819.47 [797.72, 834.58] | -0.57% |
| baseline → only | jvm | truffle-jit | map-regex-dynamic | 1,149.59 [1,142.78, 1,160.48] | 1,044.80 [1,015.47, 1,047.46] | -9.12% |
| baseline → only | svm | truffle-interpreter | scalar-wide | 35.59 [34.14, 35.75] | 33.46 [33.44, 35.43] | -6.00% |
| baseline → only | svm | truffle-interpreter | map-regex | 1,667.64 [1,566.86, 1,669.64] | 1,585.29 [1,584.34, 1,672.21] | -4.94% |
| baseline → only | svm | truffle-interpreter | map-regex-dynamic | 2,389.85 [2,311.27, 2,416.63] | 2,268.23 [2,191.83, 2,296.58] | -5.09% |
| baseline → only | svm | truffle-jit | scalar-wide | 25.32 [23.95, 25.38] | 23.44 [23.35, 24.65] | -7.43% |
| baseline → only | svm | truffle-jit | map-regex | 1,025.29 [987.64, 1,027.05] | 1,026.65 [984.70, 1,040.95] | +0.13% |
| baseline → only | svm | truffle-jit | map-regex-dynamic | 1,509.55 [1,446.46, 1,514.26] | 1,202.71 [1,200.46, 1,213.35] | -20.33% |
| candidate → only | jvm | truffle-interpreter | scalar-wide | 22.84 [22.60, 23.13] | 23.00 [21.69, 23.24] | +0.72% |
| candidate → only | jvm | truffle-interpreter | map-regex | 1,114.80 [1,046.15, 1,118.46] | 1,108.83 [1,088.63, 1,110.33] | -0.54% |
| candidate → only | jvm | truffle-interpreter | map-regex-dynamic | 1,524.68 [1,506.62, 1,552.51] | 1,483.68 [1,465.46, 1,535.17] | -2.69% |
| candidate → only | jvm | truffle-jit | scalar-wide | 12.54 [12.07, 13.07] | 12.65 [12.56, 13.19] | +0.87% |
| candidate → only | jvm | truffle-jit | map-regex | 833.96 [824.12, 845.07] | 819.47 [797.72, 834.58] | -1.74% |
| candidate → only | jvm | truffle-jit | map-regex-dynamic | 1,137.71 [1,116.51, 1,162.28] | 1,044.80 [1,015.47, 1,047.46] | -8.17% |
| candidate → only | svm | truffle-interpreter | scalar-wide | 34.96 [33.59, 35.21] | 33.46 [33.44, 35.43] | -4.29% |
| candidate → only | svm | truffle-interpreter | map-regex | 1,608.64 [1,574.78, 1,674.53] | 1,585.29 [1,584.34, 1,672.21] | -1.45% |
| candidate → only | svm | truffle-interpreter | map-regex-dynamic | 2,326.42 [2,325.09, 2,445.47] | 2,268.23 [2,191.83, 2,296.58] | -2.50% |
| candidate → only | svm | truffle-jit | scalar-wide | 23.61 [23.45, 25.28] | 23.44 [23.35, 24.65] | -0.73% |
| candidate → only | svm | truffle-jit | map-regex | 1,002.22 [985.86, 1,029.94] | 1,026.65 [984.70, 1,040.95] | +2.44% |
| candidate → only | svm | truffle-jit | map-regex-dynamic | 1,443.47 [1,431.49, 1,514.13] | 1,202.71 [1,200.46, 1,213.35] | -16.68% |

## Current-thread allocation (B/op)

| Comparison | Runtime | Mode | Fixture | Reference | Result | Change |
| --- | --- | --- | --- | ---: | ---: | ---: |
| baseline → candidate | jvm | truffle-interpreter | scalar-wide | 120.00 [120.00, 120.00] | 120.00 [120.00, 120.00] | +0.00% |
| baseline → candidate | jvm | truffle-interpreter | map-regex | 1,904.00 [1,904.00, 1,904.00] | 1,904.00 [1,904.00, 1,904.00] | +0.00% |
| baseline → candidate | jvm | truffle-interpreter | map-regex-dynamic | 1,984.00 [1,984.00, 1,984.00] | 1,984.00 [1,984.00, 1,984.00] | +0.00% |
| baseline → candidate | jvm | truffle-jit | scalar-wide | 48.00 [48.00, 48.00] | 48.00 [48.00, 48.00] | +0.00% |
| baseline → candidate | jvm | truffle-jit | map-regex | 1,732.00 [1,732.00, 1,732.00] | 1,732.00 [1,732.00, 1,732.00] | +0.00% |
| baseline → candidate | jvm | truffle-jit | map-regex-dynamic | 1,888.00 [1,888.00, 1,888.00] | 1,888.00 [1,888.00, 1,888.00] | +0.00% |
| baseline → candidate | svm | truffle-interpreter | scalar-wide | 104.00 [104.00, 104.00] | 104.00 [104.00, 104.00] | +0.00% |
| baseline → candidate | svm | truffle-interpreter | map-regex | 1,804.00 [1,804.00, 1,804.00] | 1,804.00 [1,804.00, 1,804.00] | +0.00% |
| baseline → candidate | svm | truffle-interpreter | map-regex-dynamic | 1,892.00 [1,892.00, 1,892.00] | 1,892.00 [1,892.00, 1,892.00] | +0.00% |
| baseline → candidate | svm | truffle-jit | scalar-wide | 40.00 [40.00, 40.00] | 40.00 [40.00, 40.00] | +0.00% |
| baseline → candidate | svm | truffle-jit | map-regex | 1,664.00 [1,664.00, 1,664.00] | 1,664.00 [1,664.00, 1,664.00] | +0.00% |
| baseline → candidate | svm | truffle-jit | map-regex-dynamic | 1,812.00 [1,812.00, 1,812.00] | 1,812.00 [1,812.00, 1,812.00] | +0.00% |
| baseline → only | jvm | truffle-interpreter | scalar-wide | 120.00 [120.00, 120.00] | 120.00 [120.00, 120.00] | +0.00% |
| baseline → only | jvm | truffle-interpreter | map-regex | 1,904.00 [1,904.00, 1,904.00] | 1,904.00 [1,904.00, 1,904.00] | +0.00% |
| baseline → only | jvm | truffle-interpreter | map-regex-dynamic | 1,984.00 [1,984.00, 1,984.00] | 2,060.00 [2,060.00, 2,060.00] | +3.83% |
| baseline → only | jvm | truffle-jit | scalar-wide | 48.00 [48.00, 48.00] | 48.00 [48.00, 48.00] | +0.00% |
| baseline → only | jvm | truffle-jit | map-regex | 1,732.00 [1,732.00, 1,732.00] | 1,732.00 [1,732.00, 1,732.00] | +0.00% |
| baseline → only | jvm | truffle-jit | map-regex-dynamic | 1,888.00 [1,888.00, 1,888.00] | 1,864.00 [1,864.00, 1,864.00] | -1.27% |
| baseline → only | svm | truffle-interpreter | scalar-wide | 104.00 [104.00, 104.00] | 104.00 [104.00, 104.00] | +0.00% |
| baseline → only | svm | truffle-interpreter | map-regex | 1,804.00 [1,804.00, 1,804.00] | 1,804.00 [1,804.00, 1,804.00] | +0.00% |
| baseline → only | svm | truffle-interpreter | map-regex-dynamic | 1,892.00 [1,892.00, 1,892.00] | 1,972.00 [1,972.00, 1,972.00] | +4.23% |
| baseline → only | svm | truffle-jit | scalar-wide | 40.00 [40.00, 40.00] | 40.00 [40.00, 40.00] | +0.00% |
| baseline → only | svm | truffle-jit | map-regex | 1,664.00 [1,664.00, 1,664.00] | 1,664.00 [1,664.00, 1,664.00] | +0.00% |
| baseline → only | svm | truffle-jit | map-regex-dynamic | 1,812.00 [1,812.00, 1,812.00] | 1,812.00 [1,812.00, 1,812.00] | +0.00% |
| candidate → only | jvm | truffle-interpreter | scalar-wide | 120.00 [120.00, 120.00] | 120.00 [120.00, 120.00] | +0.00% |
| candidate → only | jvm | truffle-interpreter | map-regex | 1,904.00 [1,904.00, 1,904.00] | 1,904.00 [1,904.00, 1,904.00] | +0.00% |
| candidate → only | jvm | truffle-interpreter | map-regex-dynamic | 1,984.00 [1,984.00, 1,984.00] | 2,060.00 [2,060.00, 2,060.00] | +3.83% |
| candidate → only | jvm | truffle-jit | scalar-wide | 48.00 [48.00, 48.00] | 48.00 [48.00, 48.00] | +0.00% |
| candidate → only | jvm | truffle-jit | map-regex | 1,732.00 [1,732.00, 1,732.00] | 1,732.00 [1,732.00, 1,732.00] | +0.00% |
| candidate → only | jvm | truffle-jit | map-regex-dynamic | 1,888.00 [1,888.00, 1,888.00] | 1,864.00 [1,864.00, 1,864.00] | -1.27% |
| candidate → only | svm | truffle-interpreter | scalar-wide | 104.00 [104.00, 104.00] | 104.00 [104.00, 104.00] | +0.00% |
| candidate → only | svm | truffle-interpreter | map-regex | 1,804.00 [1,804.00, 1,804.00] | 1,804.00 [1,804.00, 1,804.00] | +0.00% |
| candidate → only | svm | truffle-interpreter | map-regex-dynamic | 1,892.00 [1,892.00, 1,892.00] | 1,972.00 [1,972.00, 1,972.00] | +4.23% |
| candidate → only | svm | truffle-jit | scalar-wide | 40.00 [40.00, 40.00] | 40.00 [40.00, 40.00] | +0.00% |
| candidate → only | svm | truffle-jit | map-regex | 1,664.00 [1,664.00, 1,664.00] | 1,664.00 [1,664.00, 1,664.00] | +0.00% |
| candidate → only | svm | truffle-jit | map-regex-dynamic | 1,812.00 [1,812.00, 1,812.00] | 1,812.00 [1,812.00, 1,812.00] | +0.00% |

## Current process RSS (MiB)

| Comparison | Runtime | Mode | Fixture | Reference | Result | Change |
| --- | --- | --- | --- | ---: | ---: | ---: |
| baseline → candidate | jvm | truffle-interpreter | scalar-wide | 191.08 [190.96, 191.13] | 190.67 [190.66, 190.70] | -0.22% |
| baseline → candidate | jvm | truffle-interpreter | map-regex | 434.63 [434.62, 434.67] | 434.83 [434.78, 434.91] | +0.05% |
| baseline → candidate | jvm | truffle-interpreter | map-regex-dynamic | 420.38 [420.33, 420.42] | 420.41 [420.29, 420.54] | +0.01% |
| baseline → candidate | jvm | truffle-jit | scalar-wide | 202.96 [202.91, 202.96] | 202.96 [202.95, 202.98] | +0.00% |
| baseline → candidate | jvm | truffle-jit | map-regex | 537.25 [536.57, 537.25] | 537.20 [537.12, 537.21] | -0.01% |
| baseline → candidate | jvm | truffle-jit | map-regex-dynamic | 491.67 [491.55, 491.69] | 491.72 [491.62, 491.72] | +0.01% |
| baseline → candidate | svm | truffle-interpreter | scalar-wide | 118.28 [118.28, 118.29] | 117.78 [117.64, 118.36] | -0.42% |
| baseline → candidate | svm | truffle-interpreter | map-regex | 158.54 [158.52, 158.65] | 159.42 [159.05, 160.39] | +0.55% |
| baseline → candidate | svm | truffle-interpreter | map-regex-dynamic | 152.94 [152.78, 153.62] | 153.60 [153.46, 153.80] | +0.43% |
| baseline → candidate | svm | truffle-jit | scalar-wide | 114.16 [113.81, 115.08] | 111.23 [110.87, 112.16] | -2.57% |
| baseline → candidate | svm | truffle-jit | map-regex | 245.52 [244.04, 245.67] | 245.57 [244.29, 245.60] | +0.02% |
| baseline → candidate | svm | truffle-jit | map-regex-dynamic | 217.68 [217.34, 218.67] | 216.16 [216.05, 217.34] | -0.70% |
| baseline → only | jvm | truffle-interpreter | scalar-wide | 191.08 [190.96, 191.13] | 190.65 [190.63, 190.69] | -0.23% |
| baseline → only | jvm | truffle-interpreter | map-regex | 434.63 [434.62, 434.67] | 434.35 [434.30, 434.42] | -0.06% |
| baseline → only | jvm | truffle-interpreter | map-regex-dynamic | 420.38 [420.33, 420.42] | 436.60 [436.55, 436.70] | +3.86% |
| baseline → only | jvm | truffle-jit | scalar-wide | 202.96 [202.91, 202.96] | 202.96 [202.95, 202.98] | +0.00% |
| baseline → only | jvm | truffle-jit | map-regex | 537.25 [536.57, 537.25] | 532.45 [532.42, 532.64] | -0.89% |
| baseline → only | jvm | truffle-jit | map-regex-dynamic | 491.67 [491.55, 491.69] | 547.06 [546.98, 547.07] | +11.27% |
| baseline → only | svm | truffle-interpreter | scalar-wide | 118.28 [118.28, 118.29] | 117.66 [117.50, 118.42] | -0.53% |
| baseline → only | svm | truffle-interpreter | map-regex | 158.54 [158.52, 158.65] | 159.93 [158.51, 159.93] | +0.88% |
| baseline → only | svm | truffle-interpreter | map-regex-dynamic | 152.94 [152.78, 153.62] | 158.96 [158.68, 159.00] | +3.94% |
| baseline → only | svm | truffle-jit | scalar-wide | 114.16 [113.81, 115.08] | 111.13 [110.86, 111.45] | -2.66% |
| baseline → only | svm | truffle-jit | map-regex | 245.52 [244.04, 245.67] | 245.65 [245.14, 245.67] | +0.05% |
| baseline → only | svm | truffle-jit | map-regex-dynamic | 217.68 [217.34, 218.67] | 251.84 [251.78, 251.84] | +15.69% |
| candidate → only | jvm | truffle-interpreter | scalar-wide | 190.67 [190.66, 190.70] | 190.65 [190.63, 190.69] | -0.01% |
| candidate → only | jvm | truffle-interpreter | map-regex | 434.83 [434.78, 434.91] | 434.35 [434.30, 434.42] | -0.11% |
| candidate → only | jvm | truffle-interpreter | map-regex-dynamic | 420.41 [420.29, 420.54] | 436.60 [436.55, 436.70] | +3.85% |
| candidate → only | jvm | truffle-jit | scalar-wide | 202.96 [202.95, 202.98] | 202.96 [202.95, 202.98] | -0.00% |
| candidate → only | jvm | truffle-jit | map-regex | 537.20 [537.12, 537.21] | 532.45 [532.42, 532.64] | -0.88% |
| candidate → only | jvm | truffle-jit | map-regex-dynamic | 491.72 [491.62, 491.72] | 547.06 [546.98, 547.07] | +11.25% |
| candidate → only | svm | truffle-interpreter | scalar-wide | 117.78 [117.64, 118.36] | 117.66 [117.50, 118.42] | -0.10% |
| candidate → only | svm | truffle-interpreter | map-regex | 159.42 [159.05, 160.39] | 159.93 [158.51, 159.93] | +0.32% |
| candidate → only | svm | truffle-interpreter | map-regex-dynamic | 153.60 [153.46, 153.80] | 158.96 [158.68, 159.00] | +3.49% |
| candidate → only | svm | truffle-jit | scalar-wide | 111.23 [110.87, 112.16] | 111.13 [110.86, 111.45] | -0.09% |
| candidate → only | svm | truffle-jit | map-regex | 245.57 [244.29, 245.60] | 245.65 [245.14, 245.67] | +0.03% |
| candidate → only | svm | truffle-jit | map-regex-dynamic | 216.16 [216.05, 217.34] | 251.84 [251.78, 251.84] | +16.50% |

## Lifetime peak process RSS (MiB)

| Comparison | Runtime | Mode | Fixture | Reference | Result | Change |
| --- | --- | --- | --- | ---: | ---: | ---: |
| baseline → candidate | jvm | truffle-interpreter | scalar-wide | 191.08 [190.96, 191.13] | 190.67 [190.66, 190.70] | -0.22% |
| baseline → candidate | jvm | truffle-interpreter | map-regex | 434.63 [434.62, 434.67] | 434.83 [434.78, 434.91] | +0.05% |
| baseline → candidate | jvm | truffle-interpreter | map-regex-dynamic | 420.38 [420.33, 420.42] | 420.41 [420.29, 420.54] | +0.01% |
| baseline → candidate | jvm | truffle-jit | scalar-wide | 202.96 [202.91, 202.96] | 202.96 [202.95, 202.98] | +0.00% |
| baseline → candidate | jvm | truffle-jit | map-regex | 537.25 [536.57, 537.25] | 537.20 [537.12, 537.21] | -0.01% |
| baseline → candidate | jvm | truffle-jit | map-regex-dynamic | 491.67 [491.55, 491.69] | 491.72 [491.62, 491.72] | +0.01% |
| baseline → candidate | svm | truffle-interpreter | scalar-wide | 118.28 [118.28, 118.29] | 117.78 [117.64, 118.36] | -0.42% |
| baseline → candidate | svm | truffle-interpreter | map-regex | 171.05 [170.63, 171.48] | 171.43 [170.56, 172.40] | +0.23% |
| baseline → candidate | svm | truffle-interpreter | map-regex-dynamic | 165.17 [164.70, 166.45] | 165.78 [165.71, 166.11] | +0.37% |
| baseline → candidate | svm | truffle-jit | scalar-wide | 114.16 [113.81, 115.08] | 111.23 [110.87, 112.16] | -2.57% |
| baseline → candidate | svm | truffle-jit | map-regex | 257.59 [257.54, 259.36] | 257.50 [257.14, 258.23] | -0.04% |
| baseline → candidate | svm | truffle-jit | map-regex-dynamic | 230.99 [230.77, 231.95] | 228.48 [228.04, 230.21] | -1.09% |
| baseline → only | jvm | truffle-interpreter | scalar-wide | 191.08 [190.96, 191.13] | 190.65 [190.63, 190.69] | -0.23% |
| baseline → only | jvm | truffle-interpreter | map-regex | 434.63 [434.62, 434.67] | 434.35 [434.30, 434.42] | -0.06% |
| baseline → only | jvm | truffle-interpreter | map-regex-dynamic | 420.38 [420.33, 420.42] | 436.60 [436.55, 436.70] | +3.86% |
| baseline → only | jvm | truffle-jit | scalar-wide | 202.96 [202.91, 202.96] | 202.96 [202.95, 202.98] | +0.00% |
| baseline → only | jvm | truffle-jit | map-regex | 537.25 [536.57, 537.25] | 532.45 [532.42, 532.64] | -0.89% |
| baseline → only | jvm | truffle-jit | map-regex-dynamic | 491.67 [491.55, 491.69] | 547.06 [546.98, 547.07] | +11.27% |
| baseline → only | svm | truffle-interpreter | scalar-wide | 118.28 [118.28, 118.29] | 117.66 [117.50, 118.42] | -0.53% |
| baseline → only | svm | truffle-interpreter | map-regex | 171.05 [170.63, 171.48] | 171.94 [170.52, 171.95] | +0.52% |
| baseline → only | svm | truffle-interpreter | map-regex-dynamic | 165.17 [164.70, 166.45] | 171.18 [170.84, 171.44] | +3.64% |
| baseline → only | svm | truffle-jit | scalar-wide | 114.16 [113.81, 115.08] | 111.13 [110.86, 111.45] | -2.66% |
| baseline → only | svm | truffle-jit | map-regex | 257.59 [257.54, 259.36] | 257.06 [256.43, 257.66] | -0.21% |
| baseline → only | svm | truffle-jit | map-regex-dynamic | 230.99 [230.77, 231.95] | 264.57 [263.79, 265.17] | +14.54% |
| candidate → only | jvm | truffle-interpreter | scalar-wide | 190.67 [190.66, 190.70] | 190.65 [190.63, 190.69] | -0.01% |
| candidate → only | jvm | truffle-interpreter | map-regex | 434.83 [434.78, 434.91] | 434.35 [434.30, 434.42] | -0.11% |
| candidate → only | jvm | truffle-interpreter | map-regex-dynamic | 420.41 [420.29, 420.54] | 436.60 [436.55, 436.70] | +3.85% |
| candidate → only | jvm | truffle-jit | scalar-wide | 202.96 [202.95, 202.98] | 202.96 [202.95, 202.98] | -0.00% |
| candidate → only | jvm | truffle-jit | map-regex | 537.20 [537.12, 537.21] | 532.45 [532.42, 532.64] | -0.88% |
| candidate → only | jvm | truffle-jit | map-regex-dynamic | 491.72 [491.62, 491.72] | 547.06 [546.98, 547.07] | +11.25% |
| candidate → only | svm | truffle-interpreter | scalar-wide | 117.78 [117.64, 118.36] | 117.66 [117.50, 118.42] | -0.10% |
| candidate → only | svm | truffle-interpreter | map-regex | 171.43 [170.56, 172.40] | 171.94 [170.52, 171.95] | +0.30% |
| candidate → only | svm | truffle-interpreter | map-regex-dynamic | 165.78 [165.71, 166.11] | 171.18 [170.84, 171.44] | +3.26% |
| candidate → only | svm | truffle-jit | scalar-wide | 111.23 [110.87, 112.16] | 111.13 [110.86, 111.45] | -0.09% |
| candidate → only | svm | truffle-jit | map-regex | 257.50 [257.14, 258.23] | 257.06 [256.43, 257.66] | -0.17% |
| candidate → only | svm | truffle-jit | map-regex-dynamic | 228.48 [228.04, 230.21] | 264.57 [263.79, 265.17] | +15.80% |

## Supplemental JVM scalar-JIT confirmation

| Variant | ns/op, median [minimum, maximum] |
| --- | ---: |
| baseline | 12.38 [12.04, 12.79] |
| candidate | 12.51 [12.05, 12.96] |
| only | 12.52 [12.43, 12.74] |
