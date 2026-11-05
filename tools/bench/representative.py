#!/usr/bin/env python3
"""Measure prebuilt artifacts on a quiet Linux host; retain commands, host data and raw results."""
import argparse
import datetime
import hashlib
import itertools
import json
import os
from pathlib import Path
import platform
import random
import subprocess

WORKLOADS = ('scalar', 'scalar-wide', 'proto-policy', 'proto-repeated', 'map-regex', 'optimizer-policy')
ENGINES = ('truffle-interpreter', 'truffle-jit', 'cel-java', 'cel-java-optimized', 'cel-java-pratt-optimized')
FLAGS = ['-XX:-UseJVMCICompiler', '-Xms512m', '-Xmx512m', '--enable-native-access=ALL-UNNAMED']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_jmh(rows):
    expected = set(itertools.product(('compile', 'evaluate'), ENGINES, WORKLOADS))
    seen = set()
    for row in rows:
        if '.SteadyState.' not in row['benchmark']:
            continue
        key = (row['benchmark'].rsplit('.', 1)[1], row['params']['engine'], row['params']['workload'])
        if key not in expected or key in seen:
            raise ValueError('Unexpected or duplicate JMH cell: ' + str(key))
        seen.add(key)
        raw = row['primaryMetric']['rawData']
        if row['forks'] != 3 or len(raw) != 3 or any(len(fork) != 5 for fork in raw):
            raise ValueError('Missing JMH forks/samples')
        if any(not 0 < value < float('inf') for fork in raw for value in fork):
            raise ValueError('Invalid timing')
    if seen != expected:
        raise ValueError('Missing JMH cells: ' + str(expected - seen))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=['jmh', 'lifecycle'])
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--native', type=Path)
    parser.add_argument('--cpus', default='2,3,4,5,6,7,8,9')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source-manifest', type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise ValueError('Use the pinned Linux x86_64 toolchain')
    cpus = set(map(int, args.cpus.split(',')))
    os.sched_setaffinity(0, cpus)
    args.output.mkdir(parents=True, exist_ok=False)
    def capture(command):
        return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)
    metadata = {
        'schema': 1, 'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'host': platform.node(), 'kernel': platform.release(), 'cpus': sorted(os.sched_getaffinity(0)),
        'cpuinfo': Path('/proc/cpuinfo').read_text(), 'load_start': os.getloadavg(),
        'lscpu': capture(['lscpu']), 'java_version': capture([str(args.java), '-version']),
        'vm_flags': capture([str(args.java), *FLAGS, '-XX:+PrintFlagsFinal', '-version']),
        'java_sha256': sha(args.java), 'jar_sha256': sha(args.jar),
        'native_sha256': sha(args.native) if args.native else None,
        'source_manifest': json.loads(args.source_manifest.read_text()),
        'commands': [], 'complete': False,
    }
    meta = args.output / 'metadata.json'
    def save():
        meta.write_text(json.dumps(metadata, indent=2) + '\n')
    def run(command, logfile, timeout):
        metadata['commands'].append(command); save()
        print('Running ' + logfile.name, flush=True)
        with logfile.open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=timeout)
    save()
    if args.kind == 'jmh':
        output = args.output / 'jmh.json'
        run([str(args.java), '-jar', str(args.jar), 'dev.trufflecel.*', '-foe', 'true',
             '-rf', 'json', '-rff', str(output), '-jvmArgsAppend', ' '.join(FLAGS)],
            args.output / 'jmh.log', 10800)
        validate_jmh(json.loads(output.read_text()))
    else:
        jobs = list(itertools.product(('jvm', 'svm') if args.native else ('jvm',), ENGINES, WORKLOADS, range(3)))
        random.Random(20261105).shuffle(jobs)
        results = []
        for runtime, engine, workload, fork in jobs:
            modes = ('cold',) if runtime == 'jvm' else ('cold', 'image-cold', 'steady', 'image-steady')
            for mode in modes:
                command = ([str(args.java), *FLAGS, '-jar', str(args.jar)] if runtime == 'jvm' else [str(args.native)])
                name = f'{runtime}-{engine}-{workload}-{mode}-{fork}'
                logfile = args.output / (name + '.log')
                run(command + [engine, workload, mode], logfile, 300)
                records = [json.loads(line) for line in logfile.read_text().splitlines() if line.startswith('{')]
                if len(records) != 1:
                    raise ValueError('Missing result: ' + name)
                row = records[0]
                if (row['runtime'], row['engine'], row['workload'], row['mode']) != (runtime, engine, workload, mode):
                    raise ValueError('Mislabeled result: ' + name)
                row['fork'] = fork
                results.append(row)
                (args.output / 'results.partial.json').write_text(json.dumps(results, indent=2) + '\n')
        (args.output / 'results.partial.json').rename(args.output / 'results.json')
    if sha(args.jar) != metadata['jar_sha256'] or (args.native and sha(args.native) != metadata['native_sha256']):
        raise ValueError('Artifact changed during measurement')
    metadata.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
