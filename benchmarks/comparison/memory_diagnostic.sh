#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
python3 benchmarks/comparison/memory_diagnostic.py "$1" "${TEST_UNDECLARED_OUTPUTS_DIR}" "${MEMORY_FORKS:-3}" "${MEMORY_IDLE_MS:-12000}"
