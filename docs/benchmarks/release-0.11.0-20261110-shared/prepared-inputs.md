# Caller-prepared input controls

Same four full 69-binding inputs and captured-array lookup; preparation excluded.

| Runtime | Fixture | Planner | Ordinary map ns/op | Immutable map ns/op | Ordinary B/op | Immutable B/op |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| jvm | scalar-wide | default | 544.65 | 25.97 | 2,936.00 | 72.00 |
| jvm | scalar-wide | optimized | 517.58 | 25.45 | 2,936.00 | 72.00 |
| jvm | proto-policy | default | 1,263.32 | 687.68 | 4,158.00 | 1,294.00 |
| jvm | proto-policy | optimized | 1,220.42 | 633.29 | 4,046.00 | 1,182.00 |
| svm | scalar-wide | default | 1,292.21 | 63.91 | 3,504.00 | 120.00 |
| svm | scalar-wide | optimized | 1,223.21 | 63.96 | 3,504.00 | 120.00 |
| svm | proto-policy | default | 2,472.05 | 1,203.45 | 4,794.00 | 1,410.00 |
| svm | proto-policy | optimized | 3,172.30 | 1,040.51 | 4,598.00 | 1,214.00 |
