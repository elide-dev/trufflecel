# Native protobuf zero checks — November 8, 2026

`ProtobufType.isZero` now checks native field presence and unknown-field emptiness for
ordinary messages. It preserves descriptor identity and retains the previous host export
for map-entry and MessageSet descriptors. Map-entry export supplies special defaults;
MessageSet export can discard an empty unknown group. A raw unknown-byte test would change
the latter result, so the regression tests explicitly preserve it.

This changes only zero checks. Message equality and hashing still export temporary Java
messages. It does not change ingestion, close the protobuf performance backlog, or establish
an advantage over cel-java.

## Results

| Input | Export allocated B/op | Native allocated B/op | Export ns/op | Native ns/op |
|---|---:|---:|---:|---:|
| Empty | 376 | 16 | 51.8 ± 29.0 | 4.55 ± 0.57 |
| Populated | 5,672 | approximately 0 | 3,416 ± 263 | 2.04 ± 0.19 |
| Unknown only | 864 | 24 | 106.9 ± 61.4 | 4.21 ± 0.07 |

Latency columns are JMH means and its reported 99.9% confidence-interval half-widths.
The large uncertainty in the legacy empty/unknown cells reinforces the shared-host limitation;
do not treat ratios of these numbers as a general throughput result. Allocations are stable
across all 15 samples per cell. The populated case returns at the first present field and
reports approximately 0.000014 B/op, measurement noise rather than a claim about all messages.

The 16/24 B residual comes from obtaining a defensive copy of the empty/three-byte unknown
payload. [TruffleData #35](https://github.com/elide-dev/truffledata/issues/35) requests an
allocation-free raw unknown-byte presence/count query for 0.10.0 while preserving the copy API.
It is an inference from the current `clone()` implementation that larger unknown payloads
would require proportionally larger copies; this run did not measure large payloads. The
MessageSet and map-entry fallbacks remain necessary even with that proposed query.

## Validation

- All 41 default and explicit native guest-JIT test targets passed on Linux RBE, including
  the new compiled `optional.ofNonZeroValue` empty/populated/unknown cases.
- All 23 source test targets passed under coverage, including 19,397 conformance cases.
- Complete owned production coverage: 99.93% lines (5,347/5,351), 99.89% branches
  (5,364/5,370), exceeding the unchanged 99.8% floors.
- Host-oracle regressions cover implicit signed zero and NaN, proto2 declared/required defaults,
  oneofs, Editions presence, extension defaults, closed enums, unknown varint/bytes/groups,
  map entries, MessageSet unknown normalization and descriptor identity.
- Formatter, Gazelle and diff whitespace checks passed. No dependency versions changed.

The compressed build/coverage logs and complete LCOV report preserve this qualification.
BuildBuddy invocations: [full and native](https://app.buildbuddy.io/invocation/b5549554-122e-4eed-b0e0-1ab918d61fb9),
[coverage](https://app.buildbuddy.io/invocation/f4919c4e-659b-4455-be19-72e9bbc3e25e).

## Diagnostic method

`benchmarks/representative/ProtobufZero.java` compares the new `nativeZero` method with
`exportedZero`, the previous implementation, in the same artifact on identical immutable
snapshots. Both return a boolean consumed by JMH. Trial setup verifies both results against
the expected default/non-default outcome. The three inputs are:

- `empty`: the representative `Request` schema's default instance.
- `populated`: the unchanged first `request` from `Workloads.Raw.INPUTS`.
- `unknown`: no declared fields and unknown field 100 containing varint zero.

Snapshot construction and schema preparation are outside measurement. This is a direct Java
adapter diagnostic on one snapshot per cell, not a full CEL evaluation or four-input
representative comparison. Existing workload definitions and historical measurements are
unchanged.

The shared Apple M5 Max macOS host uses the repository's pinned GraalVM
`25.5.5-elide.27`, explicit C2 host compilation, a 512 MiB heap, JMH 1.37, three forks, five
one-second warmups and five one-second measurement iterations. The GC profiler records
allocated bytes/op. Other agents' benchmark timing was paused during this run, but the host
was not dedicated or CPU-pinned. Latency is advisory; these samples do not establish a peak
throughput claim or cross-platform result.

`metadata.json` records the exact command, base revision, source and artifact hashes,
toolchain path, hardware, load and timestamps. `source.diff.gz` records the production change
from that base. Compressed raw JSON includes every per-fork sample; the compressed log
includes warmup results. The pinned VM version and effective flags are preserved alongside
them. Build and reproduce with:

```sh
bazel build --config=local //benchmarks/representative:jmh_deploy.jar
/path/to/pinned-graalvm/bin/java -jar bazel-bin/benchmarks/representative/jmh_deploy.jar \
  'dev.trufflecel.ProtobufZero.*' -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -foe true \
  -jvmArgsAppend '-XX:-UseJVMCICompiler -Xms512m -Xmx512m --enable-native-access=ALL-UNNAMED' \
  -rf json -rff /new/results.json
```
