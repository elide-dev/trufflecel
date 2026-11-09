#!/usr/bin/env python3
# Copyright 2026 The TruffleCEL Authors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Compare native runtime profiles with identical artifacts, inputs and heap bounds."""

import argparse
import datetime
import itertools
import json
import os
from pathlib import Path
import platform
import random
import subprocess

from evaluation_refresh import (ENGINES, WORKLOADS, NATIVE_FLAGS, fixed_memory,
                                parse_record, sha, validate_result)

PROFILES = {
    'default': [],
    'shared-compiler': ['-XX:-CompileInIsolates'],
    'shared-free-32m': ['-XX:-CompileInIsolates', '-XX:MaxHeapFree=32m'],
    'shared-young-32m': ['-XX:-CompileInIsolates', '-XX:MaxNewSize=32m'],
}


def inventory(forks, workloads, profiles):
    return set(itertools.product(range(forks), workloads, ENGINES, profiles))


def validate_inventory(rows, forks, workloads, profiles, memory):
    expected = inventory(forks, workloads, profiles)
    keys = []
    for row in rows:
        keys.append(tuple(row[k] for k in ('fork', 'workload', 'engine', 'profile')))
        validate_result(row, 'svm', row['engine'], row['workload'], memory)
    if len(keys) != len(set(keys)) or set(keys) != expected:
        raise ValueError('Incomplete, duplicate or unexpected native profile cells')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('memory', 'timing'))
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--memory-native', type=Path, required=True)
    parser.add_argument('--source-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpus', default='2,3,4,5,6,7,8,9')
    parser.add_argument('--forks', type=int, default=3)
    parser.add_argument('--workloads', nargs='+', choices=WORKLOADS, default=WORKLOADS)
    parser.add_argument('--profiles', nargs='+', choices=PROFILES, default=['default', 'shared-compiler'])
    parser.add_argument('--host-conditions', required=True)
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('Pinned Linux x86_64 execution required')
    if args.forks < 1 or len(set(args.workloads)) != len(args.workloads) or len(set(args.profiles)) != len(args.profiles):
        parser.error('Invalid or duplicate matrix selection')
    os.sched_setaffinity(0, set(map(int, args.cpus.split(','))))
    args.output.mkdir(parents=True, exist_ok=False)
    binaries = {name: str(getattr(args, name).resolve()) for name in ('native', 'memory_native')}
    hashes = {name: sha(path) for name, path in binaries.items()}
    metadata = dict(schema=1, complete=False, phase=args.phase,
                    captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    host=platform.node(), kernel=platform.release(),
                    cpuinfo=Path('/proc/cpuinfo').read_text(),
                    cpus=sorted(os.sched_getaffinity(0)), load_start=os.getloadavg(),
                    host_conditions=args.host_conditions, artifacts=binaries, sha256=hashes,
                    source_manifest=json.loads(args.source_manifest.read_text()),
                    source_manifest_sha256=sha(args.source_manifest),
                    driver_sha256=sha(__file__),
                    shared_driver_sha256=sha(Path(__file__).with_name('evaluation_refresh.py')),
                    forks=args.forks, workloads=args.workloads, engines=ENGINES,
                    profiles={p: PROFILES[p] for p in args.profiles}, common_flags=NATIVE_FLAGS,
                    limits='Fresh processes, same combined executables for every profile and engine. Fixed-work memory and timing are separate processes. Current/peak RSS are not retained heap or standalone binary footprint. Fewer than three forks is exploratory only.',
                    commands=[])
    rows = []

    def save():
        (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        (args.output / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')

    save()
    # Pair profiles within each cell and rotate order; randomize the cell schedule.
    jobs = list(itertools.product(range(args.forks), args.workloads, ENGINES))
    random.Random(42).shuffle(jobs)
    for number, (fork, workload, engine) in enumerate(jobs):
        for profile in args.profiles[::1 if number % 2 == 0 else -1]:
            key = f'{fork}-{workload}-{engine}-{profile}'
            memory = args.phase == 'memory'
            command = [binaries['memory_native' if memory else 'native'], *NATIVE_FLAGS,
                       *PROFILES[profile], engine, workload, 'measure' if memory else 'steady']
            metadata['commands'].append(dict(argv=command, load_before=os.getloadavg()))
            save()
            if memory:
                row = fixed_memory(command, args.output / key)
            else:
                with (args.output / (key + '.stderr.log')).open('w') as error:
                    value = subprocess.run(command, stdout=subprocess.PIPE, stderr=error,
                                           text=True, timeout=300, check=True)
                (args.output / (key + '.json')).write_text(value.stdout)
                row = parse_record(value.stdout)
            validate_result(row, 'svm', engine, workload, memory)
            rows.append(dict(**row, profile=profile, fork=fork))
            save()
            print('completed', key, flush=True)
    validate_inventory(rows, args.forks, args.workloads, args.profiles, args.phase == 'memory')
    if hashes != {name: sha(path) for name, path in binaries.items()}:
        raise ValueError('Artifacts changed during measurement')
    metadata.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
