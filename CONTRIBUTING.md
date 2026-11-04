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

## Contribution licensing

By intentionally submitting a contribution for inclusion, you agree to license
it under **both MIT and Apache-2.0**, so recipients may choose either license,
without additional terms. You retain your copyright. Only submit work you have
the right to contribute, and retain third-party attribution and license notices.

No separate contributor license agreement (CLA), copyright assignment, or
Developer Certificate of Origin sign-off is required. Contributions must follow
the [code of conduct](CODE_OF_CONDUCT.md).

## Review standards

Use a pull request for changes to main and seek review from a maintainer other than
the author. Reviewers should check correctness and CEL compatibility, error and
unknown semantics, resource limits, thread safety, dependency/license changes,
instrumentation and Native Image behavior, and whether tests demonstrate the change.
Security-sensitive changes also require reviewing the
[threat model](docs/THREAT_MODEL.md). All relevant CI checks must pass before merge;
coverage exclusions or relaxed allocation limits need a documented technical reason.
Keep review discussion and resolution in the PR. Document any emergency administrative
bypass and arrange follow-up review before release.

Dependency update procedures and the OpenSSF readiness roadmap are in
[maintenance](docs/MAINTENANCE.md). Coding agents should also read [AGENTS.md](AGENTS.md).
