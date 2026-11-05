#!/usr/bin/env python3
"""Paired structured-data diagnostics; both artifacts must contain the same benchmark driver."""
import argparse
import datetime
import json
import math
import os
from pathlib import Path
import platform
import subprocess

from representative import FLAGS, sha


def validate(rows, methods, lengths, selected, engine):
    expected = {(method, str(length), str(percent), engine)
                for method in methods for length in lengths for percent in selected}
    seen = set()
    for row in rows:
        if not row['benchmark'].startswith('dev.trufflecel.StructuredAccess.'):
            raise ValueError('Unexpected diagnostic benchmark')
        key = (row['benchmark'].rsplit('.', 1)[-1], row['params']['length'],
               row['params']['selectedPercent'], row['params']['engine'])
        if key not in expected or key in seen:
            raise ValueError('Unexpected or duplicate diagnostic cell: ' + str(key))
        seen.add(key)
        for metric in (row['primaryMetric'], row['secondaryMetrics']['gc.alloc.rate.norm']):
            samples = metric['rawData']
            if len(samples) != 3 or any(len(fork) != 5 for fork in samples):
                raise ValueError('Missing diagnostic forks or measurements')
            if any(not math.isfinite(value) or value < 0 for fork in samples for value in fork):
                raise ValueError('Invalid diagnostic sample')
        if any(value == 0 for fork in row['primaryMetric']['rawData'] for value in fork):
            raise ValueError('Invalid zero evaluation time')
    if seen != expected:
        raise ValueError('Missing diagnostic cells: ' + str(expected - seen))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--reference-jar', type=Path, required=True)
    parser.add_argument('--candidate-jar', type=Path, required=True)
    parser.add_argument('--reference-source', type=Path, required=True)
    parser.add_argument('--candidate-source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('Use the pinned Linux x86_64 toolchain on the dedicated host')
    os.sched_setaffinity(0, set(range(2, 10)))
    args.output.mkdir(parents=True, exist_ok=False)
    artifacts = {'reference': args.reference_jar, 'candidate': args.candidate_jar}
    sources = {'reference': json.loads(args.reference_source.read_text()),
               'candidate': json.loads(args.candidate_source.read_text())}
    for name in ('Engines.java', 'Workloads.java', 'StructuredAccess.java', 'BUILD.bazel'):
        path = 'benchmarks/representative/' + name
        before = sources['reference']['files'].get(path)
        after = sources['candidate']['files'].get(path)
        if before is None or before != after:
            raise ValueError('Diagnostic harness sources differ: ' + path)
    metadata = {
        'schema': 1, 'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'host': platform.node(), 'kernel': platform.release(),
        'cpus': sorted(os.sched_getaffinity(0)), 'load_start': os.getloadavg(),
        'lscpu': subprocess.check_output(['lscpu'], text=True),
        'java_version': subprocess.check_output([str(args.java), '-version'], text=True,
                                                stderr=subprocess.STDOUT),
        'java_sha256': sha(args.java),
        'jar_sha256': {key: sha(path) for key, path in artifacts.items()},
        'source': sources,
        'commands': [], 'complete': False,
    }
    output = args.output / 'metadata.json'

    def save():
        output.write_text(json.dumps(metadata, indent=2) + '\n')

    save()
    scenarios = [
        ('scaling', ['evaluate'], [0, 16, 128, 1024], [50], 'truffle-jit'),
        ('selectivity', ['evaluate'], [128], [0, 100], 'truffle-jit'),
        ('preparation', ['prepare', 'instantiate'], [128], [50], 'truffle-interpreter'),
    ]
    for index, (label, methods, lengths, selected, engine) in enumerate(scenarios):
        # Alternate artifact order across scenarios; do not overlap timed processes.
        order = ['reference', 'candidate'] if index % 2 == 0 else ['candidate', 'reference']
        for artifact in order:
            stem = artifact + '-' + label
            result = args.output / (stem + '.json')
            command = [str(args.java), '-jar', str(artifacts[artifact]),
                       'dev.trufflecel.StructuredAccess.(' + '|'.join(methods) + ')',
                       '-p', 'engine=' + engine, '-p', 'length=' + ','.join(map(str, lengths)),
                       '-p', 'selectedPercent=' + ','.join(map(str, selected)),
                       '-f', '3', '-wi', '5', '-i', '5', '-w', '1s', '-r', '1s',
                       '-foe', 'true', '-prof', 'gc', '-rf', 'json', '-rff', str(result),
                       '-jvmArgsAppend', ' '.join(FLAGS)]
            metadata['commands'].append(command)
            save()
            print('Running ' + stem, flush=True)
            with (args.output / (stem + '.log')).open('w') as log:
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800)
            validate(json.loads(result.read_text()), methods, lengths, selected, engine)
    metadata['complete'] = True
    metadata['load_end'] = os.getloadavg()
    save()


if __name__ == '__main__':
    main()
