#!/usr/bin/env python3
"""Bounded natural guest-JIT payback with full map inputs, separate from process startup."""
import argparse
import datetime
import itertools
import json
import os
from pathlib import Path
import platform
import random
import subprocess

from prepared_lifecycle import validate
from representative import FLAGS, NATIVE_FLAGS, sha

WORKLOADS = ('scalar-wide', 'proto-policy', 'proto-repeated')
ENGINES = ('truffle-interpreter', 'truffle-jit')
MODES = (('jvm', 'cold'), ('svm', 'cold'), ('svm', 'image-cold'))


def inventory(forks):
    return {(runtime, engine, workload, 'map', mode, fork)
            for (runtime, mode), engine, workload, fork in itertools.product(
                MODES, ENGINES, WORKLOADS, range(forks))}


def validate_results(rows, forks, maximum):
    expected = inventory(forks)
    seen = set()
    for row in rows:
        job = tuple(row[key] for key in ('runtime', 'engine', 'workload', 'entry', 'mode', 'fork'))
        if job not in expected or job in seen:
            raise ValueError('Unexpected or duplicate payback cell')
        validate(row, job, maximum)
        seen.add(job)
    if seen != expected:
        raise ValueError('Incomplete focused payback matrix')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--source-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpus', required=True)
    parser.add_argument('--host-conditions', required=True)
    parser.add_argument('--forks', type=int, default=3)
    parser.add_argument('--maximum', type=int, default=1_000_000,
                        choices=(10_000, 100_000, 1_000_000, 10_000_000))
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('Use the pinned Linux x86_64 artifacts')
    if args.forks < 3:
        parser.error('At least three independent processes per cell are required')
    os.sched_setaffinity(0, set(map(int, args.cpus.split(','))))
    args.output.mkdir(parents=True, exist_ok=False)
    java_version = subprocess.check_output([str(args.java), '-version'], text=True,
                                           stderr=subprocess.STDOUT)
    if 'GraalVM' not in java_version:
        raise ValueError('Use the pinned GraalVM distribution')
    java_home = args.java.resolve().parent.parent
    metadata = dict(
        schema=1, complete=False,
        captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        host=platform.node(), kernel=platform.release(), cpus=sorted(os.sched_getaffinity(0)),
        host_conditions=args.host_conditions, load_start=os.getloadavg(),
        cpuinfo=Path('/proc/cpuinfo').read_text(), java_version=java_version,
        java_sha256=sha(args.java), jar_sha256=sha(args.jar), native_sha256=sha(args.native),
        runtime_sha256={name: sha(java_home / name)
                       for name in ('release', 'lib/modules', 'lib/server/libjvm.so')},
        source_manifest=json.loads(args.source_manifest.read_text()),
        source_manifest_sha256=sha(args.source_manifest),
        driver_sha256=sha(Path(__file__)),
        validation_driver_sha256=sha(Path(__file__).with_name('prepared_lifecycle.py')),
        shared_driver_sha256=sha(Path(__file__).with_name('representative.py')),
        jvm_flags=FLAGS, native_flags=NATIVE_FLAGS, forks=args.forks, maximum=args.maximum,
        interval='child configuration/preparation/planning plus cumulative evaluations; '
                 'excludes process launch, dataset generation and ingestion; natural compilation',
        activation='full map, four input variants, materialized repeated-list result',
        commands=[], results=[])
    path = args.output / 'payback.json'

    def save():
        path.write_text(json.dumps(metadata, indent=2) + '\n')

    jobs = sorted(inventory(args.forks))
    random.Random(20261108).shuffle(jobs)
    save()
    for index, job in enumerate(jobs):
        runtime, engine, workload, entry, mode, fork = job
        command = ([str(args.java), *FLAGS, '-jar', str(args.jar)] if runtime == 'jvm'
                   else [str(args.native), *NATIVE_FLAGS])
        command += [engine, workload, entry, mode, str(args.maximum)]
        metadata['commands'].append(command)
        save()
        name = '-'.join(map(str, job))
        print(f'{index + 1}/{len(jobs)} {name}', flush=True)
        with (args.output / (name + '.log')).open('w') as output:
            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                           check=True, timeout=300)
        records = [json.loads(line)
                   for line in (args.output / (name + '.log')).read_text().splitlines()
                   if line.startswith('{')]
        if len(records) != 1:
            raise ValueError('Expected exactly one checked payback result')
        row = records[0]
        row['fork'] = fork
        validate(row, job, args.maximum)
        metadata['results'].append(row)
        save()
    validate_results(metadata['results'], args.forks, args.maximum)
    if sha(args.jar) != metadata['jar_sha256'] or sha(args.native) != metadata['native_sha256']:
        raise ValueError('Artifact changed during measurement')
    metadata.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
