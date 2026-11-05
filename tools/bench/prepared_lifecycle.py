#!/usr/bin/env python3
"""Measure natural compilation payback and native throughput with full activation entry paths."""
import argparse
import datetime
import itertools
import json
import math
import os
from pathlib import Path
import platform
import random
import subprocess

from representative import FLAGS, NATIVE_FLAGS, WORKLOADS, sha

ENGINES = ('truffle-interpreter', 'truffle-jit')
ENTRIES = ('map', 'snapshot', 'fresh')
COLD_WORKLOADS = ('scalar-wide', 'proto-policy', 'proto-repeated')


def inventory(kind):
    jobs = []
    if kind in ('all', 'cold'):
        jobs.extend((runtime, engine, workload, entry, 'cold', fork)
                    for runtime, engine, workload, entry, fork in itertools.product(
                        ('jvm', 'svm'), ENGINES, COLD_WORKLOADS, ENTRIES, range(3)))
        jobs.extend(('svm', engine, workload, 'snapshot', 'image-cold', fork)
                    for engine, workload, fork in itertools.product(
                        ENGINES, ('proto-policy', 'proto-repeated'), range(3)))
    if kind in ('all', 'native'):
        jobs.extend(('svm', engine, workload, entry, 'steady', fork)
                    for engine, workload, entry, fork in itertools.product(
                        ENGINES, WORKLOADS, ENTRIES, range(3)))
    return jobs


def validate(row, job, maximum):
    runtime, engine, workload, entry, mode, _ = job
    for key, expected in zip(('runtime', 'engine', 'workload', 'entry', 'mode'), job[:5]):
        if row[key] != expected:
            raise ValueError('Mislabeled lifecycle result: ' + key)
    if type(row['image_prepared']) is not bool or row['image_prepared'] != mode.startswith('image-'):
        raise ValueError('Wrong preparation mode')
    for key in ('configure_ns', 'prepare_ns', 'instantiate_ns', 'snapshot_ns'):
        if type(row[key]) is not int or row[key] < 0:
            raise ValueError('Invalid setup timing')
    if type(row['last_tier_at_end']) is not bool:
        raise ValueError('Missing tier observation')
    if engine == 'truffle-interpreter' and row['last_tier_at_end']:
        raise ValueError('Interpreter unexpectedly compiled')
    if mode.endswith('cold'):
        points = row['total_ns_through_evaluations']
        expected = [str(10 ** i) for i in range(len(str(maximum)))]
        if list(points) != expected or list(row['guest_compiled_at_checkpoint']) != expected:
            raise ValueError('Missing cold checkpoints')
        times = list(points.values())
        if any(type(v) is not int or v <= 0 for v in times) or any(b <= a for a, b in zip(times, times[1:])):
            raise ValueError('Invalid cumulative timing')
        compiled = list(row['guest_compiled_at_checkpoint'].values())
        if any(type(v) is not bool for v in compiled):
            raise ValueError('Invalid checkpoint tier state')
        if engine == 'truffle-interpreter' and any(compiled):
            raise ValueError('Interpreter checkpoint unexpectedly compiled')
    else:
        if type(row['forced_guest_preparation_ns']) is not int or row['forced_guest_preparation_ns'] < 0:
            raise ValueError('Invalid forced compilation timing')
        for field, count in (('warmup_ns_per_op', 5), ('ns_per_op', 7)):
            if len(row[field]) != count or any(not math.isfinite(v) or v <= 0 for v in row[field]):
                raise ValueError('Invalid native timing samples')
        if engine == 'truffle-jit' and not row['last_tier_at_end']:
            raise ValueError('Native guest compilation not retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=('all', 'cold', 'native'))
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--source-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--maximum', type=int, default=1_000_000, choices=(10_000, 100_000, 1_000_000, 10_000_000))
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('Use the pinned Linux x86_64 artifacts on the dedicated host')
    os.sched_setaffinity(0, set(range(2, 10)))
    args.output.mkdir(parents=True, exist_ok=False)
    metadata = dict(schema=1, kind=args.kind, maximum=args.maximum,
                    captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    host=platform.node(), kernel=platform.release(), cpus=sorted(os.sched_getaffinity(0)),
                    load_start=os.getloadavg(), lscpu=subprocess.check_output(['lscpu'], text=True),
                    java_version=subprocess.check_output([str(args.java), '-version'], text=True, stderr=subprocess.STDOUT),
                    java_sha256=sha(args.java), jar_sha256=sha(args.jar), native_sha256=sha(args.native),
                    driver_sha256=sha(Path(__file__)), source_manifest=json.loads(args.source_manifest.read_text()),
                    commands=[], complete=False)
    def save():
        (args.output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    save()
    jobs = inventory(args.kind)
    random.Random(20261105).shuffle(jobs)
    results = []
    for index, job in enumerate(jobs):
        runtime, engine, workload, entry, mode, fork = job
        command = ([str(args.java), *FLAGS, '-jar', str(args.jar)] if runtime == 'jvm'
                   else [str(args.native), *NATIVE_FLAGS])
        command += [engine, workload, entry, mode, str(args.maximum)]
        name = '-'.join(map(str, job))
        metadata['commands'].append(command)
        save()
        print(f'{index + 1}/{len(jobs)} {name}', flush=True)
        logfile = args.output / (name + '.log')
        with logfile.open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        records = [json.loads(line) for line in logfile.read_text().splitlines() if line.startswith('{')]
        if len(records) != 1:
            raise ValueError('Missing result: ' + name)
        row = records[0]
        validate(row, job, args.maximum)
        row['fork'] = fork
        results.append(row)
        (args.output / 'results.partial.json').write_text(json.dumps(results, indent=2) + '\n')
    if sha(args.jar) != metadata['jar_sha256'] or sha(args.native) != metadata['native_sha256']:
        raise ValueError('Artifact changed during measurement')
    (args.output / 'results.partial.json').rename(args.output / 'results.json')
    metadata.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
