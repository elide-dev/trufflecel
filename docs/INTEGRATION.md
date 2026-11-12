# Elide and TruffleData integration

## Elide

The direct API mirrors the existing `dev.elide.cel.Cel` facade's compile/evaluate/test
contract. Replace the frontend/runtime internals with `dev.trufflecel.Cel` and keep
the public Kotlin facade and JavaScript module stable:

```kotlin
public fun compile(expression: String): CelExpression =
  CelExpression(dev.trufflecel.Cel.compile(expression))

public fun compile(expression: String, variables: Set<String>): CelExpression =
  CelExpression(dev.trufflecel.Cel.compile(expression, variables))

public class CelExpression internal constructor(private val program: dev.trufflecel.Program) {
  public val source: String get() = program.source()
  public val variables: Set<String> get() = program.variables()
  public fun evaluate(variables: Map<String, Any?> = emptyMap()): Any? = program.evaluate(variables)
  public fun test(variables: Map<String, Any?> = emptyMap()): Boolean = program.test(variables)
}
```

`RuntimeTest.elideContract` exercises the matching JVM contract, including host number
widening, bytes, null, collections, free identifiers and strict declarations. The Elide
repository has not been modified. This is a source-level facade replacement, not binary
compatibility with the `dev.cel` API. Align Truffle and Polyglot versions with the embedder;
do not load two copies of Truffle. The JAR does not bundle those dependencies.

A Polyglot integration can instead use language ID `cel`, MIME type `application/cel`,
and `context.getBindings("cel")`. Host object access is explicit: proxy objects, arrays,
and hash entries are copied into CEL values. No Java methods are exposed to expressions.

### Native startup and compiler memory

The plain Java API does not require a Polyglot `Context`. For non-regex expressions,
instantiate a prepared program and call it directly; creating and entering an otherwise
unused context adds first-request work. Source-backed instrumentation and Polyglot
bindings still require their context. TruffleRegex acceleration also requires an entered
CEL context: retain it for regex workloads, especially when omitting the optional RE2/J
fallback. Reuse environments, adapters, programs and applicable contexts across requests.

`Environment.prepare` can run during image construction. Store the resulting
`PreparedProgram` in a build-time initialized holder and call `instantiate()` at runtime.
This embeds a checked AST, not guest machine code. Creating a context or a call target
at image build time is not a substitute for runtime initialization. Input creation and
decoding remain part of the first request unless the application explicitly prepares
immutable inputs too.

The [young-generation profile comparison](benchmarks/native-profiles-20261109/young-generation/README.md)
tests a smaller young generation while retaining the default compiler isolation.
Its exact 512 MiB Serial GC configuration is:

```sh
./application -Xms512m -Xmx512m -XX:MaxNewSize=32m
```

This reduces resident heap capacity in the measured fixtures without reducing the
total heap limit or per-evaluation allocation. It can increase collection frequency;
qualify the application's throughput and live-data requirements before selecting
heap bounds. These measurements do not establish a universal young-generation size,
and TruffleCEL does not change the application's GC settings.
Across the six native fixtures, interpreter RSS falls 45.2–61.2%, while evaluation
time changes by −5.1% to +6.1%. Guest-JIT RSS falls 5.6–40.0%, with time changes of
−4.9% to +1.9%. These are three-process medians on a shared host, not performance
guarantees. See the complete ranges and matched cel-java controls in the report.

For Native Image applications using guest compilation, GraalVM
`25.5.5-elide.27` used in these measurements also supports running the compiler in the application isolate:

```sh
./application -XX:-CompileInIsolates
```

This is an **application launch option**, not a TruffleCEL library default. It avoids a
separate compiler isolate and makes compiler allocations share the application's heap
and GC. Size that heap for compilation as well as application data, and qualify the
application's other guest languages too. The native guest-JIT/blocklist smoke gate runs
in both modes. The [paired profile measurements](benchmarks/native-profiles-20261109/README.md)
report throughput, allocation, current RSS, peak RSS and compilation cost separately.
Lower compiler memory does not imply lower live application heap or a universal RSS win.
The same report also qualifies combining shared compilation with the 32 MiB cap.
On `.27`, the shared-compiler profile also reproduces a known
[non-heap management-counter defect](benchmarks/graalvm-nonheap-counter-issue.md):
`getNonHeapMemoryUsage()` can throw after guest compilation. Applications that depend
on that counter need the upstream fix before adopting this profile. Linux process RSS
and application heap counters remain available in the measured reproducer.

Choose interpretation for deployments that do not benefit from guest compilation,
using the process option `-Dpolyglot.engine.Compilation=false` before any targets are
created, or the equivalent engine option for context-owned programs. Do not change
process-global options from request handlers. Measure cumulative work over the expected
program lifetime before selecting a compilation threshold; a warmed evaluation ratio
does not establish when compilation pays back.

### Borrowed input bindings

`Program.evaluate(Map)` and `Program.test(Map)` borrow the supplied activation for the
call. Keep the map and all reachable input data stable until the call returns, including
inside host functions and adapter callbacks. Mutation during evaluation is unsupported;
Truffle assumptions cannot detect arbitrary writes to caller-owned Java collections.
Updates between calls are observed. No input values are cached in the program across calls.

Bindings are normalized when read. Collections and bytes that require conversion are
snapshotted once per binding within that evaluation; unused bindings are not traversed
or validated. Per-value depth/size bounds still apply when a binding is read, including
its nested contents. Short-circuited bindings need not be read. This avoids rebuilding a
complete activation for each request. Debugger inspection may read additional bindings.
Public collection and byte results remain owned copies.

This changes the previous eager `evaluate(Map)` behavior: unused invalid inputs no longer
cause an evaluation error, and callers must not mutate inputs during evaluation. Use
`Activation.snapshot` before evaluation when eager validation of all bindings or input
isolation is required. Explicit snapshots retain their existing semantics.

### Reusing input snapshots

When several policies or repeated evaluations use the same input, prepare its bindings once:

```java
Activation activation = Activation.snapshot(Map.of("user", userData));
boolean allowed = accessPolicy.test(activation);
Object classification = classificationPolicy.evaluate(activation);
```

`Activation.snapshot` normalizes **all** supplied bindings, including unused ones, using the
same per-value depth and size limits as ordinary map evaluation. Maps, lists, iterables,
arrays and bytes are copied into owned CEL values. Later mutations of those host collections
do not change the snapshot; create a new activation to observe new input. Do not mutate input
while snapshotting. Binding names must be non-null. Null values and missing bindings remain
distinct. Ordinary map evaluation instead borrows inputs and normalizes bindings on demand.

An activation contains no program, execution scopes or operation budget. It can serve multiple
programs, with fresh evaluation state on each call. Custom/opaque adapter values retain their
existing ownership and thread-safety contracts: preparing bindings does not freeze a live
`DynamicRecord` or make an arbitrary host object thread-safe. Use immutable adapter values
for concurrent reuse. Source-backed programs retain their Polyglot context requirements and
instrumentation behavior. Returned bytes and collections retain the ordinary output-copy rules.

Preparation has a cost. Reuse avoids repeating it; preparing a fresh activation for a single
evaluation is not promised to be faster. Benchmark preparation plus the actual number of uses,
and include upstream data ingestion when measuring the whole request.

For fixed inputs in Native Image, retain an explicit `static final Activation` in an
application class initialized at image build time. Create it with `Activation.snapshot`
in that class's initializer and reuse it at runtime. Configure build-time initialization
for that holder class, store a `PreparedProgram` alongside it if useful, and call
`PreparedProgram.instantiate()` at runtime to create the execution target.

[GraalVM's `ImageInfo.inImageBuildtimeCode()`](https://www.graalvm.org/sdk/javadoc/org/graalvm/nativeimage/ImageInfo.html)
(or the documented
`org.graalvm.nativeimage.imagecode=buildtime` system property) identifies the execution
phase, not input immutability or future reuse. TruffleCEL therefore does not automatically
cache map bindings based on that property. A map created during image construction can
still be mutated between runtime evaluations; ordinary map evaluation observes the new contents.
The representative native smoke gate checks that build-time snapshots retain owned
collections and bytes after the source input changes at runtime, in both interpreter
and guest-JIT execution. The adapter ownership restrictions above still apply.

### Prepared protobuf field access

`Environment.prepare` builds internal access plans for supported, statically typed
protobuf scalar fields, nested messages, repeated messages, and maps with qualified
scalar keys and scalar or ordinary message values. Instantiating the template creates
independent execution nodes and library caches. Plans retain schema metadata, never
request values. Reads guard the exact adapter identity; another adapter for the same
descriptor still follows the generic path. Environments with custom runtime object
adapters retain generic dispatch to preserve their precedence and callbacks.

Supported repeated scalar fields can be iterated directly over their immutable native
storage. Internal list results use owned storage transferred between evaluation stages;
public collection and byte results retain their existing conversion and ownership rules.
Filter and map still execute as separate stages, preserving callback ordering, errors,
unknowns and budgets. Nested messages retain their exact native plan; absent defaults
are constructed only when read. Repeated messages and maps materialize canonical owned
values once, preserving size bounds and protobuf map semantics without a second
normalization copy. Empty owned lists share immutable storage.

Singular Bool/Int32/Int64/Float/Double/String wrappers, Timestamp and Duration also
specialize when their descriptors are the standard protobuf-java descriptor instances.
These paths reuse the existing wrapper/time validation. Reconstructed or custom
well-known descriptors use generic conversion. Dynamic access, optional lifting,
extensions, unsigned/byte conversions, and unqualified map/message representations
continue through the generic adapter.

## Published TruffleData integration

The lockfile pins `dev.elide:truffledata-core`, `truffledata-protobuf`, and `truffledata-jdbc`
at `0.12.0` from `https://maven.elide.dev`. Compilation and JVM testing use Java 25;
Truffle/Polyglot are aligned in the current source tree to `25.5.5-elide.28` Elide GraalVM artifacts.
The protobuf adapter also directly depends on `truffledata-protobuf-runtime` at `0.12.0`,
which contains the native plans and codecs split from the protobuf-java bridge.
No sibling checkout or local Maven cache is required.

### Native rows and JDBC

`DataType` binds a type name to an immutable TruffleData `Schema`. It uses `DataLibrary`
for native reads and presence, rejects duplicate column names, distinguishes `Missing`
from `DataNull`, and preserves schema identity when accepting rows. Alpha.2 integer-width
constraints preserve unsigned CEL identity on reads and construction; ARRAY/RECORD kinds
are recognized without guessing from their Java representations.

```java
var type = new DataType("Account", batch.schema());
var env = Cel.newBuilder().objectType(type)
    .variable("account", CelType.of("Account")).build();
var predicate = env.compile("account.balance > 0 && has(account.owner)");
boolean matches = predicate.test(Map.of("account", batch.row(0)));
```

Rows from `JdbcCursor.nextBatch()` use the same path; retained batch rows remain valid after
cursor closure. The embedder owns the connection/cursor lifetime. JDBC and drivers are not
core runtime dependencies. Live `DynamicRecord` views retain TruffleData's thread-confinement
requirements; use immutable batch rows for shared programs/activations.

`DataValues.from` explicitly converts native scalar/array inputs. Exact decimals only become
CEL ints when representable without loss; other decimals require an application-selected
conversion rather than silently rounding. At the Polyglot binding boundary rows are copied
by **field name**, with missing fields omitted, rather than interpreted as ordinal arrays.

### Protobuf

Add `//:trufflecel-protobuf` alongside core (or the resulting `libprotobuf.jar` and its
Maven dependencies). Bind descriptors once, then reuse the provider and compiled program:

```java
var type = new ProtobufType(MyMessage.getDescriptor());
var env = type.register(Cel.newBuilder())
    .variable("message", CelType.of(type.name())).build();
var program = env.compile("has(message.owner) && message.count > 0u");
Object result = program.evaluate(Map.of("message", type.input(myMessage)));
```

`input(Message)` serializes the supplied message, normalizes MessageSet wire data,
and decodes through TruffleData's native plan before applying CEL well-known mappings. `snapshot` returns a `ProtobufType.Value` backed by a lossless
`ProtoRow`; it retains no protobuf `Message`. Field reads use native storage, `ProtoRow.has`
provides protobuf presence independently of readable defaults, and enums retain numeric values.
Bytes use compact immutable storage; unsigned integers preserve their full width.

Trusted embedders can register larger schemas with the explicit bounded constructor:
`new ProtobufType(descriptor, extensions, limits, additionalTypes)`, where `limits` is a
TruffleData `Limits`. Existing constructors retain `Limits.DEFAULT` (256 columns/types).
The complete upstream all-types fixture uses 512; this does not change application defaults.
The selected structural/payload budgets also apply to the native plan. Depth (100) and
wire-size (8 MiB) bounds remain in force.

Wrapper fields retain nullable wrapper types during checking. CEL construction prunes null
entries from repeated/map message fields where required, preserves JSON `Value` nulls, and
rejects null aggregate assignments. Primitive and standard well-known `Any` payloads are
registered automatically when the descriptor graph contains `Any`; application payload
descriptors still require explicit registration. CEL message equality rejects NaN equality,
including nested/repeated floating fields, while host `Value.equals` retains protobuf-java's
equality contract.

Binary decoding and CEL message construction use TruffleData's embedded codec and native
builders. To reuse a native plan directly:

```java
var row = type.plan().decode(wireBytes);
Object input = type.input(row);
var value = (ProtobufType.Value) type.decode(new Bytes(wireBytes));
byte[] roundTrip = value.toBytes().toByteArray();
```

`input(ProtoRow)` requires the exact plan returned by `type.plan()`. Native decoding retains
unknown fields and applies protobuf map/oneof semantics. The plan enforces depth, wire-size,
and value budgets. `Value.message()` explicitly exports a temporary Java message. Equality and
hashing use that host conversion to preserve protobuf semantics. Zero-value checks use native
field presence and unknown-field emptiness, with host conversion for map-entry and MessageSet
descriptors to preserve their special default and unknown-field semantics. No Java message is
retained in the evaluated value graph.

Registration covers reachable messages and enums. Supply additional descriptors for `Any`
payload types not reachable through fields; unknown `Any` types fail explicitly. Timestamps,
durations, wrappers, Struct/Value/ListValue and registered Any payloads map to CEL values.
JSON integers become doubles when constructing protobuf Value, as required by that type.
Descriptor initialization remains a host operation. Register proto2 extensions explicitly:

```java
var type = new ProtobufType(messageDescriptor, extensionFieldDescriptors);
var environment = type.register(Cel.newBuilder())
    .variable("msg", CelType.of(type.name())).build();
var program = environment.compile("proto.getExt(msg, example.my_extension)");
```

The second constructor argument is a `List<FieldDescriptor>` containing package-scoped or
message-scoped extensions. Additional message descriptors may follow it for `Any` payloads.
TruffleCEL compiles these descriptors into explicit TruffleData native field plans, using
extension wire numbers and fully qualified names. Binary ingestion, construction, defaults,
presence, repeated extensions, and nested message extensions use native rows. Host message
snapshots with extensions enter through the native binary parser; host export uses an
explicit protobuf extension registry. No classpath scanning or reflection is added.
Resolved Protobuf Editions features (including presence, required fields, packing and enum
openness) are compiled into native field plans. MessageSet wire items are normalized to native
extension fields on ingestion and restored on export, including unregistered payloads. The
bridge applies this normalization through nested messages; direct `plan()` access exposes
the normalized native wire format for MessageSet types. Custom adapters can expose registered extensions
through `ObjectType.extensions()`, with access implemented by their existing `get`/`has` methods.

The 2,771-case complete conformance suite retains its independent test-only `ProtoSchema` oracle. Separate
published-artifact tests cover all scalar types, defaults/presence, nested messages, repeated
fields/maps, unknown enums, construction, wire decoding and well-known mappings. No tests
load classes or artifacts from the sibling TruffleData checkout.

### Oracle Truffle JSON

Oracle's Truffle JSON is the shaded module `org.graalvm.shadowed:json`, not a separate
Elide language. Add `//:trufflecel-json` / `libjson.jar` to opt in:

```java
var json = new org.graalvm.shadowed.org.json.JSONObject("{\"age\":42}");
boolean result = Cel.compile("input.age >= 18").test(Map.of("input", JsonValues.from(json)));
```

`JsonValues.from` takes immutable snapshots of JSONObject/JSONArray trees, converts JSON
null, preserves signed integer identity, maps decimal numbers to CEL doubles, and rejects
integer overflow and non-finite decimal overflow. Traversal is capped at depth 100 and one
million values, including cycle protection through the depth bound. This boundary accepts
already parsed JSON; text parsing and its limits belong to the caller/Oracle JSON library.

## Native Image

Library semantics and publication consumption are validated on the JVM. The dedicated
CodSpeed benchmark also builds and runs as a Linux Native Image using the fallback interpreter. The core does not discover classes, reflect over fields, generate bytecode at runtime,
or read application resources. The optional protobuf bridge uses explicit descriptors and
temporary DynamicMessage exports for host equality/interoperability, so its reachable dependency surface is larger than core; it does not scan
classes or descriptor resources. The optional JSON bridge uses explicit JSONObject/JSONArray
access, never reflective bean conversion. The sole generated language-discovery service is produced at build time by
the Truffle registration processor. TruffleRegex supplies scoped acceleration for compatible
constant regular expressions; RE2/J preserves validation, size limits and fallback semantics.
Date/time functions use the JDK time-zone database.

The benchmark native image and workload smoke checks are qualified separately from the
embedder. Dedicated JVM compilation/allocation and native guest-JIT gates enforce the
contracts described in [benchmark details](BENCHMARKS.md). Production image size, peak
allocation across arbitrary inputs, and compiled throughput in Elide still need qualification. The embedder must provide the matching Truffle runtime and
its Native Image integration. No speculative reflection/resource configuration is shipped.

## Names, result types and AST size

`Builder.alias("short", "package.QualifiedName")` resolves a leading identifier before
container lookup; `abbreviations("package.QualifiedName")` uses the final identifier as
the alias. Aliases apply to declared variables, constants, object types, host functions
and extension function namespaces. Absolute names beginning with `.` bypass aliases.
Invalid, duplicate and built-in type aliases are rejected. Environments copy the builder's
alias table, and runtime bindings continue to use their declared qualified names.

`expectedResultType(CelType.BOOL)` constrains the static result of source preparation and
canonical imports, including unchecked planning. Dynamic results remain assignable under
CEL's gradual typing rules; use `Program.test` to require a boolean value at evaluation.

`maxAstNodes(count)` limits parser node allocations and imported AST size independently
of `maxDepth`. The default is 100,000. Parser accounting includes temporary nodes used
while recognizing macros and namespaces; input length limits still apply. Canonical input
retains its existing source-length-derived node cap as well as the explicit node limit.

## Runtime error locations

`CelException` exposes `expressionId()`, `offset()`, `offsetEncoding()`, `line()` and `column()` for runtime
failures as well as compile-time diagnostics. Runtime IDs are stable within a prepared
program; canonical imports preserve supplied expression IDs. The innermost failing
operation retains its location through folding, arithmetic lowering and error propagation.
Exception instances supplied by host callbacks are never mutated.

For source programs, offsets count Java chars and line/column are one-based. Imported
canonical `SourceInfo` uses CEL's code-point positions. `offsetEncoding()` preserves this
distinction through optimization and tracing; `format(source)` uses it to place source carets.
Absent position metadata remains
`-1` while the expression ID is still available. Budget exhaustion identifies the policy
root because optimized evaluation can charge multiple original visits together. Arbitrary
exceptions outside CEL's normalized callback errors retain their existing propagation.

`maxRegexProgramSize(instructions)` caps the compiled RE2 program size, with a default of
10,000. Oversized constant `matches` patterns are rejected during preparation; dynamic
patterns and regex extension calls are checked before matching. Cached patterns are rechecked
against the calling environment's limit. Exceeding the cap reports `RESOURCE_EXHAUSTED`.
A zero-count regex replacement still returns its input without compiling the pattern.

Constant `matches` patterns in a conservative ASCII subset can use the pinned
TruffleRegex RE2 flavor. The constant match's first execution must occur in an initialized,
entered CEL context. Instantiate the Program within its execution context; a reusable
PreparedProgram may be prepared outside it. The accelerator remains owned by that
context; the Program holds only a weak reference and checks the owning context on every accelerated call.
Evaluation outside that context or after its closure uses RE2/J. If a weak handle
is collected while its owning context remains active, the program reacquires it on
its next evaluation in that context. A match first executed without a CEL context stays on RE2/J and never
creates a hidden context. Cache eviction removes strong ownership; an evicted handle
can continue accelerating matches in its owning context until collected. Dynamic
patterns and capture/replacement extensions also remain on RE2/J. No caller context is required by the standalone Java API.

The initial accelerated subset excludes escapes, non-ASCII pattern characters and
special groups. Unicode properties and case folding differ between the two engines'
Unicode tables, and some nullable capture loops have different match boundaries.
Unsupported TruffleRegex patterns fall back after the same RE2/J syntax and program-size
validation. The context's accelerator cache is bounded to 256 entries and cleared on
disposal. Both caches evict one oldest insertion at capacity rather than clearing
all entries. Repeated syntax failures cache only error data and raise fresh CEL
exceptions; caller-specific limits remain enforced on every access.

Upstream tracking covers [counted-repetition support](https://github.com/elide-dev/graal/issues/205),
[nullable-loop match boundaries](https://github.com/elide-dev/graal/issues/206), and
[UTF-16 search progress](https://github.com/elide-dev/graal/issues/204). The latter two
also inform the RE2/J fallback for extraction/replacement extensions; constant boolean
matching always starts from zero and does not expose capture boundaries.

The default compatibility mode requires RE2/J. Removing it from that mode
also requires a supported [host entry point and lifecycle](https://github.com/elide-dev/graal/issues/207)
for context-free, concurrent evaluation, plus [analysis and complexity-budget metadata](https://github.com/elide-dev/graal/issues/208)
that preserves the documented `maxRegexProgramSize` contract or supplies an explicit
migration. TruffleRegex's `regex.Validate=true` option checks syntax inside an owning
context, but supplies neither a matcher nor an instruction count. Its parser/DFA
limits are different metrics. Unicode-sensitive matching also needs a qualified
compatibility decision: the pinned backends use different Unicode table versions.
Until these contracts and matching gaps are addressed, the fallback preserves
CEL errors, bounds and the standalone Java lifecycle.

## Per-evaluation controls and cost

`EvaluationOptions.newBuilder()` configures a per-call `budget(long)`, `timeout(Duration)`,
absolute `deadline(Instant)`, or `cancelled(BooleanSupplier)` hook. Pass the immutable options
to `Program.evaluate(bindings, options)` or the corresponding `Activation` overload. An omitted
budget uses the environment's budget; a supplied budget replaces it for that call only.
Timeouts begin at evaluation entry, and the earlier timeout/deadline wins.

`evaluateWithDetails(bindings, options)` returns an `EvaluationResult` containing `actualCost()`
and a nullable `error()`. Its `value()` method returns the owned value or throws the original
located CEL error. Cost counts successfully charged CEL operations, including those before a
failure; an operation rejected for exceeding the remaining budget is not charged. Folded work
retains its recorded cost. Concurrent calls never share counters or scopes.

Cancellation and deadline checks run at CEL cost steps and before returning the result. They
report `RESOURCE_EXHAUSTED`; they do not interrupt arbitrary host callback code. Cancellation
predicates execute on the evaluating thread. Ordinary evaluation retains its existing call
target and allocation behavior; calls with observable controls use a separate compiled target.

Repeated unary `!` or `-` tokens are scanned iteratively and collapsed by parity,
as in CEL's parser semantics. Long runs are bounded by `maxSourceLength`; they do
not consume AST depth merely because they contain many tokens. The resulting
expression still obeys `maxDepth`, `maxAstNodes` and evaluation-budget limits.


## Per-evaluation host functions

Declare `lateFunction(name, overloadId, receiver, arguments, result)` on the environment,
then supply `EvaluationOptions.newBuilder().function(overloadId, implementation)` for each
call. The implementation receives the same normalized CEL arguments as an ordinary registered
function. Options are immutable; concurrent calls may bind the same overload differently.
An undeclared or non-late-bound ID is rejected before evaluation. A reached, unbound overload
raises `UNDECLARED`; type checking still uses its declared signature.

`nonStrictFunction(...)` registers a callback that may receive `CelException` or `Unknown`
arguments. `lateFunction(..., true)` declares the same behavior for a late-bound overload.
Errors and unknowns match any parameter type, but ambiguous overloads are never guessed.
Ordinary strict calls retain CEL unknown/error propagation. Resource exhaustion and cancellation
always abort before host dispatch. A callback may return an unknown or return/throw a CEL error;
returned CEL errors propagate as failures rather than successful host values.

## Deriving an environment

`environment.toBuilder()` (or `new Environment.Builder(environment)`) copies the complete
configuration into an independent builder. Declarations, constants, explicit host callbacks,
object adapters, aliases, extension restrictions and resource limits are preserved. Adding a
declaration to the new builder does not change the original environment; registered callbacks
and adapters retain their existing application-owned lifetime and thread-safety contracts.


`cel.bind(name, initializer, body)` is lazy: the initializer runs on the first read, in the
captured outer lexical scope, and its value or CEL error is memoized for that binding only.
An unused initializer consumes no evaluation budget and produces no host side effects.
Debugger scopes show `<lazy>` before the first read; the initializer's source events occur
when that read evaluates it. Each evaluation and each comprehension iteration has fresh slots.


## Context-free tracing and asynchronous calls

`program.trace(listener).evaluate(bindings)` reports one `TraceEvent` after each executed
source expression, including CEL failures, expression IDs, and source locations. Events follow
actual evaluation order; short-circuited expressions emit nothing. Every event exposes
`offsetEncoding()` for its offset and column, including successful events without an error:
source events use `UTF16`, canonical events use `CODE_POINT`. Values in events are owned
public values, so a listener may retain them without retaining mutable evaluation state.
Listeners run synchronously on the evaluation thread and their exceptions propagate. A shared
listener must provide its own thread safety. `EvaluationOptions.trace(listener)` also supports
tracing together with per-call budgets and late-bound functions.

The ordinary target keeps its lowering and folding fast paths. A tracing target is created
lazily and shared by subsequent traced calls. Fold replacements retain their discarded immutable
source subtrees outside Truffle child edges. Arithmetic chains retain compact literal IDs and
locations with encoding flags (30 bytes of array payload per operator, plus three array headers), allowing reconstruction
without retaining the original chain. Comprehension-size nodes restore normal list semantics only
in the tracing target. Thus an unchanged tree needs no second source tree; a five-node folded
expression retains those five source nodes plus its single runtime fold node. Requesting tracing
creates one additional restored tree. This is an explicit retained-memory tradeoff, not a claim
that tracing is allocation-free or that program retained heap is unchanged.

`program.evalAsync(bindings, options, executor)` returns a `CompletableFuture<Object>`. Inputs
are snapshotted before submission, and each task has independent controls and scopes. Timeouts
start when execution begins; absolute deadlines retain their absolute meaning. Cancelling the
future prevents queued work and requests cooperative cancellation at CEL cost steps for running
work; it does not interrupt arbitrary host callbacks. The caller chooses and manages the executor.
Executor rejection is synchronous, and evaluation failures complete the future exceptionally.
A context-bound embedding must arrange its normal context entry on the executor thread.

Field-level partial evaluation uses owned activations and typed attribute paths:

```java
var inputs = PartialActivation.of(
    Map.of("request", Map.of("region", "us")),
    AttributePattern.of("request").qualify("approved"));
var pending = program.partialEvaluate(inputs);
var finished = pending.advanceEvaluation(
    Map.of(Attribute.of("request").qualify("approved"), true));
```

Patterns can contain `.wildcard()` qualifiers for individual map keys, list indices,
or object fields. Known sibling fields remain available, including through local
bindings and comprehensions. `has()` preserves unknown presence. Resolutions are
immutable snapshots; resolving a parent replaces previous child resolutions.
Reading a whole aggregate with an unresolved descendant produces an unknown for
that aggregate. Supply the complete parent value to resolve such a whole-object
read; individual child resolutions do not reconstruct arbitrary host objects.
Each advance evaluates the original expression afresh, with a new operation budget
and fresh lazy scopes. Host callbacks may therefore run again. Results expose
`isComplete()`, `unknownAttributes()`, `error()` and `actualCost()`; a failed result's
`value()` throws its CEL error. Partial execution uses the same lazily restored
source target as tracing, preserving source semantics through optimization.

A protobuf message can also supply the entire evaluation context:

```java
var type = new ProtobufType(Request.getDescriptor());
var environment = type.registerContext(Cel.newBuilder()).build();
var program = environment.compile("region == 'us' && approved");
var result = program.evaluate(type.context(request));
```

`context(Message)` snapshots before evaluation; `context(ProtobufType.Value)` reuses
an existing native snapshot. Every declared root field is bound, including protobuf
defaults. The message wrapper is not retained.

`type.mask(FieldMask)` creates a typed projection. Register it and snapshot through
that view, or use its `registerContext` and `context` methods. Selected singular
message subpaths receive distinct projected CEL types; hidden fields fail both
static checking and dynamic selection. Repeated fields, maps and automatically
mapped well-known messages can be selected as whole fields but cannot be traversed
by a mask. Empty masks expose no fields; `*` exposes every field. Masks use protobuf
field names. Use `mask("DistinctViewName", mask)` when several views share one
environment. Projection equality compares visible fields and their presence;
projection values retain native rows, not host messages. Field masks govern CEL
field visibility; they do not redact the original host message or its serialized
bytes.


## Generated POJO, JavaBean and record snapshots

Add `dev.elide:trufflecel-processor` at the same version as core to your Java annotation
processor path. With javac, use `-processor dev.trufflecel.processor.PojoProcessor` (or enable
processor discovery with `-proc:full`). Bazel consumers can use
`//src/main/java/dev/trufflecel/processor:plugin` in their Java target's `plugins`.
The processor has no runtime dependencies and does not belong in a Native Image runtime classpath.

```java
@CelPojo("app.User")
public record User(long id, @CelField("display_name") String displayName) {}

var type = UserCel.INSTANCE;
var environment = type.register(Cel.newBuilder())
    .variable("user", CelType.of(type.name())).build();
var policy = environment.compile("user.id > 0 && has(user.display_name)");
var snapshot = type.snapshot(new User(42, "Ada"));
boolean allowed = policy.test(Map.of("user", snapshot));
```

An adapter is generated beside the annotated Java type, named `<Type>Cel`; nested Java types
use enclosing names joined with underscores, such as `Models_UserCel`. The default CEL type name
is the qualified Java name. The generator reads public instance fields, ordinary JavaBean
`getX()`/boolean `isX()` accessors, or record components. `@CelField` can rename a property,
expose a zero-argument accessor, or exclude it with `ignore = true`. Duplicate CEL field names
are rejected rather than choosing between a public field and a getter. Static members and
unannotated non-getter methods are not exposed. Both annotations have class-file retention so
already compiled nested types and inherited annotated members remain available to the processor;
no runtime reflection is used.

Supported properties are primitive/boxed booleans and numbers, characters and strings, `UInt`,
`Bytes`, `byte[]`, `Instant`, `Duration`, enums (their declared names as CEL strings), typed `List`
and `Map`, and other annotated classes or records. Map keys must map to CEL string, integer, uint
or boolean types. Raw collections, unresolved wildcard/generic properties, other arrays and arbitrary host
objects receive compile-time diagnostics. Inherited generic members are resolved against the concrete
annotated subclass before their types are checked. Register nested adapters explicitly when their fields
are selected or their CEL constructors are used.

Call `adapter.snapshot(host)` before evaluation. Generated accessors run once during this call,
copying nested objects, collections and bytes into immutable `PojoType.Value` objects. The caller
must keep the host graph stable during copying. Subsequent host mutation is invisible; snapshots
can be shared by concurrent evaluations and `Activation.snapshot`. They contain no original POJO
references. The default traversal limits are depth 100 and one million values, shared across all
nested adapters and containers; `snapshot(host, maxDepth, maxNodes)` selects tighter limits.
Cycles fail with `INVALID_ARGUMENT`, and exceeded limits fail with `RESOURCE_EXHAUSTED`.

Every generated property is present in a host snapshot, including null properties, represented
as CEL null. CEL constructors produce the same owned value type; omitted constructor fields are
absent, so `has()` is false and an ordinary read raises `NO_SUCH_KEY`. Construction validates
field names and nested value types and counts existing snapshot subgraphs against the same depth
and node limits. Null map keys are rejected in both snapshots and CEL construction. Evaluating a raw host POJO does not implicitly snapshot it;
only generated snapshots are accepted by these adapters. Policy execution never invokes bean
getters or constructs host objects.

### Omitting RE2/J

RE2/J is an optional Maven dependency. Applications using the default compatibility
mode must declare `com.google.re2j:re2j:1.8` explicitly. To omit it, opt in to:

```java
var environment = Cel.newBuilder().truffleRegexOnly(10_000).build();
```

Create and initialize a Polyglot `cel` context, enter it, then prepare/instantiate
and evaluate regex programs there. This mode never creates a hidden context or
loads RE2/J. Both constant and dynamic boolean `matches` use TruffleRegex RE2;
unsupported patterns and extraction/replacement operations fail explicitly. Missing
or closed contexts also report CEL errors. A zero-count replacement remains a no-op.

Patterns are limited to 4,096 UTF-16 code units, bounding context-cache key storage.
The argument bounds TruffleRegex parser-tree nodes. It is a different resource
metric from RE2 instructions; `maxRegexProgramSize` cannot be combined with this
mode. Existing CEL source/value bounds and the backend's non-backtracking execution
remain enforced. Syntax/unsupported-backend failures follow the environment's
validation policy. The mode uses TruffleRegex's Unicode tables and does not promise
RE2/J compatibility for patterns excluded from the default accelerator subset.

The default compatibility mode and accelerated constant-match hot path are preserved.

For the Polyglot language entry point, configure the context instead:

```java
Context context = Context.newBuilder("cel")
    .allowExperimentalOptions(true)
    .option("cel.TruffleRegexOnly", "true")
    .option("cel.RegexParserTreeLimit", "10000")
    .build();
boolean result = context.eval("cel", "'abc'.matches('[a-z]+')").asBoolean();
```

These language options apply to `Context.eval`/`parse`. Direct Java environments
select their own policy through `truffleRegexOnly`. Engines keep parsed programs
separate when contexts select different regex policies or parser bounds.
