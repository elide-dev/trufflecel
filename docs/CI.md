# CI and release operations

`ci.yml` is the entry point for main pushes, pull requests and manual checks. It calls
`check.bazel.yml` and `benchmarks.yml`. Successful main pushes then call
`release-please.yml`; a newly created release calls `publish.maven.yml` directly in the
same dependency chain. This avoids relying on a second workflow being triggered by a bot's
GitHub release event. Failed correctness, guest-JIT, allocation, or simulation checks block
release creation. `check.bazel.yml` runs both the JVM suite (including `//benchmarks:compiled`)
and `//benchmarks:jit_smoke`, which builds with Truffle blocklist checks enabled and verifies
actual native guest compilation.

Jobs use Ubuntu 24.04, commit-pinned actions, StepSecurity harden-runner in audit mode,
explicit permissions, timeouts and non-persistent checkout credentials. Codecov reporting and CodSpeed
measurement get OIDC permission; only Release Please gets repository/PR write permission and permission to dispatch checks.
The publisher has read-only GitHub access and scoped R2 credentials in its upload step.

## Repository provisioning

Provide these existing organization conventions to `elide-dev/trufflecel`:

| Secret or service | Purpose |
| --- | --- |
| `BUILDBUDDY_API_KEY` or `BUILDBUDDY_APIKEY` | RBE and cache; without it CI explicitly builds on the hosted Linux runner |
| `CLOUDFLARE_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | Publishing to the `elide-maven` R2 bucket |
| `CODECOV_TOKEN` (optional) / Codecov GitHub integration | JVM LCOV and JVM/native JIT test reporting; uses OIDC when no token is provided |
| CodSpeed GitHub integration | Enable `elide-dev/trufflecel`; uploads use GitHub OIDC, without a stored CodSpeed token |

Release Please uses the built-in `GITHUB_TOKEN`, with Contents, Pull requests and Actions
write permissions. No bot app or PAT is required. Following Studio's convention, it explicitly
dispatches `ci.yml` on each created/updated release branch. Those runs execute the same Bazel
and simulation checks; they cannot run Release Please or publish because those jobs require
a main push. Publishing still runs directly after release creation in the main CI chain.
The repository must allow GitHub Actions to create pull requests (already enabled here).
Fork PRs get no BuildBuddy key; their builds use the local Linux profile.

The files must be committed and pushed to the repository for GitHub to run them. Branch
protection should require the reusable Bazel and benchmark jobs before merging. The local
configuration does not install the CodSpeed app, grant organization-secret access, or modify
branch protection. Those administrative settings must be provisioned on GitHub/CodSpeed.

## Version and publishing contract

`VERSION` and `.release-please-manifest.json` record the latest release. Conventional
commits produce stable release PRs, updating VERSION, the manifest and CHANGELOG together.
Before 1.0, feature commits advance the minor version. All three Maven
artifacts share the resulting version. Bazel's default publication version is a development
snapshot; the release workflow always supplies the exact version explicitly.
The Bazel root module is unversioned because it is not published to a Bazel registry;
Maven release bumps therefore leave the dependency lockfile unchanged.

The publisher checks out `v<version>`, verifies both version files, verifies that HEAD is
that tag's commit, and verifies that the commit belongs to main before using R2 credentials.
It reruns tests on the release checkout, builds/stages the JARs, and uses the immutable uploader.
The global `maven-publication` concurrency group serializes GitHub publishers. External/manual
publishers must also be serialized with CI. Existing/partial versions are never overwritten.

For recovery, dispatch `publish.maven.yml` on main with an existing release version. It still
requires a matching tag and files, main ancestry, passing tests and an unused Maven version.
An upload that already wrote part of a version needs inspection/repair before retrying.
Release Please may have created a GitHub release before a Maven upload failure; the workflow
status and preserved staging artifacts are the evidence of publication success.

See [Publishing](PUBLISHING.md) for local staging and the uploader's integrity checks.

## Codecov reporting

The Bazel jobs upload production JVM LCOV coverage and JUnit test results to Codecov,
using the same pinned action and token/OIDC authentication as TruffleData. Reports use
`jvm` and `native-jit` flags. JVM test results are preserved before the coverage invocation
changes Bazel's test-log symlinks; failure details are retained and uploaded even when tests
fail. LCOV source paths are validated against the checkout before upload.

Uploads run on main, manual runs, and same-repository PRs; fork PRs retain reports as
GitHub artifacts without uploading to Codecov. Upload errors fail CI. The existing local
coverage threshold remains enforced independently. Enable this repository in Codecov and
allow its GitHub OIDC identity, or provide `CODECOV_TOKEN` through repository/organization
secrets. Reports are also retained under `build/reports/` in the existing CI artifacts.
