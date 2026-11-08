#!/usr/bin/env bash
set -euo pipefail
artifacts="${TEST_UNDECLARED_OUTPUTS_DIR:-$TEST_TMPDIR}"
corpus="$TEST_TMPDIR/corpus"
"$1" "$corpus"
"$2" --target_class=dev.trufflecel.FuzzTarget \
  '--instrumentation_includes=dev.trufflecel.**' \
  '--instrumentation_excludes=dev.trufflecel.FuzzTarget' \
  -runs="${TRUFFLECEL_FUZZ_RUNS:-2000}" -max_len=4097 -timeout=10 \
  "--coverage_report=$artifacts/coverage.txt" \
  "-artifact_prefix=$artifacts/" "$corpus" 2>&1 | tee "$artifacts/jazzer.log"
python3 - "$artifacts/jazzer.log" <<'PY'
import pathlib
import re
import sys
text = pathlib.Path(sys.argv[1]).read_text()
if not re.search(r'cov: [1-9][0-9]*', text):
    raise SystemExit('Jazzer produced no coverage; instrumentation must be active')
PY
