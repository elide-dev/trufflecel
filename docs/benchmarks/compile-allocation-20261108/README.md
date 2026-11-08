# Compile-and-evaluate allocation diagnosis — November 8, 2026

A local diagnostic identified avoidable allocations introduced during 0.10.0 frontend work.
For `Cel.compile("x * 2 + 1").evaluate(activation)`, with a reused `Map.of("x", 42L)`
activation, allocated bytes per operation were:

| Source | Allocated bytes/op |
| --- | ---: |
| Released main, `f47ffa6` | 3,176 |
| Integrated 0.10.0 before optimization, `4718846` | 3,968 |
| Candidate implemented in `8199977` | 3,432 |

The candidate removes 536 bytes/op (13.5%) relative to the integrated pre-fix source.
It remains 256 bytes/op above released main. Every one of seven sampled windows reported
the same allocation for its revision. These are JVM allocation observations, **not a native
throughput result or proof that the CodSpeed regression is resolved**.

The patch reuses lexer tokens when no source-offset adjustment is needed, shares the fixed
expression-terminator set, allocates recovery findings only after a syntax failure, skips lazy
binding resolution on trees without bindings, and resolves partial-attribute metadata only on
the lazily created tracing/partial-evaluation target. Error recovery, offsets, resource limits,
and trace-restoration metadata remain present.

The triggering [CodSpeed check](https://github.com/elide-dev/trufflecel/runs/128617026540)
reported `compile-evaluate` at 5.4 ms versus 6 ms (-10.42%) for 256 operations, comparing
`68af876` with `f47ffa6`. It also reported different runner hardware and used main because
no successful stacked-base run existed for `d2344d5`. That result does not isolate code or
hardware effects. The optimized candidate still needs native CodSpeed qualification against
a matched baseline; no threshold, workload, or historical baseline was changed.

## Method and limitations

The same temporary harness ran sequentially in isolated worktrees on one Apple M5 Max host
with 18 logical CPUs and 48 GiB RAM. Bazel selected the pinned GraalVM runtime; its exact
version is in [java-version.txt](java-version.txt). JVMCI host compilation was disabled to use
C2, Truffle used its fallback runtime, and the heap was fixed at 512 MiB. Each process warmed
20,000 operations and then measured seven windows of 20,000 operations. `ThreadMXBean`
reported bytes allocated by the benchmark thread; every result was consumed through a volatile
sink and each window recorded checksum 85. The activation map was created once outside the
loops, matching the simulation fixture. Builds completed before measurements, and measurement
processes did not overlap.

This was one JVM process per source revision on a developer machine, without CPU affinity,
a dedicated idle-host reservation, or convergence analysis. The retained nanosecond samples
are noisy and are not used for a throughput claim. Native Image compilation and x86-64
CodSpeed simulation have different execution characteristics.

## Reproduction and evidence

[CompileProfile.java.txt](CompileProfile.java.txt) and [target.bazel.txt](target.bazel.txt) preserve
the temporary harness and target. In each isolated checkout of `f47ffa6`, `4718846`, or
`8199977`, copy the Java file to `benchmarks/CompileProfile.java` and append the target fragment
to `benchmarks/BUILD.bazel`, which already loads `java_test`. Then run:

```sh
bazel build --config=local //benchmarks:compile_profile
bazel-bin/benchmarks/compile_profile > /tmp/compile-profile-result.txt
```

The historical diagnostic harness is not an automatically run benchmark or a new performance
gate. [evidence.json](evidence.json) contains source provenance, runtime flags, hardware,
harness/candidate-JAR hashes, and parsed samples. Original outputs are
[baseline-jvm.txt](baseline-jvm.txt), [head-before-jvm.txt](head-before-jvm.txt), and
[head-after-jvm.txt](head-after-jvm.txt). [optimization.patch](optimization.patch) records the
candidate source change; an explanatory comment was added after measuring it and before
committing `8199977`.

Nine RBE coverage targets passed after the patch: parser, parser recovery, policy compiler,
lazy binding, tracing, partial evaluation, CSE, canonical AST interchange, and the complete
ordinary conformance corpus ([invocation](https://app.buildbuddy.io/invocation/72611f66-d367-4207-8c61-120caa8fc0ab)).
Integrated native qualification and CI are separate from these checks.
