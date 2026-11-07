# Ingestion stage baseline, 2026-11-07

JVM diagnostic on TruffleData 0.8.0 and GraalVM 25.5.5-elide.27, based on PR #47
at 8559e4f. Three forks, three 500 ms warmups and three 500 ms measurements per
stage, with JMH GC profiling. Four rotating existing representative requests;
setup checks each decoded result against the original generated message.

| Stage | Mean ns/op | Allocated B/op |
| --- | ---: | ---: |
| copyWire | 10.0 | 230.0 |
| decodeCel | 3050.7 | 28884.0 |
| decodeNative | 1912.6 | 5128.0 |
| decodeStock | 427.9 | 4284.0 |
| serializeMessage | 639.2 | 346.0 |
| snapshotBound | 3865.8 | 3820.1 |
| snapshotCel | 3188.0 | 29056.0 |

This shared developer host also ran Bazel builds. Timings are advisory; do not
subtract or add independently compiled stage timings to estimate end-to-end cost.
The bound snapshot path allocates less but is not faster in this run. It also
rejects Editions/extension/MessageSet graphs, so these data do not justify replacing
all snapshots with that path.

CEL decode allocates substantially more than direct native-plan decode. Source
inspection shows CEL wire normalization walks every field and builds temporary
unknown-field objects even when no reachable schema uses MessageSet. That is a
TruffleCEL-owned optimization opportunity requiring semantic regression checks.

The command and source hashes are in metadata.json; jmh.json.gz preserves every
fork/sample and runtime arguments. baseline.patch.gz plus IngestionStages.java
records the source delta. baseline.log.gz retains the complete run output.
Final-release JVM/native comparisons remain gated on the final TruffleData release.

## Simulation fixture correction

PR #47 changed six historical literal fixtures to host-input fixtures under the
same names. The reported two-variable-transform regression therefore compared
different work. The historical names now retain the original expressions, and six
new -input names establish independent runtime-input baselines. No historical
measurement was edited. A new CodSpeed run is needed before making a regression
claim about identical workloads.

Validation: all 35 Bazel test targets passed, plus Linux jit_smoke/native_smoke
(BuildBuddy 6174a1cc-de11-48a5-94e7-fcd1c41771f0), actionlint and the Python workflow
and measurement tests. No release or dependency upgrade is performed here.
