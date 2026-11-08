#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
python3 -m unittest discover -s benchmarks/comparison -p test_memory_diagnostic.py
