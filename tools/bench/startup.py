#!/usr/bin/env python3
"""Measure launch through a flushed, verified first result in fresh processes."""
import argparse
import datetime
import itertools
import json
import os
from pathlib import Path
import platform
import random
import selectors
import signal
import subprocess
import time

from representative import FLAGS, NATIVE_FLAGS, WORKLOADS, sha

ENGINES = ('truffle-interpreter', 'truffle-jit', 'cel-java-optimized')
READY = b'TRUFFLECEL_STARTUP_READY'
PHASES = ('fixture_and_wire_ns', 'adapter_ns', 'decode_ns', 'configure_ns',
          'prepare_ns', 'plan_ns', 'evaluate_and_verify_ns')


def observe(command, stdout_path, stderr_path, timeout):
    """The ready clock excludes JSON reporting/context cleanup; exit has its own clock."""
    with stderr_path.open('wb') as stderr:
        start = time.perf_counter_ns()
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=stderr,
                                   stdin=subprocess.DEVNULL, start_new_session=True)
        output = bytearray()
        pending = bytearray()
        ready = []
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while selector.get_map():
                    remaining = timeout - (time.perf_counter_ns() - start) / 1e9
                    if remaining <= 0:
                        raise TimeoutError('Startup process exceeded timeout')
                    for key, _ in selector.select(remaining):
                        data = os.read(key.fd, 65536)
                        if not data:
                            selector.unregister(key.fileobj)
                            continue
                        output.extend(data)
                        if len(output) > 1024 * 1024:
                            raise ValueError('Startup process output exceeds limit')
                        pending.extend(data)
                        while b'\n' in pending:
                            line, _, rest = pending.partition(b'\n')
                            pending[:] = rest
                            if line.rstrip(b'\r') == READY:
                                ready.append(time.perf_counter_ns() - start)
                process.wait(timeout=max(0.001, timeout - (time.perf_counter_ns() - start) / 1e9))
            exited = time.perf_counter_ns() - start
            if process.returncode != 0:
                raise ValueError(f'Startup process failed: exit {process.returncode}')
            if len(ready) != 1:
                raise ValueError('Expected exactly one verified-result marker')
            records = [json.loads(line) for line in output.decode().splitlines() if line.startswith('{')]
            if len(records) != 1 or records[0].get('verified') is not True:
                raise ValueError('Expected exactly one verified result record')
            return dict(record=records[0], launch_to_verified_ns=ready[0], launch_to_exit_ns=exited)
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            process.stdout.close()
            stdout_path.write_bytes(output)


def validate(row):
    if not (type(row['launch_to_verified_ns']) is int and
            type(row['launch_to_exit_ns']) is int and
            0 < row['launch_to_verified_ns'] <= row['launch_to_exit_ns']):
        raise ValueError('Invalid external startup timing')
    record = row['record']
    if record.get('verified') is not True or type(record.get('control')) is not bool:
        raise ValueError('Unverified startup record')
    if row['engine'] == 'control':
        if record != {'control': True, 'verified': True}:
            raise ValueError('Control initialized an engine or returned extra data')
        return
    labels = ('runtime', 'engine', 'workload', 'mode', 'input_index')
    if record['control'] or any(record.get(k) != row[k] for k in labels):
        raise ValueError('Mislabeled startup record')
    if record.get('image_prepared') != (row['mode'] == 'image'):
        raise ValueError('Image preparation differs from requested mode')
    if row['runtime'] == 'jvm' and row['mode'] == 'image':
        raise ValueError('JVM cannot use image-prepared ASTs')
    if type(record.get('guest_compiled_at_first_result')) is not bool:
        raise ValueError('Missing first-result compilation state')
    if row['engine'] != 'truffle-jit' and record['guest_compiled_at_first_result']:
        raise ValueError('Unexpected guest compilation')
    if any(type(record.get(k)) is not int or record[k] < 0 for k in PHASES):
        raise ValueError('Invalid phase timing')
    if sum(record[k] for k in PHASES) != record.get('main_to_verified_ns'):
        raise ValueError('Startup phases do not partition the internal interval')
    if record['main_to_verified_ns'] > row['launch_to_verified_ns']:
        raise ValueError('Internal interval exceeds launch-to-result observation')


def inventory(native, forks):
    cells = [('jvm', 'control', 'control', 'control')]
    cells += [('jvm', e, w, 'runtime') for e, w in itertools.product(ENGINES, WORKLOADS)]
    if native:
        cells += [('svm', 'control', 'control', mode) for mode in ('runtime', 'image')]
        cells += [('svm', e, w, mode) for e, w, mode in itertools.product(ENGINES, WORKLOADS, ('runtime', 'image'))]
    return {(runtime, engine, workload, mode, fork) for runtime, engine, workload, mode in cells for fork in range(forks)}


def validate_results(rows, native, forks):
    expected = inventory(native, forks)
    seen = set()
    for row in rows:
        key = tuple(row[k] for k in ('runtime', 'engine', 'workload', 'mode', 'fork'))
        if key not in expected or key in seen:
            raise ValueError('Unexpected or repeated startup cell')
        if row['input_index'] != row['fork'] % 4:
            raise ValueError('Input variant differs from the paired fork schedule')
        validate(row)
        seen.add(key)
    if seen != expected:
        raise ValueError('Incomplete startup matrix')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--native', type=Path, help='Runtime-prepared native artifact')
    parser.add_argument('--native-prepared', type=Path, help='Separate image-prepared native artifact')
    parser.add_argument('--source-manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpus', required=True)
    parser.add_argument('--forks', type=int, default=5)
    parser.add_argument('--host-conditions', required=True, help='Describe reservation, background load and thermal conditions')
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('Use pinned Linux x86_64 GraalVM artifacts on the measurement host')
    if bool(args.native) != bool(args.native_prepared):
        parser.error('Supply both native artifacts, or omit both for a JVM-only run')
    if args.forks < 3:
        parser.error('At least three independent processes per cell are required')
    os.sched_setaffinity(0, set(map(int, args.cpus.split(','))))
    args.output.mkdir(parents=True, exist_ok=False)
    def capture(command):
        return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)
    java_version = capture([str(args.java), '-version'])
    if 'GraalVM' not in java_version:
        raise ValueError('Use the pinned GraalVM distribution, not the system JDK')
    java_home = args.java.resolve().parent.parent
    runtime_files = ('release', 'lib/modules', 'lib/server/libjvm.so')
    runtime_sha256 = {name: sha(java_home / name) for name in runtime_files}
    metadata = dict(schema=1, complete=False, captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    host=platform.node(), kernel=platform.release(), cpuinfo=Path('/proc/cpuinfo').read_text(),
                    cpus=sorted(os.sched_getaffinity(0)), host_conditions=args.host_conditions,
                    load_start=os.getloadavg(), java_version=java_version,
                    vm_flags=capture([str(args.java), *FLAGS, '-XX:+PrintFlagsFinal', '-version']),
                    java_sha256=sha(args.java), java_home_release=(java_home / 'release').read_text(),
                    runtime_sha256=runtime_sha256, jar_sha256=sha(args.jar),
                    driver_sha256=sha(Path(__file__)), shared_driver_sha256=sha(Path(__file__).with_name('representative.py')),
                    source_manifest_sha256=sha(args.source_manifest),
                    native_sha256=sha(args.native) if args.native else None,
                    native_prepared_sha256=sha(args.native_prepared) if args.native_prepared else None,
                    source_manifest=json.loads(args.source_manifest.read_text()),
                    jvm_flags=FLAGS, native_flags=NATIVE_FLAGS, forks=args.forks,
                    clock='parent monotonic perf_counter_ns before Popen through receipt of flushed verified-result marker',
                    control='same entry point, no engine/fixture initialization; reported separately, never subtracted',
                    commands=[], results=[])
    path = args.output / 'startup.json'
    def save():
        path.write_text(json.dumps(metadata, indent=2) + '\n')
    save()
    jobs = sorted(inventory(bool(args.native), args.forks))
    random.Random(20261108).shuffle(jobs)
    for runtime, engine, workload, mode, fork in jobs:
        base = ([str(args.java), *FLAGS, '-jar', str(args.jar)] if runtime == 'jvm'
                else [str(args.native_prepared if mode == 'image' else args.native), *NATIVE_FLAGS])
        index = fork % 4
        command = base + (['control'] if engine == 'control' else [engine, workload, mode, str(index)])
        name = '-'.join(map(str, (runtime, engine, workload, mode, fork)))
        metadata['commands'].append(command)
        save()
        row = observe(command, args.output / (name + '.stdout'), args.output / (name + '.stderr'), 180)
        row.update(runtime=runtime, engine=engine, workload=workload, mode=mode, fork=fork, input_index=index)
        validate(row)
        metadata['results'].append(row)
        save()
    validate_results(metadata['results'], bool(args.native), args.forks)
    metadata.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
