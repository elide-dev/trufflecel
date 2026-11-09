# Native profile qualification tables

Values are process medians with min–max process ranges in brackets. Timing and memory use separate processes; ranges are not confidence intervals.

## Evaluation: interpreter first, guest JIT separate

| Workload | Engine | Profile | ns/op | B/op | Current RSS MiB | Current PSS MiB | Peak RSS MiB |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| scalar | truffle-interpreter | default | 30.77 [29.43–30.98] | 104.00 [104.00–104.00] | 117.68 [117.56–117.70] | 114.86 [114.74–114.87] | 117.68 [117.56–117.70] |
| scalar | truffle-interpreter | shared-compiler | 29.85 [29.39–30.25] | 104.00 [104.00–104.00] | 117.70 [117.51–117.70] | 114.88 [114.80–114.92] | 117.70 [117.51–117.70] |
| scalar | cel-java | default | 82.23 [80.02–83.17] | 240.00 [240.00–240.00] | 164.21 [164.16–164.98] | 161.63 [161.63–162.44] | 164.21 [164.16–164.98] |
| scalar | cel-java | shared-compiler | 79.95 [79.08–83.28] | 240.00 [240.00–240.00] | 164.49 [164.47–165.67] | 161.95 [161.88–163.13] | 164.49 [164.47–165.67] |
| scalar | cel-java-optimized | default | 79.41 [78.00–79.94] | 240.00 [240.00–240.00] | 165.34 [165.14–165.68] | 162.76 [162.57–163.12] | 165.34 [165.14–165.68] |
| scalar | cel-java-optimized | shared-compiler | 80.32 [77.97–82.19] | 240.00 [240.00–240.00] | 164.84 [164.67–165.34] | 162.26 [162.13–162.76] | 164.84 [164.67–165.34] |
| scalar | truffle-jit | default | 18.14 [18.13–18.20] | 40.00 [40.00–40.00] | 113.77 [113.50–114.13] | 95.44 [95.12–95.74] | 113.77 [113.50–114.13] |
| scalar | truffle-jit | shared-compiler | 18.02 [18.01–18.28] | 40.00 [40.00–40.00] | 97.66 [96.98–98.45] | 94.85 [94.17–95.66] | 97.66 [96.98–98.45] |
| scalar-wide | truffle-interpreter | default | 33.58 [33.28–35.29] | 104.00 [104.00–104.00] | 117.57 [117.57–118.45] | 114.75 [114.75–115.67] | 117.57 [117.57–118.45] |
| scalar-wide | truffle-interpreter | shared-compiler | 35.30 [33.31–35.46] | 104.00 [104.00–104.00] | 117.76 [117.00–118.49] | 114.93 [114.17–115.67] | 117.76 [117.00–118.49] |
| scalar-wide | cel-java | default | 1256.63 [1245.39–1386.15] | 3504.00 [3504.00–3504.00] | 133.54 [133.24–133.96] | 130.96 [130.65–131.40] | 161.58 [161.18–162.38] |
| scalar-wide | cel-java | shared-compiler | 1287.77 [1203.88–1290.29] | 3504.00 [3504.00–3504.00] | 133.48 [133.44–134.25] | 130.90 [130.90–131.65] | 161.62 [161.34–162.43] |
| scalar-wide | cel-java-optimized | default | 1267.49 [1141.08–1343.23] | 3504.00 [3504.00–3504.00] | 133.73 [133.70–134.71] | 131.26 [131.14–132.14] | 161.20 [160.61–162.39] |
| scalar-wide | cel-java-optimized | shared-compiler | 1258.44 [1221.74–1262.87] | 3504.00 [3504.00–3504.00] | 134.29 [134.02–134.29] | 131.71 [131.56–131.71] | 161.59 [161.24–162.10] |
| scalar-wide | truffle-jit | default | 23.60 [23.48–23.99] | 40.00 [40.00–40.00] | 114.80 [113.94–114.80] | 96.17 [95.29–96.18] | 114.80 [113.94–114.80] |
| scalar-wide | truffle-jit | shared-compiler | 24.95 [23.84–25.01] | 40.00 [40.00–40.00] | 97.00 [96.36–98.18] | 94.21 [93.65–95.36] | 97.00 [96.36–98.18] |
| proto-policy | truffle-interpreter | default | 337.25 [327.09–352.86] | 360.00 [360.00–360.00] | 168.06 [167.78–168.96] | 165.24 [164.99–166.13] | 168.06 [167.78–168.96] |
| proto-policy | truffle-interpreter | shared-compiler | 329.32 [323.93–353.39] | 360.00 [360.00–360.00] | 167.51 [167.18–168.28] | 164.68 [164.37–165.50] | 167.51 [167.18–168.28] |
| proto-policy | cel-java | default | 2506.10 [2452.40–2508.38] | 4794.00 [4794.00–4794.00] | 119.61 [118.82–120.16] | 117.01 [116.28–117.60] | 168.14 [167.42–168.32] |
| proto-policy | cel-java | shared-compiler | 2399.78 [2378.65–2561.52] | 4794.00 [4794.00–4794.00] | 119.16 [119.04–119.55] | 116.58 [116.45–117.01] | 167.85 [167.49–167.88] |
| proto-policy | cel-java-optimized | default | 2330.67 [2264.80–2372.06] | 4598.00 [4598.00–4598.00] | 124.51 [123.72–124.76] | 121.92 [121.25–122.18] | 168.56 [168.26–169.18] |
| proto-policy | cel-java-optimized | shared-compiler | 2263.07 [2248.30–2313.09] | 4598.00 [4598.00–4598.00] | 123.91 [123.01–124.75] | 121.38 [120.43–122.18] | 168.56 [167.59–168.93] |
| proto-policy | truffle-jit | default | 191.20 [190.63–192.71] | 340.00 [340.00–340.00] | 257.50 [256.75–257.50] | 237.67 [237.04–237.67] | 257.50 [256.75–257.50] |
| proto-policy | truffle-jit | shared-compiler | 202.57 [195.00–204.42] | 340.00 [340.00–340.00] | 190.30 [190.06–190.46] | 187.49 [187.24–187.68] | 190.75 [190.06–190.90] |
| proto-repeated | truffle-interpreter | default | 5001.93 [4980.82–5269.45] | 4752.00 [4752.00–4752.00] | 117.25 [117.22–118.00] | 114.42 [114.40–115.21] | 165.85 [165.82–166.73] |
| proto-repeated | truffle-interpreter | shared-compiler | 5002.42 [4983.50–5018.30] | 4752.00 [4752.00–4752.00] | 117.29 [117.25–118.07] | 114.47 [114.47–115.22] | 165.73 [165.39–166.43] |
| proto-repeated | cel-java | default | 11914.68 [11861.12–12455.10] | 15704.00 [15704.00–15704.00] | 66.83 [66.81–67.15] | 64.34 [64.30–64.59] | 166.29 [166.02–166.66] |
| proto-repeated | cel-java | shared-compiler | 11871.37 [11867.70–11905.27] | 15704.00 [15704.00–15704.00] | 66.39 [66.39–67.14] | 63.80 [63.79–64.60] | 166.35 [166.29–166.48] |
| proto-repeated | cel-java-optimized | default | 11893.52 [11892.55–11958.81] | 15704.00 [15704.00–15704.00] | 67.43 [66.85–67.90] | 64.84 [64.29–65.36] | 166.80 [166.42–167.32] |
| proto-repeated | cel-java-optimized | shared-compiler | 11884.96 [11762.23–12012.89] | 15704.00 [15704.00–15704.00] | 67.31 [65.60–67.79] | 64.74 [63.02–65.22] | 166.83 [166.58–167.09] |
| proto-repeated | truffle-jit | default | 1866.60 [1769.70–1876.04] | 4720.00 [4720.00–4720.00] | 194.40 [190.92–194.47] | 175.08 [171.58–175.15] | 240.65 [240.24–241.03] |
| proto-repeated | truffle-jit | shared-compiler | 1790.42 [1769.14–1798.86] | 4720.00 [4720.00–4720.00] | 144.05 [143.91–144.12] | 141.24 [141.12–141.31] | 189.66 [189.57–190.45] |
| map-regex | truffle-interpreter | default | 1557.28 [1551.25–1576.24] | 1804.00 [1804.00–1804.00] | 159.28 [159.26–160.39] | 156.50 [156.44–157.57] | 170.29 [170.08–172.28] |
| map-regex | truffle-interpreter | shared-compiler | 1573.48 [1556.73–1643.82] | 1804.00 [1804.00–1804.00] | 159.26 [158.79–159.47] | 156.44 [156.00–156.65] | 170.18 [170.08–170.74] |
| map-regex | cel-java | default | 6467.00 [6098.10–6502.53] | 6176.00 [6176.00–6176.00] | 92.17 [91.85–92.57] | 89.64 [89.27–89.97] | 162.98 [162.70–163.48] |
| map-regex | cel-java | shared-compiler | 6582.54 [6064.17–6637.61] | 6176.00 [6176.00–6176.00] | 92.54 [91.94–92.56] | 89.96 [89.34–89.96] | 163.47 [162.61–163.49] |
| map-regex | cel-java-optimized | default | 6184.64 [6081.16–6488.95] | 6176.00 [6176.00–6176.00] | 93.18 [92.41–93.32] | 90.60 [89.90–90.77] | 162.98 [162.41–163.48] |
| map-regex | cel-java-optimized | shared-compiler | 6328.90 [6278.56–6386.55] | 6176.00 [6176.00–6176.00] | 93.38 [93.31–93.72] | 90.78 [90.77–91.14] | 163.03 [162.86–163.31] |
| map-regex | truffle-jit | default | 1000.21 [991.54–1028.54] | 1664.00 [1664.00–1664.00] | 244.83 [244.80–245.45] | 224.55 [224.53–224.91] | 257.42 [256.88–257.67] |
| map-regex | truffle-jit | shared-compiler | 1026.21 [994.52–1029.93] | 1664.00 [1664.00–1664.00] | 184.40 [183.15–184.83] | 181.69 [180.33–182.12] | 197.70 [196.51–198.20] |
| optimizer-policy | truffle-interpreter | default | 151.59 [150.19–155.76] | 184.00 [184.00–184.00] | 168.75 [167.90–168.80] | 165.97 [165.09–165.98] | 168.75 [167.90–168.80] |
| optimizer-policy | truffle-interpreter | shared-compiler | 151.36 [150.92–159.90] | 184.00 [184.00–184.00] | 167.74 [167.28–168.57] | 164.91 [164.46–165.72] | 167.74 [167.28–168.57] |
| optimizer-policy | cel-java | default | 1804.87 [1756.02–1853.46] | 3912.00 [3912.00–3912.00] | 134.71 [134.67–135.30] | 132.13 [132.13–132.70] | 166.73 [166.52–167.36] |
| optimizer-policy | cel-java | shared-compiler | 1652.13 [1636.59–1695.69] | 3912.00 [3912.00–3912.00] | 134.31 [134.09–134.64] | 131.70 [131.52–132.07] | 167.02 [167.00–167.06] |
| optimizer-policy | cel-java-optimized | default | 1589.13 [1574.55–1656.16] | 3804.00 [3804.00–3804.00] | 136.75 [135.80–137.27] | 134.20 [133.21–134.69] | 167.52 [167.05–167.83] |
| optimizer-policy | cel-java-optimized | shared-compiler | 1597.62 [1548.45–1623.02] | 3804.00 [3804.00–3804.00] | 136.71 [135.87–136.79] | 134.14 [133.33–134.20] | 167.46 [166.92–167.87] |
| optimizer-policy | truffle-jit | default | 83.11 [82.80–89.11] | 168.00 [168.00–168.00] | 258.62 [258.50–258.92] | 238.79 [238.78–239.14] | 258.62 [258.50–258.92] |
| optimizer-policy | truffle-jit | shared-compiler | 88.96 [82.44–91.29] | 168.00 [168.00–168.00] | 189.48 [189.45–189.55] | 186.67 [186.67–186.73] | 189.48 [189.45–189.55] |

## Forced guest preparation

Includes 2,000 warmup evaluations and synchronous last-tier compilation. This is excluded from hot timing and is not natural-JIT payback.

| Workload | Profile | Preparation ms |
| --- | --- | ---: |
| scalar | default | 10.83 [10.36–12.34] |
| scalar | shared-compiler | 12.21 [9.83–12.54] |
| scalar-wide | default | 10.15 [10.06–10.47] |
| scalar-wide | shared-compiler | 9.59 [9.39–11.75] |
| proto-policy | default | 71.37 [70.05–75.86] |
| proto-policy | shared-compiler | 70.16 [69.55–70.76] |
| proto-repeated | default | 53.60 [52.43–53.73] |
| proto-repeated | shared-compiler | 53.08 [52.96–53.99] |
| map-regex | default | 48.87 [48.55–54.19] |
| map-regex | shared-compiler | 48.87 [48.31–49.69] |
| optimizer-policy | default | 63.08 [61.14–66.51] |
| optimizer-policy | shared-compiler | 62.28 [58.43–62.28] |

## Fresh-process first verified result

Both embeddings use the same artifact for each preparation mode. Direct Java retains a context for regex; cel-java is an unchanged control. No control timing is subtracted.

| Preparation | Workload | Engine | Embedding | Launch → result ms | Main → result ms (diagnostic) |
| --- | --- | --- | --- | ---: | ---: |
| runtime | scalar | truffle-interpreter | context | 1.21 [1.11–1.43] | 0.34 [0.31–0.44] |
| runtime | scalar | truffle-interpreter | direct | 1.20 [1.09–1.31] | 0.29 [0.28–0.34] |
| runtime | scalar | truffle-jit | context | 1.24 [1.11–1.35] | 0.34 [0.30–0.38] |
| runtime | scalar | truffle-jit | direct | 1.20 [1.07–1.28] | 0.29 [0.27–0.34] |
| runtime | scalar | cel-java-optimized | context | 1.67 [1.59–1.85] | 0.72 [0.68–0.87] |
| runtime | scalar | cel-java-optimized | direct | 1.74 [1.66–1.80] | 0.78 [0.74–0.83] |
| runtime | scalar-wide | truffle-interpreter | context | 1.22 [1.12–1.42] | 0.35 [0.32–0.54] |
| runtime | scalar-wide | truffle-interpreter | direct | 1.30 [1.15–1.44] | 0.36 [0.29–0.50] |
| runtime | scalar-wide | truffle-jit | context | 1.27 [1.11–1.34] | 0.34 [0.31–0.38] |
| runtime | scalar-wide | truffle-jit | direct | 1.26 [1.13–1.34] | 0.31 [0.29–0.37] |
| runtime | scalar-wide | cel-java-optimized | context | 1.69 [1.57–1.90] | 0.74 [0.70–0.98] |
| runtime | scalar-wide | cel-java-optimized | direct | 1.64 [1.46–1.80] | 0.76 [0.67–0.83] |
| runtime | proto-policy | truffle-interpreter | context | 1.34 [1.16–1.39] | 0.39 [0.37–0.49] |
| runtime | proto-policy | truffle-interpreter | direct | 1.26 [1.13–1.42] | 0.36 [0.33–0.42] |
| runtime | proto-policy | truffle-jit | context | 1.34 [1.21–1.44] | 0.40 [0.36–0.46] |
| runtime | proto-policy | truffle-jit | direct | 1.25 [1.13–1.38] | 0.36 [0.32–0.42] |
| runtime | proto-policy | cel-java-optimized | context | 3.06 [2.82–3.15] | 2.13 [1.85–2.22] |
| runtime | proto-policy | cel-java-optimized | direct | 2.91 [2.74–3.16] | 2.04 [1.91–2.22] |
| runtime | proto-repeated | truffle-interpreter | context | 1.31 [1.19–1.48] | 0.39 [0.34–0.45] |
| runtime | proto-repeated | truffle-interpreter | direct | 1.29 [1.13–1.41] | 0.34 [0.30–0.38] |
| runtime | proto-repeated | truffle-jit | context | 1.30 [1.19–1.37] | 0.36 [0.34–0.42] |
| runtime | proto-repeated | truffle-jit | direct | 1.23 [1.11–1.38] | 0.32 [0.29–0.40] |
| runtime | proto-repeated | cel-java-optimized | context | 2.63 [2.51–2.82] | 1.72 [1.60–1.85] |
| runtime | proto-repeated | cel-java-optimized | direct | 2.75 [2.49–2.86] | 1.79 [1.68–1.87] |
| runtime | map-regex | truffle-interpreter | context | 1.47 [1.27–1.64] | 0.50 [0.35–0.64] |
| runtime | map-regex | truffle-interpreter | direct | 1.38 [1.18–1.52] | 0.45 [0.35–0.60] |
| runtime | map-regex | truffle-jit | context | 1.43 [1.25–1.54] | 0.45 [0.35–0.58] |
| runtime | map-regex | truffle-jit | direct | 1.40 [1.26–1.54] | 0.48 [0.39–0.58] |
| runtime | map-regex | cel-java-optimized | context | 2.28 [2.02–2.61] | 1.32 [1.17–1.64] |
| runtime | map-regex | cel-java-optimized | direct | 2.18 [2.02–2.37] | 1.29 [1.21–1.49] |
| runtime | optimizer-policy | truffle-interpreter | context | 1.32 [1.13–1.46] | 0.38 [0.33–0.48] |
| runtime | optimizer-policy | truffle-interpreter | direct | 1.26 [1.17–1.38] | 0.33 [0.31–0.43] |
| runtime | optimizer-policy | truffle-jit | context | 1.30 [1.18–1.43] | 0.36 [0.33–0.47] |
| runtime | optimizer-policy | truffle-jit | direct | 1.20 [1.11–1.39] | 0.32 [0.30–0.45] |
| runtime | optimizer-policy | cel-java-optimized | context | 4.11 [3.96–4.47] | 3.16 [2.98–3.51] |
| runtime | optimizer-policy | cel-java-optimized | direct | 3.95 [3.68–4.08] | 3.04 [2.80–3.25] |
| image | scalar | truffle-interpreter | context | 1.26 [1.06–1.31] | 0.29 [0.25–0.35] |
| image | scalar | truffle-interpreter | direct | 1.13 [1.01–1.30] | 0.26 [0.22–0.34] |
| image | scalar | truffle-jit | context | 1.23 [1.10–1.33] | 0.29 [0.27–0.38] |
| image | scalar | truffle-jit | direct | 1.13 [1.03–1.21] | 0.23 [0.23–0.29] |
| image | scalar | cel-java-optimized | context | 1.15 [1.05–1.21] | 0.19 [0.17–0.25] |
| image | scalar | cel-java-optimized | direct | 1.11 [0.97–1.33] | 0.19 [0.17–0.34] |
| image | scalar-wide | truffle-interpreter | context | 1.20 [1.06–1.29] | 0.28 [0.26–0.35] |
| image | scalar-wide | truffle-interpreter | direct | 1.20 [1.02–1.30] | 0.27 [0.22–0.29] |
| image | scalar-wide | truffle-jit | context | 1.20 [1.05–1.30] | 0.29 [0.25–0.33] |
| image | scalar-wide | truffle-jit | direct | 1.11 [1.02–1.28] | 0.26 [0.22–0.31] |
| image | scalar-wide | cel-java-optimized | context | 1.13 [0.96–1.26] | 0.22 [0.17–0.29] |
| image | scalar-wide | cel-java-optimized | direct | 1.16 [1.03–1.21] | 0.23 [0.18–0.25] |
| image | proto-policy | truffle-interpreter | context | 1.26 [1.08–1.30] | 0.34 [0.27–0.38] |
| image | proto-policy | truffle-interpreter | direct | 1.21 [1.08–1.32] | 0.27 [0.23–0.32] |
| image | proto-policy | truffle-jit | context | 1.23 [1.11–1.31] | 0.29 [0.28–0.33] |
| image | proto-policy | truffle-jit | direct | 1.19 [1.13–1.27] | 0.26 [0.23–0.30] |
| image | proto-policy | cel-java-optimized | context | 1.39 [1.37–1.68] | 0.60 [0.54–0.75] |
| image | proto-policy | cel-java-optimized | direct | 1.48 [1.36–1.71] | 0.62 [0.57–0.73] |
| image | proto-repeated | truffle-interpreter | context | 1.28 [1.07–1.31] | 0.32 [0.27–0.36] |
| image | proto-repeated | truffle-interpreter | direct | 1.25 [1.14–1.46] | 0.28 [0.24–0.40] |
| image | proto-repeated | truffle-jit | context | 1.19 [1.11–1.31] | 0.31 [0.28–0.35] |
| image | proto-repeated | truffle-jit | direct | 1.09 [1.03–1.29] | 0.25 [0.23–0.31] |
| image | proto-repeated | cel-java-optimized | context | 1.55 [1.42–2.13] | 0.66 [0.58–0.80] |
| image | proto-repeated | cel-java-optimized | direct | 1.52 [1.42–2.06] | 0.63 [0.59–0.78] |
| image | map-regex | truffle-interpreter | context | 1.28 [1.08–1.45] | 0.34 [0.27–0.61] |
| image | map-regex | truffle-interpreter | direct | 1.32 [1.11–1.52] | 0.41 [0.29–0.63] |
| image | map-regex | truffle-jit | context | 1.26 [1.11–1.53] | 0.35 [0.26–0.56] |
| image | map-regex | truffle-jit | direct | 1.24 [1.12–1.48] | 0.33 [0.27–0.55] |
| image | map-regex | cel-java-optimized | context | 1.17 [0.98–1.23] | 0.23 [0.20–0.30] |
| image | map-regex | cel-java-optimized | direct | 1.14 [1.01–1.26] | 0.25 [0.20–0.29] |
| image | optimizer-policy | truffle-interpreter | context | 1.24 [1.06–1.31] | 0.30 [0.25–0.35] |
| image | optimizer-policy | truffle-interpreter | direct | 1.14 [1.07–1.24] | 0.26 [0.24–0.30] |
| image | optimizer-policy | truffle-jit | context | 1.17 [1.10–1.31] | 0.30 [0.27–0.35] |
| image | optimizer-policy | truffle-jit | direct | 1.20 [1.06–1.30] | 0.27 [0.23–0.32] |
| image | optimizer-policy | cel-java-optimized | context | 1.52 [1.36–1.77] | 0.66 [0.57–0.82] |
| image | optimizer-policy | cel-java-optimized | direct | 1.43 [1.36–1.76] | 0.61 [0.57–0.80] |

| Empty control | Launch → marker ms |
| --- | ---: |
| runtime | 0.98 [0.86–1.01] |
| image | 0.90 [0.83–0.97] |

## Natural-JIT cumulative cost

Default runtime flags, three forks, full map inputs. Includes configuration, preparation and cumulative evaluations; excludes launch, fixture creation and ingestion. Do not add these times to startup.

The first sampled win requires all JIT forks to be last-tier compiled and their maximum time below the interpreter minimum at that and every later checkpoint. Powers-of-ten checkpoints do not establish an exact crossover or guarantee.

| Runtime | Mode | Workload | First sampled win | Interpreter at 1M ms | Guest JIT at 1M ms |
| --- | --- | --- | ---: | ---: | ---: |
| jvm | cold | scalar-wide | None through 1M | 138.93 [137.53–140.17] | 179.00 [177.88–180.99] |
| jvm | cold | proto-policy | None through 1M | 375.33 [370.21–379.35] | 587.56 [586.54–594.30] |
| jvm | cold | proto-repeated | 1000000 | 3041.76 [3034.77–3051.91] | 2092.15 [2081.26–2139.30] |
| svm | cold | scalar-wide | 1000000 | 50.20 [50.19–51.07] | 43.20 [40.00–44.10] |
| svm | cold | proto-policy | 1000000 | 366.19 [349.08–383.25] | 294.73 [293.73–295.67] |
| svm | cold | proto-repeated | 100000 | 5008.22 [4999.35–5212.18] | 1876.86 [1875.96–1920.53] |
| svm | image-cold | scalar-wide | 1000000 | 51.08 [49.35–51.88] | 41.26 [41.18–41.54] |
| svm | image-cold | proto-policy | 1000000 | 361.12 [342.79–377.84] | 294.44 [292.36–297.74] |
| svm | image-cold | proto-repeated | 100000 | 5018.33 [5005.37–5046.64] | 1912.29 [1880.97–1961.24] |

### All cumulative checkpoints

| Runtime | Mode | Workload | Evaluations | Interpreter ms | Guest JIT ms | All JIT last-tier |
| --- | --- | --- | ---: | ---: | ---: | --- |
| jvm | cold | scalar-wide | 1 | 85.03 [83.75–85.96] | 85.40 [85.00–85.81] | False |
| jvm | cold | scalar-wide | 10 | 85.08 [83.80–86.01] | 85.46 [85.05–85.86] | False |
| jvm | cold | scalar-wide | 100 | 85.36 [84.11–86.33] | 85.74 [85.42–86.19] | False |
| jvm | cold | scalar-wide | 1000 | 88.01 [86.45–88.60] | 133.72 [132.78–134.49] | False |
| jvm | cold | scalar-wide | 10000 | 93.50 [91.89–93.97] | 153.10 [153.08–155.49] | True |
| jvm | cold | scalar-wide | 100000 | 102.99 [101.73–104.13] | 156.73 [156.51–158.88] | True |
| jvm | cold | scalar-wide | 1000000 | 138.93 [137.53–140.17] | 179.00 [177.88–180.99] | True |
| jvm | cold | proto-policy | 1 | 91.97 [91.42–93.44] | 92.21 [91.65–92.37] | False |
| jvm | cold | proto-policy | 10 | 92.18 [91.62–93.64] | 92.40 [91.84–92.57] | False |
| jvm | cold | proto-policy | 100 | 93.85 [93.43–95.38] | 94.15 [93.59–94.37] | False |
| jvm | cold | proto-policy | 1000 | 101.42 [101.19–103.20] | 243.13 [242.24–243.79] | False |
| jvm | cold | proto-policy | 10000 | 120.15 [118.58–128.60] | 401.38 [400.42–412.00] | True |
| jvm | cold | proto-policy | 100000 | 160.26 [158.00–166.56] | 431.42 [429.68–441.07] | True |
| jvm | cold | proto-policy | 1000000 | 375.33 [370.21–379.35] | 587.56 [586.54–594.30] | True |
| jvm | cold | proto-repeated | 1 | 89.13 [87.97–89.45] | 87.95 [87.05–88.70] | False |
| jvm | cold | proto-repeated | 10 | 90.80 [89.73–91.19] | 89.69 [88.68–90.59] | False |
| jvm | cold | proto-repeated | 100 | 98.80 [98.14–99.29] | 97.36 [96.35–98.40] | False |
| jvm | cold | proto-repeated | 1000 | 111.49 [110.93–111.91] | 197.12 [194.91–197.85] | False |
| jvm | cold | proto-repeated | 10000 | 189.04 [188.67–189.81] | 343.76 [341.31–345.19] | True |
| jvm | cold | proto-repeated | 100000 | 484.32 [482.74–487.84] | 536.62 [535.40–539.42] | True |
| jvm | cold | proto-repeated | 1000000 | 3041.76 [3034.77–3051.91] | 2092.15 [2081.26–2139.30] | True |
| svm | cold | scalar-wide | 1 | 0.41 [0.40–0.42] | 0.43 [0.40–0.45] | False |
| svm | cold | scalar-wide | 10 | 0.41 [0.40–0.42] | 0.43 [0.40–0.45] | False |
| svm | cold | scalar-wide | 100 | 0.42 [0.40–0.43] | 0.44 [0.40–0.45] | False |
| svm | cold | scalar-wide | 1000 | 0.46 [0.45–0.47] | 6.94 [6.62–7.29] | False |
| svm | cold | scalar-wide | 10000 | 0.89 [0.89–0.93] | 10.62 [9.95–11.47] | True |
| svm | cold | scalar-wide | 100000 | 5.26 [5.23–5.46] | 13.72 [12.73–14.45] | True |
| svm | cold | scalar-wide | 1000000 | 50.20 [50.19–51.07] | 43.20 [40.00–44.10] | True |
| svm | cold | proto-policy | 1 | 0.54 [0.49–0.59] | 0.53 [0.51–0.54] | False |
| svm | cold | proto-policy | 10 | 0.55 [0.50–0.60] | 0.54 [0.52–0.55] | False |
| svm | cold | proto-policy | 100 | 0.59 [0.54–0.65] | 0.58 [0.56–0.59] | False |
| svm | cold | proto-policy | 1000 | 0.95 [0.92–1.09] | 33.01 [32.45–33.14] | False |
| svm | cold | proto-policy | 10000 | 4.64 [4.63–4.84] | 73.57 [73.12–74.12] | True |
| svm | cold | proto-policy | 100000 | 41.69 [40.60–42.26] | 97.64 [96.92–97.75] | True |
| svm | cold | proto-policy | 1000000 | 366.19 [349.08–383.25] | 294.73 [293.73–295.67] | True |
| svm | cold | proto-repeated | 1 | 0.47 [0.46–0.48] | 0.47 [0.47–0.49] | False |
| svm | cold | proto-repeated | 10 | 0.53 [0.51–0.53] | 0.53 [0.53–0.55] | False |
| svm | cold | proto-repeated | 100 | 1.09 [1.02–1.09] | 1.09 [1.08–1.10] | False |
| svm | cold | proto-repeated | 1000 | 6.84 [6.34–6.84] | 24.75 [24.44–24.92] | False |
| svm | cold | proto-repeated | 10000 | 62.48 [58.24–62.52] | 76.06 [75.80–76.27] | True |
| svm | cold | proto-repeated | 100000 | 529.80 [521.63–549.94] | 252.95 [252.28–259.74] | True |
| svm | cold | proto-repeated | 1000000 | 5008.22 [4999.35–5212.18] | 1876.86 [1875.96–1920.53] | True |
| svm | image-cold | scalar-wide | 1 | 0.36 [0.34–0.43] | 0.36 [0.25–0.38] | False |
| svm | image-cold | scalar-wide | 10 | 0.36 [0.34–0.43] | 0.37 [0.25–0.38] | False |
| svm | image-cold | scalar-wide | 100 | 0.37 [0.34–0.43] | 0.37 [0.25–0.38] | False |
| svm | image-cold | scalar-wide | 1000 | 0.41 [0.39–0.48] | 6.72 [6.57–6.91] | False |
| svm | image-cold | scalar-wide | 10000 | 0.86 [0.84–0.94] | 10.24 [9.95–10.26] | True |
| svm | image-cold | scalar-wide | 100000 | 5.44 [5.35–5.65] | 13.20 [12.93–13.30] | True |
| svm | image-cold | scalar-wide | 1000000 | 51.08 [49.35–51.88] | 41.26 [41.18–41.54] | True |
| svm | image-cold | proto-policy | 1 | 0.38 [0.37–0.43] | 0.41 [0.40–0.43] | False |
| svm | image-cold | proto-policy | 10 | 0.38 [0.38–0.44] | 0.42 [0.41–0.44] | False |
| svm | image-cold | proto-policy | 100 | 0.42 [0.41–0.47] | 0.45 [0.45–0.47] | False |
| svm | image-cold | proto-policy | 1000 | 0.79 [0.77–0.83] | 32.53 [32.36–33.10] | False |
| svm | image-cold | proto-policy | 10000 | 4.41 [4.35–4.52] | 73.10 [72.58–73.69] | True |
| svm | image-cold | proto-policy | 100000 | 40.34 [39.25–41.86] | 97.37 [96.04–97.47] | True |
| svm | image-cold | proto-policy | 1000000 | 361.12 [342.79–377.84] | 294.44 [292.36–297.74] | True |
| svm | image-cold | proto-repeated | 1 | 0.40 [0.38–0.41] | 0.38 [0.25–0.41] | False |
| svm | image-cold | proto-repeated | 10 | 0.46 [0.44–0.48] | 0.44 [0.31–0.47] | False |
| svm | image-cold | proto-repeated | 100 | 1.01 [1.00–1.04] | 0.99 [0.87–1.03] | False |
| svm | image-cold | proto-repeated | 1000 | 6.61 [6.47–6.76] | 24.01 [23.98–24.05] | False |
| svm | image-cold | proto-repeated | 10000 | 61.87 [61.59–62.31] | 74.79 [72.60–75.55] | True |
| svm | image-cold | proto-repeated | 100000 | 528.85 [528.39–543.20] | 258.45 [248.64–260.28] | True |
| svm | image-cold | proto-repeated | 1000000 | 5018.33 [5005.37–5046.64] | 1912.29 [1880.97–1961.24] | True |
