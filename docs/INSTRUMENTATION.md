# Coverage, profiling, and debugging

TruffleCEL exposes source sections and all seven Truffle standard tags through generated
instrumentation wrappers. Polyglot `Context.eval` programs are instrumentable by default.
Instruments can attach before execution or after a program has already run.

| Tag | CEL location |
| --- | --- |
| `RootTag`, `RootBodyTag` | Entire expression body |
| `ExpressionTag` | Every executable expression node |
| `StatementTag` | Root body, comprehension bodies, and conditional branches |
| `CallTag` | Function and receiver calls |
| `ReadVariableTag` | Identifier reads, with the variable name in node metadata |
| `WriteVariableTag` | Comprehension and optional-map bindings, with the variable name |

CEL has no statement syntax or mutable assignment. Statement tags identify useful stepping
and coverage locations; write tags represent local bindings. Parser offsets preserve the
source spans of nested expressions. Untaken branches do not emit execution events.

Debugger scopes expose a read-only snapshot of the activation and currently visible local
bindings, including comprehension variables. A local binding shadows an activation binding
of the same name. Scopes are flattened; they do not expose a separate parent-scope hierarchy.
Instrument callbacks receive CEL values through Truffle interop. Exceptional execution also
produces instrumentation events.

## Direct Java embedding

The string-based `compile` and no-argument `PreparedProgram.instantiate` APIs remain
context-free. To attach instruments to a direct Java `Program`, use a Truffle `Source` and
keep its owning Polyglot context entered during compilation and evaluation:

```java
try (var context = org.graalvm.polyglot.Context.newBuilder("cel").build()) {
  context.enter();
  try {
    var source = com.oracle.truffle.api.source.Source
        .newBuilder("cel", "x * 2 + 1", "policy.cel").build();
    var environment = Cel.newBuilder().variable("x", CelType.INT).build();
    var program = environment.compile(source);
    var result = program.evaluate(Map.of("x", 3L));
  } finally {
    context.leave();
  }
}
```

`PreparedProgram.instantiate(source)` offers the same instrumentation path for an already
checked AST. Its source text must exactly match the prepared expression. Both APIs require
language ID `cel` and an entered context; CEL initialization happens automatically.

Programs created without a `Source` may combine constant-right integer arithmetic, such as
`x * 2 + 1`, into fewer execution nodes. Intermediate overflow checks, unknown propagation,
and the original evaluation budget are preserved. The `Source` APIs retain every original
operator and literal, including when instantiating a template that has already produced an
optimized program, so tools continue to receive the complete sequence of source events.

Expressions without local bindings or source instrumentation create evaluation state inside
the call target, allowing guest compilation to eliminate it on eligible paths. Comprehensions
keep their escaping state outside guest compilation to limit compilation cost. Source-backed
programs also keep that state in their frame arguments and retain the binding-read boundary
used by instrumented compilation.
Each evaluation still has independent bindings, local scopes, and a resource budget,
including reentrant evaluations from host functions.

Local reads use lexical slot numbers resolved when the program is created. Range expressions
are resolved in the enclosing scope before iteration variables are introduced; nested bindings
and absolute global names retain their usual semantics. Runtime scopes still retain names and
values for debugger snapshots, and local reads retain a Truffle boundary for instrumented
frame access.

Instrumentation wrappers and interop exports are generated at build time. Source sections,
node metadata, and scope snapshots are created when tools request them. No runtime classpath
scanning or reflection registration is introduced. Attached instruments can add allocation
and execution overhead of their own.

The JVM suite verifies all tags, nested source sections, local values, exceptional events,
late-attached debugger breakpoints, and last-tier guest compilation with an instrument
attached and performance warnings treated as errors. The native guest-JIT gate also checks
late-attached debugger breakpoints and comprehension scopes. Native Image support depends on
including the desired instruments in the image; these tests do not certify every external
instrument or debugger transport.

See the official [Truffle instrumentation API](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/instrumentation/InstrumentableNode.html),
[standard tags](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/instrumentation/StandardTags.html),
and [debugger API](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/debug/Debugger.html)
for instrument and debugger integration.
