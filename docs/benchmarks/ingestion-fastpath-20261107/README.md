# Ingestion normalization shortcut, 2026-11-07

Follow-up to [the preserved baseline](../ingestion-20261107/README.md), with the
same fixture, pinned dependencies, three forks and JMH GC profiling. ProtobufType
now determines whether MessageSet is reachable through normal fields, groups or
registered extension edges. Graphs without MessageSet bypass wire normalization;
size limits and native decoder validation remain active.

| Stage | Mean ns/op | Allocated B/op |
| --- | ---: | ---: |
| copyWire | 41.0 | 230.0 |
| decodeCel | 14896.9 | 5366.2 |
| decodeNative | 4708.7 | 5128.1 |
| decodeStock | 402.3 | 4284.0 |
| serializeMessage | 111.8 | 346.0 |
| snapshotBound | 3405.8 | 3884.0 |
| snapshotCel | 2164.6 | 5538.0 |

CEL snapshot allocation decreased from 29,056 to approximately 5,538 B/op on this
fixture. These are shared-host measurements with concurrent builds, so timing
results are advisory and do not establish a release performance claim. The bound
snapshot path still requires upstream descriptor compatibility before adoption.

Regression coverage includes MessageSet reachable only through extensions, nested
groups, unknown groups carrying scalar field numbers, recursive schemas, and
opaque unregistered extension payloads. All 37 targets including Linux native
smoke and guest-JIT passed ([results](https://app.buildbuddy.io/invocation/692e1e03-7c6f-40da-9a67-bf6e7b88e696)).
Coverage: 99.92% lines, 99.88% branches. Metadata, raw JMH output, run log and the
exact source patch are preserved alongside this report. Final TruffleData adoption
and the matched release performance comparison remain pending its final release.
