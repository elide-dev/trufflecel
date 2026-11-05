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
ACTIVATION_FOCUSED = ('scalar-wide', 'proto-policy', 'map-regex')
FLAGS = ['-XX:-UseJVMCICompiler', '-Xms512m', '-Xmx512m', '--enable-native-access=ALL-UNNAMED']
NATIVE_FLAGS = ['-Xms512m', '-Xmx512m']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_activation(rows, benchmark, workloads=ACTIVATION_FOCUSED):
    if not workloads or len(set(workloads)) != len(workloads) or not set(workloads) <= set(WORKLOADS):
        raise ValueError('Invalid activation workload selection')
    if benchmark == 'ActivationPreparation':
        expected = set(itertools.product(('copyBindings', 'normalizeValues', 'snapshot'), ('scalar', 'scalar-wide'), (None,)))
    elif benchmark == 'ActivationEvaluation':
        expected = set(itertools.product(('evaluateMap', 'evaluateSnapshot', 'snapshotAndEvaluate'),
                                        workloads, ENGINES[:2]))
    else:
        raise ValueError('Unknown activation benchmark')
    seen = set()
    for row in rows:
        key = (row['benchmark'].rsplit('.', 1)[1], row['params']['workload'], row['params'].get('engine'))
        if row['benchmark'] != 'dev.trufflecel.' + benchmark + '.' + key[0] or key not in expected or key in seen:
            raise ValueError('Unexpected or duplicate activation cell: ' + str(key))
        seen.add(key)
        metrics = [row['primaryMetric'], row['secondaryMetrics']['gc.alloc.rate.norm']]
        for metric in metrics:
            raw = metric['rawData']
            if row['forks'] != 3 or len(raw) != 3 or any(len(fork) != 5 for fork in raw):
                raise ValueError('Missing activation forks/samples')
            if any(not 0 <= value < float('inf') for fork in raw for value in fork):
                raise ValueError('Invalid activation measurement')
    if seen != expected:
        raise ValueError('Missing activation cells: ' + str(expected - seen))


def validate_jmh(rows):
    expected = set(itertools.product(('compile', 'evaluate'), ENGINES, WORKLOADS))
    stages = set(itertools.product(('parse', 'check', 'optimize', 'plan'), ENGINES[-2:], ('true', 'x * 2 + 1')))
    seen = set()
    stage_seen = set()
    for row in rows:
        frontend = '.FrontendStages.' in row['benchmark']
        key = (row['benchmark'].rsplit('.', 1)[1], row['params']['engine'], row['params']['source' if frontend else 'workload'])
        inventory, observed = (stages, stage_seen) if frontend else (expected, seen)
        if key not in inventory or key in observed:
            raise ValueError('Unexpected or duplicate JMH cell: ' + str(key))
        observed.add(key)
        raw = row['primaryMetric']['rawData']
        if row['forks'] != 3 or len(raw) != 3 or any(len(fork) != 5 for fork in raw):
            raise ValueError('Missing JMH forks/samples')
        if any(not 0 < value < float('inf') for fork in raw for value in fork):
            raise ValueError('Invalid timing')
    if seen != expected:
        raise ValueError('Missing JMH cells: ' + str(expected - seen))
    if stage_seen != stages:
        raise ValueError('Missing frontend stages: ' + str(stages - stage_seen))


def validate_lifecycle(row, runtime, engine, workload, mode):
    if (row['runtime'], row['engine'], row['workload'], row['mode']) != (runtime, engine, workload, mode):
        raise ValueError('Mislabeled lifecycle result')
    if any(type(row[k]) is not int or row[k] < 0 for k in ('configure_ns', 'prepare_ns', 'plan_ns')):
        raise ValueError('Invalid phase timings')
    if mode.endswith('cold'):
        points = row['total_ns_through_evaluations']
        if list(points) != ['1', '10', '100', '1000', '10000']:
            raise ValueError('Missing cold checkpoints')
        times = list(points.values())
        if any(type(v) is not int or v <= 0 for v in times) or any(b <= a for a, b in zip(times, times[1:])):
            raise ValueError('Invalid cumulative timings')
    else:
        for field, count in (('warmup_ns_per_op', 5), ('ns_per_op', 7)):
            if len(row[field]) != count or any(not 0 < n < float('inf') for n in row[field]):
                raise ValueError('Invalid native samples')


def validate_lifecycle_results(rows, native=True):
    runtimes = ('jvm', 'svm') if native else ('jvm',)
    expected = {
        (runtime, engine, workload, mode, fork)
        for runtime, engine, workload, fork in itertools.product(runtimes, ENGINES, WORKLOADS, range(3))
        for mode in (('cold',) if runtime == 'jvm' else ('cold', 'image-cold', 'steady', 'image-steady'))
    }
    expected.update((runtime, engine, 'proto-policy', 'ingest', fork)
                    for runtime, engine, fork in itertools.product(runtimes, ('truffle-interpreter', 'cel-java'), range(3)))
    seen = set()
    for row in rows:
        key = tuple(row[k] for k in ('runtime', 'engine', 'workload', 'mode', 'fork'))
        if key not in expected or key in seen:
            raise ValueError('Unexpected or duplicate lifecycle cell: ' + str(key))
        validate_lifecycle(row, *key[:4])
        seen.add(key)
    if seen != expected:
        raise ValueError('Missing lifecycle cells: ' + str(expected - seen))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=['jmh', 'activation', 'lifecycle'])
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--native', type=Path)
    parser.add_argument('--cpus', default='2,3,4,5,6,7,8,9')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source-manifest', type=Path, required=True)
    parser.add_argument('--activation-workloads', help='Comma-separated activation workloads; defaults to all six')
    args = parser.parse_args()
    if args.activation_workloads is not None and args.kind != 'activation':
        parser.error('--activation-workloads requires activation mode')
    activation_workloads = tuple(args.activation_workloads.split(',')) if args.activation_workloads is not None else WORKLOADS
    if not activation_workloads or len(set(activation_workloads)) != len(activation_workloads) or not set(activation_workloads) <= set(WORKLOADS):
        parser.error('Select unique known activation workloads')
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
        'native_flags': NATIVE_FLAGS if args.native else None,
        'source_manifest': json.loads(args.source_manifest.read_text()),
        'commands': [], 'complete': False,
    }
    if args.kind == 'activation':
        metadata['activation_workloads'] = activation_workloads
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
        run([str(args.java), '-jar', str(args.jar), 'dev.trufflecel.(SteadyState|FrontendStages).*', '-foe', 'true',
             '-rf', 'json', '-rff', str(output), '-jvmArgsAppend', ' '.join(FLAGS)],
            args.output / 'jmh.log', 10800)
        validate_jmh(json.loads(output.read_text()))
    elif args.kind == 'activation':
        for benchmark, workloads in (
                ('ActivationPreparation', 'scalar,scalar-wide'),
                ('ActivationEvaluation', ','.join(activation_workloads))):
            output = args.output / (benchmark + '.json')
            run([str(args.java), '-jar', str(args.jar), 'dev.trufflecel.' + benchmark + '.*',
                 '-p', 'workload=' + workloads, '-foe', 'true', '-prof', 'gc',
                 '-rf', 'json', '-rff', str(output), '-jvmArgsAppend', ' '.join(FLAGS)],
                args.output / (benchmark + '.log'), 10800)
            validate_activation(json.loads(output.read_text()), benchmark, activation_workloads)
    else:
        jobs = list(itertools.product(('jvm', 'svm') if args.native else ('jvm',), ENGINES, WORKLOADS, range(3)))
        random.Random(20261105).shuffle(jobs)
        results = []
        for runtime, engine, workload, fork in jobs:
            modes = ('cold',) if runtime == 'jvm' else ('cold', 'image-cold', 'steady', 'image-steady')
            for mode in modes:
                command = ([str(args.java), *FLAGS, '-jar', str(args.jar)] if runtime == 'jvm' else [str(args.native), *NATIVE_FLAGS])
                name = f'{runtime}-{engine}-{workload}-{mode}-{fork}'
                logfile = args.output / (name + '.log')
                run(command + [engine, workload, mode], logfile, 300)
                records = [json.loads(line) for line in logfile.read_text().splitlines() if line.startswith('{')]
                if len(records) != 1:
                    raise ValueError('Missing result: ' + name)
                row = records[0]
                validate_lifecycle(row, runtime, engine, workload, mode)
                row['fork'] = fork
                results.append(row)
                (args.output / 'results.partial.json').write_text(json.dumps(results, indent=2) + '\n')
        for runtime, engine, fork in itertools.product(('jvm', 'svm') if args.native else ('jvm',), ('truffle-interpreter', 'cel-java'), range(3)):
            command = ([str(args.java), *FLAGS, '-jar', str(args.jar)] if runtime == 'jvm' else [str(args.native), *NATIVE_FLAGS])
            logfile = args.output / f'{runtime}-{engine}-ingest-{fork}.log'
            run(command + [engine, 'proto-policy', 'ingest'], logfile, 300)
            records = [json.loads(line) for line in logfile.read_text().splitlines() if line.startswith('{')]
            if len(records) != 1:
                raise ValueError('Missing ingestion result')
            row = records[0]
            validate_lifecycle(row, runtime, engine, 'proto-policy', 'ingest')
            row['fork'] = fork
            results.append(row)
            (args.output / 'results.partial.json').write_text(json.dumps(results, indent=2) + '\n')
        validate_lifecycle_results(results, bool(args.native))
        (args.output / 'results.partial.json').rename(args.output / 'results.json')
    if sha(args.jar) != metadata['jar_sha256'] or (args.native and sha(args.native) != metadata['native_sha256']):
        raise ValueError('Artifact changed during measurement')
    metadata.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
