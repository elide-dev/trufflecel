#!/usr/bin/env bash
set -euo pipefail
binary=$1
mode=${2:-runtime}
"$binary" control
for engine in truffle-interpreter truffle-jit cel-java-optimized; do
  for workload in scalar scalar-wide proto-policy proto-repeated map-regex optimizer-policy; do
    for input in 0 1 2 3; do
      "$binary" "$engine" "$workload" "$mode" "$input"
    done
  done
done
# Runtime-only JVM/native artifacts and image-prepared native artifacts must not mix.
other=image
if [[ "$mode" == image ]]; then other=runtime; fi
if "$binary" truffle-interpreter scalar "$other" 0 >/dev/null 2>&1; then
  echo "Accepted the wrong preparation mode" >&2
  exit 1
fi
