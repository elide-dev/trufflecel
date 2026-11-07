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
at `0.8.0` from `https://maven.elide.dev`. Compilation and JVM testing use Java 25;
Truffle/Polyglot are aligned in the current source tree to `25.5.5-elide.27` Elide GraalVM artifacts.
The protobuf adapter also directly depends on `truffledata-protobuf-runtime` at `0.8.0`,
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

`input(Message)` snapshots through TruffleData's bound `ProtoCompiler.Source` and applies
CEL well-known mappings. `snapshot` returns a `ProtobufType.Value` backed by a lossless
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
and value budgets. `Value.message()` explicitly exports a temporary Java message; equality,
hashing, and zero-value checks currently use that host conversion to preserve their existing
protobuf semantics. No Java message is retained in the evaluated value graph.

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
the Truffle registration processor. RE2/J supplies bounded regular-expression semantics.
Date/time functions use the JDK time-zone database.

The benchmark native image and workload smoke checks are qualified separately from the
embedder. Dedicated JVM compilation/allocation and native guest-JIT gates enforce the
contracts described in [benchmark details](BENCHMARKS.md). Production image size, peak
allocation across arbitrary inputs, and compiled throughput in Elide still need qualification. The embedder must provide the matching Truffle runtime and
its Native Image integration. No speculative reflection/resource configuration is shipped.
