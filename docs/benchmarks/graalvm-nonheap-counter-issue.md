# Native Image non-heap memory counter becomes negative after guest compilation and GC

Status: filed as [elide-dev/graal#227](https://github.com/elide-dev/graal/issues/227).
The observed runtime is the Elide
GraalVM distribution `25.5.5-elide.27`. Reproduction on an unmodified upstream
GraalVM distribution has not been attempted.

## Failure

With `-XX:-CompileInIsolates`, Native Image's
`ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage()` throws after a
compiled Truffle call target is exercised and `System.gc()` runs:

```text
Exception in thread "main" java.lang.IllegalArgumentException: used parameter = -13896 is negative.
    at java.management@25.0.4.1.1/java.lang.management.MemoryUsage.<init>(MemoryUsage.java:154)
    at org.graalvm.nativeimage.builder/com.oracle.svm.core.heap.AbstractMemoryMXBean.getNonHeapMemoryUsage(AbstractMemoryMXBean.java:63)
    at dev.trufflecel.MemoryPhases.checkpoint(MemoryPhases.java:149)
    at dev.trufflecel.MemoryPhases.main(MemoryPhases.java:110)
```

The bean should return a nonnegative used-byte count. Inspection of the pinned
`svm.jar` shows this getter passes
`RuntimeCodeInfoMemory.getSizeCounters().totalSize().rawValue()` into
`new MemoryUsage(...)`. The exception therefore demonstrates an invalid size
counter; it does not establish that actual executable memory was freed incorrectly.
No CEL result error or loss of last-tier guest code was observed before the getter
failed. Application heap counters and Linux process memory snapshots were available.

## Reproduction

The smallest existing harness selection is one fresh process, one scalar expression
(`x * 2 + 1`), and the compiler running in the application isolate:

```sh
bazel test //benchmarks/comparison:memory_diagnostic \
  --test_env=MEMORY_SOURCE_REVISION="$(git rev-parse HEAD)" \
  --test_env=MEMORY_FORKS=1 \
  --test_env=MEMORY_IDLE_MS=0 \
  --test_env=MEMORY_VARIANTS=shared-compiler \
  --test_env=MEMORY_WORKLOADS=scalar \
  --test_env=MEMORY_ENGINES=trufflecel \
  --test_output=all
```

The test executes a Linux amd64 Native Image on RBE. On a Linux amd64 host the
checkpoint child itself can also be run without the Python profiler:

```sh
printf '\n\n\n\n\n\n\n\n\n\n\n' | \
  bazel-bin/benchmarks/comparison/memory-phases \
    -Xmx512m -XX:-CompileInIsolates trufflecel scalar true 0
```

This command is a reduced invocation of the same checkpoint child; the observed
failure below came from the RBE profiler invocation. The current harness records
the getter exception as `non_heap_used: null` with `non_heap_error`, so a known
diagnostic failure does not discard the independent RSS/heap measurements. Before
that handling was added, the same exception terminated the process. The negative
value need not be identical across builds.

The child initializes and enters a CEL Polyglot context, compiles the scalar
expression through the standalone Java API, verifies four inputs, runs 2,000 warmup
evaluations, explicitly compiles its `OptimizedCallTarget` at the last tier, then
runs eight batches of 65,536 evaluations. The first post-evaluation `System.gc()`
precedes the failing getter. Standalone target options are explicitly verified:

```text
polyglot.engine.Compilation=true
polyglot.engine.BackgroundCompilation=false
polyglot.engine.CompilationFailureAction=Throw
polyglot.compiler.TreatPerformanceWarningsAsErrors=all
```

The image uses Serial GC, optimization level 3, and `-march=compatibility`, with
Truffle blocklist and never-part-of-compilation checks enabled. It contains both
TruffleCEL and cel-java comparison code; cel-java is not invoked in the failing
child. The fixture and full build options are in
[`MemoryPhases.java`](../../benchmarks/comparison/MemoryPhases.java),
[`Fixtures.java`](../../benchmarks/comparison/Fixtures.java), and
[`BUILD.bazel`](../../benchmarks/comparison/BUILD.bazel).

## Preserved evidence

- [Failing RBE invocation](https://app.buildbuddy.io/invocation/8adc6051-aecf-480d-96d3-1708430350a9).
- Production source baseline: `67921ff`; diagnostic harness: `8d87446`.
  The reachable equivalent of that harness commit is `171cc15`; handling that
  preserves the exception without aborting profiling is `aa70c27` / `092d3b1`.
- GraalVM `25.5.5-elide.27`, Java `25.0.4.1.1+1`; TruffleData `0.9.0`.
- Linux `5.15.0`, reported CPU model `AMD EPYC`, CPU affinity `0–6`.
- Native executable SHA-256: `a19d95ad65d025e964192295ee7e964a0722bc82544b33484df511c200e0df2c`.
- Raw stderr artifact: `0-shared-compiler-scalar-trufflecel/stderr.txt` under
  `//benchmarks/comparison:memory_diagnostic` undeclared outputs.
- Raw stderr SHA-256: `61889e055f8720bcd3d27fb1e33c0af2cc8bef064cc561ba70c6d445a360cd0b`.

Six default-configuration engine/workload cells completed before the first shared
compiler cell hit this error. Those completed cells do not prove the default
configuration is unaffected under other workloads or longer lifetimes.

The completed [three-fork phase report](native-memory-phases-010.json) also shows
that a positive counter cannot be treated as reliable in shared-compiler mode.
Immediately after comprehension last-tier compilation, all three forks report
110,744 bytes with the default compiler isolate but only 72 bytes with the compiler
in the application isolate. Both modes have 36 KiB of executable anonymous resident
pages at that phase. After evaluation, the default reports 51,176 bytes while the
shared compiler getter throws for `-59496`; the latter happens before the explicit
GC checkpoint, so the defect is not limited to calls to `System.gc()`. These are
observations of the accounting discrepancy, not proof of its implementation cause.

An upstream regression should exercise guest code replacement and reclamation in
both compiler-isolation modes, assert nonnegative runtime-code accounting after
GC, and verify installed guest code remains valid and future compilation works.

## Reconfirmed with TruffleData 0.10.0

The [November 9 phase exploration](native-profiles-20261109/phase-exploration.json)
reproduces the defect on production revision `29269155d6c4b6470c74f55c5cbbe37b31fc3955`,
GraalVM `25.5.5-elide.27` and TruffleData `0.10.0`. In the single shared-compiler
comprehension process, the getter reports `used parameter = -58248 is negative`
at the evaluated and subsequent GC/idle checkpoints, and `-103944` after context
closure. The report preserves the binary/driver hashes, phases, heap counters,
Linux RSS and last-tier checks. This is a fresh reproduction of the accounting
failure, not a three-fork performance result or evidence of corrupted guest code.

The later [exact-profile controls](native-profiles-20261109/young-generation/counter-controls/counters.json)
also reproduce those negative values with both `-XX:-CompileInIsolates` and
`-XX:MaxNewSize=32m`. With the cap alone and default compiler isolation, this
single-process diagnostic completes without counter errors. Baseline, interpreter
and cel-java controls also complete. This does not prove those configurations
cannot fail under other workloads or longer lifetimes.
