# Full activation comparison

Time is ns/op: median of three process means [minimum–maximum].
Allocation is Java heap bytes/op; no ingestion is included.

| Runtime | Workload | Entry | Reference ns/op | Candidate ns/op | Speedup | Reference B/op | Candidate B/op |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| truffle-interpreter | scalar-wide | evaluateMap | 1,701.7 [1,643.2–1,753.9] | 1,730.3 [1,729.9–1,754.8] | 0.98× | 7,760.1 | 7,760.1 |
| truffle-interpreter | proto-policy | evaluateMap | 2,396.6 [2,300.2–2,408.9] | 2,290.8 [2,283.4–2,387.0] | 1.05× | 8,944.1 | 8,944.1 |
| truffle-interpreter | map-regex | evaluateMap | 2,476.7 [2,430.5–2,507.6] | 2,500.1 [2,485.4–2,637.4] | 0.99× | 10,382.1 | 10,382.1 |
| truffle-jit | scalar-wide | evaluateMap | 1,704.0 [1,700.0–1,722.0] | 1,717.5 [1,705.6–1,718.3] | 0.99× | 7,672.1 | 7,672.1 |
| truffle-jit | proto-policy | evaluateMap | 2,298.1 [2,275.0–2,336.3] | 2,338.6 [2,237.0–2,345.5] | 0.98× | 8,920.1 | 8,920.1 |
| truffle-jit | map-regex | evaluateMap | 2,272.0 [2,249.4–2,374.1] | 2,277.5 [2,269.2–2,303.4] | 1.00× | 10,258.1 | 10,258.1 |
| truffle-interpreter | scalar-wide | evaluateSnapshot | 16.9 [16.9–17.9] | 16.9 [16.7–16.9] | 1.01× | 112.0 | 112.0 |
| truffle-interpreter | proto-policy | evaluateSnapshot | 541.8 [530.9–549.7] | 527.8 [511.8–545.9] | 1.03× | 1,296.0 | 1,296.0 |
| truffle-interpreter | map-regex | evaluateSnapshot | 711.2 [689.0–712.1] | 718.1 [708.3–725.7] | 0.99× | 2,734.0 | 2,722.0 |
| truffle-jit | scalar-wide | evaluateSnapshot | 8.2 [8.2–8.5] | 8.1 [8.0–8.2] | 1.01× | 24.0 | 24.0 |
| truffle-jit | proto-policy | evaluateSnapshot | 492.1 [491.2–508.4] | 475.2 [473.4–483.9] | 1.04× | 1,272.0 | 1,272.0 |
| truffle-jit | map-regex | evaluateSnapshot | 503.7 [503.3–523.5] | 507.7 [501.4–515.4] | 0.99× | 2,610.0 | 2,610.0 |
| truffle-interpreter | scalar-wide | snapshotAndEvaluate | 2,299.6 [2,295.0–2,325.3] | 2,389.8 [2,330.6–2,410.2] | 0.96× | 9,816.1 | 9,816.1 |
| truffle-interpreter | proto-policy | snapshotAndEvaluate | 3,016.7 [3,008.5–3,023.3] | 2,935.6 [2,934.8–2,946.4] | 1.03× | 11,000.2 | 10,976.1 |
| truffle-interpreter | map-regex | snapshotAndEvaluate | 3,213.0 [3,159.8–3,368.4] | 3,106.9 [3,083.5–3,127.1] | 1.03× | 12,426.2 | 12,402.2 |
| truffle-jit | scalar-wide | snapshotAndEvaluate | 2,338.8 [2,334.8–2,342.1] | 2,308.1 [2,307.5–2,316.5] | 1.01× | 9,728.1 | 9,728.1 |
| truffle-jit | proto-policy | snapshotAndEvaluate | 3,083.6 [3,057.1–3,093.1] | 3,139.2 [3,104.5–3,153.3] | 0.98× | 10,976.2 | 10,976.2 |
| truffle-jit | map-regex | snapshotAndEvaluate | 3,067.4 [2,889.2–3,078.2] | 2,930.0 [2,929.8–3,084.2] | 1.05× | 12,314.2 | 12,314.1 |
| truffle-interpreter | scalar | evaluateMap | 18.4 [18.2–18.9] | 18.0 [18.0–18.2] | 1.02× | 112.0 | 112.0 |
| truffle-interpreter | proto-repeated | evaluateMap | 5,891.0 [5,886.4–5,900.4] | 4,371.4 [4,230.2–4,425.6] | 1.35× | 18,984.3 | 13,012.2 |
| truffle-interpreter | optimizer-policy | evaluateMap | 1,930.2 [1,922.7–1,979.6] | 1,938.9 [1,918.3–1,943.4] | 1.00× | 8,024.1 | 8,024.1 |
| truffle-jit | scalar | evaluateMap | 8.4 [8.4–8.5] | 8.5 [8.2–8.9] | 0.99× | 24.0 | 24.0 |
| truffle-jit | proto-repeated | evaluateMap | 4,661.5 [4,521.8–4,730.7] | 3,091.2 [2,904.4–3,103.5] | 1.51× | 18,992.2 | 13,028.2 |
| truffle-jit | optimizer-policy | evaluateMap | 1,917.5 [1,906.5–1,923.8] | 1,837.4 [1,817.5–1,890.9] | 1.04× | 8,020.1 | 7,984.1 |
| truffle-interpreter | scalar | evaluateSnapshot | 15.1 [15.0–15.1] | 15.3 [15.0–15.8] | 0.99× | 112.0 | 112.0 |
| truffle-interpreter | proto-repeated | evaluateSnapshot | 3,922.4 [3,921.2–3,942.9] | 2,492.5 [2,485.1–2,537.3] | 1.57× | 11,336.2 | 5,364.1 |
| truffle-interpreter | optimizer-policy | evaluateSnapshot | 182.4 [182.4–194.6] | 161.8 [156.2–166.2] | 1.13× | 376.0 | 376.0 |
| truffle-jit | scalar | evaluateSnapshot | 4.9 [4.9–5.0] | 5.0 [4.9–5.0] | 0.99× | 24.0 | 24.0 |
| truffle-jit | proto-repeated | evaluateSnapshot | 2,720.0 [2,711.1–2,797.2] | 1,195.7 [1,189.0–1,197.3] | 2.27× | 11,344.1 | 5,380.1 |
| truffle-jit | optimizer-policy | evaluateSnapshot | 160.3 [157.1–162.1] | 119.5 [111.9–126.4] | 1.34× | 336.0 | 336.0 |
| truffle-interpreter | scalar | snapshotAndEvaluate | 29.7 [29.5–29.9] | 29.6 [29.3–29.7] | 1.01× | 288.0 | 288.0 |
| truffle-interpreter | proto-repeated | snapshotAndEvaluate | 6,485.4 [6,451.2–6,500.5] | 5,075.1 [5,028.9–5,203.3] | 1.28× | 21,016.3 | 15,068.3 |
| truffle-interpreter | optimizer-policy | snapshotAndEvaluate | 2,593.0 [2,588.8–2,639.0] | 2,572.5 [2,512.2–2,767.8] | 1.01× | 10,080.1 | 10,056.1 |
| truffle-jit | scalar | snapshotAndEvaluate | 18.9 [18.9–19.0] | 19.1 [18.9–19.5] | 0.99× | 200.0 | 200.0 |
| truffle-jit | proto-repeated | snapshotAndEvaluate | 5,331.9 [5,225.7–5,363.6] | 3,570.6 [3,554.3–3,631.5] | 1.49× | 21,048.3 | 15,084.2 |
| truffle-jit | optimizer-policy | snapshotAndEvaluate | 2,616.2 [2,602.2–2,618.4] | 2,563.6 [2,559.7–2,605.8] | 1.02× | 10,040.1 | 10,040.1 |

## Paired scaling and preparation

Time is ns/op: median of three process means [minimum–maximum].
Allocation is Java heap bytes/op; no ingestion is included.

| Operation | Length | Selected % | Reference ns/op | Candidate ns/op | Speedup | Reference B/op | Candidate B/op |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| evaluate | 0 | 50 | 44.5 [41.9–44.6] | 15.7 [15.0–18.6] | 2.84× | 216.0 | 240.0 |
| evaluate | 16 | 50 | 359.9 [347.2–373.1] | 161.4 [160.4–161.7] | 2.23× | 1,240.0 | 440.0 |
| evaluate | 128 | 50 | 2,424.6 [2,411.4–2,438.2] | 995.2 [977.9–1,014.9] | 2.44× | 9,640.1 | 3,832.1 |
| evaluate | 1024 | 50 | 18,572.3 [18,489.4–19,118.7] | 7,524.0 [7,516.6–7,547.5] | 2.47× | 70,528.9 | 27,664.4 |
| evaluate | 128 | 0 | 1,369.9 [1,359.5–1,371.7] | 379.9 [357.2–394.1] | 3.61× | 3,952.1 | 240.0 |
| evaluate | 128 | 100 | 3,289.9 [3,257.6–3,318.2] | 1,542.2 [1,540.5–1,552.7] | 2.13× | 14,304.2 | 6,016.1 |
| instantiate | 128 | 50 | 902.6 [900.4–930.1] | 904.8 [887.8–926.1] | 1.00× | 1,567.9 | 1,799.9 |
| prepare | 128 | 50 | 1,534.7 [1,480.3–1,561.0] | 1,575.7 [1,487.0–1,630.3] | 0.97× | 5,672.1 | 5,768.1 |
