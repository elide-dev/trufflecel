/* Copyright (c) 2024-2026 Elide Technologies, Inc.
 * SPDX-License-Identifier: Apache-2.0
 * Adapted from Komodo native benchmark hooks. */
#include "celbench.h"
#include "core.h"
#include <stdlib.h>
#include <unistd.h>
/* Keep the native workload beneath a stable flamegraph root. */
__attribute__((noinline)) void
__codspeed_root_frame__celbench(void *thread, void (*callback)(void *)) {
  callback(thread);
}

int celbench_measure(void *thread, void (*callback)(void *),
                       const char *name) {
  InstrumentHooks *hooks = instrument_hooks_init();
  if (!hooks)
    return 1;
  int status = 0;
  if (instrument_hooks_is_instrumented(hooks)) {
    status = instrument_hooks_set_integration(hooks, "trufflecel-native", "1.0.0");
    if (!status)
      status = instrument_hooks_start_benchmark_inline(hooks);
    if (!status) {
      __codspeed_root_frame__celbench(thread, callback);
      status = instrument_hooks_stop_benchmark_inline(hooks);
      if (!status)
        status = instrument_hooks_set_executed_benchmark(hooks, getpid(), name);
    }
  } else if (getenv("TRUFFLECEL_REQUIRE_CODSPEED")) {
    status = 1;
  } else {
    __codspeed_root_frame__celbench(thread, callback);
  }
  instrument_hooks_deinit(hooks);
  return status;
}
