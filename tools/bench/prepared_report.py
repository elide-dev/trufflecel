#!/usr/bin/env python3
"""Audit complete prepared-input lifecycle measurements and compare the JVM activation matrix."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics as st

from prepared_lifecycle import inventory, validate
from structured_report import compare, table


def describe(values):
    return dict(values=values, median=st.median(values), min=min(values), max=max(values))


def sustained_crossing(points, faster, slower, strict=False):
    def wins(point):
        a, b = faster[point], slower[point]
        return a['max'] < b['min'] if strict else a['median'] < b['median']
    return next((int(point) for i, point in enumerate(points)
                 if all(wins(later) for later in points[i:])), None)


def lifecycle(directory):
    metadata = json.loads((directory / 'metadata.json').read_text())
    if not metadata['complete']:
        raise ValueError('Lifecycle run is incomplete')
    rows = json.loads((directory / 'results.json').read_text())
    expected = set(inventory(metadata['kind']))
    seen, grouped = set(), defaultdict(list)
    for row in rows:
        job = tuple(row[key] for key in ('runtime', 'engine', 'workload', 'entry', 'mode', 'fork'))
        if job not in expected or job in seen:
            raise ValueError('Unexpected or duplicate lifecycle result')
        validate(row, job, metadata['maximum'])
        seen.add(job)
        grouped[job[:-1]].append(row)
    if seen != expected:
        raise ValueError('Missing lifecycle processes')
    native, cold, drift = [], [], []
    for key, forks in sorted(grouped.items()):
        runtime, engine, workload, entry, mode = key
        forks.sort(key=lambda row: row['fork'])
        label = dict(runtime=runtime, engine=engine, workload=workload, entry=entry, mode=mode)
        phases = {phase: describe([row[phase] for row in forks])
                  for phase in ('configure_ns', 'prepare_ns', 'instantiate_ns', 'snapshot_ns')}
        if mode.endswith('cold'):
            points = list(forks[0]['total_ns_through_evaluations'])
            cold.append(dict(**label, phases_ns=phases,
                             cumulative_ns={point: describe([row['total_ns_through_evaluations'][point]
                                                             for row in forks]) for point in points},
                             compiled_processes={point: sum(row['guest_compiled_at_checkpoint'][point]
                                                             for row in forks) for point in points}))
        else:
            native.append(dict(**label, phases_ns=phases,
                               ns_per_op=describe([st.mean(row['ns_per_op']) for row in forks]),
                               forced_guest_preparation_ns=describe([row['forced_guest_preparation_ns'] for row in forks])))
            for row in forks:
                change = st.mean(row['ns_per_op'][-2:]) / st.mean(row['ns_per_op'][:2]) - 1
                if abs(change) > .1:
                    drift.append(dict(**label, fork=row['fork'], fraction=change))
    indexed = {(r['runtime'], r['engine'], r['workload'], r['entry'], r['mode']): r for r in cold}
    payback = []
    for row in cold:
        if row['engine'] != 'truffle-jit':
            continue
        other = indexed[row['runtime'], 'truffle-interpreter', row['workload'], row['entry'], row['mode']]
        points = list(row['cumulative_ns'])
        payback.append(dict(runtime=row['runtime'], workload=row['workload'], entry=row['entry'], mode=row['mode'],
                            first_sustained_median_checkpoint=sustained_crossing(points, row['cumulative_ns'], other['cumulative_ns']),
                            first_sustained_disjoint_range_checkpoint=sustained_crossing(points, row['cumulative_ns'], other['cumulative_ns'], True)))
    return dict(metadata=metadata, native=native, cold=cold, guest_jit_payback=payback,
                measurement_drift_over_10_percent=drift)


def metric(value, scale=1):
    return f"{value['median']/scale:,.2f} [{value['min']/scale:,.2f}–{value['max']/scale:,.2f}]"


def tables(summary):
    lines = ['# Native throughput', '',
             'ns/op: median of three process means [minimum–maximum]. Five one-second warmups,',
             'seven half-second measurements per process. Runtime AST preparation; ingestion and',
             'setup excluded. Guest-JIT compilation is forced and checked before/after timing.', '',
             '| Runtime mode | Workload | Entry | ns/op [process range] |',
             '| --- | --- | --- | ---: |']
    for row in summary['native']:
        lines.append(f"| {row['engine']} | {row['workload']} | {row['entry']} | {metric(row['ns_per_op'])} |")
    lines += ['', '# Natural guest-JIT payback', '',
              'First sampled checkpoint whose median stays below the interpreter median at all later',
              'sampled checkpoints. The stricter column requires disjoint process ranges at every',
              'remaining checkpoint. This is an observed comparison within this finite curve, not an',
              'exact crossover count or a guarantee. A dash means no sustained crossing was observed.',
              'Preparation and four reusable snapshots are included; ingestion and VM launch are excluded.', '',
              '| Runtime | Workload | Entry | AST preparation | Median crossing | Disjoint-range crossing |',
              '| --- | --- | --- | --- | ---: | ---: |']
    for row in summary['guest_jit_payback']:
        lines.append('| ' + ' | '.join(str(row[key]) for key in ('runtime', 'workload', 'entry', 'mode')) +
                     f" | {row['first_sustained_median_checkpoint'] or '—'} | {row['first_sustained_disjoint_range_checkpoint'] or '—'} |")
    lines += ['', '# Cumulative cold curves', '',
              'Milliseconds: median [minimum–maximum] across three fresh processes. Guest compilation',
              'is natural, synchronous and unforced. All checkpoint samples remain in the JSON summary.', '',
              '| Runtime | Engine | Workload | Entry | Mode | Evaluations | Cumulative ms | Last-tier processes |',
              '| --- | --- | --- | --- | --- | ---: | ---: | ---: |']
    for row in summary['cold']:
        for point, value in row['cumulative_ns'].items():
            lines.append('| ' + ' | '.join(str(row[key]) for key in ('runtime', 'engine', 'workload', 'entry', 'mode')) +
                         f" | {point} | {metric(value, 1_000_000)} | {row['compiled_processes'][point]}/3 |")
    lines += ['', '# Native measurement drift', '']
    if summary['measurement_drift_over_10_percent']:
        for row in summary['measurement_drift_over_10_percent']:
            lines.append(f"- {row['engine']} / {row['workload']} / {row['entry']}, fork {row['fork']}: {row['fraction']:+.1%}")
    else:
        lines.append('No fork exceeds 10% first-two versus last-two measured-window drift.')
    lines += ['', 'This audit does not prove peak convergence or establish statistical equivalence.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--activation', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--lifecycle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    activation = compare(args.activation, [args.baseline])
    measured = lifecycle(args.lifecycle)
    for key in ('java_sha256', 'java_version', 'host', 'kernel', 'cpus'):
        if measured['metadata'][key] != activation['baseline']['identity'][key]:
            raise ValueError('Lifecycle and activation host/toolchain differ: ' + key)
    def production(source):
        return {path: digest for path, digest in source['files'].items()
                if path.startswith('src/main/') and path.endswith('.java')}
    if production(measured['metadata']['source_manifest']) != production(activation['candidate']['source']):
        raise ValueError('Lifecycle and activation production sources differ')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'summary.json').write_text(json.dumps(dict(activation=activation, lifecycle=measured), indent=2) + '\n')
    (args.output / 'activation-comparison.md').write_text(table(activation['cells'], '# JVM activation comparison',
        ['engine', 'workload', 'method'], ['Runtime', 'Workload', 'Entry']))
    (args.output / 'lifecycle-tables.md').write_text(tables(measured))


if __name__ == '__main__':
    main()
