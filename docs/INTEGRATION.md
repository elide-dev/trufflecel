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
at `0.1.0-alpha.3` from `https://maven.elide.dev`. Compilation and JVM testing use Java 25;
Truffle/Polyglot are aligned to the release's `25.5.5-elide.5` Elide GraalVM artifacts.
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

`input` snapshots via the published `ProtobufAdapter` and applies CEL well-known mappings.
`snapshot` exposes the raw `ProtobufType.Value` (immutable protobuf plus native row).
Registration covers the reachable message graph and enums. Supply additional descriptors
in the constructor for `Any` payload types not reachable through fields. Unknown `Any`
types fail explicitly. `ObjectType.construct` and `decode` support CEL message literals and
binary payloads. Native row reads are complemented by descriptor-guided handling of bytes,
unsigned widths, enum numbers, map entries, readable defaults, and protobuf presence.
Well-known timestamps, durations, wrappers, Struct/Value/ListValue and registered Any payloads
map to CEL values. JSON integers become doubles when constructing protobuf Value, as required
by that protobuf representation.

Alpha.3 stores protobuf bytes in compact, immutable byte-backed `DataArray` values;
regression coverage checks that representation and CEL byte conversion. Enum values are still
name strings, and readable proto3 scalar defaults still differ from CEL presence semantics.

The compatibility bridge retains the original immutable protobuf message for exact type,
presence, equality and binary operations; it is not yet a wholly native protobuf object model.
[TruffleData #1](https://github.com/elide-dev/truffledata/issues/1) requests the metadata,
presence and native construction APIs needed to remove that sidecar. Descriptor APIs remain
in the optional bridge, including read-path compatibility conversions. Descriptor-declared
fields are supported; proto2 extension registries are not exposed by this bridge yet.
Applications requiring extensions can supply a specialized `ObjectType`.

The core 1,905-case suite retains its independent test-only `ProtoSchema` oracle. Separate
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
DynamicMessage, so its reachable dependency surface is larger than core; it does not scan
classes or descriptor resources. The optional JSON bridge uses explicit JSONObject/JSONArray
access, never reflective bean conversion. The sole generated language-discovery service is produced at build time by
the Truffle registration processor. RE2/J supplies bounded regular-expression semantics.
Date/time functions use the JDK time-zone database.

The benchmark native image and workload smoke checks are qualified separately from the
embedder. Native guest-JIT compilation currently has unresolved collection-path blocklist
violations; production image size, peak allocation and compiled Truffle throughput have not
been qualified. The embedder must provide the matching Truffle runtime and
its Native Image integration. No speculative reflection/resource configuration is shipped.
