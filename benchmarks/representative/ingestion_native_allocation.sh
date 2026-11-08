#!/usr/bin/env bash
set -euo pipefail
binary="$1"
output="${TEST_UNDECLARED_OUTPUTS_DIR:?}"
for fork in 0 1 2; do
  for fixture in ordinary unknown64k messageset; do
    "$binary" -Xmx512m "$fixture" > "$output/$fixture-$fork.jsonl"
  done
done
sha256sum "$binary" > "$output/binary.sha256"
uname -srm > "$output/kernel.txt"
cat /proc/cpuinfo > "$output/cpuinfo.txt"
