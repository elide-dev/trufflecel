# OpenSSF Passing assessment

TruffleCEL's registered project is
[16472](https://www.bestpractices.dev/en/projects/16472). The saved assessment on
that service determines the badge. Repository evidence is a proposed assessment,
not a certificate and not a substitute for saving reviewed answers.

The November 9, 2026 review covers all **67 Passing criteria** in the
[official criteria](https://www.bestpractices.dev/en/criteria/0).
[`.bestpractices.json`](../.bestpractices.json) records a status and specific
justification for each: 54 Met, 12 Not Applicable, and one Unmet suggested item.
The suggested `warnings_strict` item remains Unmet because we do not globally
enable every javac warning or fail on all warnings from pinned upstream sources.
It is not a required Passing criterion. These are proposed answers until the
project owner reviews and saves them.

## Save the assessment

```sh
python3 tools/best-practices.py
open build/best-practices.html
```

The generated page splits proposals into bounded links to the official Passing
edit form. Sign in as the owner of project 16472, open each link, review its
highlighted proposals, then save before opening the next link. The script uses
the service's documented proposal interface and never handles credentials or
submits answers. Existing answers are not forcibly replaced. The service can
also read `.bestpractices.json` from main after this change lands.

After saving all sections, check the public project page for the attained tier
and any remaining criterion. Never replace the live badge with a static Passing
image before the service awards it. Revisit the assessment after material changes,
new reports, new vulnerabilities and releases.

## Evidence reviewed

| Area | Evidence and limits |
| --- | --- |
| Basics, licensing, build and tests | README, dual-license texts, contribution and interface docs, pinned Bazel/GraalVM tools, full conformance, CI and 99.8% line/branch gates |
| Change control | Public Git commits and PRs between releases; immutable version tags/Maven coordinates; categorized release notes |
| Report handling | Both bug-labelled issues (#13/#29) resolved through linked PRs; five of seven enhancement-labelled issues resolved. The repository began on November 3, 2026: only its available history is claimed. |
| Private reports | On November 9, Sam Gammon confirmed that no vulnerability reports had arrived through any channel since project creation. This supports N/A for historical response timing; the first report must trigger reassessment. |
| Developer knowledge | On November 9, Sam Gammon, the primary developer named in GOVERNANCE.md, explicitly confirmed both secure-design and common-vulnerability knowledge criteria against the [secure development guide](SECURE_DEVELOPMENT.md). No certification or course completion is claimed. |
| Vulnerabilities and credentials | Authenticated GitHub API audit found zero open Dependabot alerts, zero open secret alerts, no project advisories and no open CodeQL vulnerability findings. Secret scanning and push protection are enabled. Scanner coverage and custom-dependency limits remain documented in MAINTENANCE.md. |
| Static analysis | CodeQL security-extended for Java, Python and workflows on PRs/main; source-only Java analysis does not fully analyze generated/native code. |
| Dynamic analysis | Raw parser/AST/decoder and structured evaluator Jazzer targets, coverage enforcement, JVM assertions and daily campaigns. This is bounded testing, not exhaustive security proof. |
| Cryptography | The delivered CEL library implements no cryptography or password/key management. Tooling uses standard hash libraries; legacy Maven checksums are not authentication. |
| Delivery | HTTPS source/artifact delivery and pinned download digests. New publisher supports signed provenance; no retrospective attestation is claimed for 0.10.0. |

The open code-scanning findings during this audit were Scorecard process findings
(review history, repository age, fuzzing recognition, branch protection and badge
registration), not CodeQL vulnerability findings. They remain visible and tracked
in [OpenSSF evidence](OPENSSF.md); they were not dismissed to support this assessment.

Gold/Silver requirements such as an independent security audit, organization-wide
account attestations and recovery exercises remain separate follow-ups. Passing
does not certify perfect security or eliminate the need for independent reviews.
