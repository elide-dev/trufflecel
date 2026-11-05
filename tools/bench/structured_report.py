#!/usr/bin/env python3
"""Compare complete activation runs and matched structured-data diagnostics."""
import argparse
import json
from pathlib import Path
import statistics as st

from activation_report import describe, summarize
from activation_suite_report import combine
from structured import validate


def drift(row):
    return [dict(fork=i, fraction=st.mean(samples[-2:]) / st.mean(samples[:2]) - 1)
            for i, samples in enumerate(row['primaryMetric']['rawData'])
            if abs(st.mean(samples[-2:]) / st.mean(samples[:2]) - 1) > .1]


def paired(directory):
    metadata = json.loads((directory / 'metadata.json').read_text())
    if not metadata['complete']:
        raise ValueError('Paired diagnostics are incomplete')
    scenarios = [
        ('scaling', ['evaluate'], [0, 16, 128, 1024], [50], 'truffle-jit'),
        ('selectivity', ['evaluate'], [128], [0, 100], 'truffle-jit'),
        ('preparation', ['prepare', 'instantiate'], [128], [50], 'truffle-interpreter'),
    ]
    result = []
    arguments = None
    for label, methods, lengths, selected, engine in scenarios:
        cells = {}
        for artifact in ('reference', 'candidate'):
            rows = json.loads((directory / (artifact + '-' + label + '.json')).read_text())
            validate(rows, methods, lengths, selected, engine)
            for row in rows:
                if arguments is None:
                    arguments = row['jvmArgs']
                if row['jvmArgs'] != arguments:
                    raise ValueError('Paired JVM arguments differ')
                key = (row['benchmark'].rsplit('.', 1)[-1], row['params']['length'],
                       row['params']['selectedPercent'])
                cells.setdefault(key, {})[artifact] = dict(
                    ns_per_op=describe(row['primaryMetric']),
                    bytes_per_op=describe(row['secondaryMetrics']['gc.alloc.rate.norm']),
                    measurement_drift_over_10_percent=drift(row))
        for (method, length, selected_percent), values in cells.items():
            result.append(dict(scenario=label, method=method, length=int(length),
                               selected_percent=int(selected_percent), **values))
    return dict(metadata=metadata, cells=result)


def compare(activation, baseline_directories):
    baseline = combine(baseline_directories)
    candidate = summarize(activation)
    metadata = json.loads((activation / 'metadata.json').read_text())
    for key in ('java_sha256', 'java_version', 'host', 'kernel', 'cpus'):
        if metadata[key] != baseline['identity'][key]:
            raise ValueError('Activation comparison environments differ: ' + key)
    for name in ('Engines.java', 'Workloads.java', 'ActivationEvaluation.java'):
        path = 'benchmarks/representative/' + name
        for run in baseline['runs']:
            if metadata['source_manifest']['files'][path] != run['source']['files'][path]:
                raise ValueError('Activation benchmark source differs: ' + path)
    raw = json.loads((activation / 'ActivationEvaluation.json').read_text())
    if any(row['jvmArgs'] != baseline['runs'][0]['jvm_args'] for row in raw):
        raise ValueError('Activation JVM arguments differ')
    def key(row):
        return row['engine'], row['workload'], row['method']
    before = {key(row): row for row in baseline['cells']}
    after = {key(row): row for row in candidate['cells'] if row['benchmark'] == 'ActivationEvaluation'}
    if before.keys() != after.keys():
        raise ValueError('Activation comparison inventories differ')
    return dict(baseline=baseline, candidate=candidate,
                cells=[dict(engine=k[0], workload=k[1], method=k[2],
                            reference=before[k], candidate=after[k]) for k in before])


def format_time(metric):
    return f"{metric['median']:,.1f} [{metric['min']:,.1f}–{metric['max']:,.1f}]"


def table(rows, title, columns, labels):
    lines = [title, '', 'Time is ns/op: median of three process means [minimum–maximum].',
             'Allocation is Java heap bytes/op; no ingestion is included.', '',
             '| ' + ' | '.join(labels) + ' | Reference ns/op | Candidate ns/op | Speedup | Reference B/op | Candidate B/op |',
             '| ' + ' | '.join(['---'] * len(labels)) + ' | ---: | ---: | ---: | ---: | ---: |']
    for row in rows:
        reference, candidate = row['reference'], row['candidate']
        speedup = reference['ns_per_op']['median'] / candidate['ns_per_op']['median']
        lines.append('| ' + ' | '.join(str(row[c]) for c in columns) +
                     f" | {format_time(reference['ns_per_op'])} | {format_time(candidate['ns_per_op'])} | {speedup:.2f}×" +
                     f" | {reference['bytes_per_op']['median']:,.1f} | {candidate['bytes_per_op']['median']:,.1f} |")
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--activation', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, nargs='+', required=True)
    parser.add_argument('--paired', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    activation = compare(args.activation, args.baseline)
    diagnostics = paired(args.paired)
    for key in ('java_sha256', 'java_version', 'host', 'kernel', 'cpus'):
        if diagnostics['metadata'][key] != activation['baseline']['identity'][key]:
            raise ValueError('Diagnostic and activation environments differ: ' + key)
    for artifact, source in (
            ('reference', activation['baseline']['runs'][0]['source']),
            ('candidate', activation['candidate']['source'])):
        def production(manifest):
            return {path: digest for path, digest in manifest['files'].items()
                    if path.startswith('src/main/') and path.endswith('.java')}
        before = production(source)
        after = production(diagnostics['metadata']['source'][artifact])
        # Historical manifests recorded only selected production files. Require all
        # recorded hashes to match; candidate manifests contain the complete inventory.
        matches = (before == after if artifact == 'candidate' else
                   bool(before) and all(after.get(path) == digest for path, digest in before.items()))
        if not matches:
            raise ValueError('Diagnostic and activation production sources differ: ' + artifact)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'comparison-summary.json').write_text(json.dumps(
        dict(activation=activation, diagnostics=diagnostics), indent=2) + '\n')
    (args.output / 'comparison-tables.md').write_text(
        table(activation['cells'], '# Full activation comparison', ['engine', 'workload', 'method'],
              ['Runtime', 'Workload', 'Entry']) + '\n' +
        table(diagnostics['cells'], '## Paired scaling and preparation', ['method', 'length', 'selected_percent'],
              ['Operation', 'Length', 'Selected %']))


if __name__ == '__main__':
    main()
