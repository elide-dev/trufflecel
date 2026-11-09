# Third-party components

First-party TruffleCEL is `Apache-2.0 OR MIT`. That choice does not replace the
licenses of third-party code, schemas, fonts, data or tools.

| Component | Inventory and license scope |
| --- | --- |
| CEL specification corpus and schemas | [`third_party/cel-spec`](third_party/cel-spec/README.md) pins the upstream revision and Apache-2.0 license. Conformance fixtures are test-only; canonical CEL descriptors derived from Google schemas are embedded in the protobuf module. See [NOTICE](NOTICE). |
| CEL Policy corpus | [`third_party/cel-policy`](third_party/cel-policy/UPSTREAM.md) retains its upstream revision and Apache-2.0 license. These fixtures are not shipped in runtime JARs. |
| Runtime Maven libraries | `MODULE.bazel` and `maven_install.json` pin dependencies; publication POMs declare consumer dependencies. The thin JARs do not shade these libraries. Dependencies retain their own licenses. |
| cel-java comparison engine | The isolated `cel_java` Maven installation and `benchmarks/` pin comparison-only dependencies. Production code must not depend on cel-java. |
| GraalVM, TruffleData and build tools | Versioned archives, checksums, overrides and lockfiles in the Bazel configuration define the inputs. Their distribution licenses remain separate from TruffleCEL's license. |
| Chart fonts | [`tools/bench/charts/fonts`](tools/bench/charts/fonts) contains unmodified Fontsource 5.3.0 Latin variable fonts: Inter, Instrument Sans and JetBrains Mono. Each has its own SIL Open Font License and copyright notice. They are documentation tooling assets, not runtime dependencies. |

This is a scope guide, not a complete transitive SBOM. Review the resolved
dependency graph and upstream license text for the exact artifact being shipped.
Keep provenance and original notices when updating vendored materials. Do not
apply a first-party SPDX header to upstream source, generated schema descriptors,
archived benchmark evidence or third-party assets.
