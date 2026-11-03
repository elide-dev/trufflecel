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
`ConformanceTest.CORE` explicitly selects core suites; optional extensions remain in the
snapshot so adding support does not silently change the upstream baseline. Every selected
case is a JUnit parameter; there is no case skip or expected-failure mechanism.
