#!/usr/bin/env python3
"""Regenerate paired full-input cel-java control tables from immutable raw evidence."""
import argparse
import hashlib
import importlib
import json
from pathlib import Path
import statistics
import sys
import tarfile
import tempfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def stats(values):
    assert len(values) == 3
    return dict(median=statistics.median(values), minimum=min(values), maximum=max(values), forks=values)


def show(s):
    return f"{s['median']:.2f} [{s['minimum']:.2f}, {s['maximum']:.2f}]"


def analyze(path):
    with tarfile.open(path) as raw, tempfile.TemporaryDirectory() as scratch:
        def read(name):
            return raw.extractfile(name).read()
        source_bytes = read('source.json')
        source = json.loads(source_bytes)
        for name in ('evaluation_refresh', 'cel_java_inputs'):
            data = read(f'tools/{name}.py')
            assert sha(data) == source['file_sha256'][f'tools/bench/{name}.py']
            (Path(scratch) / (name + '.py')).write_bytes(data)
        sys.path.insert(0, scratch)
        driver = importlib.import_module('evaluation_refresh')
        control = importlib.import_module('cel_java_inputs')
        phases = {}
        metadata = {}
        for phase in ('memory', 'jvm-timing', 'native-timing'):
            meta = json.loads(read(f'{phase}/metadata.json'))
            rows = json.loads(read(f'{phase}/results.json'))
            assert meta['complete'] and meta['source_manifest'] == source
            assert meta['source_manifest_sha256'] == sha(source_bytes)
            assert meta['driver_sha256'] == sha(read('tools/evaluation_refresh.py'))
            assert meta['entrypoint_sha256'] == sha(read('tools/cel_java_inputs.py'))
            assert tuple(meta['engines']) == control.ENGINES
            assert tuple(meta['workloads']) == control.WORKLOADS
            driver.validate_inventory(rows, phase, control.ENGINES, control.WORKLOADS)
            metadata[phase] = meta
            phases[phase] = rows
        for key in ('sha256', 'runtime_sha256', 'jvm_flags', 'native_flags'):
            assert all(metadata[p][key] == metadata['memory'][key] for p in metadata)
        artifacts = dict(line.split()[::-1] for line in read('artifact-sha256.txt').decode().splitlines())
        for key, name in {'jmh': 'jmh.jar', 'memory_jar': 'memory.jar',
                          'native': 'timing-native', 'memory_native': 'memory-native'}.items():
            assert metadata['memory']['sha256'][key] == artifacts[name]
        sys.path.pop(0)
    times = {}
    drifts = {}
    for row in phases['jvm-timing']:
        assert row['primaryMetric']['scoreUnit'] == 'ns/op'
        key = ('jvm', row['params']['engine'], row['params']['workload'])
        times[key] = stats([statistics.mean(fork) for fork in row['primaryMetric']['rawData']])
        drifts[key] = [abs(statistics.mean(fork[:2]) - statistics.mean(fork[-2:])) / statistics.mean(fork)
                       for fork in row['primaryMetric']['rawData']]
    for runtime in ('svm',):
        for engine in control.ENGINES:
            for workload in control.WORKLOADS:
                rows = sorted((r for r in phases['native-timing'] if (r['runtime'], r['engine'], r['workload'])
                               == (runtime, engine, workload)), key=lambda r: r['fork'])
                times[(runtime, engine, workload)] = stats([statistics.mean(r['ns_per_op']) for r in rows])
                drifts[(runtime, engine, workload)] = [
                    abs(statistics.mean(r['ns_per_op'][:2]) - statistics.mean(r['ns_per_op'][-2:]))
                    / statistics.mean(r['ns_per_op']) for r in rows]
    memory = {}
    for runtime in ('jvm', 'svm'):
        for engine in control.ENGINES:
            for workload in control.WORKLOADS:
                rows = sorted((r for r in phases['memory'] if (r['runtime'], r['engine'], r['workload'])
                               == (runtime, engine, workload)), key=lambda r: r['fork'])
                memory[(runtime, engine, workload)] = stats([statistics.mean(r['allocated_bytes_per_op']) for r in rows])
    result = dict(raw_sha256=sha(path.read_bytes()), source_manifest_sha256=sha(source_bytes),
                  source_commit=source['commit'], production_revision=source['production_revision'],
                  process_count=96, statistic='median [minimum, maximum] of three independent fork means',
                  drift_screen='absolute difference of first-two and last-two measurement means, divided by whole-fork mean',
                  maximum_relative_measurement_drift=max(v for values in drifts.values() for v in values),
                  timing_unit='ns/op', allocation_unit='B/op', rows=[])
    for runtime in ('jvm', 'svm'):
        for workload in control.WORKLOADS:
            for optimizer in ('default', 'optimized'):
                base = 'cel-java' + ('-optimized' if optimizer == 'optimized' else '')
                ordinary = (runtime, base + '-prepared-map', workload)
                immutable = (runtime, base + '-immutable', workload)
                result['rows'].append(dict(runtime=runtime, workload=workload, optimizer=optimizer,
                    ordinary_ns=times[ordinary], immutable_ns=times[immutable],
                    ordinary_bytes=memory[ordinary], immutable_bytes=memory[immutable],
                    ordinary_relative_measurement_drift=drifts[ordinary],
                    immutable_relative_measurement_drift=drifts[immutable],
                    speedup=times[ordinary]['median'] / times[immutable]['median'],
                    allocation_reduction_bytes=memory[ordinary]['median'] - memory[immutable]['median']))
    return result


def tables(result):
    lines = ['# cel-java full-input representation controls', '',
             'Three independent fork means per value: median [minimum, maximum]. Ranges are not confidence intervals.',
             'All cells use four full 69-binding inputs and the same captured-array evaluation path.',
             'Caller-side immutable-map preparation is excluded. Timing and allocation use separate processes.', '',
             '| Runtime | Workload | Optimizer | Ordinary map ns/op | Immutable map ns/op | Ordinary B/op | Immutable B/op |',
             '|---|---|---|---:|---:|---:|---:|']
    for row in result['rows']:
        lines.append(f"| {row['runtime']} | {row['workload']} | {row['optimizer']} | "
                     + ' | '.join(show(row[key]) for key in ('ordinary_ns', 'immutable_ns', 'ordinary_bytes', 'immutable_bytes')) + ' |')
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = analyze(Path(__file__).with_name('raw.tar.gz'))
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    (args.output / 'tables.md').write_text(tables(result))
    print('Validated 24 JVM timing forks, 24 native timing forks and 48 allocation processes')


if __name__ == '__main__':
    main()
