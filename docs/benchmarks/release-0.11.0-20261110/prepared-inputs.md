# Caller-prepared input controls

Same four full 69-binding inputs and captured-array lookup; preparation excluded.

| Runtime | Fixture | Planner | Ordinary map ns/op | Immutable map ns/op | Ordinary B/op | Immutable B/op |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar-wide | default | 516.52 | 25.86 | 2,936.00 | 72.00 |
| jvm | scalar-wide | optimized | 523.75 | 25.44 | 2,936.00 | 72.00 |
| jvm | proto-policy | default | 1,221.42 | 694.40 | 4,158.00 | 1,294.00 |
| jvm | proto-policy | optimized | 1,153.31 | 632.08 | 4,046.00 | 1,182.00 |
| svm | scalar-wide | default | 1,270.00 | 63.90 | 3,504.00 | 120.00 |
| svm | scalar-wide | optimized | 1,353.79 | 64.18 | 3,504.00 | 120.00 |
| svm | proto-policy | default | 2,417.21 | 1,208.21 | 4,794.00 | 1,410.00 |
| svm | proto-policy | optimized | 2,308.80 | 1,037.30 | 4,598.00 | 1,214.00 |
