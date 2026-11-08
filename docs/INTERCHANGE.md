# Canonical CEL ASTs and conformance RPC

The optional `trufflecel-protobuf` artifact includes `CanonicalAst`, an independent bridge
for the pinned `cel.expr` protobuf schemas. It has no cel-java dependency. Schema descriptors
are generated with Bazel's pinned protoc and embedded at build time; no runtime schema
resources, reflective class discovery or Java serialization are used.

```java
var env = Cel.newBuilder().variable("x", CelType.INT).build();
com.google.protobuf.Message parsed = CanonicalAst.parse(env, "x + 1");
com.google.protobuf.Message checked = CanonicalAst.check(env, parsed);
byte[] wire = checked.toByteArray();
var imported = CanonicalAst.decode("cel.expr.CheckedExpr", new Bytes(wire));
var program = CanonicalAst.program(env, imported);
assert program.evaluate(Map.of("x", 41L)).equals(42L);
```

Messages implement protobuf's `Message` API with the canonical descriptors. Generated
`dev.cel.expr` classes can exchange their serialized bytes with these messages. `prototype`
and `message` also expose the pinned schema for integrations that do not generate classes.
`type` converts between `CelType` and canonical `Type` messages.

Parsing expands iteration macros into canonical comprehensions, including two-variable loops and
optional local bindings. Import handles arbitrary accumulator comprehensions, optional
aggregate entries, namespaces, object constructors and block extensions. Parsed input uses
dynamic evaluation; checked input is checked again against the receiving environment.
Imported annotations cannot bypass declarations, type checking or execution budgets.
Expression ids must be positive and unique. Import bounds node count, depth and serialized
size (8 MiB); source parsing retains the environment's source/depth limits.
Opaque host constants that have no canonical CEL literal representation cannot be exported.

All 2,771 pinned cases execute through both the ordinary frontend and canonical interchange,
with both the independent fixture adapter and the production protobuf adapter.
Comparison-only tests exchange checked ASTs with cel-java in both directions. cel-java stays
outside production dependency resolution.

## Standalone service

```sh
bazel run --config=local //tools/conformance:server -- 50051
```

The service listens on `127.0.0.1` and implements the pinned
`cel.expr.conformance.ConformanceService/Parse`, `/Check` and `/Eval` unary gRPC methods.
Parse returns canonical ASTs and source locations. Check consumes declarations and containers;
Eval accepts parsed or checked expressions, values, errors and unknown bindings. Compile issues
and evaluation failures use the canonical response fields. Requests are bounded to 8 MiB and
evaluations use the normal CEL budgets. The server has a bounded host executor queue.

To register application or conformance fixture messages, pass an explicit protobuf
`FileDescriptorSet` containing its imports as the second argument:

```sh
bazel run --config=local //tools/conformance:server -- 50051 /path/to/descriptors.pb
```

Use protoc's `--descriptor_set_out` with `--include_imports` to create that file. The server
registers its message and extension descriptors explicitly; it does not scan application
classes or fetch descriptors over the network. The service and its gRPC transport are host
tools and are not dependencies of any publication JAR.

```sh
bazel test --config=local //tools/conformance:test //benchmarks/comparison:ast_interchange
```

The transport test exercises a real loopback channel, including concurrent reuse, errors,
unknown propagation and the Parse/Check/Eval sequence. Native guest compilation remains a
separate Linux gate; passing the JVM interoperability tests does not establish Native Image
compatibility.

Lazy `cel.bind` uses the explicit canonical intrinsic
`cel.@lazyBind(identifier, initializer, body)`. Import validates its arity and binding
identifier and preserves lexical scope, including enclosing lazy block slots. This
is a TruffleCEL extension: consumers must support it or reject it; cel-java is not
claimed to execute this intrinsic. Ordinary imported accumulator comprehensions
retain eager accumulator initialization. Removing or modifying `SourceInfo`,
including `macro_calls`, never changes evaluation semantics.

## Owned ASTs, macros and source reconstruction

The core artifact exposes `Ast.parse(environment, source)`, immutable `Ast.Node`
values, preorder `nodes()` / ID `find()` navigation, and a thread-confined
`editor()` with `replace`, bottom-up `transform`, and `freeze`. Edits preserve
untouched expression IDs, assign IDs to new nodes, discard stale macro source
metadata, and recheck structure and types when `prepare` or `compile` is called.
No mutable Truffle nodes or opaque host constant values are exposed.

`Macro.global`, `receiver`, `globalVarargs`, and `receiverVarargs` register through
`Environment.Builder.macro`. Argument counts exclude receivers; variadic counts
are minimum counts and exact signatures take precedence. Qualified global macro
names are supported. Expanders receive immutable syntax and return `Ast.Node`
trees. `Macro.Context.freshName()` supplies identifiers that cannot collide with
source identifiers, for hygienic comprehensions. Expansions share the parser's
node limit and pass the same depth, checker, policy and runtime planning gates.
Callbacks execute at compile time and are trusted embedder code; CEL budgets do
not interrupt arbitrary callback code. Generated calls are already-expanded
syntax, so they do not recursively invoke custom expanders.

`standardMacros(Set<String>)` chooses built-in expansions independently of custom
registrations; `macros(false)` disables both. Custom expansion source and built-in
macro calls are exported in canonical `SourceInfo.macro_calls`.

`Ast.unparse()` and `CanonicalAst.unparse(environment, message)` reconstruct CEL
source, including optional entries, quoted fields and original macro calls.
Imported comprehensions without original macro metadata have no general CEL
surface spelling and are rejected explicitly. Nested id-only macro references
are resolved with size/depth limits and cycle rejection. Source metadata can
change reconstructed source text; it never changes evaluation of the supplied
canonical expression. `CanonicalAst.ast` imports editable trees and `parsed`
exports them without a dependency from the core artifact on protobuf.

`CanonicalAst.toV1Alpha1` and `toCelExpr` bridge `cel.expr` and
`google.api.expr.v1alpha1` AST message identities while preserving wire fields,
unknown fields and source metadata. `prototype`, `decode`, `check` and `program`
accept the legacy AST family; checking returns the input schema family. The
legacy descriptors are derived in memory from the pinned schema, with no runtime
resource loading. The compatible field contract is defined by the upstream
[syntax](https://github.com/googleapis/googleapis/blob/master/google/api/expr/v1alpha1/syntax.proto)
and [checked-expression](https://github.com/googleapis/googleapis/blob/master/google/api/expr/v1alpha1/checked.proto)
schemas. Schema conversion does not give other engines support for TruffleCEL
intrinsics such as `cel.@lazyBind`.
