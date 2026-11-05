#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
for engine in truffle-interpreter truffle-jit cel-java cel-java-optimized cel-java-pratt-optimized; do
  for workload in scalar scalar-wide proto-policy proto-repeated map-regex optimizer-policy; do
    "$1" "$engine" "$workload" smoke
    "$1" "$engine" "$workload" image-smoke
  done
done
