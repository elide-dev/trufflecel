# 0.10.0 adoption-main comparison

Median of three process medians [minimum–maximum process median]. Lower is better.
RSS uses three fixed-work process peaks. These ranges are descriptive, not confidence intervals.

## Time (ns/op)

| Runtime / stage | Fixture | TruffleCEL | cel-java | Lower median |
| --- | --- | ---: | ---: | --- |
| jvm / compile | scalar | 1,790.65 [1,380.80–1,799.09] | 247,035.91 [241,677.36–258,202.65] | TruffleCEL |
| jvm / compile | policy | 4,538.38 [4,393.17–4,594.85] | 291,093.63 [289,765.38–294,939.04] | TruffleCEL |
| jvm / compile | comprehension | 7,733.99 [7,226.50–7,919.78] | 542,308.80 [509,124.38–542,393.37] | TruffleCEL |
| svm / compile | scalar | 2,071.79 [2,048.60–2,072.93] | 195,612.65 [195,538.01–195,710.41] | TruffleCEL |
| svm / compile | policy | 4,305.18 [4,304.14–4,322.95] | 204,013.60 [202,523.18–204,327.40] | TruffleCEL |
| svm / compile | comprehension | 6,925.24 [6,902.72–6,925.67] | 247,026.27 [246,184.18–247,606.88] | TruffleCEL |
| jvm / evaluate | scalar | 13.97 [13.94–14.39] | 37.94 [36.50–41.09] | TruffleCEL |
| jvm / evaluate | policy | 42.11 [41.82–42.15] | 138.38 [135.27–142.68] | TruffleCEL |
| jvm / evaluate | comprehension | 97.61 [97.24–102.75] | 523.43 [521.27–539.68] | TruffleCEL |
| svm / evaluate | scalar | 25.03 [24.99–25.07] | 133.74 [133.29–133.95] | TruffleCEL |
| svm / evaluate | policy | 83.81 [83.53–85.54] | 251.81 [249.72–254.24] | TruffleCEL |
| svm / evaluate | comprehension | 119.24 [115.88–119.76] | 834.23 [833.23–834.50] | TruffleCEL |
| svm / image-ast | scalar | 25.18 [25.16–25.19] | 134.53 [133.72–134.96] | TruffleCEL |
| svm / image-ast | policy | 84.34 [83.95–84.35] | 248.72 [248.29–253.51] | TruffleCEL |
| svm / image-ast | comprehension | 116.54 [115.68–116.77] | 833.41 [829.49–840.67] | TruffleCEL |
| jvm / interpret | scalar | 34.64 [33.83–35.37] | 41.34 [34.33–42.00] | TruffleCEL † |
| jvm / interpret | policy | 76.27 [76.12–79.75] | 132.33 [122.57–137.38] | TruffleCEL |
| jvm / interpret | comprehension | 231.61 [221.55–233.43] | 546.02 [525.18–546.09] | TruffleCEL |
| svm / interpret | scalar | 49.02 [48.16–49.58] | 134.27 [134.04–134.35] | TruffleCEL |
| svm / interpret | policy | 137.11 [137.00–137.58] | 251.04 [246.68–254.43] | TruffleCEL |
| svm / interpret | comprehension | 399.88 [399.62–400.03] | 830.10 [829.70–833.52] | TruffleCEL |
| svm / image-interpret | scalar | 48.34 [48.01–49.13] | 134.03 [133.75–135.69] | TruffleCEL |
| svm / image-interpret | policy | 137.19 [136.80–137.32] | 251.52 [248.95–253.01] | TruffleCEL |
| svm / image-interpret | comprehension | 398.62 [398.23–399.23] | 836.90 [831.87–837.94] | TruffleCEL |

† Observed process ranges overlap.

## Current-thread heap allocation (B/op)

| Runtime / stage | Fixture | TruffleCEL | cel-java | Lower median |
| --- | --- | ---: | ---: | --- |
| jvm / compile | scalar | 2,904.00 [2,904.00–2,904.08] | 247,322.85 [247,311.38–257,400.68] | TruffleCEL |
| jvm / compile | policy | 5,152.00 [5,152.00–5,152.00] | 267,552.38 [267,233.94–268,467.86] | TruffleCEL |
| jvm / compile | comprehension | 7,848.00 [7,848.00–7,848.00] | 326,504.34 [323,667.44–326,864.07] | TruffleCEL |
| svm / compile | scalar | 2,496.00 [2,496.00–2,496.00] | 230,272.00 [230,272.00–230,272.00] | TruffleCEL |
| svm / compile | policy | 4,336.00 [4,336.00–4,336.00] | 237,960.00 [237,960.00–237,960.00] | TruffleCEL |
| svm / compile | comprehension | 6,456.00 [6,456.00–6,456.00] | 285,512.00 [285,512.00–285,512.00] | TruffleCEL |
| jvm / evaluate | scalar | 24.00 [24.00–24.00] | 175.45 [174.10–187.13] | TruffleCEL |
| jvm / evaluate | policy | 48.00 [48.00–48.00] | 256.00 [256.00–256.00] | TruffleCEL |
| jvm / evaluate | comprehension | 226.00 [226.00–226.00] | 674.00 [674.00–674.00] | TruffleCEL |
| svm / evaluate | scalar | 16.00 [16.00–16.00] | 216.00 [216.00–216.00] | TruffleCEL |
| svm / evaluate | policy | 40.00 [40.00–40.00] | 412.00 [412.00–412.00] | TruffleCEL |
| svm / evaluate | comprehension | 188.00 [188.00–188.00] | 648.00 [648.00–648.00] | TruffleCEL |
| svm / image-ast | scalar | 16.00 [16.00–16.00] | 216.00 [216.00–216.00] | TruffleCEL |
| svm / image-ast | policy | 40.00 [40.00–40.00] | 412.00 [412.00–412.00] | TruffleCEL |
| svm / image-ast | comprehension | 188.00 [188.00–188.00] | 648.00 [648.00–648.00] | TruffleCEL |
| jvm / interpret | scalar | 96.00 [96.00–96.00] | 188.27 [186.64–190.11] | TruffleCEL |
| jvm / interpret | policy | 120.00 [120.00–120.00] | 264.54 [256.03–304.94] | TruffleCEL |
| jvm / interpret | comprehension | 322.00 [322.00–322.00] | 674.00 [674.00–674.00] | TruffleCEL |
| svm / interpret | scalar | 80.00 [80.00–80.00] | 216.00 [216.00–216.00] | TruffleCEL |
| svm / interpret | policy | 104.00 [104.00–104.00] | 412.00 [412.00–412.00] | TruffleCEL |
| svm / interpret | comprehension | 268.00 [268.00–268.00] | 648.00 [648.00–648.00] | TruffleCEL |
| svm / image-interpret | scalar | 80.00 [80.00–80.00] | 216.00 [216.00–216.00] | TruffleCEL |
| svm / image-interpret | policy | 104.00 [104.00–104.00] | 412.00 [412.00–412.00] | TruffleCEL |
| svm / image-interpret | comprehension | 268.00 [268.00–268.00] | 648.00 [648.00–648.00] | TruffleCEL |

† Observed process ranges overlap.

## Fixed-work peak RSS (MiB)

| Runtime / stage | Fixture | TruffleCEL | cel-java | Lower median |
| --- | --- | ---: | ---: | --- |
| jvm / compile | scalar | 179.12 [179.11–179.33] | 412.50 [411.96–425.98] | TruffleCEL |
| jvm / compile | policy | 181.62 [178.70–182.09] | 415.16 [414.55–416.58] | TruffleCEL |
| jvm / compile | comprehension | 216.03 [211.52–217.53] | 482.41 [482.09–484.52] | TruffleCEL |
| svm / compile | scalar | 54.10 [52.73–54.36] | 78.98 [78.69–79.12] | TruffleCEL |
| svm / compile | policy | 68.98 [67.48–69.02] | 78.84 [78.60–78.84] | TruffleCEL |
| svm / compile | comprehension | 83.02 [82.71–83.38] | 79.26 [79.23–79.52] | cel-java |
| jvm / evaluate | scalar | 192.57 [191.80–192.60] | 244.62 [235.82–244.62] | TruffleCEL |
| jvm / evaluate | policy | 208.49 [207.91–209.31] | 245.07 [244.60–247.32] | TruffleCEL |
| jvm / evaluate | comprehension | 306.56 [305.31–306.59] | 281.00 [280.68–282.19] | cel-java |
| svm / evaluate | scalar | 85.33 [84.38–86.09] | 80.95 [80.69–81.27] | cel-java |
| svm / evaluate | policy | 103.70 [102.90–103.89] | 78.84 [78.70–81.72] | cel-java |
| svm / evaluate | comprehension | 149.88 [148.75–150.06] | 79.48 [78.73–80.39] | cel-java |
| svm / image-ast | scalar | 87.59 [84.84–87.59] | 79.23 [78.98–79.24] | cel-java |
| svm / image-ast | policy | 102.91 [102.14–103.13] | 77.77 [76.67–79.88] | cel-java |
| svm / image-ast | comprehension | 149.94 [148.69–150.12] | 75.90 [75.59–78.34] | cel-java |
| jvm / interpret | scalar | 178.07 [177.79–178.09] | 243.62 [236.39–244.52] | TruffleCEL |
| jvm / interpret | policy | 190.33 [190.03–191.28] | 244.77 [244.71–246.46] | TruffleCEL |
| jvm / interpret | comprehension | 250.07 [248.10–250.83] | 280.26 [279.93–280.49] | TruffleCEL |
| svm / interpret | scalar | 74.90 [73.23–77.17] | 81.57 [81.37–83.57] | TruffleCEL |
| svm / interpret | policy | 83.20 [81.89–83.21] | 79.53 [78.61–79.71] | cel-java |
| svm / interpret | comprehension | 83.86 [82.58–84.11] | 78.73 [77.73–78.77] | cel-java |
| svm / image-interpret | scalar | 74.30 [74.11–74.76] | 78.66 [78.48–79.36] | TruffleCEL |
| svm / image-interpret | policy | 82.89 [82.38–83.28] | 77.43 [76.74–79.80] | cel-java |
| svm / image-interpret | comprehension | 83.86 [82.77–84.79] | 76.14 [76.14–76.28] | cel-java |

† Observed process ranges overlap.
