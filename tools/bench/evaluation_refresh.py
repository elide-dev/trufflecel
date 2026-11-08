#!/usr/bin/env python3
"""Matched six-fixture evaluation measurements; timing and fixed-work memory are separate processes."""

import argparse
import datetime
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import selectors
import subprocess

WORKLOADS = ('scalar', 'scalar-wide', 'proto-policy', 'proto-repeated', 'map-regex', 'optimizer-policy')
ENGINES = ('truffle-interpreter', 'truffle-jit', 'cel-java', 'cel-java-optimized')
JVM_FLAGS = ['-XX:-UseJVMCICompiler', '-Xms512m', '-Xmx512m', '--enable-native-access=ALL-UNNAMED']
NATIVE_FLAGS = ['-Xms512m', '-Xmx512m']


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def finite(values, count, zero=False):
    if len(values) != count or any(not math.isfinite(x) or x < 0 or (x == 0 and not zero) for x in values):
        raise ValueError('Invalid sample values')


def validate_jmh(row, engine, workload):
    if (row['benchmark'], row['params'], row['forks']) != (
            'dev.trufflecel.SteadyState.evaluate', dict(engine=engine, workload=workload), 3):
        raise ValueError('Incorrect JMH cell')
    data = row['primaryMetric']['rawData']
    if len(data) != 3:
        raise ValueError('Missing JMH forks')
    for fork in data:
        finite(fork, 5)


def validate_result(row, runtime, engine, workload, memory):
    if (row['runtime'], row['engine'], row['workload']) != (runtime, engine, workload):
        raise ValueError('Mislabeled process result')
    if memory:
        finite(row['allocated_bytes_per_op'], 7, zero=True)
        if (row['input_variants'], row['bindings_per_input'], row['operations_per_sample'], row['warmup_operations']) != (
                4, 1 if workload == 'scalar' else 69, 65536, 5 * 65536):
            raise ValueError('Incorrect fixed-work/input inventory')
        if row['allocation_calibration_bytes'] < 1024 * 1024:
            raise ValueError('Allocation calibration failed')
        if engine.startswith('truffle-'):
            jit = engine == 'truffle-jit'
            if row['guest_last_tier'] != jit or row['compilation_enabled'] != jit or row['background_compilation']:
                raise ValueError('Incorrect guest compilation mode')
            if row['compilation_failure_action'] != 'Throw' or row['compiler_options'].get('compiler.TreatPerformanceWarningsAsErrors') != 'all':
                raise ValueError('Compiler failure gates were relaxed')
    else:
        if row['mode'] != 'steady' or row['image_prepared']:
            raise ValueError('Incorrect native preparation mode')
        finite(row['warmup_ns_per_op'], 5)
        finite(row['ns_per_op'], 7)


def parse_record(stdout):
    records = [json.loads(line) for line in stdout.splitlines() if line.startswith('{')]
    if len(records) != 1:
        raise ValueError('Expected exactly one result record')
    return records[0]


def fixed_memory(command, directory):
    directory.mkdir()
    with (directory / 'stderr.log').open('w') as error:
        child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=error, text=True, bufsize=1)
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(child.stdout, selectors.EVENT_READ)
                if not selector.select(timeout=300):
                    raise TimeoutError('Memory checkpoint timed out')
                line = child.stdout.readline()
            row = parse_record(line)
            snapshots = {}
            for name in ('status', 'smaps_rollup'):
                value = (Path('/proc') / str(child.pid) / name).read_text()
                (directory / name).write_text(value)
                snapshots[name] = {key: int(number) * 1024 for key, number in
                                   re.findall(r'^(\w+):\s+(\d+) kB$', value, re.MULTILINE)}
            child.stdin.write('\n')
            child.stdin.flush()
            rest, _ = child.communicate(timeout=30)
            if child.returncode or rest.strip():
                raise RuntimeError('Memory process failed after checkpoint')
            (directory / 'stdout.json').write_text(line)
            return dict(**row, process_memory=snapshots)
        except BaseException:
            child.kill()
            child.wait()
            raise


def validate_inventory(rows, phase, engines=ENGINES, workloads=WORKLOADS):
    if phase == 'jvm-timing':
        expected = set(itertools.product(engines, workloads))
        keys = [(row['params']['engine'], row['params']['workload']) for row in rows]
        for row in rows:
            validate_jmh(row, row['params']['engine'], row['params']['workload'])
    else:
        runtimes = ('jvm', 'svm') if phase == 'memory' else ('svm',)
        expected = set(itertools.product(runtimes, engines, workloads, range(3)))
        keys = [tuple(row[k] for k in ('runtime', 'engine', 'workload', 'fork')) for row in rows]
        for row in rows:
            validate_result(row, row['runtime'], row['engine'], row['workload'], phase == 'memory')
    if len(keys) != len(set(keys)) or set(keys) != expected:
        raise ValueError('Incomplete, duplicate or unexpected measurement cells')


def main(*, engines=ENGINES, workloads=WORKLOADS, experiment='six-fixture evaluation', entrypoint=None,
         qualify=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('jvm-timing', 'native-timing', 'memory'))
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--jmh', type=Path, required=True)
    parser.add_argument('--memory-jar', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--memory-native', type=Path, required=True)
    parser.add_argument('--source-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpus', default='2,3,4,5,6,7,8,9')
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise ValueError('Pinned Linux x86_64 execution required')
    os.sched_setaffinity(0, set(map(int, args.cpus.split(','))))
    args.output.mkdir(parents=True, exist_ok=False)
    artifacts = {name: str(getattr(args, name).resolve()) for name in ('java', 'jmh', 'memory_jar', 'native', 'memory_native')}
    hashes = {name: sha(path) for name, path in artifacts.items()}
    java_home = Path(artifacts['java']).parent.parent
    cpuinfo = Path('/proc/cpuinfo').read_text()
    governors = {p.name + '/' + p.parent.name: p.read_text().strip() for p in
                 Path('/sys/devices/system/cpu/cpufreq').glob('policy*/scaling_governor')}
    metadata = dict(schema=1, phase=args.phase, captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    host=platform.node(), kernel=platform.release(), cpus=sorted(os.sched_getaffinity(0)),
                    load_start=os.getloadavg(), cpuinfo=cpuinfo, governors=governors,
                    lscpu=subprocess.check_output(['lscpu'], text=True),
                    java_version=subprocess.check_output([artifacts['java'], '-version'], stderr=subprocess.STDOUT, text=True),
                    vm_flags=subprocess.check_output([artifacts['java'], *JVM_FLAGS, '-XX:+PrintFlagsFinal', '-version'], stderr=subprocess.STDOUT, text=True),
                    source_manifest=json.loads(args.source_manifest.read_text()), artifacts=artifacts, sha256=hashes,
                    artifact_bytes={name: Path(path).stat().st_size for name, path in artifacts.items()},
                    source_manifest_sha256=sha(args.source_manifest),
                    runtime_sha256={name: sha(java_home / name) for name in ('release', 'lib/modules', 'lib/server/libjvm.so')},
                    driver_sha256=sha(__file__), jvm_flags=JVM_FLAGS, native_flags=NATIVE_FLAGS,
                    experiment=experiment, engines=engines, workloads=workloads,
                    entrypoint_sha256=sha(entrypoint or __file__),
                    fixed_work_jvm_extra_flags=['-Xbatch'], commands=[], complete=False,
                    limits='Observed low-load shared Linux host, fixed affinity; no isolated CPUs or global tuning. Timing, allocation and RSS are distinct observations. RSS includes setup, input preparation, counter calibration, warmup and fixed evaluation in a combined executable/JVM; not retained heap or standalone deployment size.')
    records = []
    meta = args.output / 'metadata.json'
    results = args.output / 'results.json'

    def save():
        meta.write_text(json.dumps(metadata, indent=2) + '\n')
        results.write_text(json.dumps(records, indent=2) + '\n')

    def command(argv):
        metadata['commands'].append(dict(argv=argv, load_before=os.getloadavg()))
        save()
        return argv

    save()
    if args.phase == 'jvm-timing':
        jobs = list(itertools.product(engines, workloads))
        random.Random(20261108).shuffle(jobs)
        for engine, workload in jobs:
            key = engine + '-' + workload
            target = args.output / (key + '.json')
            argv = [artifacts['java'], *JVM_FLAGS, '-jar', artifacts['jmh'], 'dev.trufflecel.SteadyState.evaluate',
                    '-p', 'engine=' + engine, '-p', 'workload=' + workload, '-f', '3', '-wi', '5', '-i', '5',
                    '-w', '1s', '-r', '1s', '-foe', 'true', '-rf', 'json', '-rff', str(target),
                    '-jvmArgsAppend', ' '.join(JVM_FLAGS)]
            with (args.output / (key + '.log')).open('w') as log:
                subprocess.run(command(argv), stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
            rows = json.loads(target.read_text())
            if len(rows) != 1:
                raise ValueError('Expected one JMH cell')
            validate_jmh(rows[0], engine, workload)
            records.append(rows[0]); save()
            print('completed', key, flush=True)
    else:
        memory = args.phase == 'memory'
        runtimes = ('jvm', 'svm') if memory else ('svm',)
        jobs = list(itertools.product(range(3), workloads, engines, runtimes))
        random.Random(20261108).shuffle(jobs)
        for fork, workload, engine, runtime in jobs:
            key = f'{runtime}-{engine}-{workload}-{fork}'
            if memory:
                prefix = ([artifacts['java'], *JVM_FLAGS, '-Xbatch', '-jar', artifacts['memory_jar']]
                          if runtime == 'jvm' else [artifacts['memory_native'], *NATIVE_FLAGS])
                row = fixed_memory(command([*prefix, engine, workload, 'measure']), args.output / key)
            else:
                argv = command([artifacts['native'], *NATIVE_FLAGS, engine, workload, 'steady'])
                with (args.output / (key + '.stderr.log')).open('w') as error:
                    value = subprocess.run(argv, stdout=subprocess.PIPE, stderr=error, text=True, timeout=300, check=True)
                (args.output / (key + '.json')).write_text(value.stdout)
                row = parse_record(value.stdout)
            validate_result(row, runtime, engine, workload, memory)
            row['fork'] = fork
            records.append(row); save()
            print('completed', key, flush=True)
    validate_inventory(records, args.phase, engines, workloads)
    if hashes != {name: sha(path) for name, path in artifacts.items()}:
        raise ValueError('Artifacts changed during measurement')
    if qualify is not None:
        qualify(args.output, metadata, records)
    metadata.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
