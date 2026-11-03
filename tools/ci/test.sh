#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
exec python3 -m unittest discover -s .github/scripts -p 'test_*.py'
