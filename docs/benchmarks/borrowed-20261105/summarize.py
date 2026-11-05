#!/usr/bin/env python3
"""Audit and summarize the complete borrowed-input comparisons retained beside this script."""
from collections import defaultdict
import json
import math
from pathlib import Path
import statistics as st
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[2] / 'tools/bench'))
from activation_report import describe
from prepared_report import describe as describe_values
from representative import WORKLOADS, validate_lifecycle
from structured_report import drift

ENGINES = ('truffle-interpreter', 'truffle-jit', 'cel-java-optimized')
TARGETED = ('scalar-wide', 'proto-policy', 'optimizer-policy')


def jmh(rows, benchmark, engines, workloads=WORKLOADS):
    expected = {(e, w) for e in engines for w in workloads}
    seen, results = set(), []
    for row in rows:
        key = row['params']['engine'], row['params']['workload']
        assert key in expected and key not in seen
        seen.add(key)
        assert row['benchmark'] == 'dev.trufflecel.' + benchmark
        assert row['primaryMetric']['scoreUnit'] == 'ns/op'
        for metric in (row['primaryMetric'], row['secondaryMetrics']['gc.alloc.rate.norm']):
            raw = metric['rawData']
            assert row['forks'] == 3 and len(raw) == 3 and all(len(f) == 5 for f in raw)
            assert all(math.isfinite(v) and v >= 0 for f in raw for v in f)
        results.append(dict(engine=key[0], workload=key[1], ns_per_op=describe(row['primaryMetric']),
                            bytes_per_op=describe(row['secondaryMetrics']['gc.alloc.rate.norm']),
                            drift_over_10_percent=drift(row)))
    assert seen == expected
    return results


def native(rows):
    expected = {(e, w, f) for e in ENGINES for w in TARGETED for f in range(3)}
    seen, grouped = set(), defaultdict(list)
    for row in rows:
        key = row['engine'], row['workload'], row['fork']
        assert key in expected and key not in seen
        seen.add(key)
        validate_lifecycle(row, 'svm', key[0], key[1], 'steady')
        assert row['image_prepared'] is False
        assert type(row['forced_guest_preparation_ns']) is int and row['forced_guest_preparation_ns'] >= 0
        grouped[key[:2]].append(row)
    assert seen == expected
    results = []
    for (engine, workload), forks in sorted(grouped.items()):
        forks.sort(key=lambda r: r['fork'])
        drift = []
        for row in forks:
            change = st.mean(row['ns_per_op'][-2:]) / st.mean(row['ns_per_op'][:2]) - 1
            if abs(change) > .1:
                drift.append(dict(fork=row['fork'], fraction=change))
        results.append(dict(engine=engine, workload=workload,
                            ns_per_op=describe_values([st.mean(r['ns_per_op']) for r in forks]),
                            drift_over_10_percent=drift))
    return results


def metric(value):
    return f"{value['median']:,.1f} [{value['min']:,.1f}–{value['max']:,.1f}]"


def main():
    jmeta = json.loads((ROOT / 'jvm/metadata.json').read_text())
    nmeta = json.loads((ROOT / 'native/metadata.json').read_text())
    assert jmeta['complete'] and nmeta['complete']
    for key in ('source_manifest', 'java_sha256', 'java_version', 'cpus', 'host', 'kernel', 'jar_sha256'):
        assert jmeta[key] == nmeta[key]
    comparison = jmh(json.loads((ROOT / 'jvm/comparison.json').read_text()), 'SteadyState.evaluate', ENGINES)
    snapshots = jmh(json.loads((ROOT / 'jvm/snapshots.json').read_text()), 'ActivationEvaluation.evaluateSnapshot', ENGINES[:2])
    native_rows = native(json.loads((ROOT / 'native/results.json').read_text()))
    followup_meta = json.loads((ROOT / 'snapshot-followup/metadata.json').read_text())
    assert followup_meta['complete']
    for key in ('source_manifest', 'java_sha256', 'jar_sha256', 'cpus', 'host', 'kernel'):
        assert followup_meta[key] == jmeta[key]
    followup = jmh(json.loads((ROOT / 'snapshot-followup/jmh.json').read_text()),
                   'ActivationEvaluation.evaluateSnapshot', ('truffle-interpreter',), ('optimizer-policy',))
    summary = dict(jvm=comparison, snapshots=snapshots, native=native_rows, snapshot_followup=followup)
    (ROOT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    lines = ['# Measured evaluation', '',
             'ns/op: median of three process means [minimum–maximum]. JVM uses JMH; native uses',
             'five warmup windows and seven measured windows. Preparation and ingestion are excluded.', '']
    for title, rows, allocation in [('JVM borrowed map comparison', comparison, True),
                                    ('JVM explicit snapshot controls', snapshots, True),
                                    ('Native borrowed map comparison (targeted workloads)', native_rows, False),
                                    ('Snapshot control follow-up (15 warmup iterations)', followup, True)]:
        lines += ['## ' + title, '', '| Workload | Engine | ns/op [process range] |' + (' B/op |' if allocation else ''),
                  '| --- | --- | ---: |' + (' ---: |' if allocation else '')]
        for row in sorted(rows, key=lambda r: (WORKLOADS.index(r['workload']), ENGINES.index(r['engine']))):
            lines.append(f"| {row['workload']} | {row['engine']} | {metric(row['ns_per_op'])} |" +
                         (f" {row['bytes_per_op']['median']:,.1f} |" if allocation else ''))
        lines += ['', 'Drift audit (>10%, first two versus last two measured windows):', '']
        flagged = [r for r in rows if r['drift_over_10_percent']]
        lines += ([f"- {r['engine']}/{r['workload']}: {r['drift_over_10_percent']}" for r in flagged]
                  or ['No flagged process.'])
        lines += ['']
    (ROOT / 'tables.md').write_text('\n'.join(lines))


if __name__ == '__main__':
    main()
