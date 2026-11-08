#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
measurement_lock=/tmp/trufflecel-010-remote-measurement.lock
mkdir "$measurement_lock"
trap 'rmdir "$measurement_lock"' EXIT
python3 run.py "$@"
