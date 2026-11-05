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

## Published TruffleData integration

The lockfile pins `dev.elide:truffledata-core`, `truffledata-protobuf`, and `truffledata-jdbc`
at `0.6.0` from `https://maven.elide.dev`. Compilation and JVM testing use Java 25;
Truffle/Polyglot are aligned in the current source tree to `25.5.5-elide.24` Elide GraalVM artifacts.
The protobuf adapter also directly depends on `truffledata-protobuf-runtime` at `0.6.0`,
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
