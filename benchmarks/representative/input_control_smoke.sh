#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
for engine in cel-java-prepared-map cel-java-optimized-prepared-map cel-java-immutable cel-java-optimized-immutable; do
  for workload in scalar-wide proto-policy; do
    "$1" "$engine" "$workload" smoke
    "$2" "$engine" "$workload" smoke
  done
done
