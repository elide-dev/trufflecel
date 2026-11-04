# Upstream CEL conformance data

Source: https://github.com/cel-expr/cel-spec
Revision: `21ec6f4e52e8d694687ff9ed7408d3eccbb955d7` (also stored in `REVISION`).
License: Apache-2.0, retained in `LICENSE`.

`corpus.json` is a lossless protobuf-JSON import of upstream `tests/simple/testdata/*.textproto`
using protobuf's official text parser, with original field names and numeric enum values.
`schemas.json` contains descriptor-derived data for test fixture objects and extensions.
Neither file is on the runtime classpath. Regenerate from an exact checkout with:

```sh
python tools/import_conformance.py /path/to/cel-spec
```

Regeneration requires `protoc` and Python `protobuf`; ordinary Bazel builds/tests do not.
The importer checks the source revision and fails on unknown textproto fields/types.
`ConformanceTest` executes all 2,771 cases in all 32 imported files, with no suite
allowlist, per-case skips, or expected-failure mechanism. `unknowns` is an empty upstream
file; it contributes no cases. This includes bindings, block, encoders, sets, proto2
extensions, and plumbing in addition to the previously enabled core and extension suites.
The plumbing cases execute through the JVM harness; they do not imply a CEL RPC server.

An upstream case encoded as `eval_error` may be rejected earlier by the checker; in that
situation the harness also parses and executes unchecked to verify the required runtime
error. Syntax failures are never accepted this way. The harness checks that an evaluation
error occurs, not that its diagnostic text matches the upstream reference text.
