#!/usr/bin/env python3
"""Summarize a complete activation run without combining independent diagnostic stages."""
import argparse
import json
from pathlib import Path
import statistics as st

from representative import validate_activation


def describe(metric):
    means = [st.mean(fork) for fork in metric['rawData']]
    return dict(fork_means=means, median=st.median(means), min=min(means), max=max(means))


def summarize(directory):
    metadata = json.loads((directory / 'metadata.json').read_text())
    if not metadata['complete']:
        raise ValueError('Run is incomplete')
    summary = []
    drift = []
    for benchmark in ('ActivationPreparation', 'ActivationEvaluation'):
        rows = json.loads((directory / (benchmark + '.json')).read_text())
        validate_activation(rows, benchmark)
        for row in rows:
            name = row['benchmark'].rsplit('.', 1)[1]
            summary.append(dict(benchmark=benchmark, method=name, **row['params'],
                                ns_per_op=describe(row['primaryMetric']),
                                bytes_per_op=describe(row['secondaryMetrics']['gc.alloc.rate.norm']),
                                jmh_mean_ns=row['primaryMetric']['score'],
                                jmh_error_ns=row['primaryMetric']['scoreError']))
            for fork, samples in enumerate(row['primaryMetric']['rawData']):
                change = st.mean(samples[-2:]) / st.mean(samples[:2]) - 1
                if abs(change) > .1:
                    drift.append(dict(method=name, **row['params'], fork=fork, fraction=change))
    return dict(source=metadata['source_manifest'], cells=summary, measurement_drift_over_10_percent=drift)


def tables(summary):
    lines = ['# Activation measurements', '',
             'Medians of three JMH process means; brackets show the minimum and maximum process means.',
             'All cells use the GC profiler. Allocation is Java heap bytes/op, not RSS or native memory.', '',
             '## Preparation diagnostics', '',
             '| Input | Operation | ns/op [process range] | bytes/op |',
             '| --- | --- | ---: | ---: |']
    def value(row):
        metric = row['ns_per_op']
        return f"{metric['median']:,.1f} [{metric['min']:,.1f}–{metric['max']:,.1f}]"
    for row in summary['cells']:
        if row['benchmark'] == 'ActivationPreparation':
            lines.append(f"| {row['workload']} | {row['method']} | {value(row)} | {row['bytes_per_op']['median']:,.1f} |")
    lines.extend(['', 'These isolated stages are not additive. The scalar copy diagnostic is a generic',
                  'map copy; ordinary single-binding evaluation has its own compact fast path.', '',
                  '## Evaluation', '',
                  '| Runtime | Workload | Entry | ns/op [process range] | bytes/op |',
                  '| --- | --- | --- | ---: | ---: |'])
    for row in summary['cells']:
        if row['benchmark'] == 'ActivationEvaluation':
            lines.append(f"| {row['engine']} | {row['workload']} | {row['method']} | {value(row)} | {row['bytes_per_op']['median']:,.1f} |")
    lines.extend(['', 'Snapshot reuse excludes its one-time preparation; `snapshotAndEvaluate` includes it.',
                  'All entries retain the full activation, including unused bindings. No ingestion is timed.', '',
                  '## Warmup audit', ''])
    drift = summary['measurement_drift_over_10_percent']
    if drift:
        lines.append('Cells with more than 10% first-two versus last-two measurement drift in any fork:')
        lines.append('')
        for row in drift:
            lines.append(f"- {row.get('engine', 'preparation')} / {row['workload']} / {row['method']}, fork {row['fork']}: {row['fraction']:+.1%}")
    else:
        lines.append('No cell exceeds 10% first-two versus last-two measurement drift in any fork.')
    lines.extend(['', 'This audit does not prove that host compilation has ceased or establish a peak rate.', ''])
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    summary = summarize(args.directory)
    (args.directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (args.directory / 'tables.md').write_text(tables(summary))


if __name__ == '__main__':
    main()
