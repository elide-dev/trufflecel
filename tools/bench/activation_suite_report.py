#!/usr/bin/env python3
"""Combine disjoint runs with matching artifacts, JVM arguments, host identity and affinity."""
import argparse
import json
from pathlib import Path

from activation_report import summarize
from representative import ENGINES, WORKLOADS, validate_activation


def combine(directories):
    runs, cells, raw = [], [], []
    identity = None
    for directory in directories:
        meta = json.loads((directory / 'metadata.json').read_text())
        settings = {key: meta[key] for key in (
            'jar_sha256', 'java_sha256', 'java_version', 'host', 'kernel', 'cpus')}
        if identity is None:
            identity = settings
        elif settings != identity:
            raise ValueError('Cannot combine different benchmark artifacts, hosts, kernels or CPU affinities')
        summary = summarize(directory)
        rows = json.loads((directory / 'ActivationEvaluation.json').read_text())
        # Require the same effective fork arguments, not just a matching JVM executable.
        arguments = rows[0]['jvmArgs']
        if any(row['jvmArgs'] != arguments for row in rows):
            raise ValueError('Mixed JVM arguments')
        if runs and arguments != runs[0]['jvm_args']:
            raise ValueError('JVM arguments differ across runs')
        runs.append(dict(directory=directory.name, source=summary['source'], jvm_args=arguments,
                         captured_at=meta['captured_at'],
                         measurement_drift_over_10_percent=summary['measurement_drift_over_10_percent']))
        raw.extend(rows)
        cells.extend(dict(row, run=directory.name) for row in summary['cells']
                     if row['benchmark'] == 'ActivationEvaluation')
    # Disjoint partial runs must cover the entire matrix exactly once.
    validate_activation(raw, 'ActivationEvaluation', WORKLOADS)
    return dict(identity=identity, runs=runs, cells=cells)


def tables(summary):
    rows = {(r['engine'], r['workload'], r['method']): r for r in summary['cells']}
    lines = ['# Snapshot reuse across all six workloads', '',
             'These disjoint runs use the same benchmark JAR, JVM arguments, host, kernel and CPU affinity.',
             'Values are medians of three process means; ranges and raw samples remain in each run.',
             'Preparation is excluded only from reused-snapshot evaluation. No ingestion is timed.', '',
             '## Time', '',
             '| Runtime | Workload | Map (ns/op) | Reused snapshot (ns/op) | New snapshot + evaluation (ns/op) |',
             '| --- | --- | ---: | ---: | ---: |']
    methods = ('evaluateMap', 'evaluateSnapshot', 'snapshotAndEvaluate')
    for engine in ENGINES[:2]:
        for workload in WORKLOADS:
            values = [rows[engine, workload, method]['ns_per_op']['median'] for method in methods]
            lines.append(f'| {engine} | {workload} | ' + ' | '.join(f'{v:,.1f}' for v in values) + ' |')
    lines.extend(['', '## Allocated Java heap', '',
                  '| Runtime | Workload | Map (bytes/op) | Reused snapshot (bytes/op) | New snapshot + evaluation (bytes/op) |',
                  '| --- | --- | ---: | ---: | ---: |'])
    for engine in ENGINES[:2]:
        for workload in WORKLOADS:
            values = [rows[engine, workload, method]['bytes_per_op']['median'] for method in methods]
            lines.append(f'| {engine} | {workload} | ' + ' | '.join(f'{v:,.1f}' for v in values) + ' |')
    lines.extend(['', 'Allocation is measured with JMH’s GC profiler; it is not RSS or native memory.', '',
                  '## Runs', ''])
    for run in summary['runs']:
        workloads = sorted({r['workload'] for r in summary['cells'] if r['run'] == run['directory']})
        lines.append(f"- `{run['directory']}`: {', '.join(workloads)}.")
        if run['measurement_drift_over_10_percent']:
            lines.append('  See the run’s warmup audit for cells exceeding the 10% measurement-drift check.')
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    summary = combine(args.directories)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'suite-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (args.output / 'suite-tables.md').write_text(tables(summary))


if __name__ == '__main__':
    main()
