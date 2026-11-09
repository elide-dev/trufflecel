#!/usr/bin/env bash
set -euo pipefail
cd "${TEST_SRCDIR}/${TEST_WORKSPACE}"
"$1"
# Qualify the opt-in lower-RSS native compiler profile against the same strict
# guest-JIT/blocklist fixture inventory; do not relax compilation failure gates.
exec "$1" -XX:-CompileInIsolates
