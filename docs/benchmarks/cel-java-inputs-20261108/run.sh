#!/usr/bin/env bash
set -euo pipefail
bench_root=/home/sam/trufflecel-010-bench-20261108/input-control
bench_java=/home/sam/trufflecel-010-bench-20261108/toolchain/graalvm-ce-25.5.5-elide.27-linux-amd64/bin/java
bench_lock=/tmp/trufflecel-010-remote-measurement.lock
mkdir "$bench_lock"
trap 'rmdir "$bench_lock"' EXIT
cd "$bench_root"
for phase in memory jvm-timing native-timing; do
  python3 tools/cel_java_inputs.py "$phase" --java "$bench_java" \
    --jmh "$bench_root/jmh.jar" --memory-jar "$bench_root/memory.jar" \
    --native "$bench_root/timing-native" --memory-native "$bench_root/memory-native" \
    --source-manifest source.json --cpus 2,3,4,5,6,7,8,9 --output "$phase"
done
