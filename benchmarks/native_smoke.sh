#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
"$1" --smoke
"$1" evaluate-scalar
# Require instrumentation must fail outside the runner; never silently measure a no-op.
if TRUFFLECEL_REQUIRE_CODSPEED=1 "$1" evaluate-scalar; then
  echo "Uninstrumented measurement unexpectedly succeeded" >&2
  exit 1
fi
