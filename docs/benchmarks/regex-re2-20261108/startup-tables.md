# Unrelated scalar-wide startup control

Four fresh processes per revision/runtime/engine: one first verified result for each input variant.
All 69 bindings and shared protobuf fixture/ingestion remain present. Runtime-prepared native only.
Times are milliseconds. Ranges are observed minima/maxima, not confidence intervals.

| Runtime | Engine | RE2/J baseline | TruffleRegex candidate | Difference of medians | Median paired difference | Candidate slower pairs |
|---|---|---:|---:|---:|---:|---:|
| jvm | truffle-interpreter | 256.538 [253.209, 263.657] | 258.236 [257.339, 261.488] | +1.698 | +2.876 | 3/4 |
| jvm | truffle-jit | 255.786 [254.722, 258.492] | 258.755 [258.013, 267.564] | +2.969 | +2.969 | 3/4 |
| svm | truffle-interpreter | 1.638 [1.176, 2.006] | 1.733 [1.257, 2.126] | +0.095 | +0.093 | 4/4 |
| svm | truffle-jit | 1.665 [1.405, 2.024] | 1.574 [1.255, 2.191] | -0.091 | -0.091 | 1/4 |

The paired difference is candidate minus baseline for the same input variant. Its median
need not equal the difference of the two unpaired medians. All four unpaired process ranges overlap.
No launch-to-first-result sample had valid last-tier guest code. This fixture contains no regex call;
it checks startup effects on unrelated work, not the first-use cost of compiling a regex.

| Runtime | Engine | Input variant | Baseline ms | Candidate ms | Paired difference ms |
|---|---|---:|---:|---:|---:|
| jvm | truffle-interpreter | 0 | 253.209 | 257.936 | +4.727 |
| jvm | truffle-interpreter | 1 | 254.556 | 257.339 | +2.783 |
| jvm | truffle-interpreter | 2 | 263.657 | 258.535 | -5.122 |
| jvm | truffle-interpreter | 3 | 258.520 | 261.488 | +2.968 |
| jvm | truffle-jit | 0 | 256.422 | 259.279 | +2.856 |
| jvm | truffle-jit | 1 | 254.722 | 267.564 | +12.842 |
| jvm | truffle-jit | 2 | 255.149 | 258.231 | +3.082 |
| jvm | truffle-jit | 3 | 258.492 | 258.013 | -0.478 |
| svm | truffle-interpreter | 0 | 2.006 | 2.104 | +0.098 |
| svm | truffle-interpreter | 1 | 1.176 | 1.257 | +0.081 |
| svm | truffle-interpreter | 2 | 2.003 | 2.126 | +0.123 |
| svm | truffle-interpreter | 3 | 1.273 | 1.361 | +0.088 |
| svm | truffle-jit | 0 | 2.024 | 2.191 | +0.167 |
| svm | truffle-jit | 1 | 1.741 | 1.654 | -0.087 |
| svm | truffle-jit | 2 | 1.589 | 1.494 | -0.095 |
| svm | truffle-jit | 3 | 1.405 | 1.255 | -0.149 |
