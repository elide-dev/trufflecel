#!/usr/bin/env python3
# Copyright 2026 The TruffleCEL Authors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Check management counters in the exact native heap/compiler configurations."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import platform
import subprocess

PROFILES = {'default': [], 'young-32m': ['-XX:MaxNewSize=32m'],
            'shared-young-32m': ['-XX:MaxNewSize=32m', '-XX:-CompileInIsolates']}
PHASES = ['baseline', 'context', 'prepared', 'first_results', 'warmup', 'compiled',
          'evaluated', 'application_gc', 'idle', 'idle_application_gc', 'context_closed_gc']
def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--source-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    source = json.loads(args.source_manifest.read_text())
    binary = args.binary.resolve()
    if sha(binary) != source['artifacts']['memory-phases']:
        raise ValueError('Probe does not match source manifest')
    report = dict(complete=False, captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  host=platform.node(), kernel=platform.release(), source_manifest=source,
                  binary_sha256=sha(binary), driver_sha256=sha(__file__), records=[])
    def save():
        (args.output / 'counters.json').write_text(json.dumps(report, indent=2) + '\n')
    save()
    for profile, flags in PROFILES.items():
        for engine in ('trufflecel', 'truffle-interpreter', 'cel-java'):
            command = [str(binary), '-Xms512m', '-Xmx512m', *flags, engine, 'comprehension', 'true', '0']
            child = subprocess.run(command, input='\n' * len(PHASES), capture_output=True,
                                   text=True, timeout=180)
            prefix = args.output / (profile + '-' + engine)
            prefix.with_suffix('.stdout').write_text(child.stdout)
            prefix.with_suffix('.stderr').write_text(child.stderr)
            child.check_returncode()
            phases = [json.loads(line) for line in child.stdout.splitlines()]
            if [p['phase'] for p in phases] != PHASES:
                raise ValueError('Unexpected checkpoint inventory')
            if engine == 'trufflecel' and not all(p['last_tier'] for p in phases[5:-1]):
                raise ValueError('Guest code was not retained')
            if engine == 'truffle-interpreter' and any(p['last_tier'] for p in phases):
                raise ValueError('Interpreter unexpectedly compiled')
            report['records'].append(dict(profile=profile, engine=engine, command=command, phases=phases))
            save()
    if sha(binary) != report['binary_sha256']:
        raise ValueError('Probe changed during qualification')
    report['complete'] = True
    save()
if __name__ == '__main__':
    main()
