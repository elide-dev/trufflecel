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
`ConformanceTest.SUITES` selects 20 core suites and six extension suites: `optionals`,
`macros2`, `string_ext`, `lists_ext`, `math_ext`, and `network_ext`. All 2,587 selected
cases execute as JUnit parameters; there is no case skip or expected-failure mechanism.
Other extensions remain in the snapshot. An upstream case encoded as `eval_error` may
be rejected earlier by the checker; in that situation the harness also parses and executes
unchecked to verify the required runtime error. Syntax failures are never accepted this way.
