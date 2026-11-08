#!/usr/bin/env bash
set -euo pipefail
bench_root=/home/sam/trufflecel-010-bench-20261108/startup-67ab33c
bench_java=/home/sam/trufflecel-010-bench-20261108/toolchain/graalvm-ce-25.5.5-elide.27-linux-amd64/bin/java
bench_lock=/tmp/trufflecel-010-remote-measurement.lock
mkdir "$bench_lock"
trap 'rmdir "$bench_lock"' EXIT
cd "$bench_root"
python3 - <<'PY' > host-before.json
import datetime,json,os,platform,subprocess
from pathlib import Path
governors={str(p):p.read_text().strip() for p in Path('/sys/devices/system/cpu').glob('cpu[0-9]*/cpufreq/scaling_governor')}
boost=Path('/sys/devices/system/cpu/cpufreq/boost')
print(json.dumps({'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'host':platform.node(),'kernel':platform.release(),'load':os.getloadavg(),'governors':governors,'boost':boost.read_text().strip() if boost.exists() else None,'active_process_names':subprocess.check_output(['ps','-eo','comm,pcpu','--sort=-pcpu'],text=True).splitlines()[:15],'note':'Read-only host snapshot. No governor/boost changes or exclusive OS CPU reservation; background monitoring remains.'},indent=2))
PY
python3 tools/startup.py --java "$bench_java" --jar startup.jar \
  --native "$bench_root/startup-runtime-native" --native-prepared "$bench_root/startup-image-native" \
  --source-manifest source.json --output startup-results --cpus 2,3,4,5,6,7,8,9 --forks 5 \
  --host-conditions 'One benchmark window at a time under a shared atomic lock; CPUs2-9; background monitoring remains; no governor/boost changes, OS CPU reservation or thermal control; file caches are not flushed.'
python3 tools/focused_payback.py --java "$bench_java" --jar prepared-lifecycle.jar \
  --native "$bench_root/prepared-native" --source-manifest source.json --output payback-results \
  --cpus 2,3,4,5,6,7,8,9 --forks 3 --maximum 1000000 \
  --host-conditions 'One benchmark window at a time under a shared atomic lock; CPUs2-9; background monitoring remains; no governor/boost changes, OS CPU reservation or thermal control; file caches are not flushed.'
python3 - <<'PY' > host-after.json
import datetime,json,os,platform
print(json.dumps({'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'host':platform.node(),'load':os.getloadavg()},indent=2))
PY
