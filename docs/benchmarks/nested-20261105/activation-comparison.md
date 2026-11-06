# JVM activation comparison

Time is ns/op: median of three process means [minimum–maximum].
Allocation is Java heap bytes/op; no ingestion is included.

| Runtime | Workload | Entry | Reference ns/op | Candidate ns/op | Speedup | Reference B/op | Candidate B/op |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| truffle-interpreter | scalar | evaluateMap | 18.0 [18.0–18.2] | 18.1 [17.9–18.5] | 1.00× | 112.0 | 112.0 |
| truffle-interpreter | scalar-wide | evaluateMap | 1,730.3 [1,729.9–1,754.8] | 1,726.5 [1,714.0–1,727.7] | 1.00× | 7,760.1 | 7,760.1 |
| truffle-interpreter | proto-policy | evaluateMap | 2,290.8 [2,283.4–2,387.0] | 2,187.1 [2,076.9–2,215.7] | 1.05× | 8,944.1 | 8,462.1 |
| truffle-interpreter | proto-repeated | evaluateMap | 4,371.4 [4,230.2–4,425.6] | 4,273.4 [4,202.4–4,425.7] | 1.02× | 13,012.2 | 13,012.2 |
| truffle-interpreter | map-regex | evaluateMap | 2,500.1 [2,485.4–2,637.4] | 2,487.9 [2,479.3–2,516.3] | 1.00× | 10,382.1 | 10,382.1 |
| truffle-interpreter | optimizer-policy | evaluateMap | 1,938.9 [1,918.3–1,943.4] | 1,905.4 [1,796.0–1,915.1] | 1.02× | 8,024.1 | 7,872.1 |
| truffle-jit | scalar | evaluateMap | 8.5 [8.2–8.9] | 8.3 [8.3–8.5] | 1.03× | 24.0 | 24.0 |
| truffle-jit | scalar-wide | evaluateMap | 1,717.5 [1,705.6–1,718.3] | 1,712.8 [1,706.9–1,736.9] | 1.00× | 7,672.1 | 7,672.1 |
| truffle-jit | proto-policy | evaluateMap | 2,338.6 [2,237.0–2,345.5] | 2,232.3 [2,017.6–2,287.6] | 1.05× | 8,920.1 | 8,432.1 |
| truffle-jit | proto-repeated | evaluateMap | 3,091.2 [2,904.4–3,103.5] | 3,091.3 [3,062.1–3,125.3] | 1.00× | 13,028.2 | 13,028.2 |
| truffle-jit | map-regex | evaluateMap | 2,277.5 [2,269.2–2,303.4] | 2,274.0 [2,268.3–2,285.6] | 1.00× | 10,258.1 | 10,258.1 |
| truffle-jit | optimizer-policy | evaluateMap | 1,837.4 [1,817.5–1,890.9] | 1,925.7 [1,786.3–1,928.0] | 0.95× | 7,984.1 | 7,832.1 |
| truffle-interpreter | scalar | evaluateSnapshot | 15.3 [15.0–15.8] | 14.9 [14.7–15.5] | 1.03× | 112.0 | 112.0 |
| truffle-interpreter | scalar-wide | evaluateSnapshot | 16.9 [16.7–16.9] | 17.2 [17.1–17.4] | 0.98× | 112.0 | 112.0 |
| truffle-interpreter | proto-policy | evaluateSnapshot | 527.8 [511.8–545.9] | 305.2 [302.9–318.3] | 1.73× | 1,296.0 | 814.0 |
| truffle-interpreter | proto-repeated | evaluateSnapshot | 2,492.5 [2,485.1–2,537.3] | 2,512.5 [2,490.1–2,558.7] | 0.99× | 5,364.1 | 5,364.1 |
| truffle-interpreter | map-regex | evaluateSnapshot | 718.1 [708.3–725.7] | 717.4 [715.5–722.9] | 1.00× | 2,722.0 | 2,734.0 |
| truffle-interpreter | optimizer-policy | evaluateSnapshot | 161.8 [156.2–166.2] | 80.9 [80.6–82.0] | 2.00× | 376.0 | 224.0 |
| truffle-jit | scalar | evaluateSnapshot | 5.0 [4.9–5.0] | 5.0 [4.9–5.0] | 1.00× | 24.0 | 24.0 |
| truffle-jit | scalar-wide | evaluateSnapshot | 8.1 [8.0–8.2] | 8.2 [8.1–8.4] | 0.99× | 24.0 | 24.0 |
| truffle-jit | proto-policy | evaluateSnapshot | 475.2 [473.4–483.9] | 250.2 [249.6–251.7] | 1.90× | 1,272.0 | 784.0 |
| truffle-jit | proto-repeated | evaluateSnapshot | 1,195.7 [1,189.0–1,197.3] | 1,199.4 [1,182.1–1,209.6] | 1.00× | 5,380.1 | 5,380.1 |
| truffle-jit | map-regex | evaluateSnapshot | 507.7 [501.4–515.4] | 501.4 [500.5–510.7] | 1.01× | 2,610.0 | 2,610.0 |
| truffle-jit | optimizer-policy | evaluateSnapshot | 119.5 [111.9–126.4] | 48.0 [47.8–51.1] | 2.49× | 336.0 | 184.0 |
| truffle-interpreter | scalar | snapshotAndEvaluate | 29.6 [29.3–29.7] | 29.8 [29.1–30.2] | 0.99× | 288.0 | 288.0 |
| truffle-interpreter | scalar-wide | snapshotAndEvaluate | 2,389.8 [2,330.6–2,410.2] | 2,321.7 [2,310.0–2,371.9] | 1.03× | 9,816.1 | 9,816.1 |
| truffle-interpreter | proto-policy | snapshotAndEvaluate | 2,935.6 [2,934.8–2,946.4] | 2,872.8 [2,701.8–3,055.6] | 1.02× | 10,976.1 | 10,518.1 |
| truffle-interpreter | proto-repeated | snapshotAndEvaluate | 5,075.1 [5,028.9–5,203.3] | 4,915.4 [4,891.8–5,067.6] | 1.03× | 15,068.3 | 15,044.2 |
| truffle-interpreter | map-regex | snapshotAndEvaluate | 3,106.9 [3,083.5–3,127.1] | 3,093.3 [3,057.9–3,132.3] | 1.00× | 12,402.2 | 12,402.2 |
| truffle-interpreter | optimizer-policy | snapshotAndEvaluate | 2,572.5 [2,512.2–2,767.8] | 2,443.5 [2,395.4–2,585.5] | 1.05× | 10,056.1 | 9,904.1 |
| truffle-jit | scalar | snapshotAndEvaluate | 19.1 [18.9–19.5] | 19.3 [18.9–19.4] | 0.99× | 200.0 | 200.0 |
| truffle-jit | scalar-wide | snapshotAndEvaluate | 2,308.1 [2,307.5–2,316.5] | 2,383.8 [2,323.6–2,461.0] | 0.97× | 9,728.1 | 9,728.1 |
| truffle-jit | proto-policy | snapshotAndEvaluate | 3,139.2 [3,104.5–3,153.3] | 2,822.1 [2,755.0–3,007.8] | 1.11× | 10,976.2 | 10,488.1 |
| truffle-jit | proto-repeated | snapshotAndEvaluate | 3,570.6 [3,554.3–3,631.5] | 3,586.8 [3,582.8–3,720.8] | 1.00× | 15,084.2 | 15,084.2 |
| truffle-jit | map-regex | snapshotAndEvaluate | 2,930.0 [2,929.8–3,084.2] | 2,945.4 [2,944.6–3,056.1] | 0.99× | 12,314.1 | 12,326.1 |
| truffle-jit | optimizer-policy | snapshotAndEvaluate | 2,563.6 [2,559.7–2,605.8] | 2,464.5 [2,409.9–2,581.8] | 1.04× | 10,040.1 | 9,888.1 |
