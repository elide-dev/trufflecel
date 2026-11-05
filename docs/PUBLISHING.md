# Maven publishing

The release pipeline follows TruffleData's Bazel publications and Komodo's R2/rclone
upload convention. It produces three thin Maven artifacts under `dev.elide`:

| Artifact | Runtime dependencies in addition to core |
| --- | --- |
| `trufflecel-core` | Truffle API, RE2/J, TruffleData core |
| `trufflecel-protobuf` | TruffleData protobuf and protobuf-runtime, protobuf-java, TruffleData core, Truffle API |
| `trufflecel-json` | Oracle's shaded Truffle JSON |

Both optional artifacts depend on the same version of `trufflecel-core`. Java 25 is
required. Publications built from the current source tree pin TruffleData `0.6.0` and Elide GraalVM `25.5.5-elide.24`,
matching `maven_install.json`. JARs contain owned classes only; test libraries, JDBC
drivers, GraalVM and other runtime dependencies are not bundled.

## Build and stage

```sh
bazel build //tools/release:publications --//tools/release:version=0.1.0
# Full suite, publication build, repository layout and checksums; no network writes:
tools/release/publish.sh 0.1.0
```

The second command prints a unique directory under ignored `build/release-repo.*`.
It contains binary JARs, source JARs, POMs, Maven metadata and MD5/SHA-1/SHA-256/SHA-512
checksums. Both JARs are taken from each library's JavaInfo, including generated Truffle
classes in the binary. No separate source inventory is maintained. Bazel adds
`META-INF/LICENSE`, `LICENSE-MIT`, `LICENSE-APACHE`, and `NOTICE` to binary and
source JARs. POMs list both licenses and explicitly identify the choice as
`MIT OR Apache-2.0`. Publication tests verify these files against the repository
texts for all three modules. Existing published versions remain immutable.

The Bazel build setting defaults to `0.1.0-SNAPSHOT` for CI validation. The release script
requires an explicit immutable version and rejects snapshots. The example version is
only a staging example, not a statement that a release has been published.

## Publish

The publishing host needs Bash, Python 3, curl, rclone and Bazelisk. Builds and JVM tests
continue to use the hermetic Bazel toolchains and configured BuildBuddy RBE/cache.
Configure rclone credentials outside the repository, using remote `elide:` and bucket
`elide-maven`, as in TruffleData. Override with `MAVEN_BUCKET` when needed.

```sh
# From a clean, committed checkout; writes to the public repository:
tools/release/publish.sh 0.1.0 --publish
```

The script refuses an existing version, fails on listing/authentication errors, and
preserves existing metadata. It runs the full suite, stages publications, uploads immutable
payloads, verifies bucket contents and public bytes at `https://maven.elide.dev`, and only
then updates discovery metadata. It does not tag or push Git history.

Serialize publishers across machines: R2/rclone has no multi-artifact transaction or
cross-machine release lock. A failed upload can leave a partial immutable version; inspect
and repair it before retrying. Automatic overwrite/resume is deliberately unsupported.
No signing keys or Maven Central upload are configured.

## CI validation

`bazel test //...` includes release layout/POM/checksum/metadata checks and a JVM consumer
test. The consumer compiles against the actual publication JARs via `java_import`, exercises
the native row and optional adapters, and verifies Polyglot language discovery. POM dependency
versions are checked against the Maven lock. Core's JAR is checked for optional-code leakage.
Small repository-layout checks use Python from the pinned RBE image; language and consumer
tests run on the JVM.

GitHub Actions and BuildBuddy build the publications. GitHub Actions archives the JARs,
sources and POMs with the test and coverage reports. Release Please now invokes the reusable Maven publisher after successful main checks.
Pull requests cannot publish. See [CI setup](CI.md) for credentials, tag verification and
manual recovery. No external CI secrets or bucket settings are modified by this setup.

## Consume

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.elide.dev")
    maven("https://maven.elide.dev/gvm-elide/25.5.5-elide.24")
}
dependencies {
    implementation("dev.elide:trufflecel-core:<published-version>")
    implementation("dev.elide:trufflecel-protobuf:<published-version>") // optional
    implementation("dev.elide:trufflecel-json:<published-version>") // optional
}
```

The POMs declare the Elide and GraalVM fork repositories for Maven consumers. Gradle and
Bazel consumers must explicitly configure them. For Bazel, add the desired coordinates to
`maven.install` and repin. The embedder supplies the matching Truffle runtime and any JDBC
libraries/drivers it needs.

## Release Please

Release PRs maintain `VERSION`, `.release-please-manifest.json` and `CHANGELOG.md`. The
reusable publisher checks out and verifies the exact release tag before calling the uploader
with its version. All modules release together. The explicit local `--publish` command
remains available; coordinate manual publishes with CI to avoid concurrent metadata writes.
