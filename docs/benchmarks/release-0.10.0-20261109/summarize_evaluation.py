#!/usr/bin/env python3
"""Summarize the final-dependency six-fixture evaluation; raw data remain unchanged."""

import argparse
import json
from pathlib import Path
import statistics

ENGINES = ('truffle-interpreter', 'truffle-jit', 'cel-java', 'cel-java-optimized')
WORKLOADS = ('scalar', 'scalar-wide', 'proto-policy', 'proto-repeated', 'map-regex', 'optimizer-policy')
RUNTIMES = ('jvm', 'svm')
METRICS = (
    ('time_ns', 'Evaluation time (ns/op)', 'time_forks_ns'),
    ('allocation_bytes', 'Current-thread allocation (B/op)', 'allocation_forks_bytes'),
    ('rss_mib', 'Current RSS after fixed sampled work (MiB)', 'rss_forks_mib'),
    ('peak_rss_mib', 'Lifetime peak RSS through fixed sampled work (MiB)', 'peak_rss_forks_mib'),
)


def read(path):
    return json.loads(path.read_text())


def drift(samples):
    return (statistics.mean(samples[-2:]) / statistics.mean(samples[:2]) - 1) * 100


def overlaps(a, b):
    return max(min(a), min(b)) <= min(max(a), max(b))


def summarize(root):
    phases = ('jvm-timing', 'native-timing', 'memory')
    metadata = {phase: read(root / phase / 'metadata.json') for phase in phases}
    for phase, meta in metadata.items():
        if meta['phase'] != phase or not meta['complete']:
            raise ValueError('Incomplete or mislabeled phase: ' + phase)
        for field in ('source_manifest', 'sha256', 'driver_sha256', 'jvm_flags', 'native_flags', 'cpus'):
            if meta[field] != metadata['memory'][field]:
                raise ValueError('Phase configuration mismatch: ' + field)
    jvm, native, memory = (read(root / p / 'results.json') for p in phases)
    size = len(ENGINES) * len(WORKLOADS)
    if (len(jvm), len(native), len(memory)) != (size, size * 3, size * 6):
        raise ValueError('Incorrect matrix size')
    cells = {}
    for row in jvm:
        key = ('jvm', row['params']['engine'], row['params']['workload'])
        if key in cells or row['primaryMetric']['scoreUnit'] != 'ns/op':
            raise ValueError('Duplicate JVM cell or incorrect units')
        forks = row['primaryMetric']['rawData']
        if len(forks) != 3 or any(len(f) != 5 for f in forks):
            raise ValueError('Incomplete JVM cell')
        means = [statistics.mean(f) for f in forks]
        cells[key] = dict(time_ns=statistics.mean(means), time_forks_ns=means,
                          measurement_drift_percent=[drift(f) for f in forks])
    for engine in ENGINES:
        for workload in WORKLOADS:
            rows = sorted((r for r in native if (r['engine'], r['workload']) == (engine, workload)),
                          key=lambda r: r['fork'])
            if [r['fork'] for r in rows] != [0, 1, 2] or any(len(r['ns_per_op']) != 7 for r in rows):
                raise ValueError('Incomplete native cell')
            times = [statistics.median(r['ns_per_op']) for r in rows]
            cells[('svm', engine, workload)] = dict(
                time_ns=statistics.median(times), time_forks_ns=times,
                measurement_drift_percent=[drift(r['ns_per_op']) for r in rows])
    expected = {(r, e, w) for r in RUNTIMES for e in ENGINES for w in WORKLOADS}
    if set(cells) != expected:
        raise ValueError('Timing inventory mismatch')
    for key, cell in cells.items():
        rows = sorted((r for r in memory if (r['runtime'], r['engine'], r['workload']) == key),
                      key=lambda r: r['fork'])
        if [r['fork'] for r in rows] != [0, 1, 2]:
            raise ValueError('Incomplete memory cell')
        alloc = [statistics.median(r['allocated_bytes_per_op']) for r in rows]
        rss = [r['process_memory']['smaps_rollup']['Rss'] / 1048576 for r in rows]
        peak = [r['process_memory']['status']['VmHWM'] / 1048576 for r in rows]
        cell.update(runtime=key[0], engine=key[1], workload=key[2],
                    allocation_bytes=statistics.median(alloc), allocation_forks_bytes=alloc,
                    rss_mib=statistics.median(rss), rss_forks_mib=rss,
                    peak_rss_mib=statistics.median(peak), peak_rss_forks_mib=peak)
    counts = {}
    for runtime in RUNTIMES:
        for engine in ENGINES[:2]:
            counts[runtime + '/' + engine] = stats = {}
            for metric, _, fork_key in METRICS:
                result = dict(lower=0, tied=0, higher=0, overlapping_fork_ranges=0)
                for workload in WORKLOADS:
                    ours = cells[(runtime, engine, workload)]
                    best = min((cells[(runtime, e, workload)] for e in ENGINES[2:]), key=lambda c: c[metric])
                    result['lower' if ours[metric] < best[metric] else 'higher' if ours[metric] > best[metric] else 'tied'] += 1
                    result['overlapping_fork_ranges'] += overlaps(ours[fork_key], best[fork_key])
                stats[metric] = result
    return dict(schema=1, cells=list(cells.values()), counts=counts,
                timing_aggregation=dict(jvm='mean of three fork means', svm='median of three fork medians'),
                memory_aggregation='median of three fork medians (allocation) or process checkpoints (RSS)',
                caveat='Point estimates and observed ranges, not significance tests. RSS is whole-process footprint at uncontrolled GC position; extra guest-JIT preparation is included.')


def tables(summary):
    cells = {(c['runtime'], c['engine'], c['workload']): c for c in summary['cells']}
    result = []
    for metric, label, _ in METRICS:
        result.extend(['## ' + label, '',
                       '| Runtime | Fixture | TruffleCEL interpreter | TruffleCEL guest JIT | cel-java default | cel-java optimized |',
                       '| --- | --- | ---: | ---: | ---: | ---: |'])
        for runtime in RUNTIMES:
            for workload in WORKLOADS:
                values = ' | '.join(f'{cells[(runtime, e, workload)][metric]:,.2f}' for e in ENGINES)
                result.append(f'| {runtime} | {workload} | {values} |')
        result.append('')
    return '\n'.join(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', type=Path, default=Path(__file__).resolve().parent / 'raw')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    summary = summarize(args.raw)
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (args.output / 'tables.md').write_text(tables(summary))
    print(json.dumps(summary['counts'], indent=2))
    print('Cells with at least one first-two/last-two measurement drift over 10%:')
    for cell in summary['cells']:
        if any(abs(d) > 10 for d in cell['measurement_drift_percent']):
            print(cell['runtime'], cell['engine'], cell['workload'], cell['measurement_drift_percent'])


if __name__ == '__main__':
    main()
