# Native throughput

ns/op: median of three process means [minimum–maximum]. Five one-second warmups,
seven half-second measurements per process. Runtime AST preparation; ingestion and
setup excluded. Guest-JIT compilation is forced and checked before/after timing.

| Runtime mode | Workload | Entry | ns/op [process range] |
| --- | --- | --- | ---: |
| truffle-interpreter | map-regex | fresh | 3,782.88 [3,759.85–3,921.74] |
| truffle-interpreter | map-regex | map | 3,116.77 [3,100.82–3,225.12] |
| truffle-interpreter | map-regex | snapshot | 1,107.62 [1,101.54–1,122.92] |
| truffle-interpreter | optimizer-policy | fresh | 2,821.64 [2,792.31–2,963.57] |
| truffle-interpreter | optimizer-policy | map | 2,107.64 [2,094.04–2,213.94] |
| truffle-interpreter | optimizer-policy | snapshot | 142.32 [141.44–148.39] |
| truffle-interpreter | proto-policy | fresh | 3,214.64 [3,197.22–3,391.48] |
| truffle-interpreter | proto-policy | map | 2,587.32 [2,533.60–2,625.02] |
| truffle-interpreter | proto-policy | snapshot | 492.50 [482.32–534.35] |
| truffle-interpreter | proto-repeated | fresh | 6,783.83 [6,764.85–6,786.88] |
| truffle-interpreter | proto-repeated | map | 6,057.78 [6,053.73–6,345.04] |
| truffle-interpreter | proto-repeated | snapshot | 4,043.23 [4,031.74–4,209.40] |
| truffle-interpreter | scalar | fresh | 41.66 [41.02–41.72] |
| truffle-interpreter | scalar | map | 29.59 [29.49–29.74] |
| truffle-interpreter | scalar | snapshot | 18.80 [18.69–19.65] |
| truffle-interpreter | scalar-wide | fresh | 2,624.68 [2,611.70–2,708.73] |
| truffle-interpreter | scalar-wide | map | 1,959.61 [1,952.75–1,988.13] |
| truffle-interpreter | scalar-wide | snapshot | 24.30 [23.18–25.24] |
| truffle-jit | map-regex | fresh | 3,417.83 [3,381.74–3,609.47] |
| truffle-jit | map-regex | map | 2,720.61 [2,704.74–2,743.55] |
| truffle-jit | map-regex | snapshot | 726.56 [725.87–729.70] |
| truffle-jit | optimizer-policy | fresh | 2,712.04 [2,707.31–2,833.28] |
| truffle-jit | optimizer-policy | map | 2,020.28 [2,008.37–2,027.77] |
| truffle-jit | optimizer-policy | snapshot | 69.19 [68.90–73.62] |
| truffle-jit | proto-policy | fresh | 3,063.22 [3,062.44–3,189.57] |
| truffle-jit | proto-policy | map | 2,377.36 [2,319.80–2,424.12] |
| truffle-jit | proto-policy | snapshot | 358.89 [356.83–377.23] |
| truffle-jit | proto-repeated | fresh | 4,170.73 [4,159.70–4,201.50] |
| truffle-jit | proto-repeated | map | 3,526.90 [3,491.01–3,694.70] |
| truffle-jit | proto-repeated | snapshot | 1,590.74 [1,535.12–1,597.65] |
| truffle-jit | scalar | fresh | 31.23 [30.99–32.59] |
| truffle-jit | scalar | map | 20.03 [19.98–20.22] |
| truffle-jit | scalar | snapshot | 10.17 [10.04–10.69] |
| truffle-jit | scalar-wide | fresh | 2,638.82 [2,616.88–2,740.16] |
| truffle-jit | scalar-wide | map | 2,015.06 [1,957.18–2,045.49] |
| truffle-jit | scalar-wide | snapshot | 14.80 [14.21–15.13] |

# Natural guest-JIT payback

First sampled checkpoint whose median stays below the interpreter median at all later
sampled checkpoints. The stricter column requires disjoint process ranges at every
remaining checkpoint. This is an observed comparison within this finite curve, not an
exact crossover count or a guarantee. A dash means no sustained crossing was observed.
Preparation and four reusable snapshots are included; ingestion and VM launch are excluded.

| Runtime | Workload | Entry | AST preparation | Median crossing | Disjoint-range crossing |
| --- | --- | --- | --- | ---: | ---: |
| jvm | proto-policy | fresh | cold | — | — |
| jvm | proto-policy | map | cold | — | — |
| jvm | proto-policy | snapshot | cold | — | — |
| jvm | proto-repeated | fresh | cold | 100000 | 100000 |
| jvm | proto-repeated | map | cold | 100000 | 100000 |
| jvm | proto-repeated | snapshot | cold | 100000 | 100000 |
| jvm | scalar-wide | fresh | cold | — | — |
| jvm | scalar-wide | map | cold | 1000000 | — |
| jvm | scalar-wide | snapshot | cold | — | — |
| svm | proto-policy | fresh | cold | 1000000 | 1000000 |
| svm | proto-policy | map | cold | 1000000 | 1000000 |
| svm | proto-policy | snapshot | cold | 1000000 | 1000000 |
| svm | proto-policy | snapshot | image-cold | 1000000 | 1000000 |
| svm | proto-repeated | fresh | cold | 100000 | 100000 |
| svm | proto-repeated | map | cold | 100000 | 100000 |
| svm | proto-repeated | snapshot | cold | 100000 | 100000 |
| svm | proto-repeated | snapshot | image-cold | 100000 | 100000 |
| svm | scalar-wide | fresh | cold | 1000000 | — |
| svm | scalar-wide | map | cold | — | — |
| svm | scalar-wide | snapshot | cold | 1000000 | 1000000 |

# Cumulative cold curves

Milliseconds: median [minimum–maximum] across three fresh processes. Guest compilation
is natural, synchronous and unforced. All checkpoint samples remain in the JSON summary.

| Runtime | Engine | Workload | Entry | Mode | Evaluations | Cumulative ms | Last-tier processes |
| --- | --- | --- | --- | --- | ---: | ---: | ---: |
| jvm | truffle-interpreter | proto-policy | fresh | cold | 1 | 95.89 [92.07–95.93] | 0/3 |
| jvm | truffle-interpreter | proto-policy | fresh | cold | 10 | 96.59 [92.76–96.61] | 0/3 |
| jvm | truffle-interpreter | proto-policy | fresh | cold | 100 | 101.20 [97.35–101.29] | 0/3 |
| jvm | truffle-interpreter | proto-policy | fresh | cold | 1000 | 118.31 [114.19–118.73] | 0/3 |
| jvm | truffle-interpreter | proto-policy | fresh | cold | 10000 | 170.92 [168.95–175.83] | 0/3 |
| jvm | truffle-interpreter | proto-policy | fresh | cold | 100000 | 459.46 [459.28–460.08] | 0/3 |
| jvm | truffle-interpreter | proto-policy | fresh | cold | 1000000 | 2,897.87 [2,862.34–2,921.21] | 0/3 |
| jvm | truffle-interpreter | proto-policy | map | cold | 1 | 92.35 [92.02–92.37] | 0/3 |
| jvm | truffle-interpreter | proto-policy | map | cold | 10 | 92.91 [92.58–92.95] | 0/3 |
| jvm | truffle-interpreter | proto-policy | map | cold | 100 | 96.51 [96.14–96.53] | 0/3 |
| jvm | truffle-interpreter | proto-policy | map | cold | 1000 | 112.43 [111.99–113.06] | 0/3 |
| jvm | truffle-interpreter | proto-policy | map | cold | 10000 | 156.37 [155.70–157.80] | 0/3 |
| jvm | truffle-interpreter | proto-policy | map | cold | 100000 | 393.80 [392.09–397.46] | 0/3 |
| jvm | truffle-interpreter | proto-policy | map | cold | 1000000 | 2,297.38 [2,296.26–2,308.40] | 0/3 |
| jvm | truffle-interpreter | proto-policy | snapshot | cold | 1 | 92.16 [91.91–93.97] | 0/3 |
| jvm | truffle-interpreter | proto-policy | snapshot | cold | 10 | 92.41 [92.17–94.22] | 0/3 |
| jvm | truffle-interpreter | proto-policy | snapshot | cold | 100 | 94.66 [94.37–96.52] | 0/3 |
| jvm | truffle-interpreter | proto-policy | snapshot | cold | 1000 | 106.19 [106.15–108.54] | 0/3 |
| jvm | truffle-interpreter | proto-policy | snapshot | cold | 10000 | 130.11 [129.92–136.29] | 0/3 |
| jvm | truffle-interpreter | proto-policy | snapshot | cold | 100000 | 194.95 [190.58–205.71] | 0/3 |
| jvm | truffle-interpreter | proto-policy | snapshot | cold | 1000000 | 510.50 [506.70–523.34] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | fresh | cold | 1 | 87.41 [87.28–89.14] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | fresh | cold | 10 | 88.96 [88.70–90.54] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | fresh | cold | 100 | 96.72 [96.01–101.02] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | fresh | cold | 1000 | 118.53 [116.07–129.82] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | fresh | cold | 10000 | 212.38 [209.96–231.82] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | fresh | cold | 100000 | 696.73 [673.29–711.20] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | fresh | cold | 1000000 | 5,140.20 [5,016.37–5,213.88] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | map | cold | 1 | 93.35 [87.85–95.42] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | map | cold | 10 | 94.80 [89.14–96.86] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | map | cold | 100 | 101.48 [95.62–103.83] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | map | cold | 1000 | 123.68 [112.54–132.37] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | map | cold | 10000 | 225.98 [215.16–228.55] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | map | cold | 100000 | 659.83 [635.70–669.23] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | map | cold | 1000000 | 4,613.24 [4,480.54–4,833.27] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | snapshot | cold | 1 | 90.57 [90.56–109.79] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | snapshot | cold | 10 | 91.88 [91.88–111.08] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | snapshot | cold | 100 | 97.61 [97.51–116.67] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | snapshot | cold | 1000 | 107.53 [107.28–126.64] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | snapshot | cold | 10000 | 175.07 [175.05–195.80] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | snapshot | cold | 100000 | 450.07 [439.83–470.44] | 0/3 |
| jvm | truffle-interpreter | proto-repeated | snapshot | cold | 1000000 | 2,823.48 [2,731.00–2,852.30] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | fresh | cold | 1 | 85.33 [84.79–85.51] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | fresh | cold | 10 | 85.78 [85.29–85.95] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | fresh | cold | 100 | 88.33 [87.73–88.40] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | fresh | cold | 1000 | 97.19 [96.56–97.83] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | fresh | cold | 10000 | 136.26 [135.99–137.85] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | fresh | cold | 100000 | 375.29 [375.22–385.40] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | fresh | cold | 1000000 | 2,404.96 [2,401.71–2,502.91] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | map | cold | 1 | 84.60 [84.06–84.87] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | map | cold | 10 | 84.93 [84.40–85.20] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | map | cold | 100 | 86.55 [86.00–86.82] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | map | cold | 1000 | 94.25 [93.92–94.50] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | map | cold | 10000 | 122.64 [121.76–122.82] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | map | cold | 100000 | 316.07 [309.10–318.57] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | map | cold | 1000000 | 1,893.24 [1,818.62–1,902.99] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | snapshot | cold | 1 | 85.15 [84.56–85.44] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | snapshot | cold | 10 | 85.19 [84.60–85.48] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | snapshot | cold | 100 | 85.42 [84.86–85.78] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | snapshot | cold | 1000 | 87.12 [86.51–87.53] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | snapshot | cold | 10000 | 90.77 [89.98–91.16] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | snapshot | cold | 100000 | 100.48 [100.28–100.50] | 0/3 |
| jvm | truffle-interpreter | scalar-wide | snapshot | cold | 1000000 | 130.25 [128.89–131.66] | 0/3 |
| jvm | truffle-jit | proto-policy | fresh | cold | 1 | 93.10 [93.09–94.44] | 0/3 |
| jvm | truffle-jit | proto-policy | fresh | cold | 10 | 93.93 [93.79–95.12] | 0/3 |
| jvm | truffle-jit | proto-policy | fresh | cold | 100 | 98.52 [98.49–99.66] | 0/3 |
| jvm | truffle-jit | proto-policy | fresh | cold | 1000 | 214.54 [210.01–220.46] | 0/3 |
| jvm | truffle-jit | proto-policy | fresh | cold | 10000 | 346.51 [340.94–353.82] | 3/3 |
| jvm | truffle-jit | proto-policy | fresh | cold | 100000 | 626.37 [617.95–635.19] | 3/3 |
| jvm | truffle-jit | proto-policy | fresh | cold | 1000000 | 3,028.26 [2,955.76–3,033.66] | 3/3 |
| jvm | truffle-jit | proto-policy | map | cold | 1 | 92.51 [91.88–96.80] | 0/3 |
| jvm | truffle-jit | proto-policy | map | cold | 10 | 93.08 [92.45–97.40] | 0/3 |
| jvm | truffle-jit | proto-policy | map | cold | 100 | 96.68 [96.02–101.38] | 0/3 |
| jvm | truffle-jit | proto-policy | map | cold | 1000 | 209.88 [209.62–216.47] | 0/3 |
| jvm | truffle-jit | proto-policy | map | cold | 10000 | 336.34 [329.68–336.44] | 3/3 |
| jvm | truffle-jit | proto-policy | map | cold | 100000 | 570.80 [560.12–572.72] | 3/3 |
| jvm | truffle-jit | proto-policy | map | cold | 1000000 | 2,429.37 [2,397.75–2,464.49] | 3/3 |
| jvm | truffle-jit | proto-policy | snapshot | cold | 1 | 97.39 [94.04–98.26] | 0/3 |
| jvm | truffle-jit | proto-policy | snapshot | cold | 10 | 97.67 [94.30–98.52] | 0/3 |
| jvm | truffle-jit | proto-policy | snapshot | cold | 100 | 100.39 [96.57–100.90] | 0/3 |
| jvm | truffle-jit | proto-policy | snapshot | cold | 1000 | 205.25 [201.19–207.34] | 0/3 |
| jvm | truffle-jit | proto-policy | snapshot | cold | 10000 | 313.35 [309.82–316.54] | 3/3 |
| jvm | truffle-jit | proto-policy | snapshot | cold | 100000 | 363.18 [360.61–366.03] | 3/3 |
| jvm | truffle-jit | proto-policy | snapshot | cold | 1000000 | 637.41 [633.41–637.90] | 3/3 |
| jvm | truffle-jit | proto-repeated | fresh | cold | 1 | 89.57 [87.38–91.20] | 0/3 |
| jvm | truffle-jit | proto-repeated | fresh | cold | 10 | 91.10 [88.90–92.82] | 0/3 |
| jvm | truffle-jit | proto-repeated | fresh | cold | 100 | 98.76 [96.45–101.05] | 0/3 |
| jvm | truffle-jit | proto-repeated | fresh | cold | 1000 | 186.82 [183.44–189.27] | 0/3 |
| jvm | truffle-jit | proto-repeated | fresh | cold | 10000 | 294.87 [290.57–299.19] | 3/3 |
| jvm | truffle-jit | proto-repeated | fresh | cold | 100000 | 650.30 [640.25–652.63] | 3/3 |
| jvm | truffle-jit | proto-repeated | fresh | cold | 1000000 | 3,837.40 [3,782.38–3,891.45] | 3/3 |
| jvm | truffle-jit | proto-repeated | map | cold | 1 | 87.73 [86.14–91.13] | 0/3 |
| jvm | truffle-jit | proto-repeated | map | cold | 10 | 89.15 [87.53–92.67] | 0/3 |
| jvm | truffle-jit | proto-repeated | map | cold | 100 | 95.88 [94.18–99.99] | 0/3 |
| jvm | truffle-jit | proto-repeated | map | cold | 1000 | 178.13 [178.06–186.31] | 0/3 |
| jvm | truffle-jit | proto-repeated | map | cold | 10000 | 280.07 [275.84–282.78] | 3/3 |
| jvm | truffle-jit | proto-repeated | map | cold | 100000 | 579.36 [576.53–593.51] | 3/3 |
| jvm | truffle-jit | proto-repeated | map | cold | 1000000 | 3,201.91 [3,191.09–3,326.14] | 3/3 |
| jvm | truffle-jit | proto-repeated | snapshot | cold | 1 | 87.46 [86.90–89.43] | 0/3 |
| jvm | truffle-jit | proto-repeated | snapshot | cold | 10 | 88.59 [88.04–90.54] | 0/3 |
| jvm | truffle-jit | proto-repeated | snapshot | cold | 100 | 93.85 [93.24–95.89] | 0/3 |
| jvm | truffle-jit | proto-repeated | snapshot | cold | 1000 | 174.10 [172.43–175.48] | 0/3 |
| jvm | truffle-jit | proto-repeated | snapshot | cold | 10000 | 243.56 [242.64–248.09] | 3/3 |
| jvm | truffle-jit | proto-repeated | snapshot | cold | 100000 | 390.43 [386.91–391.51] | 3/3 |
| jvm | truffle-jit | proto-repeated | snapshot | cold | 1000000 | 1,486.91 [1,460.83–1,491.51] | 3/3 |
| jvm | truffle-jit | scalar-wide | fresh | cold | 1 | 85.03 [82.36–87.58] | 0/3 |
| jvm | truffle-jit | scalar-wide | fresh | cold | 10 | 85.48 [82.81–88.03] | 0/3 |
| jvm | truffle-jit | scalar-wide | fresh | cold | 100 | 87.95 [85.25–90.57] | 0/3 |
| jvm | truffle-jit | scalar-wide | fresh | cold | 1000 | 144.32 [142.14–150.02] | 0/3 |
| jvm | truffle-jit | scalar-wide | fresh | cold | 10000 | 198.68 [197.64–202.62] | 3/3 |
| jvm | truffle-jit | scalar-wide | fresh | cold | 100000 | 446.84 [442.94–447.55] | 3/3 |
| jvm | truffle-jit | scalar-wide | fresh | cold | 1000000 | 2,531.32 [2,506.23–2,534.19] | 3/3 |
| jvm | truffle-jit | scalar-wide | map | cold | 1 | 84.73 [83.89–85.28] | 0/3 |
| jvm | truffle-jit | scalar-wide | map | cold | 10 | 85.06 [84.22–85.61] | 0/3 |
| jvm | truffle-jit | scalar-wide | map | cold | 100 | 86.64 [85.80–87.08] | 0/3 |
| jvm | truffle-jit | scalar-wide | map | cold | 1000 | 137.79 [137.44–138.31] | 0/3 |
| jvm | truffle-jit | scalar-wide | map | cold | 10000 | 178.70 [175.52–182.24] | 3/3 |
| jvm | truffle-jit | scalar-wide | map | cold | 100000 | 361.73 [356.34–376.41] | 3/3 |
| jvm | truffle-jit | scalar-wide | map | cold | 1000000 | 1,832.09 [1,817.40–1,922.12] | 3/3 |
| jvm | truffle-jit | scalar-wide | snapshot | cold | 1 | 85.96 [85.02–86.87] | 0/3 |
| jvm | truffle-jit | scalar-wide | snapshot | cold | 10 | 86.01 [85.06–86.91] | 0/3 |
| jvm | truffle-jit | scalar-wide | snapshot | cold | 100 | 86.31 [85.33–87.13] | 0/3 |
| jvm | truffle-jit | scalar-wide | snapshot | cold | 1000 | 131.08 [130.18–131.96] | 0/3 |
| jvm | truffle-jit | scalar-wide | snapshot | cold | 10000 | 148.00 [146.47–148.86] | 3/3 |
| jvm | truffle-jit | scalar-wide | snapshot | cold | 100000 | 150.23 [148.94–151.07] | 3/3 |
| jvm | truffle-jit | scalar-wide | snapshot | cold | 1000000 | 167.39 [163.64–167.39] | 3/3 |
| svm | truffle-interpreter | proto-policy | fresh | cold | 1 | 0.67 [0.61–0.77] | 0/3 |
| svm | truffle-interpreter | proto-policy | fresh | cold | 10 | 0.73 [0.66–0.85] | 0/3 |
| svm | truffle-interpreter | proto-policy | fresh | cold | 100 | 1.20 [1.19–1.36] | 0/3 |
| svm | truffle-interpreter | proto-policy | fresh | cold | 1000 | 6.01 [5.74–6.13] | 0/3 |
| svm | truffle-interpreter | proto-policy | fresh | cold | 10000 | 51.78 [50.22–52.68] | 0/3 |
| svm | truffle-interpreter | proto-policy | fresh | cold | 100000 | 378.65 [378.07–383.63] | 0/3 |
| svm | truffle-interpreter | proto-policy | fresh | cold | 1000000 | 3,587.84 [3,570.49–3,652.52] | 0/3 |
| svm | truffle-interpreter | proto-policy | map | cold | 1 | 0.64 [0.59–0.70] | 0/3 |
| svm | truffle-interpreter | proto-policy | map | cold | 10 | 0.68 [0.63–0.74] | 0/3 |
| svm | truffle-interpreter | proto-policy | map | cold | 100 | 1.02 [0.98–1.11] | 0/3 |
| svm | truffle-interpreter | proto-policy | map | cold | 1000 | 4.38 [4.30–4.68] | 0/3 |
| svm | truffle-interpreter | proto-policy | map | cold | 10000 | 39.75 [37.49–40.29] | 0/3 |
| svm | truffle-interpreter | proto-policy | map | cold | 100000 | 288.93 [284.68–290.11] | 0/3 |
| svm | truffle-interpreter | proto-policy | map | cold | 1000000 | 2,647.74 [2,572.03–2,678.25] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | cold | 1 | 0.68 [0.49–0.73] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | cold | 10 | 0.69 [0.50–0.74] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | cold | 100 | 0.75 [0.55–0.80] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | cold | 1000 | 1.32 [1.09–1.39] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | cold | 10000 | 7.06 [6.21–7.13] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | cold | 100000 | 63.62 [59.52–64.59] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | cold | 1000000 | 531.45 [502.17–542.16] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | image-cold | 1 | 0.59 [0.53–0.64] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | image-cold | 10 | 0.60 [0.54–0.64] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | image-cold | 100 | 0.65 [0.60–0.70] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | image-cold | 1000 | 1.19 [1.14–1.28] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | image-cold | 10000 | 6.57 [6.50–7.05] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | image-cold | 100000 | 61.58 [59.98–63.93] | 0/3 |
| svm | truffle-interpreter | proto-policy | snapshot | image-cold | 1000000 | 528.44 [512.63–532.36] | 0/3 |
| svm | truffle-interpreter | proto-repeated | fresh | cold | 1 | 0.60 [0.56–0.63] | 0/3 |
| svm | truffle-interpreter | proto-repeated | fresh | cold | 10 | 0.69 [0.65–0.72] | 0/3 |
| svm | truffle-interpreter | proto-repeated | fresh | cold | 100 | 1.55 [1.43–1.57] | 0/3 |
| svm | truffle-interpreter | proto-repeated | fresh | cold | 1000 | 10.16 [9.46–10.29] | 0/3 |
| svm | truffle-interpreter | proto-repeated | fresh | cold | 10000 | 92.70 [91.31–94.38] | 0/3 |
| svm | truffle-interpreter | proto-repeated | fresh | cold | 100000 | 722.76 [720.18–726.53] | 0/3 |
| svm | truffle-interpreter | proto-repeated | fresh | cold | 1000000 | 7,007.82 [6,992.57–7,082.45] | 0/3 |
| svm | truffle-interpreter | proto-repeated | map | cold | 1 | 0.57 [0.51–0.64] | 0/3 |
| svm | truffle-interpreter | proto-repeated | map | cold | 10 | 0.65 [0.59–0.72] | 0/3 |
| svm | truffle-interpreter | proto-repeated | map | cold | 100 | 1.39 [1.32–1.53] | 0/3 |
| svm | truffle-interpreter | proto-repeated | map | cold | 1000 | 8.88 [8.64–9.24] | 0/3 |
| svm | truffle-interpreter | proto-repeated | map | cold | 10000 | 82.41 [81.09–85.02] | 0/3 |
| svm | truffle-interpreter | proto-repeated | map | cold | 100000 | 661.85 [654.87–664.80] | 0/3 |
| svm | truffle-interpreter | proto-repeated | map | cold | 1000000 | 6,177.96 [6,154.43–6,317.56] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | cold | 1 | 0.59 [0.56–1.08] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | cold | 10 | 0.63 [0.61–1.13] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | cold | 100 | 1.07 [1.03–1.58] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | cold | 1000 | 5.45 [5.39–6.17] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | cold | 10000 | 49.62 [48.51–51.62] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | cold | 100000 | 431.44 [424.79–446.98] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | cold | 1000000 | 4,106.84 [4,075.19–4,286.59] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | image-cold | 1 | 0.55 [0.47–0.57] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | image-cold | 10 | 0.60 [0.51–0.62] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | image-cold | 100 | 1.08 [0.97–1.12] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | image-cold | 1000 | 5.55 [5.54–5.61] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | image-cold | 10000 | 50.81 [49.75–51.59] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | image-cold | 100000 | 443.78 [425.81–444.20] | 0/3 |
| svm | truffle-interpreter | proto-repeated | snapshot | image-cold | 1000000 | 4,074.40 [4,041.94–4,167.22] | 0/3 |
| svm | truffle-interpreter | scalar-wide | fresh | cold | 1 | 0.51 [0.49–0.51] | 0/3 |
| svm | truffle-interpreter | scalar-wide | fresh | cold | 10 | 0.56 [0.54–0.56] | 0/3 |
| svm | truffle-interpreter | scalar-wide | fresh | cold | 100 | 0.96 [0.93–0.98] | 0/3 |
| svm | truffle-interpreter | scalar-wide | fresh | cold | 1000 | 4.88 [4.76–5.35] | 0/3 |
| svm | truffle-interpreter | scalar-wide | fresh | cold | 10000 | 44.93 [43.57–45.49] | 0/3 |
| svm | truffle-interpreter | scalar-wide | fresh | cold | 100000 | 315.46 [313.46–319.79] | 0/3 |
| svm | truffle-interpreter | scalar-wide | fresh | cold | 1000000 | 2,935.83 [2,893.78–2,954.51] | 0/3 |
| svm | truffle-interpreter | scalar-wide | map | cold | 1 | 0.51 [0.46–0.55] | 0/3 |
| svm | truffle-interpreter | scalar-wide | map | cold | 10 | 0.55 [0.49–0.59] | 0/3 |
| svm | truffle-interpreter | scalar-wide | map | cold | 100 | 0.83 [0.76–0.89] | 0/3 |
| svm | truffle-interpreter | scalar-wide | map | cold | 1000 | 3.63 [3.42–3.82] | 0/3 |
| svm | truffle-interpreter | scalar-wide | map | cold | 10000 | 31.48 [30.16–32.79] | 0/3 |
| svm | truffle-interpreter | scalar-wide | map | cold | 100000 | 226.48 [225.82–230.76] | 0/3 |
| svm | truffle-interpreter | scalar-wide | map | cold | 1000000 | 2,031.87 [1,987.73–2,079.02] | 0/3 |
| svm | truffle-interpreter | scalar-wide | snapshot | cold | 1 | 0.55 [0.51–0.57] | 0/3 |
| svm | truffle-interpreter | scalar-wide | snapshot | cold | 10 | 0.55 [0.51–0.57] | 0/3 |
| svm | truffle-interpreter | scalar-wide | snapshot | cold | 100 | 0.55 [0.52–0.58] | 0/3 |
| svm | truffle-interpreter | scalar-wide | snapshot | cold | 1000 | 0.59 [0.55–0.61] | 0/3 |
| svm | truffle-interpreter | scalar-wide | snapshot | cold | 10000 | 0.99 [0.88–0.99] | 0/3 |
| svm | truffle-interpreter | scalar-wide | snapshot | cold | 100000 | 4.67 [4.07–4.77] | 0/3 |
| svm | truffle-interpreter | scalar-wide | snapshot | cold | 1000000 | 40.51 [36.77–41.05] | 0/3 |
| svm | truffle-jit | proto-policy | fresh | cold | 1 | 0.63 [0.51–0.68] | 0/3 |
| svm | truffle-jit | proto-policy | fresh | cold | 10 | 0.69 [0.56–0.73] | 0/3 |
| svm | truffle-jit | proto-policy | fresh | cold | 100 | 1.15 [1.05–1.16] | 0/3 |
| svm | truffle-jit | proto-policy | fresh | cold | 1000 | 32.60 [31.00–33.16] | 0/3 |
| svm | truffle-jit | proto-policy | fresh | cold | 10000 | 110.05 [108.22–110.81] | 3/3 |
| svm | truffle-jit | proto-policy | fresh | cold | 100000 | 426.17 [416.32–430.70] | 3/3 |
| svm | truffle-jit | proto-policy | fresh | cold | 1000000 | 3,421.78 [3,358.38–3,568.75] | 3/3 |
| svm | truffle-jit | proto-policy | map | cold | 1 | 0.70 [0.66–0.74] | 0/3 |
| svm | truffle-jit | proto-policy | map | cold | 10 | 0.75 [0.71–0.79] | 0/3 |
| svm | truffle-jit | proto-policy | map | cold | 100 | 1.10 [1.06–1.12] | 0/3 |
| svm | truffle-jit | proto-policy | map | cold | 1000 | 30.77 [30.31–31.71] | 0/3 |
| svm | truffle-jit | proto-policy | map | cold | 10000 | 98.03 [96.92–99.54] | 3/3 |
| svm | truffle-jit | proto-policy | map | cold | 100000 | 330.38 [320.68–331.19] | 3/3 |
| svm | truffle-jit | proto-policy | map | cold | 1000000 | 2,535.88 [2,430.52–2,542.05] | 3/3 |
| svm | truffle-jit | proto-policy | snapshot | cold | 1 | 0.67 [0.67–0.71] | 0/3 |
| svm | truffle-jit | proto-policy | snapshot | cold | 10 | 0.68 [0.68–0.72] | 0/3 |
| svm | truffle-jit | proto-policy | snapshot | cold | 100 | 0.74 [0.73–0.78] | 0/3 |
| svm | truffle-jit | proto-policy | snapshot | cold | 1000 | 26.55 [26.49–27.32] | 0/3 |
| svm | truffle-jit | proto-policy | snapshot | cold | 10000 | 65.10 [64.49–65.91] | 3/3 |
| svm | truffle-jit | proto-policy | snapshot | cold | 100000 | 108.94 [107.35–109.23] | 3/3 |
| svm | truffle-jit | proto-policy | snapshot | cold | 1000000 | 460.18 [452.91–467.29] | 3/3 |
| svm | truffle-jit | proto-policy | snapshot | image-cold | 1 | 0.62 [0.62–0.68] | 0/3 |
| svm | truffle-jit | proto-policy | snapshot | image-cold | 10 | 0.63 [0.63–0.69] | 0/3 |
| svm | truffle-jit | proto-policy | snapshot | image-cold | 100 | 0.69 [0.69–0.75] | 0/3 |
| svm | truffle-jit | proto-policy | snapshot | image-cold | 1000 | 29.17 [28.13–30.28] | 0/3 |
| svm | truffle-jit | proto-policy | snapshot | image-cold | 10000 | 67.48 [67.33–70.37] | 3/3 |
| svm | truffle-jit | proto-policy | snapshot | image-cold | 100000 | 110.95 [110.70–111.07] | 3/3 |
| svm | truffle-jit | proto-policy | snapshot | image-cold | 1000000 | 455.63 [435.25–456.89] | 3/3 |
| svm | truffle-jit | proto-repeated | fresh | cold | 1 | 0.62 [0.57–0.65] | 0/3 |
| svm | truffle-jit | proto-repeated | fresh | cold | 10 | 0.71 [0.67–0.75] | 0/3 |
| svm | truffle-jit | proto-repeated | fresh | cold | 100 | 1.59 [1.51–1.65] | 0/3 |
| svm | truffle-jit | proto-repeated | fresh | cold | 1000 | 24.62 [24.07–25.36] | 0/3 |
| svm | truffle-jit | proto-repeated | fresh | cold | 10000 | 103.88 [102.60–106.11] | 3/3 |
| svm | truffle-jit | proto-repeated | fresh | cold | 100000 | 519.26 [515.90–522.14] | 3/3 |
| svm | truffle-jit | proto-repeated | fresh | cold | 1000000 | 4,614.22 [4,517.33–4,659.23] | 3/3 |
| svm | truffle-jit | proto-repeated | map | cold | 1 | 0.59 [0.56–0.63] | 0/3 |
| svm | truffle-jit | proto-repeated | map | cold | 10 | 0.67 [0.64–0.72] | 0/3 |
| svm | truffle-jit | proto-repeated | map | cold | 100 | 1.44 [1.33–1.46] | 0/3 |
| svm | truffle-jit | proto-repeated | map | cold | 1000 | 22.72 [22.30–23.47] | 0/3 |
| svm | truffle-jit | proto-repeated | map | cold | 10000 | 90.31 [87.74–90.45] | 3/3 |
| svm | truffle-jit | proto-repeated | map | cold | 100000 | 425.05 [408.86–425.07] | 3/3 |
| svm | truffle-jit | proto-repeated | map | cold | 1000000 | 3,719.68 [3,587.34–3,745.03] | 3/3 |
| svm | truffle-jit | proto-repeated | snapshot | cold | 1 | 0.53 [0.53–0.62] | 0/3 |
| svm | truffle-jit | proto-repeated | snapshot | cold | 10 | 0.58 [0.57–0.66] | 0/3 |
| svm | truffle-jit | proto-repeated | snapshot | cold | 100 | 1.05 [1.00–1.12] | 0/3 |
| svm | truffle-jit | proto-repeated | snapshot | cold | 1000 | 19.40 [19.15–19.51] | 0/3 |
| svm | truffle-jit | proto-repeated | snapshot | cold | 10000 | 58.78 [58.07–59.23] | 3/3 |
| svm | truffle-jit | proto-repeated | snapshot | cold | 100000 | 215.85 [208.99–217.23] | 3/3 |
| svm | truffle-jit | proto-repeated | snapshot | cold | 1000000 | 1,640.72 [1,571.95–1,641.14] | 3/3 |
| svm | truffle-jit | proto-repeated | snapshot | image-cold | 1 | 0.52 [0.49–0.54] | 0/3 |
| svm | truffle-jit | proto-repeated | snapshot | image-cold | 10 | 0.57 [0.53–0.58] | 0/3 |
| svm | truffle-jit | proto-repeated | snapshot | image-cold | 100 | 0.99 [0.99–1.09] | 0/3 |
| svm | truffle-jit | proto-repeated | snapshot | image-cold | 1000 | 19.66 [19.26–20.88] | 0/3 |
| svm | truffle-jit | proto-repeated | snapshot | image-cold | 10000 | 59.93 [59.28–60.98] | 3/3 |
| svm | truffle-jit | proto-repeated | snapshot | image-cold | 100000 | 218.64 [210.91–219.49] | 3/3 |
| svm | truffle-jit | proto-repeated | snapshot | image-cold | 1000000 | 1,647.78 [1,625.49–1,656.07] | 3/3 |
| svm | truffle-jit | scalar-wide | fresh | cold | 1 | 0.53 [0.49–0.56] | 0/3 |
| svm | truffle-jit | scalar-wide | fresh | cold | 10 | 0.58 [0.54–0.61] | 0/3 |
| svm | truffle-jit | scalar-wide | fresh | cold | 100 | 1.00 [0.93–1.00] | 0/3 |
| svm | truffle-jit | scalar-wide | fresh | cold | 1000 | 11.88 [11.74–12.48] | 0/3 |
| svm | truffle-jit | scalar-wide | fresh | cold | 10000 | 53.91 [51.86–54.27] | 3/3 |
| svm | truffle-jit | scalar-wide | fresh | cold | 100000 | 318.98 [310.30–329.67] | 3/3 |
| svm | truffle-jit | scalar-wide | fresh | cold | 1000000 | 2,893.14 [2,830.08–3,020.96] | 3/3 |
| svm | truffle-jit | scalar-wide | map | cold | 1 | 0.45 [0.45–0.50] | 0/3 |
| svm | truffle-jit | scalar-wide | map | cold | 10 | 0.48 [0.48–0.53] | 0/3 |
| svm | truffle-jit | scalar-wide | map | cold | 100 | 0.75 [0.75–0.81] | 0/3 |
| svm | truffle-jit | scalar-wide | map | cold | 1000 | 10.63 [10.43–11.44] | 0/3 |
| svm | truffle-jit | scalar-wide | map | cold | 10000 | 41.56 [40.92–43.00] | 3/3 |
| svm | truffle-jit | scalar-wide | map | cold | 100000 | 234.80 [229.00–234.98] | 3/3 |
| svm | truffle-jit | scalar-wide | map | cold | 1000000 | 2,060.49 [1,979.58–2,070.22] | 3/3 |
| svm | truffle-jit | scalar-wide | snapshot | cold | 1 | 0.56 [0.55–0.60] | 0/3 |
| svm | truffle-jit | scalar-wide | snapshot | cold | 10 | 0.56 [0.55–0.61] | 0/3 |
| svm | truffle-jit | scalar-wide | snapshot | cold | 100 | 0.56 [0.55–0.61] | 0/3 |
| svm | truffle-jit | scalar-wide | snapshot | cold | 1000 | 7.45 [7.42–8.16] | 0/3 |
| svm | truffle-jit | scalar-wide | snapshot | cold | 10000 | 9.95 [9.81–10.76] | 3/3 |
| svm | truffle-jit | scalar-wide | snapshot | cold | 100000 | 11.62 [11.53–12.47] | 3/3 |
| svm | truffle-jit | scalar-wide | snapshot | cold | 1000000 | 28.12 [27.70–28.18] | 3/3 |

# Native measurement drift

No fork exceeds 10% first-two versus last-two measured-window drift.

This audit does not prove peak convergence or establish statistical equivalence.
