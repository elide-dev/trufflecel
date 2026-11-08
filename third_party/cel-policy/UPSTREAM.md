# CEL Policy conformance corpus

Source: https://github.com/cel-expr/cel-policy
Revision: `8e4cf0378e3f0d3406b537afbcc6f59562652ad6`

`testdata/` is the complete unmodified upstream `conformance/testdata/` tree.
Its Apache-2.0 license and per-file copyright notices are preserved. The test
harness executes every policy, including expected compile failures. Diagnostics
are checked by failure category because the independent parser does not promise
ANTLR-specific diagnostic wording. Fixture host functions are supplied explicitly.

The Kubernetes-shaped fixture is translated by a test-only adapter matching the
upstream [K8sTestTagHandler](https://github.com/cel-expr/cel-go/blob/master/policy/test_tag_handler_k8s.go).
It executes its supplied CEL assertions without claiming Kubernetes resource matching.
The fixture runner enables optional types, as the upstream policy runner does.
