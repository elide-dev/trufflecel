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

## Negative and error-category matrix

Corpus `eval_error` cases only establish that an error occurred. `NegativeCases` in the test
support library adds 431 independently specified cases that record the rejection phase
(parse, check or evaluate) and, where the contract defines one, the stable
`CelException.Code`; no assertion depends on diagnostic wording. Check-phase cases also record
the outcome when the checker is bypassed, because dynamic integrations and canonical
`ParsedExpr` import plan without type checking.

| Area | Cases | What they cover |
| --- | ---: | --- |
| Malformed input | 71 | Unterminated literals and aggregates, invalid escapes and code points, literal overflow, reserved words, malformed macros and `has()` arguments, lone and trailing commas |
| Declarations and types | 69 | Operator and function overload mismatches, undeclared references, unregistered types, host function arity and argument types, disabled macros, container resolution, binding/declaration mismatches |
| Unknown propagation | 46 | Unknown-over-error precedence, short-circuit absorption, aggregates, comprehensions, optionals, host functions, protobuf fields, budget-over-unknown precedence |
| Resource limits | 18 | Evaluation budgets (including non-absorption by `exists`, `||` and optional fallbacks), depth, nesting and source-length limits, deep parse trees, extension caps |
| Collections | 35 | Index and key failures, key type equivalence, invalid map keys, duplicate keys, errors inside literals |
| Arithmetic | 30 | Signed, unsigned, timestamp and duration overflow, division by zero, invalid regular expressions and time zones |
| Conversions | 32 | Numeric, string, timestamp and duration conversion failures, well-known type constructor ranges |
| Short-circuit | 38 | Error absorption by `&&`, `\|\|`, `?:`, `exists`, `all`, optional fallbacks, bindings and blocks |
| Comprehensions | 18 | Errors inside transforms, filters and nested loops; macro shape errors |
| Optionals | 18 | Absent `value()`, non-optional entries, type mismatches, lazy fallbacks |
| Protobuf | 56 | Unknown fields, ill-typed and out-of-range construction, null assignment, wrappers, `Any`, `Value`, `Struct`, maps, enums, proto2 defaults, binding type mismatches |

Every case executes through six paths in `//src/test/java/dev/trufflecel:negativeMatrix`:
the direct checked frontend, strict compile-time validation, the direct unchecked frontend,
canonical `ParsedExpr` import, canonical `CheckedExpr` import after a wire round trip, and a
snapshot activation. The 60 protobuf cases run with both the test fixture adapter and the
shipped `ProtobufType` adapter (2,946 executions). `//benchmarks:negative_compiled` evaluates every evaluation-phase case
(404 executions) from forced last-tier guest-compiled code and requires the same phase and
category as the interpreter. `//benchmarks/representative:negative_differential` runs the 368
cases that cel-java 0.15.0 can express against its default and Pratt parsers, each with and
without the constant-folding and subexpression optimizers (1,472 executions), asserting the
recorded upstream phase and `CelErrorCode`.

Optimizer-time failures are encoded explicitly: 41 cases are marked as folded, meaning
cel-java's optimized configurations reject them with `CelOptimizationException` before
evaluation while its plain configurations fail at evaluation. TruffleCEL's default policy
raises the same failures at evaluation from prebuilt error nodes; every case also records its
outcome under strict validation, which rejects all 41 folded cases at compile time with the
evaluation category, plus seven more (an invalid constant pattern with a variable receiver, an
invalid timestamp literal, a `cel.bind` initializer failure, constant duplicate and invalid map
keys, and the range cap). The table requires a strict outcome for every folded case. See
[compile-time validation](VALIDATION.md).

The table records 22 divergences, each with its reason; none is hidden by a weaker assertion:

- cel-java reports timestamp and duration range failures, enum range failures,
  `optional.value()` on an absent optional, dynamic field selection on messages and iteration
  over non-aggregates as `INTERNAL_ERROR`, and string parsing failures as `BAD_FORMAT`.
- cel-java does not validate `Duration` and `Timestamp` ranges in message literals, rejects
  double map key literals only at evaluation, accepts duplicate message literal fields,
  evaluates `cel.bind` initializers lazily, collapses unlimited repeated unary operators, and its
  duration arithmetic overflows below the protobuf range. Its Pratt parser rejects the lone
  comma that the grammar (`"[" [ExprList] [","] "]"`) and its default parser accept.

The differential harness registers the well-known type descriptors with cel-java, so wrapper
literal construction compares like for like: unknown wrapper fields and ill-typed wrapper
values are check failures in both engines, and wrapper overflows are `NUMERIC_OVERFLOW`.

Sixty-three cases are TruffleCEL-only: explicit unknown bindings, configured resource limits,
inferred declarations, `cel.block` (produced by cel-java's optimizer rather than parsed) and
unregistered extension lookups. Unchecked construction with an ill-typed field is recorded
by phase only: the fixture adapter reports `NO_OVERLOAD` and the shipped adapter
`INVALID_ARGUMENT`, and the contract does not define that category. The matrix does not
cover cel-java's unknown-tracking API, custom macros, or every extension function.

The matrix found and fixed: an uncaught regular-expression syntax exception escaping
`matches`, a stack overflow in `CanonicalAst.parse` for 5,000-term operator chains (the depth
limit now applies before cloning), parser nesting limits reported as `SYNTAX` rather than
`RESOURCE_EXHAUSTED`, overflow categories lost in protobuf construction, `null` accepted at
check time for non-nullable scalar fields, host functions invoked with undeclared arities or
argument types in unchecked programs, and rejection of the grammar's lone trailing comma.

The compiled runner originally showed that 101 of the 404 evaluation-phase executions
deoptimized and invalidated on every error. The cause was the partial evaluator's slow-path
exception list: a handler for `IllegalArgumentException`, `ArithmeticException` or a few related
types is compiled as a deoptimization without a speculation, so it recurs on every throw, and
`CelException` extended `IllegalArgumentException`; the exact-arithmetic intrinsics guard
overflow with the same kind of deoptimization. `CelException` now extends `RuntimeException`
directly and compiled integer arithmetic uses explicit overflow checks, so every error path is
ordinary compiled control flow. An error raised inside a `@TruffleBoundary` method still
invalidates the first compilation once, when the boundary's no-exception speculation fails. The
runner asserts that each case settles within three compilations (one initial compilation plus
one per distinct boundary that raises the error) and that 1,000 further errors neither
deoptimize nor invalidate the compiled code: 248 executions settle after one compilation, 155
after two and one after three (a folded constant failure throws its prebuilt exception as
ordinary control flow, so it settles in the first compilation). `//benchmarks:compiled` additionally checks that a program which
has already raised errors keeps the same happy-path allocation budget.

| Metric | Measured after the negative matrix |
| --- | ---: |
| Line hits | 4,497 / 4,501 (99.91%) |
| Branch hits | 4,590 / 4,595 (99.89%) |
| CI minimum, each metric | 99.8% |

## Compile-time validation and constant folding

Every compilation now validates literals, precompiles constant regular expressions and folds
closed pure subtrees at the original budget cost ([validation](VALIDATION.md)). The folds are
exercised by every existing suite, because they are always on: the complete corpus runs an
additional strict-validation variant (`strictConforms`) proving that strict mode never rejects a
case that evaluates successfully, the negative matrix records each case's strict outcome, and the
compiled-negative gate evaluates every folded error node from last-tier guest code.
`ValidationTest` compares, budget by budget, the outcomes of folded and source-backed (unfolded)
programs for the same expressions, so a folded node cannot bypass a limit that the original
would hit. It also covers the every-path and unknown-precedence rules, positional literal
validators, multi-issue line/column diagnostics, the homogeneous-literal and nesting validators,
the dynamic-pattern cache bound, the regex-free duration and time-zone parsers, folding bounds,
and host-function overload checks. The compiled regex case in `benchmarks/CompiledTest.java`
dropped from 3,904 to 96 bytes per evaluation (its ceiling is 192).

| Metric | Measured after compile-time validation |
| --- | ---: |
| Line hits | 4,828 / 4,832 (99.92%) |
| Branch hits | 4,912 / 4,917 (99.90%) |
| CI minimum, each metric | 99.8% |

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
- `ProtobufType.prepared`: map fields whose value type is a well-known message have no
  prepared accessor; the corpus and matrix read such maps through the generic path only.

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
