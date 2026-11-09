# Maintenance and supply-chain security

## Dependency updates

Dependabot runs weekly for Bazel, GitHub Actions and maintained chart-tooling Python
requirements. Minor/patch updates are grouped;
major upgrades remain separate PRs. All updates require the normal review and CI gates;
there is no automatic merge or execution of privileged PR code. This replaces the need
for a separate self-hosted Renovate deployment for those ecosystems.

[Bazel support](https://blog.bazel.build/2026/02/20/dependabot.html) includes Bzlmod
and lockfile generation. It does **not** mean every dependency in MODULE.bazel is
covered: the current parser discovers `bazel_dep` declarations, not `maven.install`
coordinates, `go_sdk.download`, custom GraalVM archives, or our CodSpeed source archive.
The `rules_graalvm` nominal version is ignored because a reviewed fork commit overrides it.
There is no independent Go module or npm package manifest in this repo. Python
requirements under `tools/bench/charts` are maintained; archived benchmark
requirements retain their original versions and are not safe defaults for new work.

Maintainers should review these remaining pins monthly and before releases:

| Dependency | Update and validation |
| --- | --- |
| Maven libraries, including TruffleData | Update MODULE.bazel; run `REPIN=1 bazel run --config=local @maven//:pin`; update publication POM dependencies in `tools/release/BUILD.bazel` |
| Comparison-only cel-java | Update its isolated Maven installation; run `REPIN=1 bazel run --config=local @cel_java//:pin`; rerun comparisons |
| GraalVM and rules_graalvm fork | Update matching Maven versions, repositories, archive URLs, checksums and fork commit together; run native JIT/blocklist and allocation gates |
| Bazel, Go SDK, CodSpeed hooks, downloaded CI tools | Review upstream releases, update versions and verified digests, regenerate Bazel lockfile, run affected tooling |
| CEL specification corpus | Record upstream revision and license; run every conformance file and update compatibility counts |

Review transitive lockfile changes, licensing and advisories, not only the top-level
version. Never substitute an unverified checksum to make a download pass. Regenerate
Gazelle targets and run the complete tests, coverage gate and publication consumer tests
for runtime upgrades. Dependabot PRs may need maintainer lockfile repair when module
extensions or custom repositories cannot run inside its updater environment.

## Security automation

- `security.yml` runs CodeQL on Java, Python and Actions on PRs, main and weekly.
  Java uses source analysis (`build-mode: none`); generated Truffle classes and full
  build dependency resolution are outside this analysis. CI still compiles and tests them.
- Dependency review rejects introduced high/critical known vulnerabilities on PRs.
  Its visibility is limited to dependencies recognized by GitHub's dependency graph;
  custom archives and embedded dependency representations still need manual review.
- `scorecard.yml` publishes OpenSSF results on main and weekly and uploads SARIF to
  GitHub code scanning. Its isolated job follows Scorecard's publishing restrictions.
- Existing CI enforces conformance, 99.8% line/branch coverage, allocation ceilings,
  native guest compilation and Truffle blocklist checks. Actions are SHA-pinned,
  checkouts discard credentials, and jobs use explicit permissions and runner hardening.
- PR/main CI runs a 2,000-input coverage-guided Jazzer smoke test. The dedicated
  `fuzzing.yml` schedule runs 100,000 inputs daily on a fresh hosted runner without
  publication credentials and retains crash/coverage artifacts. A coverage check
  fails if instrumentation is inactive. Scorecard may not recognize this custom integration.
- GitHub secret scanning, push protection, private vulnerability reporting and
  Dependabot security updates are enabled. R2 publishing credentials remain scoped to
  the publication step; repository code must never log them.

Triage security alerts before release. Record resolutions or justified dismissals in
GitHub's security UI; do not suppress checks merely to improve a score. For a
vulnerability, follow [SECURITY.md](../SECURITY.md) privately.

## OpenSSF readiness

See [OpenSSF evidence](OPENSSF.md) for the measured score, current findings,
remediation and verified versus outstanding claims. The contributor and agent
policies follow the shared Elide pattern while retaining TruffleCEL's own validation
and licensing contracts.

[Scorecard](https://scorecard.dev/) provides a numeric assessment, not a gold tier.
[Best Practices gold](https://www.bestpractices.dev/en/criteria/2) is a separate,
evidence-based badge requiring passing and silver first. This project does not yet
claim that badge. CI configuration alone cannot establish it.

Track these remaining items with evidence before applying:

- Complete passing/silver/gold self-assessments and register the project with the badge service.
- Verify organization-enforced 2FA and demonstrate two independent significant
  contributors and a bus factor of at least two; CODEOWNERS alone does not prove either.
- Establish and retain independent review evidence for releases; document any emergency bypass.
- Audit per-source copyright/SPDX notices while preserving upstream ownership and licenses.
- Demonstrate reproducible release artifacts with independent rebuilds, and add
  verifiable release provenance/signatures and a dependency inventory/SBOM.
- Perform a documented security review against the threat model; expand the existing
  coverage-guided fuzzing campaign and dynamic analysis beyond its frontend/decoder scope.
- Audit download/website hardening headers and identify small newcomer tasks.

The badge criteria and Scorecard results are the source of truth; this list is a
maintenance roadmap, not a completed certification or an exhaustive audit.
