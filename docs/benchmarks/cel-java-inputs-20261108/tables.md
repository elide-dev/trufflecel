# cel-java full-input representation controls

Three independent fork means per value: median [minimum, maximum]. Ranges are not confidence intervals.
All cells use four full 69-binding inputs and the same captured-array evaluation path.
Caller-side immutable-map preparation is excluded. Timing and allocation use separate processes.

| Runtime | Workload | Optimizer | Ordinary map ns/op | Immutable map ns/op | Ordinary B/op | Immutable B/op |
|---|---|---|---:|---:|---:|---:|
| jvm | scalar-wide | default | 563.50 [516.20, 571.65] | 25.17 [25.16, 25.25] | 2936.00 [2936.00, 2936.00] | 72.00 [72.00, 72.00] |
| jvm | scalar-wide | optimized | 523.15 [517.85, 558.84] | 25.40 [25.30, 26.45] | 2936.00 [2936.00, 2936.00] | 72.00 [72.00, 72.00] |
| jvm | proto-policy | default | 1220.27 [1183.14, 1276.56] | 687.33 [677.49, 703.94] | 4158.00 [4158.00, 4158.00] | 1294.00 [1294.00, 1294.00] |
| jvm | proto-policy | optimized | 1170.68 [1061.45, 1243.41] | 632.65 [607.32, 653.50] | 4046.00 [4046.00, 4046.00] | 1182.00 [1182.00, 1182.00] |
| svm | scalar-wide | default | 1275.51 [1264.41, 1287.16] | 63.61 [63.19, 63.78] | 3504.00 [3504.00, 3504.00] | 120.00 [120.00, 120.00] |
| svm | scalar-wide | optimized | 1302.48 [1233.92, 1395.30] | 63.28 [63.23, 63.43] | 3504.00 [3504.00, 3504.00] | 120.00 [120.00, 120.00] |
| svm | proto-policy | default | 2506.15 [2484.78, 2648.95] | 1246.39 [1232.93, 1312.26] | 4794.00 [4794.00, 4794.00] | 1410.00 [1410.00, 1410.00] |
| svm | proto-policy | optimized | 2444.35 [2246.00, 2520.04] | 1030.83 [1030.09, 1106.12] | 4598.00 [4598.00, 4598.00] | 1214.00 [1214.00, 1214.00] |
