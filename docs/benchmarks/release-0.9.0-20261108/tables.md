# Released 0.9.0 comparison

Median of three process medians [minimum–maximum process median]. Lower is better.
RSS uses three fixed-work process peaks. These ranges are descriptive, not confidence intervals.

## Time (ns/op)

| Runtime / stage | Fixture | TruffleCEL | cel-java | Lower median |
| --- | --- | ---: | ---: | --- |
| jvm / compile | scalar | 1,266.95 [1,233.74–1,731.13] | 241,521.38 [223,786.52–248,120.64] | TruffleCEL |
| jvm / compile | policy | 3,804.19 [3,705.56–3,911.37] | 281,115.81 [260,874.23–291,485.87] | TruffleCEL |
| jvm / compile | comprehension | 6,362.68 [6,302.94–6,829.78] | 484,200.40 [474,040.69–484,342.66] | TruffleCEL |
| svm / compile | scalar | 2,185.24 [2,175.18–2,187.61] | 199,547.76 [197,247.66–203,992.35] | TruffleCEL |
| svm / compile | policy | 4,138.49 [4,084.42–4,161.26] | 208,311.37 [206,823.30–213,834.01] | TruffleCEL |
| svm / compile | comprehension | 6,789.40 [6,715.75–6,854.55] | 250,120.45 [248,732.42–255,258.85] | TruffleCEL |
| jvm / evaluate | scalar | 13.61 [13.04–14.26] | 36.21 [35.89–37.16] | TruffleCEL |
| jvm / evaluate | policy | 46.70 [46.29–48.21] | 126.29 [123.80–128.65] | TruffleCEL |
| jvm / evaluate | comprehension | 92.85 [92.43–96.02] | 502.71 [486.58–518.65] | TruffleCEL |
| svm / evaluate | scalar | 24.03 [23.94–24.29] | 124.85 [123.92–127.83] | TruffleCEL |
| svm / evaluate | policy | 98.94 [98.78–99.66] | 236.20 [235.25–244.98] | TruffleCEL |
| svm / evaluate | comprehension | 109.42 [107.77–110.42] | 786.63 [773.18–802.77] | TruffleCEL |
| svm / image-ast | scalar | 24.33 [24.07–24.91] | 124.64 [123.52–125.10] | TruffleCEL |
| svm / image-ast | policy | 97.57 [96.02–99.58] | 238.04 [236.68–248.24] | TruffleCEL |
| svm / image-ast | comprehension | 108.28 [107.82–109.71] | 791.81 [786.63–796.60] | TruffleCEL |
| jvm / interpret | scalar | 33.24 [32.46–36.84] | 35.85 [35.02–36.96] | TruffleCEL † |
| jvm / interpret | policy | 92.41 [81.61–92.60] | 125.73 [117.06–127.92] | TruffleCEL |
| jvm / interpret | comprehension | 238.62 [224.55–240.91] | 518.72 [501.67–562.90] | TruffleCEL |
| svm / interpret | scalar | 43.88 [43.81–43.97] | 125.26 [123.50–126.57] | TruffleCEL |
| svm / interpret | policy | 148.28 [147.77–148.69] | 235.95 [233.90–244.52] | TruffleCEL |
| svm / interpret | comprehension | 368.84 [358.97–373.87] | 790.77 [787.58–796.81] | TruffleCEL |
| svm / image-interpret | scalar | 44.36 [43.78–44.54] | 124.47 [123.88–125.55] | TruffleCEL |
| svm / image-interpret | policy | 148.57 [148.37–149.91] | 239.08 [230.39–241.75] | TruffleCEL |
| svm / image-interpret | comprehension | 363.81 [359.26–366.81] | 777.52 [775.57–789.60] | TruffleCEL |

† Observed process ranges overlap.

## Current-thread heap allocation (B/op)

| Runtime / stage | Fixture | TruffleCEL | cel-java | Lower median |
| --- | --- | ---: | ---: | --- |
| jvm / compile | scalar | 3,288.00 [3,288.00–3,288.00] | 257,626.86 [257,400.68–261,920.59] | TruffleCEL |
| jvm / compile | policy | 4,952.00 [4,952.00–4,952.07] | 267,989.62 [267,066.03–268,648.46] | TruffleCEL |
| jvm / compile | comprehension | 8,136.00 [8,136.00–8,136.00] | 324,052.10 [323,555.62–326,934.56] | TruffleCEL |
| svm / compile | scalar | 2,832.00 [2,832.00–2,832.00] | 230,184.00 [230,184.00–230,184.00] | TruffleCEL |
| svm / compile | policy | 4,200.00 [4,200.00–4,200.00] | 237,872.00 [237,872.00–237,872.00] | TruffleCEL |
| svm / compile | comprehension | 6,816.00 [6,816.00–6,816.00] | 285,424.00 [285,424.00–285,424.00] | TruffleCEL |
| jvm / evaluate | scalar | 24.00 [24.00–24.00] | 185.36 [171.20–193.49] | TruffleCEL |
| jvm / evaluate | policy | 48.00 [48.00–48.00] | 256.00 [256.00–256.03] | TruffleCEL |
| jvm / evaluate | comprehension | 234.00 [234.00–234.00] | 674.00 [674.00–674.00] | TruffleCEL |
| svm / evaluate | scalar | 16.00 [16.00–16.00] | 216.00 [216.00–216.00] | TruffleCEL |
| svm / evaluate | policy | 40.00 [40.00–40.00] | 412.00 [412.00–412.00] | TruffleCEL |
| svm / evaluate | comprehension | 196.00 [196.00–196.00] | 648.00 [648.00–648.00] | TruffleCEL |
| svm / image-ast | scalar | 16.00 [16.00–16.00] | 216.00 [216.00–216.00] | TruffleCEL |
| svm / image-ast | policy | 40.00 [40.00–40.00] | 412.00 [412.00–412.00] | TruffleCEL |
| svm / image-ast | comprehension | 196.00 [196.00–196.00] | 648.00 [648.00–648.00] | TruffleCEL |
| jvm / interpret | scalar | 128.00 [128.00–128.00] | 174.25 [163.97–190.82] | TruffleCEL |
| jvm / interpret | policy | 152.00 [152.00–152.00] | 256.00 [256.00–256.00] | TruffleCEL |
| jvm / interpret | comprehension | 362.00 [362.00–362.00] | 674.00 [629.50–674.00] | TruffleCEL |
| svm / interpret | scalar | 104.00 [104.00–104.00] | 216.00 [216.00–216.00] | TruffleCEL |
| svm / interpret | policy | 128.00 [128.00–128.00] | 412.00 [412.00–412.00] | TruffleCEL |
| svm / interpret | comprehension | 300.00 [300.00–300.00] | 648.00 [648.00–648.00] | TruffleCEL |
| svm / image-interpret | scalar | 104.00 [104.00–104.00] | 216.00 [216.00–216.00] | TruffleCEL |
| svm / image-interpret | policy | 128.00 [128.00–128.00] | 412.00 [412.00–412.00] | TruffleCEL |
| svm / image-interpret | comprehension | 300.00 [300.00–300.00] | 648.00 [648.00–648.00] | TruffleCEL |

† Observed process ranges overlap.

## Fixed-work peak RSS (MiB)

| Runtime / stage | Fixture | TruffleCEL | cel-java | Lower median |
| --- | --- | ---: | ---: | --- |
| jvm / compile | scalar | 177.52 [176.86–177.67] | 413.16 [411.91–413.75] | TruffleCEL |
| jvm / compile | policy | 179.40 [174.72–180.94] | 415.30 [404.32–421.48] | TruffleCEL |
| jvm / compile | comprehension | 215.58 [214.60–216.31] | 481.56 [481.04–495.78] | TruffleCEL |
| svm / compile | scalar | 55.47 [54.07–56.24] | 76.15 [75.07–76.19] | TruffleCEL |
| svm / compile | policy | 65.38 [65.10–65.57] | 76.20 [75.38–76.36] | TruffleCEL |
| svm / compile | comprehension | 81.28 [80.27–81.30] | 76.00 [75.30–76.38] | cel-java |
| jvm / evaluate | scalar | 194.99 [194.77–195.85] | 234.98 [234.15–235.11] | TruffleCEL |
| jvm / evaluate | policy | 209.99 [209.64–212.71] | 244.29 [243.31–244.74] | TruffleCEL |
| jvm / evaluate | comprehension | 307.83 [307.11–307.91] | 280.19 [279.41–281.00] | cel-java |
| svm / evaluate | scalar | 81.26 [80.97–82.08] | 78.23 [77.93–80.07] | cel-java |
| svm / evaluate | policy | 99.07 [98.73–101.13] | 77.88 [77.18–79.18] | cel-java |
| svm / evaluate | comprehension | 141.04 [140.55–142.32] | 75.00 [74.82–75.17] | cel-java |
| svm / image-ast | scalar | 81.89 [81.45–83.39] | 76.29 [76.11–77.31] | cel-java |
| svm / image-ast | policy | 98.52 [98.40–99.50] | 74.71 [73.92–75.08] | cel-java |
| svm / image-ast | comprehension | 141.34 [140.79–142.71] | 73.01 [72.85–74.21] | cel-java |
| jvm / interpret | scalar | 191.06 [190.60–191.54] | 235.25 [235.08–236.15] | TruffleCEL |
| jvm / interpret | policy | 202.42 [202.36–204.79] | 243.38 [243.21–244.31] | TruffleCEL |
| jvm / interpret | comprehension | 247.34 [247.26–248.75] | 280.80 [280.42–281.52] | TruffleCEL |
| svm / interpret | scalar | 80.45 [80.04–81.49] | 78.71 [78.51–78.78] | cel-java |
| svm / interpret | policy | 80.29 [79.96–80.49] | 77.01 [76.21–77.10] | cel-java |
| svm / interpret | comprehension | 81.14 [80.39–82.09] | 75.19 [74.76–76.00] | cel-java |
| svm / image-interpret | scalar | 79.80 [79.79–81.16] | 76.10 [75.99–76.58] | cel-java |
| svm / image-interpret | policy | 80.18 [79.62–81.09] | 75.20 [73.90–75.60] | cel-java |
| svm / image-interpret | comprehension | 80.15 [78.98–80.16] | 72.98 [72.75–74.29] | cel-java |

† Observed process ranges overlap.
