# Bazel, Gazelle, and BuildBuddy

## Execution profiles

The default is `--config=remote`: Linux amd64 actions on BuildBuddy's hosted Firecracker
executors, using the same digest-pinned Ubuntu image as Komodo. Actions have networking
disabled. JDKs, Go SDKs, and Maven artifacts are declared build inputs; Bazel's ZIP helper
uses the C++ tools in the pinned image. Local fallback is disabled, so a broken remote
setup cannot silently pass by executing on the developer machine.

Configure an organization credential in **untracked** `user.bazelrc` (mode `0600`) or
`~/.bazelrc`:

```text
common --remote_header=x-buildbuddy-api-key=YOUR_KEY
```

Never commit credentials. The repository's `.gitignore` excludes `user.bazelrc`.
`--config=local` explicitly disables remote execution, remote cache, remote downloader,
and build-event upload, and restores host execution/runfiles. It is also the right
profile for `bazel run` workspace tools on macOS: RBE produces Linux executables.

Available configurations:

* `remote`: authenticated RBE plus cache and build-event service (default).
* `buildbuddy`: cache/BES settings underlying `remote`.
* `local`: entirely host-side execution, with no uploads to the shared action cache.
* `ci` / `workflows`: CI metadata and minimal test downloads.
* `untrusted`: a separate action-cache namespace for BuildBuddy pull-request workflows.

Outputs are separated by platform. BLAKE3 digests, compressed/chunked cache transfers,
repository download caching with direct fallback, action-path stripping, lost-input
rewinding, and detailed timing profiles follow Komodo's configuration. Test logs and
XML remain available with minimal downloads. Cache namespace: `elide-dev/trufflecel/v1`.

## Commands

```sh
bazel build //:trufflecel //:trufflecel-protobuf //:trufflecel-json
bazel test //...
bazel coverage //src/test/...
bazel run //tools:coverage --config=local
```

The coverage checker validates the LCOV source inventory and enforces 99.8% lines / 99.8%
branches. Run it after coverage, before cleaning or rebuilding with coverage disabled.
Its report path defaults to `bazel-out/_coverage/_coverage_report.dat`.

```sh
bazel run //:gazelle --config=local
bazel run //bazel/gazelle:check --config=local
bazel run //tools:format --config=local
bazel run //tools:format --config=local -- --check
```

The repository-owned Gazelle extension follows Komodo's approach without importing its
Kotlin/compiler policy. It derives source membership and dependencies from Java imports,
generates a helper library and individual JUnit test targets, and deletes stale targets.
It preserves hand-maintained processor, data, visibility, and JVM configuration. Tool
BUILD files and platforms are intentionally hand-maintained. Add an import-prefix mapping
to `bazel/gazelle/jvm.go` when introducing a new external Java library. Its Go tests also
run under `bazel test //...`; language behavior tests all run on the JVM.

## CI

`.github/workflows/ci.yml` checks Gazelle, Java formatting, the whole test suite, coverage,
and all three JARs. Configure the repository's `BUILDBUDDY_API_KEY` secret for RBE. Fork pull
requests receive no secret and use host Linux execution. Credentials are written to a
mode-0600 untracked RC file and removed even if a step fails. Reports and the JARs are
uploaded as GitHub Actions artifacts. Reusable CodSpeed simulation checks run alongside
Bazel checks; successful main builds run Release Please and publish newly created releases
as described in [Publishing](PUBLISHING.md). See [CI setup](CI.md) for activation and secrets.

`buildbuddy.yaml` provides the equivalent native BuildBuddy workflows. Enabling these
requires installing/configuring the BuildBuddy GitHub app for the repository; adding
this file alone does not install the app. Pull-request workflows use the isolated cache
namespace, and main builds use the trusted namespace. No GitHub settings or secrets are
changed by this repository's setup.

Dependencies are locked in `MODULE.bazel.lock` and `maven_install.json`. After deliberately
changing Maven coordinates, regenerate the Maven lock with:

```sh
REPIN=1 bazel run @maven//:pin --config=local
```

The Java toolchains target Java 25 to match TruffleData. Optional protobuf and JSON Java
libraries are generated separately from core; their dependencies cannot leak into the core
closure. The Maven repository list includes the Elide release repository and versioned
GraalVM fork repository. Repin with `REPIN=1 bazel run @maven//:pin --config=local`.
