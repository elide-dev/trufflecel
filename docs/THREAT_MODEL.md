# Security boundaries

TruffleCEL evaluates potentially untrusted CEL source against values supplied by a
trusted embedder. Assets include host data confidentiality, correct authorization
results, process availability and the integrity of compiled programs and artifacts.

The parser/type checker, evaluator, adapters and their dependencies are inside the
library's trusted computing base. Host functions, type registries and the surrounding
application are controlled by the embedder. CEL does not grant arbitrary Java method
access. This library is not an operating-system sandbox.

| Threat | Controls and limits |
| --- | --- |
| Malicious expressions exhaust CPU, memory or nesting | Configure source/AST/evaluation limits; preserve operation charging and depth checks. Host callbacks must enforce their own deadlines and resource limits. Use process isolation where stronger guarantees are required. |
| Policies produce incorrect authorization results | Independent type checking, upstream conformance, regression tests and explicit error/unknown handling. Embedders must decide how errors and unknowns affect authorization. |
| Malformed serialized ASTs or structured text bypass frontend guarantees | Canonical AST import validates ids, size and depth and rechecks checked trees against the receiving environment. JSON/YAML decoding uses data-only parsers, bounded traversal and operation charging; YAML Java object tags are rejected. |
| Conformance RPC exposes host capabilities | The host tool binds loopback, bounds request size and executor queues, and accepts descriptor sets only through explicit host configuration. Its transport is outside the publication dependency closure. |
| Java POJO adaptation exposes host code or mutable state | The optional annotation processor inspects compile-time type metadata and emits direct accessors. Only explicit host snapshot calls invoke getters, which the embedder must trust. Generated snapshots share one bounded traversal/cycle guard and retain no original POJO references; CEL evaluation accepts only owned values and never invokes getters. The processor stays off runtime/Native Image classpaths. |
| Mutable inputs or concurrent evaluations leak state | Callers keep borrowed inputs stable during evaluation; explicit snapshots provide eager isolation. Per-evaluation conversion caches/budgets and concurrency tests prevent cross-call state sharing; review adapters for retained host objects. |
| Native compilation changes runtime behavior | Native JIT/blocklist, allocation and compilation checks; instrumentation and boundary review. These are not substitutes for a security audit. |
| Dependency, CI or release compromise | Pinned dependencies/actions/download digests, review, scanning, least-privilege workflows, immutable Maven versions and checksum verification. Custom dependencies still require manual advisory review. |
| Secrets exposed through automation | No secrets in fork builds, no persistent checkout credentials, scoped publication credentials, secret scanning and push protection. Treat PR content as untrusted, including logs and workflow inputs. |

Review this model when adding syntax, extensions, adapters, callbacks, serialization,
native execution paths or release credentials. Report suspected boundary violations
through [the private security process](../SECURITY.md). This document records design
assumptions; it is not evidence of an independent security review.
