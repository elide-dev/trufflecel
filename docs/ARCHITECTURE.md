# TruffleCEL architecture

Compile expressions once, then evaluate them against fresh inputs. The frontend implements
CEL parsing, name resolution, and gradual typing; each `Program` owns a reusable Truffle call
target. Evaluation state belongs to the individual call.

```mermaid
flowchart LR
    Source["CEL source"] --> Parser["Parser"]
    Parser --> Checker["Type checker"]
    Env["Declarations and bindings"] --> Checker
    Checker --> Program["Program / Truffle AST"]
    Input["Input variables"] --> Activation["Activation, scopes and budget"]
    Program --> Eval["Evaluate call target"]
    Activation --> Eval
    Eval --> Result["CEL value or error"]
    Program -. "Graal partial evaluation" .-> Code["Compiled guest code"]
    Code -.-> Eval
```

Truffle specializes expression execution and can compile hot guest code on a compatible
GraalVM runtime. Generic collections, libraries, input conversion, and object adapters use
explicit Truffle boundaries. The JVM and Native Image compilation gates exercise this split
and enforce allocation, AST-size, and compiler-graph budgets.

```mermaid
flowchart TB
    Host["Java / Elide embedder"] --> API["Cel / Environment / Program"]
    Host --> Polyglot["Polyglot Context: cel"]
    Polyglot --> Core["trufflecel-core"]
    API --> Core
    Rows["TruffleData batch / JDBC rows"] --> Data["DataType schema adapter"]
    Data --> Core
    Proto["Protobuf messages and wire data"] --> PB["trufflecel-protobuf / ProtobufType"]
    PB --> Core
    JSON["Oracle JSONObject / JSONArray"] --> JS["trufflecel-json / JsonValues"]
    JS --> Core
    Core --> Runtime["Embedder-supplied Truffle runtime"]
    Runtime --> JVM["GraalVM JVM"]
    Runtime --> Native["Native Image"]
```

The direct Java API does not require a Polyglot context. Optional adapters keep protobuf and
Oracle JSON dependencies out of the core JAR. Native TruffleData rows retain their schema;
host collections and JSON trees are converted to bounded CEL snapshots. See the
[integration guide](INTEGRATION.md) for object bindings, presence, and numeric semantics.
