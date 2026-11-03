#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
"$1" --smoke
for engine in trufflecel cel-java; do
  for stage in compile evaluate interpret; do
    "$1" "$engine" "$stage" scalar --memory
  done
done
