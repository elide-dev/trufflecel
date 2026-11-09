# Contributing to TruffleCEL

Contributions are open to everyone. Bug reports, documentation, CEL conformance
cases, performance investigations, and code are welcome. You do not need an
invitation or an employer affiliation to participate.

## Getting started

Read the [README](README.md), [Bazel guide](docs/BAZEL.md), and
[code of conduct](CODE_OF_CONDUCT.md). Use the pinned Bazel and GraalVM versions
from the repository. Local development does not require BuildBuddy credentials;
use `--config=local` for local execution.

Open an [issue](https://github.com/elide-dev/trufflecel/issues) for a bug or to
discuss a substantial API or architecture change. Small fixes can go straight
to a pull request. Include a reproducer and expected behavior; for language
changes, link the relevant [CEL specification](https://github.com/cel-expr/cel-spec).
Report vulnerabilities privately using the [security policy](SECURITY.md).

## Preparing a pull request

Keep changes focused and explain the observable behavior they change. Add
regression tests for bugs and meaningful tests for new behavior. Preserve
resource limits, Native Image compatibility, and Truffle instrumentation.
Avoid reflection and new runtime resources unless the design requires them.

```sh
bazel run --config=local //bazel/gazelle:update
bazel run --config=local //tools:format
bazel test --config=local //...
bazel coverage --config=local //src/test/...
bazel run --config=local //tools:coverage
```

Gazelle maintains Java targets. See the [coverage audit](docs/COVERAGE.md) for
coverage requirements and [benchmark methodology](docs/BENCHMARKS.md) for
performance work. Native JIT/blocklist validation runs in CI. Describe which
checks you ran and any limitations in the pull request; fork contributors do
not need access to organization secrets.

Maintainers review changes for correctness, compatibility, tests, and project
scope. Use a descriptive conventional commit-style PR title such as
`fix: preserve uint comparisons` or `docs: clarify embedding`. Maintainers can
help with release categorization. See [governance](GOVERNANCE.md) for decisions.

## Choose the right validation

| Change | Evidence expected |
| --- | --- |
| Parser, checker or evaluator | Relevant regression tests plus complete conformance; preserve rejection phase, CEL error categories, limits and concurrent reuse |
| Protobuf/JSON or ownership | Shipped adapter tests, mutable-input/aliasing cases and applicable wire/interchange tests |
| Compilation, allocation or native paths | JVM checks plus Linux native guest-JIT/blocklist gates; a JVM pass alone does not qualify Native Image |
| Performance claims | Matched engine/configuration/input contracts, verified outputs, raw forks, source/runtime hashes and the [comparison guide](docs/BENCHMARK_COMPARISON.md); publish losses and unresolved scope |
| GitHub Actions | `actionlint` and `python3 -m unittest discover -s .github/scripts -p 'test_*.py'`; inspect token permissions, trigger trust and fork behavior |
| Documentation/charts | Check local links, versioned examples, source data, font licensing and rendered output; do not rewrite archived measurements |

Run builds sequentially in one checkout. Avoid simultaneous benchmark jobs on a
shared host. Use the checks above to choose focused validation while preparing
the change; CI still runs the repository's full required gates. Report exactly
what ran, failed, was skipped, or still requires Linux or release infrastructure.

## Coding and review advice

Java follows the repository's google-java-format tool; Gazelle owns generated Java
targets. Follow existing Python and shell conventions, quote paths and shell
arguments, and keep dependency versions with verified checksums. Resolve warnings
or explain a narrow suppression; do not disable checks globally. Keep new
first-party source copyright and `SPDX-License-Identifier: Apache-2.0 OR MIT`
notices, preserving shebangs and existing attribution. Upstream/generated files
retain their own notices; the historical source-header audit remains open.

Explain the concrete trigger and before/after behavior in a PR. Include a small
reproducer and evidence a reviewer can rerun. Reviewers should distinguish defects
from preferences, identify blocking concerns, and re-review affected code after a
new push. Approval requires someone other than the author; bots and agent output
do not substitute for an accountable review. Do not claim an approval that did not
occur, even when an administrator authorizes a documented bypass.

## Starter contributions

| Task | Completion check |
| --- | --- |
| Improve an integration example | Run it against the documented release and state input ownership and error behavior |
| Add a negative CEL edge case | Assert the rejection phase and defined error category; keep the complete upstream corpus intact |
| Add a decoder fuzz seed | Use a minimized, non-sensitive reproducer and run `//tools/fuzz:regression`; use the private security process for exploitable findings |
| Improve chart accessibility | Keep complete data and text alternatives, validate every displayed value, and inspect the exported SVG/PNG |

## Contribution licensing

By intentionally submitting a contribution for inclusion, you agree to license
it under **both MIT and Apache-2.0**, so recipients may choose either license,
without additional terms. You retain your copyright. Only submit work you have
the right to contribute, and retain third-party attribution and license notices.

No separate contributor license agreement (CLA), copyright assignment, or
Developer Certificate of Origin sign-off is required. Contributions must follow
the [code of conduct](CODE_OF_CONDUCT.md).
The [contributor agreement](CONTRIBUTOR_AGREEMENT.md) spells out the inbound
license grant, provenance and responsibilities for assisted contributions.
The [third-party guide](THIRD_PARTY.md) describes exceptions to first-party licensing.

## Review standards

Use a pull request for changes to main and seek review from a maintainer other than
the author. Reviewers should check correctness and CEL compatibility, error and
unknown semantics, resource limits, thread safety, dependency/license changes,
instrumentation and Native Image behavior, and whether tests demonstrate the change.
Security-sensitive changes also require reviewing the
[threat model](docs/THREAT_MODEL.md) and [secure development guide](docs/SECURE_DEVELOPMENT.md).
All relevant CI checks must pass before merge;
coverage exclusions or relaxed allocation limits need a documented technical reason.
Keep review discussion and resolution in the PR. Document any emergency administrative
bypass and arrange follow-up review before release.

Dependency update procedures and the OpenSSF readiness roadmap are in
[maintenance](docs/MAINTENANCE.md) and [OpenSSF evidence](docs/OPENSSF.md).
Coding agents should also read [AGENTS.md](AGENTS.md).
