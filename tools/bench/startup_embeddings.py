#!/usr/bin/env python3
# Copyright 2026 The TruffleCEL Authors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Paired native first-result controls for context and direct Java embeddings."""

import argparse
import datetime
import itertools
import json
import os
from pathlib import Path
import platform
import random

import startup
from representative import NATIVE_FLAGS, WORKLOADS, sha

EMBEDDINGS = ('context', 'direct')


def driver_provenance():
    return dict(driver_sha256=sha(Path(__file__)),
                startup_driver_sha256=sha(Path(startup.__file__)),
                shared_driver_sha256=sha(Path(__file__).with_name('representative.py')))


def inventory(forks):
    return set(itertools.product(startup.ENGINES, WORKLOADS, ('runtime', 'image'), range(forks), EMBEDDINGS))


def validate(rows, forks):
    keys = []
    controls = set()
    for row in rows:
        startup.validate(row)
        if row['engine'] == 'control':
            key = (row['mode'], row['fork'])
            if key in controls:
                raise ValueError('Duplicate startup control')
            controls.add(key)
            continue
        keys.append(tuple(row[k] for k in ('engine', 'workload', 'mode', 'fork', 'embedding')))
        if row['input_index'] != row['fork'] % 4:
            raise ValueError('Incorrect paired input variant')
        record = row['record']
        context = row['engine'].startswith('truffle-') and (row['embedding'] == 'context' or row['workload'] == 'map-regex')
        if record.get('embedding') != row['embedding'] or record.get('context_created') is not context:
            raise ValueError('Embedding differs from requested control')
    if set(keys) != inventory(forks) or len(keys) != len(set(keys)):
        raise ValueError('Incomplete, duplicate or unexpected embedding cells')
    if controls != set(itertools.product(('runtime', 'image'), range(forks))):
        raise ValueError('Incomplete or unexpected startup controls')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--native-prepared', type=Path, required=True)
    parser.add_argument('--source-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpus', default='2,3,4,5,6,7,8,9')
    parser.add_argument('--forks', type=int, default=8)
    parser.add_argument('--host-conditions', required=True)
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('Pinned Linux x86_64 execution required')
    if args.forks < 4:
        parser.error('At least four forks are required to cover every input variant')
    os.sched_setaffinity(0, set(map(int, args.cpus.split(','))))
    args.output.mkdir(parents=True, exist_ok=False)
    binaries = dict(runtime=str(args.native.resolve()), image=str(args.native_prepared.resolve()))
    hashes = {mode: sha(Path(path)) for mode, path in binaries.items()}
    report = dict(schema=1, complete=False,
                  captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  host=platform.node(), kernel=platform.release(), cpuinfo=Path('/proc/cpuinfo').read_text(),
                  cpus=sorted(os.sched_getaffinity(0)), host_conditions=args.host_conditions,
                  load_start=os.getloadavg(), artifacts=binaries, sha256=hashes,
                  source_manifest=json.loads(args.source_manifest.read_text()),
                  source_manifest_sha256=sha(args.source_manifest), **driver_provenance(),
                  forks=args.forks, native_flags=NATIVE_FLAGS,
                  limits='Same artifacts and heap bounds, fresh paired processes. Direct Java embedding omits an unnecessary Polyglot context for non-regex expressions only. Regex retains the context and TruffleRegex backend. Includes wire creation, adapter/decode, configuration, planning and verified first evaluation. No forced guest compilation. Empty controls are reported separately, never subtracted. Combined binaries do not measure standalone footprint.',
                  commands=[], results=[])
    path = args.output / 'startup.json'

    def save():
        path.write_text(json.dumps(report, indent=2) + '\n')

    jobs = list(itertools.product(startup.ENGINES, WORKLOADS, ('runtime', 'image'), range(args.forks)))
    jobs += [('control', 'control', mode, fork) for mode, fork in itertools.product(('runtime', 'image'), range(args.forks))]
    random.Random(42).shuffle(jobs)
    save()
    for number, (engine, workload, mode, fork) in enumerate(jobs):
        embeddings = ('control',) if engine == 'control' else EMBEDDINGS[::1 if number % 2 == 0 else -1]
        for embedding in embeddings:
            command = [binaries[mode], *NATIVE_FLAGS]
            command += ['control'] if engine == 'control' else [engine, workload, mode, str(fork % 4), embedding]
            key = f'{engine}-{workload}-{mode}-{fork}-{embedding}'
            report['commands'].append(dict(argv=command, load_before=os.getloadavg()))
            save()
            row = startup.observe(command, args.output / (key + '.stdout'), args.output / (key + '.stderr'), 120)
            row.update(runtime='svm', engine=engine, workload=workload, mode=mode,
                       fork=fork, input_index=fork % 4, embedding=embedding)
            startup.validate(row)
            report['results'].append(row)
            save()
    validate(report['results'], args.forks)
    if hashes != {mode: sha(Path(path)) for mode, path in binaries.items()}:
        raise ValueError('Artifacts changed during measurement')
    report.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
