# Measured values

Generated from the retained raw files. Times are medians of three process means
(JMH/native steady state) or three fresh-process totals (cold curves).
JMH means/errors and process minimum/maximum values are retained in `summary.json`.

## JVM evaluate

| Workload | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized | cel-java Pratt + optimized |
| --- | ---: | ---: | ---: | ---: | ---: |
| scalar | 18.18 | 8.63 | 26.93 | 28.19 | 27.16 |
| scalar-wide | 1,726.42 | 1,706.52 | 506.05 | 481.70 | 528.01 |
| proto-policy | 2,409.75 | 2,383.45 | 1,218.93 | 1,170.67 | 1,081.10 |
| proto-repeated | 5,950.78 | 4,674.57 | 7,627.56 | 7,673.37 | 7,385.05 |
| map-regex | 2,453.20 | 2,252.96 | 2,598.49 | 2,661.17 | 2,563.13 |
| optimizer-policy | 1,956.93 | 1,875.29 | 766.06 | 720.79 | 705.08 |

Unit: ns/op. Guest compilation is completed before steady-state timing.

## JVM compile

| Workload | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized | cel-java Pratt + optimized |
| --- | ---: | ---: | ---: | ---: | ---: |
| scalar | 0.94 | 0.94 | 108.66 | 114.74 | 113.57 |
| scalar-wide | 0.96 | 0.95 | 110.45 | 116.20 | 111.59 |
| proto-policy | 4.26 | 4.42 | 131.85 | 456.41 | 438.75 |
| proto-repeated | 2.52 | 2.61 | 128.37 | 355.89 | 340.85 |
| map-regex | 2.82 | 2.84 | 124.50 | 350.03 | 334.64 |
| optimizer-policy | 4.83 | 4.85 | 134.87 | 1,014.37 | 981.35 |

Unit: µs/op. Compilation includes frontend work and program creation; it excludes guest compilation.

## Native steady

| Workload | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized | cel-java Pratt + optimized |
| --- | ---: | ---: | ---: | ---: | ---: |
| scalar | 29.53 | 20.45 | 79.29 | 79.28 | 81.60 |
| scalar-wide | 2,023.14 | 1,924.21 | 1,255.44 | 1,279.90 | 1,310.62 |
| proto-policy | 2,936.60 | 2,957.37 | 2,449.39 | 2,266.35 | 2,331.92 |
| proto-repeated | 7,694.36 | 5,576.26 | 11,824.62 | 12,652.38 | 11,882.53 |
| map-regex | 3,025.09 | 2,807.42 | 6,139.25 | 6,457.67 | 6,353.00 |
| optimizer-policy | 2,372.43 | 2,250.60 | 1,701.76 | 1,575.57 | 1,588.61 |

Unit: ns/op. Guest compilation is completed before steady-state timing.

## Native image-steady

| Workload | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized | cel-java Pratt + optimized |
| --- | ---: | ---: | ---: | ---: | ---: |
| scalar | 29.61 | 20.18 | 82.72 | 80.35 | 79.02 |
| scalar-wide | 1,923.59 | 1,940.60 | 1,284.35 | 1,297.42 | 1,292.31 |
| proto-policy | 2,941.04 | 2,811.53 | 2,465.66 | 2,291.49 | 2,394.84 |
| proto-repeated | 7,667.95 | 5,465.15 | 11,878.77 | 11,858.51 | 11,804.66 |
| map-regex | 3,046.47 | 2,686.38 | 6,382.44 | 6,422.30 | 6,262.15 |
| optimizer-policy | 2,355.53 | 2,273.04 | 1,698.91 | 1,616.54 | 1,557.28 |

Unit: ns/op. Guest compilation is completed before steady-state timing.

## Upstream frontend stages

| Parser/optimizer | Source | Stage | µs/op |
| --- | --- | --- | ---: |
| cel-java optimized | `true` | check | 106.82 |
| cel-java optimized | `x * 2 + 1` | check | 106.91 |
| cel-java Pratt + optimized | `true` | check | 103.32 |
| cel-java Pratt + optimized | `x * 2 + 1` | check | 106.43 |
| cel-java optimized | `true` | optimize | 1.06 |
| cel-java optimized | `x * 2 + 1` | optimize | 3.45 |
| cel-java Pratt + optimized | `true` | optimize | 1.05 |
| cel-java Pratt + optimized | `x * 2 + 1` | optimize | 3.45 |
| cel-java optimized | `true` | parse | 1.07 |
| cel-java optimized | `x * 2 + 1` | parse | 2.41 |
| cel-java Pratt + optimized | `true` | parse | 0.08 |
| cel-java Pratt + optimized | `x * 2 + 1` | parse | 0.16 |
| cel-java optimized | `true` | plan | 0.01 |
| cel-java optimized | `x * 2 + 1` | plan | 0.08 |
| cel-java Pratt + optimized | `true` | plan | 0.01 |
| cel-java Pratt + optimized | `x * 2 + 1` | plan | 0.08 |

## Fresh-process totals

Configuration, runtime expression preparation and planning are included. Fixture creation
and VM launch are excluded. Image modes omit the frontend work done during image construction.

| Runtime/mode | Engine | Workload | First 1 (ms) | 10 | 100 | 1,000 | 10,000 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| jvm/cold | cel-java default | map-regex | 164.091 | 165.635 | 171.243 | 199.090 | 235.705 |
| jvm/cold | cel-java default | optimizer-policy | 165.874 | 166.387 | 170.651 | 186.773 | 217.515 |
| jvm/cold | cel-java default | proto-policy | 167.965 | 168.902 | 175.937 | 198.059 | 242.492 |
| jvm/cold | cel-java default | proto-repeated | 163.971 | 166.980 | 174.509 | 197.819 | 311.830 |
| jvm/cold | cel-java default | scalar | 154.375 | 154.442 | 154.829 | 158.160 | 164.985 |
| jvm/cold | cel-java default | scalar-wide | 155.726 | 156.048 | 158.074 | 164.016 | 188.528 |
| jvm/cold | cel-java optimized | map-regex | 185.491 | 186.972 | 198.029 | 219.828 | 258.451 |
| jvm/cold | cel-java optimized | optimizer-policy | 195.108 | 195.587 | 199.065 | 211.177 | 245.475 |
| jvm/cold | cel-java optimized | proto-policy | 193.273 | 194.110 | 201.006 | 222.877 | 263.022 |
| jvm/cold | cel-java optimized | proto-repeated | 188.404 | 191.465 | 199.413 | 220.099 | 333.082 |
| jvm/cold | cel-java optimized | scalar | 172.440 | 172.510 | 172.904 | 176.065 | 185.578 |
| jvm/cold | cel-java optimized | scalar-wide | 164.236 | 164.593 | 166.524 | 174.527 | 196.284 |
| jvm/cold | cel-java Pratt + optimized | map-regex | 175.382 | 177.019 | 183.018 | 210.076 | 245.786 |
| jvm/cold | cel-java Pratt + optimized | optimizer-policy | 193.694 | 194.165 | 197.812 | 211.621 | 238.243 |
| jvm/cold | cel-java Pratt + optimized | proto-policy | 169.197 | 170.012 | 178.248 | 206.716 | 247.066 |
| jvm/cold | cel-java Pratt + optimized | proto-repeated | 176.237 | 179.324 | 190.434 | 210.547 | 319.568 |
| jvm/cold | cel-java Pratt + optimized | scalar | 149.501 | 149.567 | 149.972 | 153.138 | 159.541 |
| jvm/cold | cel-java Pratt + optimized | scalar-wide | 156.167 | 156.509 | 158.489 | 163.997 | 189.028 |
| jvm/cold | TruffleCEL interpreter | map-regex | 84.217 | 84.969 | 89.813 | 109.103 | 162.736 |
| jvm/cold | TruffleCEL interpreter | optimizer-policy | 79.987 | 80.473 | 85.007 | 95.784 | 132.978 |
| jvm/cold | TruffleCEL interpreter | proto-policy | 86.074 | 86.884 | 91.977 | 109.529 | 161.672 |
| jvm/cold | TruffleCEL interpreter | proto-repeated | 80.333 | 81.886 | 89.272 | 115.112 | 232.736 |
| jvm/cold | TruffleCEL interpreter | scalar | 78.527 | 78.580 | 78.857 | 81.373 | 86.663 |
| jvm/cold | TruffleCEL interpreter | scalar-wide | 82.809 | 83.135 | 84.789 | 92.032 | 119.548 |
| jvm/cold | TruffleCEL guest JIT | map-regex | 83.888 | 84.648 | 89.626 | 175.058 | 255.836 |
| jvm/cold | TruffleCEL guest JIT | optimizer-policy | 84.218 | 84.742 | 87.809 | 170.907 | 253.191 |
| jvm/cold | TruffleCEL guest JIT | proto-policy | 85.101 | 85.960 | 91.087 | 187.245 | 290.705 |
| jvm/cold | TruffleCEL guest JIT | proto-repeated | 80.742 | 82.387 | 90.137 | 167.478 | 283.132 |
| jvm/cold | TruffleCEL guest JIT | scalar | 78.452 | 78.504 | 78.770 | 124.731 | 142.026 |
| jvm/cold | TruffleCEL guest JIT | scalar-wide | 82.984 | 83.328 | 85.764 | 137.640 | 180.418 |
| svm/cold | cel-java default | map-regex | 1.231 | 1.305 | 1.956 | 8.663 | 74.288 |
| svm/image-cold | cel-java default | map-regex | 0.388 | 0.461 | 1.109 | 7.863 | 73.424 |
| svm/cold | cel-java default | optimizer-policy | 1.892 | 1.932 | 2.189 | 4.370 | 25.876 |
| svm/image-cold | cel-java default | optimizer-policy | 0.801 | 0.831 | 1.042 | 3.122 | 24.261 |
| svm/cold | cel-java default | proto-policy | 1.799 | 1.836 | 2.120 | 4.908 | 33.120 |
| svm/image-cold | cel-java default | proto-policy | 0.852 | 0.888 | 1.184 | 4.097 | 32.833 |
| svm/cold | cel-java default | proto-repeated | 1.213 | 1.359 | 2.716 | 15.807 | 141.810 |
| svm/image-cold | cel-java default | proto-repeated | 0.377 | 0.507 | 1.766 | 15.296 | 142.344 |
| svm/cold | cel-java default | scalar | 1.160 | 1.163 | 1.177 | 1.315 | 2.434 |
| svm/image-cold | cel-java default | scalar | 0.222 | 0.224 | 0.236 | 0.352 | 1.488 |
| svm/cold | cel-java default | scalar-wide | 1.043 | 1.063 | 1.219 | 2.917 | 18.603 |
| svm/image-cold | cel-java default | scalar-wide | 0.281 | 0.300 | 0.452 | 1.959 | 18.138 |
| svm/cold | cel-java optimized | map-regex | 1.705 | 1.779 | 2.430 | 9.146 | 74.486 |
| svm/image-cold | cel-java optimized | map-regex | 0.409 | 0.488 | 1.146 | 7.845 | 73.012 |
| svm/cold | cel-java optimized | optimizer-policy | 3.577 | 3.605 | 3.838 | 5.870 | 25.783 |
| svm/image-cold | cel-java optimized | optimizer-policy | 0.624 | 0.650 | 0.846 | 2.740 | 22.248 |
| svm/cold | cel-java optimized | proto-policy | 2.463 | 2.497 | 2.757 | 5.376 | 31.561 |
| svm/image-cold | cel-java optimized | proto-policy | 0.822 | 0.854 | 1.109 | 3.701 | 30.144 |
| svm/cold | cel-java optimized | proto-repeated | 1.745 | 1.886 | 3.279 | 16.997 | 148.307 |
| svm/image-cold | cel-java optimized | proto-repeated | 0.422 | 0.559 | 1.873 | 15.784 | 146.558 |
| svm/cold | cel-java optimized | scalar | 1.093 | 1.094 | 1.106 | 1.217 | 2.259 |
| svm/image-cold | cel-java optimized | scalar | 0.244 | 0.246 | 0.256 | 0.364 | 1.440 |
| svm/cold | cel-java optimized | scalar-wide | 1.064 | 1.084 | 1.237 | 2.865 | 19.091 |
| svm/image-cold | cel-java optimized | scalar-wide | 0.316 | 0.336 | 0.482 | 1.948 | 17.916 |
| svm/cold | cel-java Pratt + optimized | map-regex | 1.344 | 1.420 | 2.081 | 8.698 | 74.328 |
| svm/image-cold | cel-java Pratt + optimized | map-regex | 0.424 | 0.498 | 1.159 | 7.964 | 74.516 |
| svm/cold | cel-java Pratt + optimized | optimizer-policy | 2.965 | 2.994 | 3.202 | 5.206 | 25.287 |
| svm/image-cold | cel-java Pratt + optimized | optimizer-policy | 0.707 | 0.733 | 0.920 | 2.789 | 22.255 |
| svm/cold | cel-java Pratt + optimized | proto-policy | 2.022 | 2.059 | 2.334 | 5.046 | 31.672 |
| svm/image-cold | cel-java Pratt + optimized | proto-policy | 0.790 | 0.823 | 1.085 | 3.736 | 30.055 |
| svm/cold | cel-java Pratt + optimized | proto-repeated | 1.422 | 1.563 | 2.909 | 16.553 | 145.552 |
| svm/image-cold | cel-java Pratt + optimized | proto-repeated | 0.411 | 0.551 | 1.891 | 15.813 | 144.627 |
| svm/cold | cel-java Pratt + optimized | scalar | 0.806 | 0.809 | 0.827 | 0.940 | 2.058 |
| svm/image-cold | cel-java Pratt + optimized | scalar | 0.251 | 0.253 | 0.266 | 0.384 | 1.484 |
| svm/cold | cel-java Pratt + optimized | scalar-wide | 0.819 | 0.846 | 0.998 | 2.529 | 18.176 |
| svm/image-cold | cel-java Pratt + optimized | scalar-wide | 0.309 | 0.334 | 0.481 | 2.057 | 18.116 |
| svm/cold | TruffleCEL interpreter | map-regex | 0.650 | 0.702 | 1.101 | 5.071 | 45.416 |
| svm/image-cold | TruffleCEL interpreter | map-regex | 0.519 | 0.566 | 0.968 | 5.012 | 46.459 |
| svm/cold | TruffleCEL interpreter | optimizer-policy | 0.612 | 0.663 | 0.996 | 4.407 | 36.706 |
| svm/image-cold | TruffleCEL interpreter | optimizer-policy | 0.473 | 0.506 | 0.805 | 4.083 | 35.645 |
| svm/cold | TruffleCEL interpreter | proto-policy | 0.644 | 0.693 | 1.079 | 4.851 | 42.582 |
| svm/image-cold | TruffleCEL interpreter | proto-policy | 0.549 | 0.593 | 0.978 | 4.886 | 43.198 |
| svm/cold | TruffleCEL interpreter | proto-repeated | 0.623 | 0.727 | 1.669 | 11.146 | 99.232 |
| svm/image-cold | TruffleCEL interpreter | proto-repeated | 0.522 | 0.622 | 1.591 | 11.088 | 98.460 |
| svm/cold | TruffleCEL interpreter | scalar | 0.561 | 0.562 | 0.567 | 0.606 | 1.026 |
| svm/image-cold | TruffleCEL interpreter | scalar | 0.322 | 0.323 | 0.328 | 0.369 | 0.789 |
| svm/cold | TruffleCEL interpreter | scalar-wide | 0.496 | 0.532 | 0.803 | 3.588 | 31.572 |
| svm/image-cold | TruffleCEL interpreter | scalar-wide | 0.491 | 0.525 | 0.832 | 3.685 | 31.140 |
| svm/cold | TruffleCEL guest JIT | map-regex | 0.655 | 0.704 | 1.111 | 18.310 | 67.040 |
| svm/image-cold | TruffleCEL guest JIT | map-regex | 0.420 | 0.470 | 0.857 | 16.975 | 64.058 |
| svm/cold | TruffleCEL guest JIT | optimizer-policy | 0.620 | 0.662 | 0.976 | 21.251 | 68.042 |
| svm/image-cold | TruffleCEL guest JIT | optimizer-policy | 0.394 | 0.427 | 0.720 | 19.724 | 65.111 |
| svm/cold | TruffleCEL guest JIT | proto-policy | 0.686 | 0.741 | 1.162 | 22.899 | 77.403 |
| svm/image-cold | TruffleCEL guest JIT | proto-policy | 0.431 | 0.474 | 0.861 | 21.578 | 75.975 |
| svm/cold | TruffleCEL guest JIT | proto-repeated | 0.632 | 0.736 | 1.799 | 21.334 | 102.923 |
| svm/image-cold | TruffleCEL guest JIT | proto-repeated | 0.541 | 0.651 | 1.642 | 20.499 | 104.558 |
| svm/cold | TruffleCEL guest JIT | scalar | 0.527 | 0.528 | 0.533 | 8.306 | 10.999 |
| svm/image-cold | TruffleCEL guest JIT | scalar | 0.291 | 0.293 | 0.298 | 6.732 | 9.263 |
| svm/cold | TruffleCEL guest JIT | scalar-wide | 0.569 | 0.602 | 0.866 | 10.918 | 41.088 |
| svm/image-cold | TruffleCEL guest JIT | scalar-wide | 0.414 | 0.445 | 0.710 | 9.561 | 38.497 |

## Protobuf wire ingestion

| Runtime | Engine | ns/op |
| --- | --- | ---: |
| jvm | cel-java default | 495.70 |
| jvm | TruffleCEL interpreter | 3,369.70 |
| svm | cel-java default | 738.23 |
| svm | TruffleCEL interpreter | 5,375.10 |

TruffleCEL decodes to native snapshots; cel-java uses generated protobuf `parseFrom`.
These are different output representations. Neither timing is included in evaluation.
