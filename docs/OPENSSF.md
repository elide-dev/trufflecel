# OpenSSF evidence and remaining work

OpenSSF Scorecard and the OpenSSF Best Practices badge assess different things.
The [live Scorecard](https://scorecard.dev/viewer/?uri=github.com/elide-dev/trufflecel)
is the source of truth for the numeric score. TruffleCEL earned the
[Passing Best Practices badge](https://www.bestpractices.dev/en/projects/16472)
on November 9, 2026; Silver and Gold remain unclaimed.

The November 9 assessment of main `666276f` scored **6.8/10**, up from
**5.9/10** at `2de7031`. Retained snapshots record
[5.9](security/scorecard-20261109-before.json) and
[6.8](security/scorecard-20261109-6.8.json). The current live assessment can be
newer; proposed changes are not an attained increase until it finishes.

## Evidence and action

| Area | 6.8 baseline finding | Action / evidence |
| --- | --- | --- |
| Token permissions, vulnerabilities, license | Each 10/10 after the previous fixes | Preserve scoped writes, patched tooling and recognized complete license texts. |
| Fuzzing | 0/10; raw-byte Jazzer entry point not recognized | Add a real `FuzzedDataProvider` evaluator target with independent Java oracles alongside frontend/decoder fuzzing. PR/main smoke runs 2,000 inputs per target; daily runs 100,000 per target with assertions and coverage enforcement. |
| Code review | 0/10; no approved changesets in the sampled window | Independent human review must occur. Document administrative exceptions; never invent approvals or count an agent's self-review. |
| Branch protection | 8/10; administrator bypass and one approval | Keep the existing policy; do not change protections merely to chase points. |
| Signed releases | 0/10; v0.10.0, v0.11.0 and v0.12.0 have no signatures/provenance | The future Maven publisher signs all twelve JAR/source/POM digests and attaches evidence to its release. See [verification and limitations](PROVENANCE.md). Existing release bytes/evidence remain unchanged; this score cannot improve from future-only configuration alone. |
| Best Practices | 0/10; registration not detected in this snapshot | Project [16472](https://www.bestpractices.dev/en/projects/16472) earned Passing (100%) after the owner reviewed and saved every section. The [assessment](BEST_PRACTICES.md) and [public snapshot](security/best-practices-20261109-passing.json) retain the evidence. The baseline predates that award. |
| Maintained | 0/10; repository younger than 90 days | Time and sustained maintenance are required. This differs from the Best Practices maintained criterion; metadata edits cannot create history. |
| Packaging | Not detected | Custom immutable R2 Maven publishing is not recognized by the scanner. Retain actual publication/consumer evidence; do not add an unused packaging action for recognition. |

All other scored checks were 10/10, including SAST, CI tests, dependency updates,
pinned dependencies, security policy and contributor diversity. Scanner recognition
is not a substitute for artifact build, dependency and publication evidence.

## Project practices

| Practice | Where to inspect |
| --- | --- |
| Adoption, interfaces and limitations | [README](../README.md), [documentation index](README.md), [compatibility](COMPATIBILITY.md) |
| Contribution rights and review | [Contributor agreement](../CONTRIBUTOR_AGREEMENT.md), [contributing](../CONTRIBUTING.md), [agent guidance](../AGENTS.md) |
| Community accountability | [Governance](../GOVERNANCE.md), [code of conduct](../CODE_OF_CONDUCT.md), CODEOWNERS |
| Security boundaries and reporting | [Security policy](../SECURITY.md), [threat model](THREAT_MODEL.md), private GitHub advisory reporting |
| Licensing | [Apache-2.0 OR MIT](../LICENSE), both full license texts, [third-party scope](../THIRD_PARTY.md), [NOTICE](../NOTICE) |
| Validation | Complete pinned conformance, edge/error cases, 99.8% line/branch gates, shipped adapter tests and native guest-JIT/blocklist checks |
| Supply chain | SHA-pinned Actions, lockfiles, checksum-pinned toolchains, dependency review, scoped credentials and [maintenance limits](MAINTENANCE.md) |
| Performance evidence | [Comparison guide](BENCHMARK_COMPARISON.md), preserved raw samples, versioned reports and reproducible charts |

Policy layout was compared with
[Toolchain](https://github.com/elide-dev/toolchain/tree/f09db1906678ec1e9a5f2abd8a00cf496351b795)
and [Myna](https://github.com/elide-dev/myna/tree/d57cd50e9cc1ec3a40fa10bd5816a0bb643a2845).
Their achievements, organization attestations and recovery exercises are not
automatically TruffleCEL evidence. TruffleCEL retains its existing no-mandatory-DCO
policy and existing CodeQL analysis; adopting useful documentation patterns does
not silently import another repository's operational policy.

## Best Practices scope

The [Passing assessment](BEST_PRACTICES.md) records repository evidence and the
primary developer's explicit knowledge/report-history confirmations. The badge
service's saved state is authoritative. Silver/Gold recovery exercises, independent
security review, reproducible rebuilds and other stronger requirements remain
follow-ups; they are not silently claimed by a Passing application.

Fuzzing remains bounded. Targets cover CEL parsing/preparation, canonical AST
import, JSON/YAML decoders, and selected evaluator arithmetic/logic/collection
oracles through borrowed and snapshotted activations. They do not constitute a
full arbitrary-host-function or compiled-guest campaign. Retain crash reproducers
and coverage, minimize failures, add regressions, and report exploitable defects
through the private security process.
