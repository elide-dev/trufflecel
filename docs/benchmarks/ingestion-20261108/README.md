# Protobuf ingestion allocation and timing — November 8, 2026

Removing the redundant copy of an immutable CEL `Bytes` value reduces protobuf
decode allocation by exactly one wire-array allocation in the measured JVM cases.
For the ordinary four-input fixture, allocation falls from **5,366 to 5,152 B/op**.
With a 64 KiB unknown payload, it falls from **202,098 to 136,344 B/op**, a 32.5%
reduction. A separate quiet-host timing run finds no measurable ordinary decode
speedup: the baseline and candidate both average about 2.23 microseconds.

The change does not improve generated-message snapshot ingestion. The existing
bound snapshot API allocates less, but is about 45% slower than the current
serialize/decode path on this fixture. These data do not justify switching all
snapshots to it. Bound ingestion also retains its descriptor-compatibility limits.

## Sources and inputs

- Production baseline: `4c5df30532a9e46a39f805614dfec2cb1fe94570`.
- Production candidate: `67ab33c`, recorded by the measured cherry-pick `6684422`.
- Allocation/JFR harness: `402b8d3`; both artifacts use the identical harness.
- TruffleData **0.9.0**, GraalVM **25.5.5-elide.27**, cel-java **0.15.0**.
- Baseline artifact source: `402b8d3`; candidate artifact source: `6684422`.

[The production patch](production.patch) and [harness patch](profile-harness.patch)
reconstruct the measured sources from the baseline without requiring the original
agent branches. Source copies are retained alongside the patches. The report
preserves raw allocation windows, all JMH observations, JFR recordings, commands,
artifact hashes, runtime flags, and host metadata. [summary.json](summary.json)
contains per-window ranges and SHA-256 hashes for the evidence files.

The `ordinary` fixture uses the existing four representative generated Requests,
with wire lengths 195, 192, 198, and 195 bytes. Each has nested principal/wrapper/time
fields, a map, and 128 repeated integers. `unknown64k` adds unknown field 100 with a
65,536-byte length-delimited payload; wire lengths are 65,736, 65,733, 65,739, and
65,736 bytes. The MessageSet control uses four 8-byte wire messages carrying an
unknown MessageSet item. Setup verifies each decoded/snapshotted message against
the original, including unknown data. Both decode-and-evaluate paths copy the full
69-binding representative activation, replace its Request, and evaluate the same
protobuf policy against independent expected results. Input maps are not trimmed.

Direct native-plan and bound snapshot measurements are intentionally absent for
MessageSet: its wire format requires CEL normalization, and the bound API does not
support that descriptor. This is a declared fixture eligibility boundary, not a
skipped failed measurement. CEL decode and snapshot still exercise and verify it.

## JVM allocated bytes

Medians of 21 windows per cell: three fresh JVMs, seven fixed-work windows each.
The ordinary and MessageSet stages warm 50,000 operations, then measure 10,000
operations per window. The large-payload stages warm 2,500 operations and measure
500 per window. Exact ranges and every window remain in the summary and raw files.

| Fixture / operation | Baseline B/op | Candidate B/op | Change |
| --- | ---: | ---: | ---: |
| Ordinary CEL decode | 5,366 | 5,152 | −214 |
| Ordinary decode plus CEL evaluation | 10,656 | 10,442 | −214 |
| 64 KiB unknown CEL decode | 202,098 | 136,344 | −65,754 |
| 64 KiB unknown decode plus CEL evaluation | 272,928 | 207,174 | −65,754 |
| MessageSet CEL decode | 4,824 | 4,800 | −24 |

Controls preserve the larger remaining costs rather than treating this as complete
ingestion parity:

| Operation | Ordinary, baseline / candidate B/op | 64 KiB unknown, baseline / candidate B/op |
| --- | ---: | ---: |
| Direct native-plan decode | 5,128 / 5,128 | 136,320 / 136,320 |
| Generated protobuf parse | 4,300 / 4,300 | 70,356 / 70,356 |
| CEL generated-message snapshot | 5,666 / 5,666 | 202,430 / 202,430 |
| Bound generated-message snapshot | 4,012 / 4,012 | 135,516 / 135,516 |
| Generated parse plus optimized cel-java evaluation | 12,892 / 12,892 | 79,100 / 79,100 |

MessageSet snapshot remains 4,864 B/op; generated MessageSet parsing remains
872 B/op. The CEL fallback retains normalization and validation rather than
pretending the legacy wire representation is an ordinary protobuf message.

These allocation observations use the pinned JVM on a shared Apple M5 Max developer
host, macOS 26.6.2, a 512 MiB heap, `-Xbatch`, and C2 host compilation
(`-XX:-UseJVMCICompiler`). Guest compilation is disabled and verified on the actual
target. The per-thread allocation counter excludes other threads. Every returned
value escapes through a volatile sink; setup checks all four inputs, and the last
result of every allocation window is checked again. An atomic local measurement
lock serializes the experiments. Other host activity was not controlled, and no
timing claim is derived from this harness.

## Separate unprofiled timing

The timing runs use the unchanged `IngestionStages` JMH harness on the dedicated
Linux Threadripper host, pinned to CPUs 2–9. Source revisions alternate between
fork groups. Each cell has three independent forks, each with three 500 ms warmup
and three 500 ms measurement iterations. Heap limits are 512 MiB and the host
compiler is C2. No allocation profiler, JFR session, other agent measurement, or
artifact transfer runs concurrently with timing. Kernel/CPU/load/affinity data and
the exact commands are retained in [timing-metadata.json](timing-metadata.json).

Means of fork means, with the range of fork means in parentheses, in ns/op:

| Stage | Baseline | Candidate |
| --- | ---: | ---: |
| CEL decode | 2,226.8 (2,193.2–2,281.0) | 2,226.2 (2,167.8–2,281.7) |
| Direct native-plan decode | 2,205.4 (2,176.6–2,234.2) | 2,278.9 (2,168.9–2,337.4) |
| Generated protobuf parse | 488.7 (465.1–501.9) | 481.6 (465.1–508.1) |
| CEL generated-message snapshot | 2,662.1 (2,598.7–2,734.7) | 2,698.8 (2,629.6–2,753.9) |
| Bound generated-message snapshot | 3,846.2 (3,786.4–3,941.9) | 3,912.5 (3,754.3–4,010.9) |

The decode ranges overlap, as do the unchanged direct-plan and generated-parser
controls. This experiment supports an allocation reduction, not a claimed small
throughput gain. The bound snapshot cost is consistently higher than the CEL
snapshot cost across these forks. Warmup is finite, and three forks do not prove
peak convergence or universal behavior. These stage timings must not be added or
subtracted to estimate an end-to-end path. The allocation and timing harnesses
have different call-site shapes and run on different hosts; their absolute stage
figures are not interchangeable.

## Allocation attribution and remaining work

JFR runs are separate fresh JVMs, after warmup, with five-second recordings of
`jdk.ObjectAllocationSample` at a requested 1,000 samples/second. Raw recordings
are preserved. Summaries select main-thread samples whose recorded stack includes
the protobuf decoder, `ProtobufType`, or `Bytes`; unrelated recording setup and
class-generation samples are excluded. Sample weights estimate allocation between
events and can alias repeated allocation patterns. Percentages below describe
selected sampled weight, not exact fractions of every allocated byte.

For the large unknown-payload decode, the baseline records the redundant
`Bytes.toByteArray` stack at 35.6% of selected weight. That stack disappears in the
candidate. Two TruffleData byte-array sites then dominate the candidate samples:

| Candidate allocation stack | Selected sampled weight |
| --- | ---: |
| `ByteArrayOutputStream.ensureCapacity` → `ProtoCodec.Reader.retain` | 51.3% |
| `ByteArrayOutputStream.toByteArray` → `ProtoCodec.State.finish` | 40.9% |

Exact fixed-work counts independently show direct native decode rising from
5,128 to 136,320 B/op when the 64 KiB unknown field is added: **131,192 additional
bytes**, approximately two payload-sized arrays plus bookkeeping. Source inspection
and the samples support reducing temporary unknown-byte staging in TruffleData
while retaining one owned final representation. Unknown ordering, tags, groups,
closed-enum values, ownership, and resource bounds must remain intact. This is
separate from the already requested allocation-free unknown-presence query.

Ordinary-message samples identify native list/map materialization, row/batch
storage, reader state and scalar wrappers as remaining allocation sites. Their
sample distributions vary substantially between recordings; this report does not
assign exact per-operation costs to those individual sites or infer a proven fix.

## Qualification and reproduction

The fixture smoke passes all eligible operations over four inputs. The production
candidate also passed [69 test targets](https://app.buildbuddy.io/invocation/37adbeb1-8204-4ce7-8f2e-e2e1ab93c663),
including complete conformance, protobuf ownership/concurrency regressions, compiled
allocation gates, native comparisons, native guest-JIT/blocklist checks, and
image-prepared programs. Native allocation-only qualification is separate from
this report; these JVM allocation figures do not claim Native Image object sizes
or timing.

Rebuild the exact historical allocation harness by applying `profile-harness.patch`
to `4c5df30`, then optionally `production.patch`. Build
`//benchmarks/representative:ingestion_profile_deploy.jar` using the pinned toolchain.
`run-allocation.py` preserves the commands for each fixture and separate JFR
recording; adjust only artifact/toolchain paths when moving hosts. The current
harness also offers:

```sh
bazel run --config=local //benchmarks/representative:ingestion_profile -- allocate ordinary
bazel run --config=local //benchmarks/representative:ingestion_profile -- allocate unknown64k
bazel run --config=local //benchmarks/representative:ingestion_profile -- allocate messageset
bazel run --config=local //benchmarks/representative:ingestion_profile -- profile unknown64k decode-cel /new/run.jfr
```

Use `run-timing.py` with the preserved JMH deploy artifacts for the separate
unprofiled comparison. Preserve any new measurements in a new location rather
than overwriting this evidence.

## Native Image allocation follow-up

A separate allocation-only entry reuses the same fixture operations and validation.
It does not call the JFR recording path. Two Linux amd64 Native Images use the pinned
GraalVM, Serial GC, optimization level 3, and `-Xmx512m`. Both builds retain Truffle
blocklist and never-part-of-compilation validation. Each image runs three fresh
processes per fixture on RBE, with the same warmup and seven fixed-work windows as
the JVM diagnostic. The per-thread allocation counter must be supported and must
pass an independent escaping 4 KiB-array check before measurement begins.

The [baseline action](https://app.buildbuddy.io/invocation/e2359482-fc93-416e-9e83-2dfbd9e40a4a)
and [candidate action](https://app.buildbuddy.io/invocation/9e6371b3-9f45-4dd3-9eeb-c3166893a12e)
both passed. Every value below is identical in all 21 windows for its cell.

| Fixture / operation | Baseline B/op | Candidate B/op | Change |
| --- | ---: | ---: | ---: |
| Ordinary CEL decode | 4,834 | 4,628 | −206 |
| Ordinary decode plus CEL evaluation | 9,352 | 9,146 | −206 |
| 64 KiB unknown CEL decode | 201,574 | 135,828 | −65,746 |
| 64 KiB unknown decode plus CEL evaluation | 271,632 | 205,886 | −65,746 |
| MessageSet CEL decode | 4,840 | 4,824 | −16 |

Native allocation controls, unchanged between revisions:

| Operation | Ordinary B/op | 64 KiB unknown B/op |
| --- | ---: | ---: |
| Direct native-plan decode | 4,612 | 135,812 |
| Generated protobuf parse | 4,076 | 70,052 |
| CEL generated-message snapshot | 5,062 | 201,802 |
| Bound generated-message snapshot | 5,568 | 136,952 |
| Generated parse plus optimized cel-java evaluation | 11,554 | 77,530 |

Unlike the JVM/C2 result, the ordinary bound snapshot allocates more than the
ordinary CEL snapshot in this native configuration. That reinforces the need to
qualify ingestion choices separately for each runtime and input shape. Adding the
unknown payload raises direct native-plan allocation by 131,200 B/op, consistent
with the JVM finding of approximately two payload-sized arrays plus bookkeeping.
The decode change saves one input clone on both runtimes; their object layouts
and absolute allocation figures differ.

The runs measure current-thread allocated bytes, not elapsed time, retained heap,
RSS, or deployment footprint. The artifacts include both engines for the controls;
their combined image size is not a per-library deployment comparison. The RBE
actions may use different workers, so no timing comparison is inferred from them.
All raw windows, binary hashes, observed CPU/kernel details, and source provenance
are retained under [native/](native/), with aggregates in
[native/summary.json](native/summary.json).

The allocation-only harness extension is commit `662ecba`; baseline artifact source
is `3b61f11`, and candidate artifact source is `662ecba`. For exact reconstruction,
apply `profile-harness.patch` then [native/harness.patch](native/harness.patch) to
`4c5df30`, then optionally `production.patch`. Run:

```sh
bazel test //benchmarks/representative:ingestion_native_allocation
```

The test writes each fresh process's output and its binary digest to Bazel's
undeclared outputs. No local system JDK or profiling-enabled Native Image is used.
