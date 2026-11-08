# Compile-time validation and constant folding

TruffleCEL spends compile time to remove evaluation work and to report problems early.
Every program is analyzed the same way when it is compiled or prepared: literal validators
run, constant regular expressions are compiled into their call nodes, and pure constant
subexpressions are evaluated once. Only the reporting policy varies between environments.
Compilation still takes microseconds, and `Environment.prepare` lets an application do all
of this while building a native image.

## Policies

| `Environment.Builder` option | Default | Effect |
| --- | --- | --- |
| `validation(Validation.DEFERRED)` | yes | CEL evaluation semantics are unchanged. A constant subexpression that is certain to fail becomes a prebuilt error node that raises the same `CelException.Code` and message, at the same budget cost, when it is evaluated. |
| `validation(Validation.STRICT)` | no | `compile`, `parse`, `prepare` and `CanonicalAst.check` reject every failure that occurs on every evaluation path, together with every validator finding. All findings are reported in one `CelException`. |
| `homogeneousLiterals(true)` | no | Rejects list literals whose elements, and map literals whose keys or values, have different static types. Entries of type `dyn` are exempt. Reported under either policy. |
| `maxComprehensionNesting(depth)` | unlimited | Rejects comprehension macros (`all`, `exists`, `exists_one`, `map`, `filter`, `transform*`, `sortBy`, `cel.bind`) and canonical comprehensions nested deeper than `depth`. Reported under either policy. |

## Diagnostics

Every compile-time `CelException` carries a `CelException.Code`, a char offset, and a
one-based line and column resolved against the source (`line()`, `column()`, `-1` when the
position is unknown, for example for canonical AST input). `issues()` lists every finding in
source order; `code()` and `offset()` describe the first one. Strict validation reports all
of its findings together, for example:

```
division by zero at offset 7 (line 2, column 4); division by zero at offset 14 (line 2, column 11)
```

Checker recovery collects independent findings in source order, using `dyn` after a failed
subexpression so sibling errors remain visible without cascades. Findings are capped at
`CelException.MAX_ISSUES` (100); resource limits still fail immediately. Single-issue message
formatting remains unchanged. `error.format(source)` adds bounded source excerpts and caret
positions without retaining the source in the exception. Evaluation failures also carry
expression IDs and source locations when available; see [integration](INTEGRATION.md).

## What is folded

A subtree folds when it is closed (references no identifier, including comprehension variables
bound outside it) and pure. Pure subtrees are literals, aggregate literals, selection and indexing
of constant maps and lists, conditionals, standard and extension functions, macros and
comprehensions over constant ranges, optional operations, and conversions. These never fold:

- identifiers, including environment constants whose value is an `Unknown`;
- host functions registered with `Environment.Builder.function` (their arguments still fold,
  and constant arguments that no declared signature accepts are a certain `NO_OVERLOAD`);
- message construction, protobuf extension selection and prepared protobuf field reads;
- `cel.block` and `cel.index`, because slots are evaluated lazily.

Folding evaluates the subtree with a counting budget. The folded node charges exactly the budget
the original subtree consumed, so a program cannot bypass a resource limit it would otherwise
hit, and a budget that the original would exhaust still fails with `RESOURCE_EXHAUSTED`.
Plan-time work is bounded by one evaluation budget per compilation in total; a subtree that
exhausts the budget is left to evaluation. Results whose size exceeds a fixed weight (4,096
units; strings and bytes count one unit per 16 chars or bytes) are not embedded in the AST.

Folded values are immutable CEL values that are safe to share between evaluations, threads
and a native image heap. Prepared templates carry them; every instantiation reuses them.

Folding is invisible: `CanonicalAst.parse` and `CanonicalAst.check` export the source tree,
and source-backed (instrumented) programs keep the complete tree so tooling observes every
literal and operator event. Uninstrumented targets replace folded subtrees with their
prebuilt results before guest compilation.

## Regular expressions

`matches` with a constant pattern compiles the pattern once at plan time and stores it in the
call node, under either policy and for instrumented programs as well. An invalid constant
pattern is a strict-validation finding; with the deferred policy the call still evaluates its
operand (so operand failures and unknowns keep their precedence) and then raises
`INVALID_ARGUMENT`. Patterns that are only known at evaluation use a bounded cache (256
entries; keys longer than 4,096 chars bypass it). The engine (RE2J) is referenced only from the
`Regex` seam, so it can be replaced locally.

## Strict validation

A folded failure is reported when every ancestor evaluates it unconditionally and no sibling
could yield an unknown that would win over the error:

| Parent | Positions reported |
| --- | --- |
| `&&`, `\|\|`, `@not_strictly_false` | none |
| `?:` | the condition |
| `or`, `orValue` | the receiver |
| other calls, including host functions | an argument whose other arguments are all constant (an unknown sibling wins over an error) |
| `has` | all |
| `cel.bind` | the body; initializer is lazy |
| other macros | the range; bodies may never run |
| canonical comprehensions | range, accumulator initializer and result |
| `cel.block` | the result; slots are lazy |
| selection, indexing, list, map and message literals | all |

Literal validators are positional: an invalid constant regular expression, `timestamp('...')`
or `duration('...')` is reported wherever it appears, including under `&&`.

After folding, strict checked compilation rechecks the tree with the folded values' precise
types, so a mismatch hidden by `dyn` (for example `xs[dyn('a')]` on a list) is a `TYPE`
finding, as it is for cel-java's optimizer.

Against the negative matrix in `NegativeCases`, strict validation rejects at compile time all
41 cases that cel-java's constant-folding optimizer rejects, plus six more (an invalid
constant pattern with a variable receiver, an invalid timestamp literal, constant duplicate
and invalid map keys, and the range cap). A failing
`cel.block` slot that the result always reads is not reported, because slot evaluation is
lazy.

## Application validators

`Environment.Builder.validator(Validator)` registers a thread-safe compile-time callback.
Its immutable `Validator.Context` provides source text, the expanded source AST before constant
substitution/folding, the result type, and checked types keyed by expression ID. A source-only
qualifier removed during resolution has no checked type; `context.type(node)` returns `dyn`
for it. Host constants are represented by their source identifier, so application objects do
not enter the public syntax tree.

Return a list of `CelException.Issue` findings, or an empty list to accept. Findings are enforced
under both validation policies, including unchecked parsing; they are combined with built-in
validation findings, sorted by source offset and capped at 100. Resource failures remain
immediate. Callbacks run once per preparation and may run concurrently; like host evaluation
callbacks, their own execution time is controlled by the embedder. `toBuilder()` preserves
registered validators and macro selections. Custom validators also run on imported ASTs,
whose source text may be empty.
