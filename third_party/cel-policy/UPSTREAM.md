# CEL Policy conformance corpus

Source: https://github.com/cel-expr/cel-policy
Revision: `8e4cf0378e3f0d3406b537afbcc6f59562652ad6`

`testdata/` is the complete unmodified upstream `conformance/testdata/` tree.
Its Apache-2.0 license and per-file copyright notices are preserved. The test
harness executes every policy, including expected compile failures. Diagnostics
are checked by failure category because the independent parser does not promise
ANTLR-specific diagnostic wording. Fixture host functions are supplied explicitly.
