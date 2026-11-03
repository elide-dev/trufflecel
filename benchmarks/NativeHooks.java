/* Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0
 * Adapted from elide/tools/microbench/NativeHooks.kt. */
package dev.trufflecel.bench;

import java.util.*;
import org.graalvm.nativeimage.*;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.*;
import org.graalvm.nativeimage.c.type.*;

/** Benchmark-only region hooks. Exceptions never unwind through the C boundary. */
@CContext(NativeHooks.Headers.class)
final class NativeHooks {
  public static final class Headers implements CContext.Directives {
    @Override
    public List<String> getHeaderFiles() {
      return List.of("\"celbench.h\"");
    }
  }

  private static final CEntryPointLiteral<CFunctionPointer> ENTRY =
      CEntryPointLiteral.create(NativeHooks.class, "invoke", IsolateThread.class);
  private static Runnable action;
  private static Throwable failure;

  @CEntryPoint(name = "cel_bench_invoke")
  static void invoke(IsolateThread thread) {
    try {
      Objects.requireNonNull(action).run();
    } catch (Throwable cause) {
      failure = cause;
    }
  }

  @CFunction("celbench_measure")
  private static native int measureNative(
      IsolateThread thread, CFunctionPointer callback, CCharPointer name);

  static void measure(String name, Runnable body) {
    if (action != null) throw new IllegalStateException("Overlapping measurement windows");
    action = body;
    failure = null;
    try (var cName = CTypeConversion.toCString(name)) {
      int status =
          measureNative(CurrentIsolate.getCurrentThread(), ENTRY.getFunctionPointer(), cName.get());
      if (status != 0)
        throw new IllegalStateException("CodSpeed hook failed with status " + status);
      if (failure != null) throw new IllegalStateException("Measured workload failed", failure);
    } finally {
      action = null;
      failure = null;
    }
  }
}
