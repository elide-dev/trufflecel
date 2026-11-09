# TruffleCEL documentation

Start with the [quick start](../README.md#get-started). TruffleCEL's public modules
share a release version; the [publishing guide](PUBLISHING.md) describes repository
configuration, runtime dependencies and artifact verification.

| Goal | Guide |
| --- | --- |
| Embed CEL in Java, Elide or Polyglot | [Integration, ownership and adapters](INTEGRATION.md) |
| Understand the frontend and runtime | [Architecture](ARCHITECTURE.md) |
| Check supported behavior and limits | [Compatibility](COMPATIBILITY.md), [extensions](EXTENSIONS.md), [validation](VALIDATION.md) |
| Import/export ASTs or run conformance RPC | [Interchange](INTERCHANGE.md) |
| Configure environments, policies or cost bounds | [Tooling](TOOLING.md) |
| Profile or debug expressions | [Instrumentation](INSTRUMENTATION.md) |
| Compare engines and memory costs | [Comparison guide](BENCHMARK_COMPARISON.md), [chart gallery](images/benchmark-0.10.0-elide/README.md), [methodology/history](BENCHMARKS.md) |
| Build and validate | [Bazel](BAZEL.md), [CI](CI.md), [coverage](COVERAGE.md) |
| Contribute code, evidence or documentation | [Contributing](../CONTRIBUTING.md), [agent guidance](../AGENTS.md), [contributor agreement](../CONTRIBUTOR_AGREEMENT.md) |
| Understand project responsibilities | [Governance](../GOVERNANCE.md), [conduct](../CODE_OF_CONDUCT.md), [support](../SUPPORT.md) |
| Review security and supply-chain practices | [Security policy](../SECURITY.md), [threat model](THREAT_MODEL.md), [maintenance](MAINTENANCE.md), [OpenSSF evidence](OPENSSF.md) |
| Redistribute code or dependencies | [Dual licensing](../LICENSE), [third-party scope](../THIRD_PARTY.md), [notices](../NOTICE) |

Versioned benchmark reports keep their original measurements and dependencies.
The current comparison guide identifies which evidence qualifies 0.10.0 and which
remains historical. Do not infer a current-release result from an older report's date.
