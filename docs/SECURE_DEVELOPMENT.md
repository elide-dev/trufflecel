# Secure development and review

Read this with the [threat model](THREAT_MODEL.md), [security policy](../SECURITY.md)
and [contribution rules](../CONTRIBUTING.md). This guide documents the design and
review expectations; it does not certify any person's knowledge or training.

## Design principles in TruffleCEL

| Principle | Application and review question |
| --- | --- |
| Least privilege | CEL sees declared values and explicitly registered host functions. Does a change expose new host capabilities or grant workflow writes beyond the job that needs them? |
| Fail-safe defaults | Reject invalid ASTs, unsupported overloads and exhausted budgets. An embedding authorization system should deny on errors or unknowns unless its policy explicitly defines another safe outcome. |
| Complete mediation | Check every imported tree and every invocation's bounds. A prepared program or cached conversion must not bypass per-evaluation limits. |
| Separation of privilege | Keep parsing/evaluation separate from host authorization; keep Maven credentials outside PR jobs. Seek independent review, especially for security boundaries. |
| Economy of mechanism | Prefer explicit adapters and narrow APIs to reflection, implicit class loading or unrestricted object graphs. Keep security-sensitive paths small enough to audit. |
| Open design | Publish source, the threat model and security-relevant limitations. Secrecy of an implementation is not a security control. |
| Least common mechanism | Do not share mutable per-call state, scopes, budgets or borrowed host values between evaluations. Test reuse and concurrency. |
| Usable security | Document ownership and limit settings with runnable examples. Errors must be actionable without disclosing secrets or changing CEL error semantics. |
| Limited attack surface | Keep the annotation processor and conformance RPC outside the production runtime closure; enable extensions and host functions only when needed. |
| Input allowlists | Accept only supported syntax, AST shapes, types and functions; reject unknown structures rather than trying to enumerate every malicious input. |

## Common failure modes and controls

| Failure mode | Countermeasure and verification |
| --- | --- |
| Unbounded source, recursion, collections or regex work | Bound source/AST size, depth and operation budgets; test exhaustion, cancellation, large inputs and regex fallback paths. Never assume a host callback obeys CEL's budget. |
| Type confusion, arithmetic errors or authorization mistakes | Type-check inputs, preserve CEL overflow/error semantics and run the full conformance corpus. Add negative and boundary regressions plus independent evaluator oracles. |
| Unsafe deserialization or code injection | Revalidate canonical ASTs; use data-only JSON/YAML parsing; reject Java object tags. Pass shell inputs as quoted data, never interpolate untrusted workflow expressions into code. |
| State leakage and time-of-check/time-of-use errors | Keep borrowed inputs stable for a call; snapshot when ownership is needed; use fresh evaluation contexts and concurrent-reuse tests. |
| Resource retention and native-runtime failures | Avoid retaining host objects or relying on reflection/resources on native paths. Run native JIT/blocklist and allocation checks for affected changes. |
| Dependency or delivery compromise | Check pins and advisories, fetch over HTTPS, verify trusted SHA-256 or stronger digests, and verify release attestations where available. |
| Credential exposure | Keep secrets out of source, logs and reproducers; use scoped CI permissions and private reports. If exposure is suspected, revoke/rotate first and investigate privately. |

Use [OWASP's secure code review guidance](https://owasp.org/www-project-code-review-guide/)
and the [CWE catalog](https://cwe.mitre.org/) for additional review patterns.
Primary developers claiming the Best Practices knowledge criteria must personally
understand these principles and mitigations. Reading this file alone is not evidence
that they do.

## Release security checks

Before release, maintainers inspect CodeQL, dependency review, secret scanning,
the complete test/coverage results and both Jazzer targets. Review custom GraalVM,
TruffleData and Maven dependencies using [maintenance procedures](MAINTENANCE.md).
Fix confirmed exploitable findings under the security policy; keep private report
details private and retain public links to disclosed advisories and fixes.

Resolve compiler/linter warnings before merging, or document a narrow suppression
with its reason. Bazel explicitly enables Java unchecked/deprecation warnings;
CodeQL adds security analysis of Java, Python and workflows. The formatter is a
style check, not a security analyzer. Fuzzing runs with JVM assertions enabled and
retains instrumentation coverage and crash reproducers.

The pinned protobuf implementation and generated upstream proto2 group fixtures
emit legacy API/unchecked warnings. Preserve their upstream code and corpus;
review these warnings during dependency updates. Comparison-only cel-java options
and the pinned GraalVM deoptimization listener have narrowly documented deprecation
suppressions. We do not claim every javac warning is enabled or treat all upstream
warnings as build failures.

The CEL library does not implement cryptography, key exchange, password storage
or nonce generation. Maven tooling uses Python's standard `hashlib`; MD5/SHA-1
sidecars exist for repository compatibility and are not authentication mechanisms.
Use HTTPS plus SHA-256/SHA-512 checks, and signed SHA-256 provenance when present.
Do not use legacy checksum sidecars as a security decision.
