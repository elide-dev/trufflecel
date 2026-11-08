# Median allocated bytes per operation

All seven raw windows and their verified results are retained beside this file. These are allocation observations, not throughput or process-memory measurements.

### OptimizedRuntime interpreter

| Fixture | Baseline | Context only | Combined |
| --- | ---: | ---: | ---: |
| scalar-map | 128 | 96 | 96 |
| scalar-snapshot | 128 | 96 | 96 |
| scalar-wide-map | 152 | 120 | 120 |
| scalar-wide-snapshot | 128 | 96 | 96 |
| scalar-controlled | 216 | 216 | 216 |
| scalar-controlled-snapshot | 160 | 160 | 160 |
| simple-lazy | 184 | 152 | 152 |
| captured-lazy | 480 | 440 | 440 |
| comprehension | 376 | 344 | 344 |
| cse | 304 | 264 | 264 |
| converted-1-declaration | 200 | 168 | 168 |
| converted-69-declarations | 472 | 440 | 168 |
| converted-1000-declarations | 4,192 | 4,160 | 168 |
| compile-scalar-inferred | 3,784.71 | 3,753.56 | 3,383.73 |
| compile-scalar-reused-environment | 3,468.50 | 3,434.92 | 3,036.43 |

### Fallback interpreter

| Fixture | Baseline | Context only | Combined |
| --- | ---: | ---: | ---: |
| scalar-map | 160 | 128 | 128 |
| scalar-snapshot | 160 | 128 | 128 |
| scalar-wide-map | 184 | 152 | 152 |
| scalar-wide-snapshot | 160 | 128 | 128 |
| scalar-controlled | 248 | 248 | 248 |
| scalar-controlled-snapshot | 224 | 192 | 224 |
| simple-lazy | 216 | 184 | 184 |
| captured-lazy | 512 | 472 | 472 |
| comprehension | 408 | 376 | 376 |
| cse | 336 | 296 | 296 |
| converted-1-declaration | 232 | 200 | 200 |
| converted-69-declarations | 504 | 472 | 200 |
| converted-1000-declarations | 4,224 | 4,192 | 200 |
| compile-scalar-inferred | 3,504 | 3,472 | 3,112 |
| compile-scalar-reused-environment | 3,184 | 3,152 | 2,760 |
