# Caller-prepared input controls

Same four full 69-binding inputs and captured-array lookup; preparation excluded.

| Runtime | Fixture | Planner | Ordinary map ns/op | Immutable map ns/op | Ordinary B/op | Immutable B/op |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar-wide | default | 508.76 | 26.01 | 2,936.00 | 72.00 |
| jvm | scalar-wide | optimized | 526.82 | 25.33 | 2,936.00 | 72.00 |
| jvm | proto-policy | default | 1,188.85 | 672.64 | 4,158.00 | 1,294.00 |
| jvm | proto-policy | optimized | 1,135.15 | 647.28 | 4,046.00 | 1,182.00 |
| svm | scalar-wide | default | 1,305.87 | 63.93 | 3,504.00 | 120.00 |
| svm | scalar-wide | optimized | 1,364.54 | 63.46 | 3,504.00 | 120.00 |
| svm | proto-policy | default | 2,469.19 | 1,188.31 | 4,794.00 | 1,410.00 |
| svm | proto-policy | optimized | 2,266.85 | 1,026.84 | 4,598.00 | 1,214.00 |
