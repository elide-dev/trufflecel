# Compatibility and scope

The conformance suite runs **all 2,771 cases across the 32 files in the upstream CEL corpus**, with no
suite exclusions or per-case skips, pinned to cel-spec revision
[`21ec6f4`](https://github.com/cel-expr/cel-spec/tree/21ec6f4e52e8d694687ff9ed7408d3eccbb955d7).
It checks values, errors, namespaces, declarations, macros and inferred types.
[Corpus provenance](../third_party/cel-spec/README.md).

CI requires **99.8% production line coverage and 99.8% branch coverage**, including optional
adapters. Additional tests cover resource limits, concurrency, interop, published TruffleData
artifacts and Maven consumption. Every corpus case runs with both an independent test-only
protobuf fixture and the shipped `ProtobufType` adapter backed by TruffleData, through both
the direct frontend and canonical AST interchange (11,084 executions of 2,771 cases).
The production-adapter path uses unmodified upstream proto2/proto3 schemas and protobuf-java
fixture construction. This is evidence of compatibility with the pinned corpus, not exhaustive
specification compliance. Corpus error cases assert an evaluation failure, not its category
or diagnostic text. A separate table of 431 independently specified negative and edge cases
(malformed input, invalid declarations and types, unknown propagation, resource limits,
collection, arithmetic, conversion, short-circuit, comprehension, optional and protobuf edge
cases) records the rejection phase and, where the contract defines one, the `CelException.Code`
category, never the diagnostic wording. Each case runs through the direct checked and unchecked
frontends, strict compile-time validation, canonical `ParsedExpr`/`CheckedExpr` interchange
with a wire round trip, borrowed and snapshot activations, both protobuf adapters for protobuf
cases, and forced last-tier guest compilation; the 368 cases expressible in cel-java also run
against both of its parsers with and without AST optimizers, with 22 recorded, explained
divergences. Strict validation rejects at compile time every case that cel-java's
constant-folding optimizer rejects. Custom functions, limits, concurrency, and unknown
propagation have separate regression tests.
See the [coverage audit](COVERAGE.md) for measured totals and remaining defensive paths.

The table describes the current `main` branch against the pinned corpus, not every CEL
extension or future specification revision. ✅ means implemented; the scope column identifies
validation evidence and execution limits.

| Capability | Status | Scope and limitations |
| --- | --- | --- |
| Independent parser, type checker, and evaluator | ✅ Implemented | No CEL Java frontend or interpreter dependency; core syntax, types, operators, namespaces, and standard functions pass the pinned suites. |
| Compile-time validation and constant folding | ✅ Implemented | Bounded constant folding and constant regular-expression precompilation on every compilation, at the original budget cost and invisible to AST export and tooling; opt-in strict validation with every-path analysis, homogeneous-literal and nesting validators, and multi-issue line/column diagnostics; see [validation](VALIDATION.md). |
| Complete pinned conformance corpus | ✅ Passing | All 2,771 cases across 32 files; no suite exclusions or per-case skips. |
| Macros and optional values | ✅ Implemented | Standard macros, two-variable comprehensions, optional selection/indexing, and optional aggregate entries. |
| String, list, math, and network extensions | ✅ Implemented | All cases in their pinned suites; see [supported APIs and limits](EXTENSIONS.md). |
| Local bindings and set operations | ✅ Implemented | `cel.bind`, `sets.contains`, `sets.equivalent`, and `sets.intersects`. |
| Lazy blocks | ✅ Implemented | `cel.block`, `cel.index`, and `cel.iterVar`; cached values/errors and isolated lexical scopes. |
| Block optimization | ✅ Implemented | Slot reads resolve to their initializer nodes; JVM guest-compilation and allocation gates cover the compiled path. Source-backed tooling retains observable boundary evaluation. |
| Base64 encoding/decoding | ✅ Implemented | Standard alphabet, padded or unpadded decoding, and CR/LF handling. |
| URL-safe Base64 and structured encoders | ✅ Implemented | `base64.encodeUrl`/`decodeUrl`, `json.encode`/`parse`, and `yaml.encode`/`parse`, including typed optional parsing and bounded data traversal. |
| Protobuf messages and well-known types | ✅ Implemented | Proto2/proto3 fields, construction, presence, native wire decoding, and registered `Any` payloads through [TruffleData](INTEGRATION.md). |
| Proto2 extensions | ✅ Implemented | `proto.hasExt` and `proto.getExt`; descriptors must be registered explicitly. |
| Protobuf Editions and MessageSet | ✅ Implemented | Resolved edition presence, packing and enum features feed native plans; explicitly registered MessageSet payloads and unknown payloads round-trip through native storage. |
| Unknown values | ✅ Implemented | Propagation, error precedence and short-circuiting have regression coverage, including AST and RPC paths. Upstream's empty `unknowns` file provides no additional conformance evidence. |
| Canonical protobuf AST interchange | ✅ Implemented | `CanonicalAst` exports/imports `ParsedExpr` and `CheckedExpr`; all pinned cases also run through interchange, with cross-engine wire tests. |
| CEL conformance RPC service | ✅ Implemented | Standalone gRPC Parse/Check/Eval service, with explicit descriptor registration, bounded requests and loopback transport tests; see [interchange and RPC](INTERCHANGE.md). |
| JVM embedding and Truffle tooling | ✅ Implemented | Thin JARs, Polyglot integration, node instrumentation, profiling/coverage tags, and debugger scopes. |
| Native Image guest compilation | ✅ Tested | Native JIT/blocklist checks and representative compiled-operation tests run in CI. |

Resource limits intentionally bound expression size/depth, evaluation work, and collection or
string expansion. See [extension support](EXTENSIONS.md), [integration](INTEGRATION.md),
and [benchmark methodology](BENCHMARKS.md) for the detailed contracts.
