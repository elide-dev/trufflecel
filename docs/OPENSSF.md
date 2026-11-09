# OpenSSF evidence and remaining work

OpenSSF Scorecard and the OpenSSF Best Practices badge assess different things.
The [live Scorecard](https://scorecard.dev/viewer/?uri=github.com/elide-dev/trufflecel)
is the source of truth for the numeric score. TruffleCEL does not currently claim
a Passing, Silver or Gold Best Practices badge.

The November 9, 2026 assessment of main `2de7031` was **5.9/10**.
Its [workflow](https://github.com/elide-dev/trufflecel/actions/runs/42549366295)
retains SARIF; the [public JSON snapshot](security/scorecard-20261109-before.json)
records the findings before this documentation/security follow-up. Do not describe
proposed changes as an attained score increase before the subsequent run finishes.

## Evidence and action

| Area | Recorded finding | Action / evidence |
| --- | --- | --- |
| Token permissions | 0/10; release workflow had top-level contents/actions writes | Read-only reusable-workflow default; release creation gets contents/PR writes, while a separate dispatch job gets Actions write and never checks out release-branch code. Caller permissions still describe the maximum grant needed by the reusable workflow. |
| Vulnerabilities | 9/10; `PYSEC-2026-4322` in chart-tooling Brotli 1.2.0 | Upgrade the maintained renderer to 1.2.1, the [fixed version](https://osv.dev/vulnerability/PYSEC-2026-4322), and cover its requirements with weekly Dependabot updates. Font decoding is a tooling operation, not a runtime dependency. |
| License | 9/10; root pointer was not recognized as an OSI/FSF license | Root LICENSE now contains the complete Apache-2.0 text and explicit MIT alternative, following Toolchain/Myna; both full alternative texts and third-party notices remain present. This clarifies the existing grant, not a relicensing of dependencies. |
| Fuzzing | 0/10; recognized integration not found | Existing PR/main CI runs real Jazzer coverage-guided fuzzing. Add a daily 100,000-run job using the same target, pinned toolchain and coverage checks. This improves sustained execution; it does not guarantee Scorecard recognizes the custom Bazel integration. |
| Code review | 0/10; no approved changesets in the sampled window | Independent human review must occur. Document administrative exceptions; never invent approvals or count an agent's self-review. |
| Branch protection | 8/10; administrator bypass and one approval | Keep the existing policy. One separate reviewer plus the author is distinct from requiring two approvals. Changing protections needs a deliberate governance decision, not score chasing. |
| Signed releases | 0/10; no signatures/provenance detected for v0.10.0 | Add verifiable provenance to a future publication build and independent rebuild evidence. Do not manufacture provenance for existing artifacts or rewrite released bytes/tags. |
| Best Practices | 0/10; no registered badge effort detected | `.bestpractices.json` provides a limited evidence-backed draft. Registration, saved self-assessment and maintainer attestations remain outstanding; the draft is not a badge. |
| Maintained | 0/10; repository younger than 90 days | Time and sustained maintenance are required. This cannot be fixed by editing metadata. |
| SAST / CI tests | 9/10 each; historical unchecked commits/PRs in the sample | Keep CodeQL and required CI active. Wait for real coverage of the moving history window; do not falsify or suppress results. |

Dependency updates, dangerous-workflow checks, binary-artifact checks, pinned
dependencies, security policy and contributor diversity each scored 10 in that
snapshot. Packaging was not detected by Scorecard; actual Maven publication uses
the [immutable release workflow](../.github/workflows/publish.maven.yml) and
[consumer validation](PUBLISHING.md). Scanner recognition is not a substitute for
the artifact's own build, dependency and publication evidence.

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

## Before claiming a Best Practices tier

Complete and save the official self-assessment. Verify security response evidence,
account controls, continuity/recovery and two-person review with the people
responsible. Audit maintained-source notices and shipped distributions, complete a
documented security review, inventory release dependencies, and demonstrate
reproducible release builds/provenance. Keep incomplete items marked as such.

The deterministic fuzz regression and PR smoke run do not prove complete dynamic
coverage. The daily campaign targets CEL parsing/preparation, canonical AST import,
and JSON/YAML decoders; it is not a full arbitrary-host-function or compiled-guest
fuzz campaign. Retain crash reproducers and coverage, minimize failures, add
regressions, and report exploitable defects through the private security process.
