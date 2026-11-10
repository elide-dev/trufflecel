# Ingestion allocation

Current-thread bytes/op; median of three fork medians, each with seven fixed-work windows. No timing claim.

| Runtime | Fixture | Operation | B/op | Fork range |
| --- | --- | --- | ---: | ---: |
| jvm | ordinary | copy-wire | 230.00 | 230.00–230.00 |
| jvm | ordinary | serialize | 514.00 | 514.00–514.00 |
| jvm | ordinary | decode-cel | 5,152.00 | 5,152.00–5,152.00 |
| jvm | ordinary | decode-native | 5,128.00 | 5,128.00–5,128.00 |
| jvm | ordinary | decode-stock | 4,300.00 | 4,300.00–4,300.00 |
| jvm | ordinary | snapshot-cel | 5,666.00 | 5,666.00–5,666.00 |
| jvm | ordinary | snapshot-bound | 3,772.00 | 3,772.00–3,772.00 |
| jvm | ordinary | decode-evaluate-cel | 10,442.00 | 10,442.00–10,442.00 |
| jvm | ordinary | decode-evaluate-stock | 12,876.00 | 12,876.00–12,876.00 |
| jvm | unknown64k | copy-wire | 65,770.00 | 65,770.00–65,770.00 |
| jvm | unknown64k | serialize | 66,126.00 | 66,126.00–66,126.00 |
| jvm | unknown64k | decode-cel | 136,344.00 | 136,344.00–136,344.00 |
| jvm | unknown64k | decode-native | 136,320.00 | 136,320.00–136,320.00 |
| jvm | unknown64k | decode-stock | 70,388.00 | 70,388.00–70,388.00 |
| jvm | unknown64k | snapshot-cel | 202,430.00 | 202,430.00–202,430.00 |
| jvm | unknown64k | snapshot-bound | 135,276.00 | 135,276.00–135,276.00 |
| jvm | unknown64k | decode-evaluate-cel | 207,174.00 | 207,174.00–207,174.00 |
| jvm | unknown64k | decode-evaluate-stock | 79,100.00 | 79,100.00–79,100.00 |
| jvm | messageset | copy-wire | 40.00 | 40.00–40.00 |
| jvm | messageset | serialize | 64.00 | 64.00–64.00 |
| jvm | messageset | decode-cel | 4,800.00 | 4,800.00–4,800.00 |
| jvm | messageset | snapshot-cel | 4,864.00 | 4,864.00–4,864.00 |
| jvm | messageset | decode-stock | 1,032.00 | 1,032.00–1,032.00 |
| svm | ordinary | copy-wire | 222.00 | 222.00–222.00 |
| svm | ordinary | serialize | 434.00 | 434.00–434.00 |
| svm | ordinary | decode-cel | 4,628.00 | 4,628.00–4,628.00 |
| svm | ordinary | decode-native | 4,612.00 | 4,612.00–4,612.00 |
| svm | ordinary | decode-stock | 4,076.00 | 4,076.00–4,076.00 |
| svm | ordinary | snapshot-cel | 5,062.00 | 5,062.00–5,062.00 |
| svm | ordinary | snapshot-bound | 5,408.00 | 5,408.00–5,408.00 |
| svm | ordinary | decode-evaluate-cel | 9,146.00 | 9,146.00–9,146.00 |
| svm | ordinary | decode-evaluate-stock | 11,554.00 | 11,554.00–11,554.00 |
| svm | unknown64k | copy-wire | 65,762.00 | 65,762.00–65,762.00 |
| svm | unknown64k | serialize | 65,974.00 | 65,974.00–65,974.00 |
| svm | unknown64k | decode-cel | 135,828.00 | 135,828.00–135,828.00 |
| svm | unknown64k | decode-native | 135,812.00 | 135,812.00–135,812.00 |
| svm | unknown64k | decode-stock | 70,052.00 | 70,052.00–70,052.00 |
| svm | unknown64k | snapshot-cel | 201,802.00 | 201,802.00–201,802.00 |
| svm | unknown64k | snapshot-bound | 136,792.00 | 136,792.00–136,792.00 |
| svm | unknown64k | decode-evaluate-cel | 205,886.00 | 205,886.00–205,886.00 |
| svm | unknown64k | decode-evaluate-stock | 77,530.00 | 77,530.00–77,530.00 |
| svm | messageset | copy-wire | 32.00 | 32.00–32.00 |
| svm | messageset | serialize | 48.00 | 48.00–48.00 |
| svm | messageset | decode-cel | 4,824.00 | 4,824.00–4,824.00 |
| svm | messageset | snapshot-cel | 4,872.00 | 4,872.00–4,872.00 |
| svm | messageset | decode-stock | 888.00 | 888.00–888.00 |
