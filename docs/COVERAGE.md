# Coverage audit

The [Codecov report](https://app.codecov.io/github/elide-dev/trufflecel) and Bazel's LCOV
report guide tests for missed lines and partially covered conditions. All 34 owned
production sources remain measured, including the optional protobuf and JSON adapters.
No coverage exclusions were added.

The November 4, 2026 audit improved the JVM suite:

| Metric | Before | After |
| --- | ---: | ---: |
| Line hits | 3,040 / 3,082 (98.64%) | 3,067 / 3,071 (99.87%) |
| Branch hits | 3,233 / 3,475 (93.04%) | 3,448 / 3,452 (99.88%) |
| CI minimum, each metric | 98% lines / 90% branches | 99.8% |

The denominator changes come from removing redundant checks, an unused implicit utility
constructor, and an unreachable enum default. The checker now uses an exhaustive switch,
so adding an AST kind requires the compiler to verify its handling. Other simplifications
remove checks whose types or values were already established earlier in the same path.

Tests cover numeric fallback dispatch and overflow, strict and unchecked argument
validation, lexical edges, optional and unknown propagation, aggregate resource limits,
interop numeric widths and missing members, debugger scopes, network classification,
protobuf null/enum mappings, and well-known type boundaries. Native compilation and
allocation checks continue to run separately from instrumented coverage.

Codecov's displayed coverage treats partially covered lines differently from the line-hit
ratio above. Compare its own before/after reports rather than comparing its percentage
with Bazel's line-hit percentage. The baseline is
[`591e706`](https://app.codecov.io/github/elide-dev/trufflecel/commit/591e7061fedfeeaa89504fe9f0349584f27729a1).

## Complete conformance corpus and TruffleData 0.4.0

The follow-up implementation enables every imported suite: 2,771 cases across 32 corpus
files, with no suite allowlist. Regression tests also cover invalid extension signatures,
Base64 padding and line breaks, set comparison budgets, lexical binding scopes, lazy block
caching (including errors), concurrent evaluations, instrumentation, and native protobuf
extension construction, defaults, presence, wire round-trips, and registration validation.

| Metric | Measured after extension support |
| --- | ---: |
| Line hits | 3,320 / 3,324 (99.88%) |
| Branch hits | 3,665 / 3,669 (99.89%) |
| CI minimum, each metric | 99.8% |

At that checkpoint, all new production lines and branches were covered. The same four defensive lines and four
invariant branch outcomes listed below remain. JVM guest-compilation checks add bindings,
blocks, and sets, with allocation ceilings of 64, 768, and 1,536 bytes per evaluation for the
representative expressions in `benchmarks/CompiledTest.java`. Existing ceilings are unchanged.
Native JIT checks exercise the same operations.

## Canonical interchange and remaining capability gaps

The follow-up adds canonical AST import/export, arbitrary accumulator comprehensions,
JSON/YAML and URL-safe Base64 encoders, Editions 2023/2024 and MessageSet support, and compiled
block slots. Every pinned case now runs twice: through the direct frontend and through
canonical AST interchange (5,542 corpus assertions). Separate tests cover untrusted AST
validation, typed encoders, malformed wire data, limits, concurrency, cross-engine AST wire
compatibility, real gRPC transport and publication consumers. No source exclusions were added.

| Metric | Measured after capability completion |
| --- | ---: |
| Line hits | 4,224 / 4,231 (99.83%) |
| Branch hits | 4,328 / 4,333 (99.88%) |
| CI minimum, each metric | 99.8% |

The gRPC host tool is tested separately from library coverage. JVM compilation/allocation
checks include structured parsing; the Linux native JIT/blocklist gate includes those paths
as well. The native gate cannot execute on a macOS ARM host and still requires Linux CI.

## Serializer failure coverage

Package-level serializer tests cover JSON destination failures during writing, flushing and
closing, preserve the original I/O exception, and verify that output budgets are charged
before characters reach the destination. Canonical AST tests also verify rejection of
unsupported compiler macro nodes independently of source parsing. The serializer entry points
remain package-private; no public API, coverage exclusions or thresholds change.

| Metric | Measured after serializer failure tests |
| --- | ---: |
| Line hits | 4,229 / 4,233 (99.91%) |
| Branch hits | 4,329 / 4,333 (99.91%) |
| Local patch coverage against `main`, counting partial lines as uncovered | 933 / 933 (100%) |

## Remaining paths

Four missed lines remain in defensive paths:

- `DataValues.from`: the checked interop exception handler for native arrays/nulls.
  TruffleData validates their representations before this conversion; arbitrary objects
  are rejected by its constructors before reaching CEL.
- `ProtobufType.Value.message`: the checked protobuf parse exception handler during host
  export. The wire bytes come from the validated native plan's encoder.

Four uncovered branch outcomes follow existing invariants:

- `Environment.prepare`: built-in type names have already been removed from the free
  variable set before its additional built-in-name check.
- `ExprNode.lowerUninstrumented`: `size` is not in the extension dispatch set, so its
  extension exclusion cannot reject a currently supported extension.
- `ExprNode.call`: scalar operators and string predicates are disjoint from the extension
  dispatch set; their extension exclusion remains defensive.
- `Parser.string`: `codePointAt` and the checked escape decoder already bound code points
  to the Unicode range. The surrogate rejection is exercised; the additional invalid-range
  check cannot fail for those inputs.

These paths remain visible in coverage. The suite does not use reflection, mocked static
methods, or corrupted immutable objects to force them. Revisit this list if dependency
contracts, supported functions, or parser inputs change.

## Reproduction

```sh
bazel coverage //src/test/...
bazel run //tools:coverage --config=local
```

CI validates and uploads the resulting LCOV report and preserves JUnit results before the
coverage invocation replaces Bazel's test-log links. See [CI reporting](CI.md#codecov-reporting).
