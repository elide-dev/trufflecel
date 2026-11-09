#!/usr/bin/env bash
set -euo pipefail
artifacts="${TEST_UNDECLARED_OUTPUTS_DIR:-$TEST_TMPDIR}"
for target in FuzzTarget EvaluationFuzzTarget; do
  corpus="$TEST_TMPDIR/corpus-$target"
  mkdir -p "$corpus" "$artifacts/$target"
  if [[ $target == FuzzTarget ]]; then "$1" "$corpus"; fi
  "$2" "--target_class=dev.trufflecel.$target" \
    '--instrumentation_includes=dev.trufflecel.**' \
    '--instrumentation_excludes=dev.trufflecel.FuzzTarget:dev.trufflecel.EvaluationFuzzTarget' \
    -runs="${TRUFFLECEL_FUZZ_RUNS:-2000}" -max_len=4097 -timeout=10 \
    "--coverage_report=$artifacts/$target/coverage.txt" \
    "-artifact_prefix=$artifacts/$target/" "$corpus" 2>&1 | tee "$artifacts/$target/jazzer.log"
  python3 - "$artifacts/$target/jazzer.log" <<'PY'
import pathlib
import re
import sys
text = pathlib.Path(sys.argv[1]).read_text()
if not re.search(r'cov: [1-9][0-9]*', text):
    raise SystemExit('Jazzer produced no coverage; instrumentation must be active')
PY
done
