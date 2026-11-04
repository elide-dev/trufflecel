# Working on TruffleCEL

TruffleCEL is an independent CEL parser, type checker, and Truffle evaluator.
Production code must not depend on cel-java; it is a benchmark comparison only.
Read CONTRIBUTING.md, SECURITY.md, and the relevant docs before changing behavior.

## Layout and build

- `src/main/java/dev/trufflecel`: frontend, runtime, instrumentation and adapters.
- `src/test/java/dev/trufflecel`: unit, integration and upstream conformance tests.
- `third_party/cel-spec`: pinned upstream corpus; preserve its license and provenance.
- `benchmarks`: CodSpeed, upstream comparison, allocation and native guest-JIT gates.
- `bazel/gazelle`: target generation; update generated targets with Gazelle.
- `tools/release`: Maven packaging, validation and immutable publication.

Use Bazel, with `.bazelversion` and the GraalVM toolchains in MODULE.bazel.
Never read, print, commit or copy credentials from `user.bazelrc` or local credentials.
Default builds use BuildBuddy RBE; use `--config=local` without credentials and for
host tools on macOS. Do not replace pinned GraalVM with the system JDK.

```sh
bazel run --config=local //bazel/gazelle:update
bazel run --config=local //tools:format
bazel test --config=local //...
bazel coverage --config=local //src/test/...
bazel run --config=local //tools:coverage
```

CI additionally runs `bazel test //benchmarks:jit_smoke` on Linux. Changes affecting
compilation, allocation or native behavior need that gate; JVM success alone does
not establish Native Image compatibility. For workflow changes run `actionlint` and
`python3 -m unittest discover -s .github/scripts -p 'test_*.py'`.

## Change requirements

Preserve CEL error semantics, bounded execution, concurrent program reuse, and
Truffle instrumentation tags. Avoid reflection, runtime resource loading and host
object retention on native paths. Add meaningful regression tests for behavior
changes; do not exclude production code to satisfy the 99.8% coverage floors.
Use the complete conformance corpus without silently skipping failures.

Keep Maven pins, lockfiles and publication POMs consistent. Follow
`docs/MAINTENANCE.md` for dependency updates and known automation limits.
Never rewrite published releases or historical benchmark measurements.
Keep changes focused, inspect the diff, and report actual validation and limitations.
Use conventional commits and style the names TruffleCEL and TruffleData.
