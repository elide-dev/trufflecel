#!/usr/bin/env python3
"""Reproduce this report's retained empty-input and paired follow-up summaries."""
import json
import math
from pathlib import Path
import statistics as st
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[2] / 'tools/bench'))
from prepared_lifecycle import validate
from prepared_report import sustained_crossing

ORDER = ('reference', 'candidate', 'candidate', 'reference', 'reference', 'candidate')


def describe(values):
    return dict(values=values, median=st.median(values), min=min(values), max=max(values))


def samples(row, forks):
    assert row['benchmark'] == 'dev.trufflecel.StructuredAccess.evaluate'
    assert row['params']['engine'] == 'truffle-jit'
    assert row['params']['selectedPercent'] == '50'
    assert row['primaryMetric']['scoreUnit'] == 'ns/op'
    for metric in (row['primaryMetric'], row['secondaryMetrics']['gc.alloc.rate.norm']):
        raw = metric['rawData']
        assert len(raw) == forks and all(len(fork) == 5 for fork in raw)
        assert all(math.isfinite(v) and v > 0 for fork in raw for v in fork)
    return row


def summarize(rows):
    time = [st.mean(fork) for r in rows for fork in r['primaryMetric']['rawData']]
    allocation = [st.mean(fork) for r in rows for fork in r['secondaryMetrics']['gc.alloc.rate.norm']['rawData']]
    drift = []
    for index, fork in enumerate(fork for row in rows for fork in row['primaryMetric']['rawData']):
        change = st.mean(fork[-2:]) / st.mean(fork[:2]) - 1
        if abs(change) > .1:
            drift.append(dict(process=index, fraction=change))
    return dict(ns_per_op=describe(time), bytes_per_op=describe(allocation), drift_over_10_percent=drift)


def production(source):
    return {p: h for p, h in source['files'].items() if p.startswith('src/main/') and p.endswith('.java')}


def extended_cold():
    directory = ROOT / 'extended-cold'
    metadata = json.loads((directory / 'metadata.json').read_text())
    primary = json.loads((ROOT / 'lifecycle/metadata.json').read_text())
    assert metadata['complete'] and len(metadata['commands']) == 12
    for key in ('java_sha256', 'jar_sha256', 'cpus', 'source_manifest'):
        assert metadata[key] == primary[key]
    expected = {('jvm', e, w, 'snapshot', 'cold', f)
                for w in ('scalar-wide', 'proto-policy')
                for e in ('truffle-interpreter', 'truffle-jit') for f in range(3)}
    seen, grouped = set(), {}
    for row in json.loads((directory / 'results.json').read_text()):
        job = tuple(row[k] for k in ('runtime', 'engine', 'workload', 'entry', 'mode', 'fork'))
        assert job in expected and job not in seen
        seen.add(job)
        validate(row, job, 10_000_000)
        grouped.setdefault((row['workload'], row['engine']), []).append(row)
    assert seen == expected
    curves = {}
    for (workload, engine), rows in grouped.items():
        rows.sort(key=lambda row: row['fork'])
        points = rows[0]['total_ns_through_evaluations']
        curves[workload, engine] = {
            p: describe([row['total_ns_through_evaluations'][p] for row in rows]) for p in points}
    summary = []
    for workload in ('scalar-wide', 'proto-policy'):
        interpreted, jit = (curves[workload, e] for e in ('truffle-interpreter', 'truffle-jit'))
        summary.append(dict(workload=workload, interpreter=interpreted, jit=jit,
                            first_sustained_median_checkpoint=sustained_crossing(list(jit), jit, interpreted),
                            first_sustained_disjoint_range_checkpoint=sustained_crossing(list(jit), jit, interpreted, True)))
    (ROOT / 'extended-cold-summary.json').write_text(json.dumps(summary, indent=2) + '\n')


def main():
    empty_meta = json.loads((ROOT / 'empty/metadata.json').read_text())
    assert empty_meta['complete']
    empty_rows = json.loads((ROOT / 'empty/jmh.json').read_text())
    assert len(empty_rows) == 2 and {r['params']['length'] for r in empty_rows} == {'0', '128'}
    result = dict(empty={r['params']['length']: summarize([samples(r, 3)]) for r in empty_rows})
    candidate_source = production(empty_meta['source_manifest'])
    assert candidate_source == json.loads((ROOT / 'source-equivalence.json').read_text())['files']
    for name in ('scaling-followup', 'empty-variant'):
        directory = ROOT / name
        meta = json.loads((directory / 'metadata.json').read_text())
        assert meta['complete'] and len(meta['commands']) == 6
        assert meta['java_sha256'] == empty_meta['java_sha256'] and meta['cpus'] == empty_meta['cpus']
        for filename in ('Engines.java', 'Workloads.java', 'StructuredAccess.java'):
            key = 'benchmarks/representative/' + filename
            assert meta['source']['reference']['files'][key] == meta['source']['candidate']['files'][key]
        current = 'candidate' if name == 'scaling-followup' else 'reference'
        assert production(meta['source'][current]) == candidate_source
        if name == 'empty-variant':
            variant = production(meta['source']['candidate'])
            assert variant.keys() == candidate_source.keys()
            assert {p for p in variant if variant[p] != candidate_source[p]} == {'src/main/java/dev/trufflecel/Values.java'}
        grouped = {'reference': [], 'candidate': []}
        for index, artifact in enumerate(ORDER):
            rows = json.loads((directory / f'{index}-{artifact}.json').read_text())
            assert len(rows) == 1 and rows[0]['params']['length'] == '128'
            assert rows[0]['jvmArgs'] == empty_rows[0]['jvmArgs']
            grouped[artifact].append(samples(rows[0], 1))
        result[name] = {label: summarize(rows) for label, rows in grouped.items()}
        a, b = (result[name][label]['ns_per_op'] for label in ('reference', 'candidate'))
        result[name]['candidate_time_change_fraction'] = b['median'] / a['median'] - 1
        result[name]['process_ranges_overlap'] = max(a['min'], b['min']) <= min(a['max'], b['max'])
    (ROOT / 'diagnostics-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    extended_cold()


if __name__ == '__main__':
    main()
