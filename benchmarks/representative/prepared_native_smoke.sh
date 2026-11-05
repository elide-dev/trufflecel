#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
for engine in truffle-interpreter truffle-jit; do
  for workload in scalar scalar-wide proto-policy proto-repeated map-regex optimizer-policy; do
    for entry in map snapshot fresh; do
      "$1" "$engine" "$workload" "$entry" smoke
      "$1" "$engine" "$workload" "$entry" image-smoke
    done
  done
done
