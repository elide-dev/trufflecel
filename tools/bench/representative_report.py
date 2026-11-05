#!/usr/bin/env python3
"""Rebuild tables and optional matplotlib figures from a complete representative run.

Usage: representative_report.py RESULTS_DIRECTORY [--plots]
The directory must contain jmh.json, lifecycle.json and both *-metadata.json files.
Plotting requires matplotlib; numerical summaries use only the standard library.
"""
import argparse
import json
from pathlib import Path
import statistics as st

from representative import ENGINES, WORKLOADS, validate_jmh, validate_lifecycle_results

LABELS = {
    'truffle-interpreter': 'TruffleCEL interpreter',
    'truffle-jit': 'TruffleCEL guest JIT',
    'cel-java': 'cel-java default',
    'cel-java-optimized': 'cel-java optimized',
    'cel-java-pratt-optimized': 'cel-java Pratt + optimized',
}
INTERPRETERS = (ENGINES[0], *ENGINES[2:])
COLORS = dict(zip(ENGINES, ('#2166ac', '#b2182b', '#4d9221', '#7f3b08', '#762a83')))


def describe(samples):
    return {'median': st.median(samples), 'min': min(samples), 'max': max(samples)}


def summarize(jmh, lifecycle):
    validate_jmh(jmh)
    validate_lifecycle_results(lifecycle)
    result = {'jmh': [], 'lifecycle': []}
    for row in jmh:
        # Forks are the independent units. Preserve JMH's mean and uncertainty separately.
        forks = [st.mean(values) for values in row['primaryMetric']['rawData']]
        result['jmh'].append({
            'benchmark': row['benchmark'], **row['params'], 'fork_means_ns': forks,
            'ns_per_op': describe(forks), 'jmh_mean_ns': row['primaryMetric']['score'],
            'jmh_error_ns': row['primaryMetric']['scoreError'],
        })
    groups = {}
    for row in lifecycle:
        key = tuple(row[k] for k in ('runtime', 'engine', 'workload', 'mode'))
        groups.setdefault(key, []).append(row)
    for key, rows in sorted(groups.items()):
        out = dict(zip(('runtime', 'engine', 'workload', 'mode'), key))
        for phase in ('configure_ns', 'prepare_ns', 'plan_ns'):
            out[phase] = describe([r[phase] for r in rows])
        if key[3].endswith('cold'):
            out['total_ns_through_evaluations'] = {
                n: describe([r['total_ns_through_evaluations'][n] for r in rows])
                for n in ('1', '10', '100', '1000', '10000')
            }
            out['guest_compiled_forks_at_checkpoint'] = {
                n: sum(r['guest_compiled_at_checkpoint'][n] for r in rows)
                for n in ('1', '10', '100', '1000', '10000')
            }
        else:
            out['ns_per_op'] = describe([st.mean(r['ns_per_op']) for r in rows])
            if key[3] != 'ingest':
                out['forced_guest_preparation_ns'] = describe([r['forced_guest_preparation_ns'] for r in rows])
        result['lifecycle'].append(out)
    return result


def tables(summary):
    lines = ['# Measured values', '',
             'Generated from the retained raw files. Times are medians of three process means',
             '(JMH/native steady state) or three fresh-process totals (cold curves).',
             'JMH means/errors and process minimum/maximum values are retained in `summary.json`.', '']
    def table(title, rows, engines=ENGINES, scale=1, unit='ns/op'):
        lines.extend(['## ' + title, '', '| Workload | ' + ' | '.join(LABELS[e] for e in engines) + ' |',
                      '| --- | ' + ' | '.join('---:' for _ in engines) + ' |'])
        for workload in WORKLOADS:
            values = [rows[(e, workload)] / scale for e in engines]
            lines.append('| ' + workload + ' | ' + ' | '.join(f'{v:,.2f}' for v in values) + ' |')
        lines.extend(['', 'Unit: ' + unit + '. ' +
                      ('Compilation includes frontend work and program creation; it excludes guest compilation.'
                       if title == 'JVM compile' else 'Guest compilation is completed before steady-state timing.'), ''])
    for stage in ('evaluate', 'compile'):
        rows = {(r['engine'], r['workload']): r['ns_per_op']['median'] for r in summary['jmh']
                if r['benchmark'] == 'dev.trufflecel.SteadyState.' + stage}
        table('JVM ' + stage, rows, scale=1000 if stage == 'compile' else 1,
              unit='µs/op' if stage == 'compile' else 'ns/op')
    for mode in ('steady', 'image-steady'):
        rows = {(r['engine'], r['workload']): r['ns_per_op']['median'] for r in summary['lifecycle'] if r['mode'] == mode}
        table('Native ' + mode, rows)
    lines.extend(['## Upstream frontend stages', '', '| Parser/optimizer | Source | Stage | µs/op |', '| --- | --- | --- | ---: |'])
    for row in summary['jmh']:
        if '.FrontendStages.' in row['benchmark']:
            lines.append(f"| {LABELS[row['engine']]} | `{row['source']}` | {row['benchmark'].rsplit('.', 1)[1]} | {row['ns_per_op']['median']/1000:,.2f} |")
    lines.extend(['', '## Fresh-process totals', '',
                  'Configuration, runtime expression preparation and planning are included. Fixture creation',
                  'and VM launch are excluded. Image modes omit the frontend work done during image construction.', '',
                  '| Runtime/mode | Engine | Workload | First 1 (ms) | 10 | 100 | 1,000 | 10,000 |',
                  '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |'])
    for row in summary['lifecycle']:
        if row['mode'].endswith('cold'):
            values = [v['median'] / 1e6 for v in row['total_ns_through_evaluations'].values()]
            lines.append(f"| {row['runtime']}/{row['mode']} | {LABELS[row['engine']]} | {row['workload']} | " + ' | '.join(f'{v:,.3f}' for v in values) + ' |')
    lines.extend(['', '## Protobuf wire ingestion', '', '| Runtime | Engine | ns/op |', '| --- | --- | ---: |'])
    for row in summary['lifecycle']:
        if row['mode'] == 'ingest':
            lines.append(f"| {row['runtime']} | {LABELS[row['engine']]} | {row['ns_per_op']['median']:,.2f} |")
    lines.extend(['', 'TruffleCEL decodes to native snapshots; cel-java uses generated protobuf `parseFrom`.',
                  'These are different output representations. Neither timing is included in evaluation.', ''])
    return '\n'.join(lines)


def plots(summary, output):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 9, 'svg.hashsalt': 'trufflecel-representative'})
    rows = {(r['engine'], r['workload']): r['ns_per_op'] for r in summary['jmh']
            if r['benchmark'] == 'dev.trufflecel.SteadyState.evaluate'}
    for name, engines, subtitle in (
            ('jvm-interpreters', INTERPRETERS, 'Host C2; guest compilation disabled'),
            ('jvm-guest', ENGINES[:2], 'Guest compilation completed before timing; preparation excluded')):
        fig, axes = plt.subplots(2, 3, figsize=(13, 7), layout='constrained')
        for ax, workload in zip(axes.flat, WORKLOADS):
            values = [rows[(e, workload)]['median'] for e in engines]
            errors = [[rows[(e, workload)]['median'] - rows[(e, workload)]['min'] for e in engines],
                      [rows[(e, workload)]['max'] - rows[(e, workload)]['median'] for e in engines]]
            bars = ax.barh(range(len(engines)), values, xerr=errors, color=[COLORS[e] for e in engines], capsize=3)
            ax.set_yticks(range(len(engines)), [LABELS[e] for e in engines], fontsize=8)
            ax.invert_yaxis(); ax.set_title(workload); ax.set_xlabel('ns/op — lower is better')
            ax.bar_label(bars, labels=[f'{v:,.1f}' for v in values], padding=5, fontsize=8)
            ax.set_xlim(0, max(rows[(e, workload)]['max'] for e in engines) * 1.3)
        fig.suptitle('JVM evaluation · ' + subtitle + '\nMedian of three JMH fork means; whiskers show process min/max', fontsize=12)
        fig.savefig(output / (name + '.svg'), metadata={'Date': None}); plt.close(fig)
    for runtime, mode in (('jvm', 'cold'), ('svm', 'cold'), ('svm', 'image-cold')):
        rows = {(r['engine'], r['workload']): r['total_ns_through_evaluations']
                for r in summary['lifecycle'] if r['runtime'] == runtime and r['mode'] == mode}
        fig, axes = plt.subplots(2, 3, figsize=(12, 7), layout='constrained')
        for ax, workload in zip(axes.flat, WORKLOADS):
            for engine in ENGINES:
                points = rows[(engine, workload)]
                x = [int(n) for n in points]
                ax.plot(x, [v['median']/1e6 for v in points.values()], marker='.', color=COLORS[engine], label=LABELS[engine])
                ax.fill_between(x, [v['min']/1e6 for v in points.values()], [v['max']/1e6 for v in points.values()], color=COLORS[engine], alpha=.1)
            ax.set_xscale('log'); ax.set_yscale('log'); ax.set_title(workload)
            ax.set_xlabel('Cumulative evaluations'); ax.set_ylabel('Total elapsed ms'); ax.grid(alpha=.15)
        handles, labels = axes.flat[0].get_legend_handles_labels()
        fig.legend(handles, labels, loc='outside lower center', ncol=3, fontsize=8)
        fig.suptitle(f'{runtime.upper()} {mode} · preparation + first N evaluations\nMedian and min/max of three fresh processes; excludes fixture creation and VM launch', fontsize=12)
        fig.savefig(output / f'{runtime}-{mode}.svg', metadata={'Date': None}); plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--plots', action='store_true')
    args = parser.parse_args()
    for name in ('jmh', 'lifecycle'):
        if not json.loads((args.directory / f'{name}-metadata.json').read_text())['complete']:
            raise ValueError('Cannot report incomplete measurements')
    summary = summarize(json.loads((args.directory / 'jmh.json').read_text()),
                        json.loads((args.directory / 'lifecycle.json').read_text()))
    (args.directory / 'summary.json').write_text(json.dumps(summary, indent=2, allow_nan=False) + '\n')
    (args.directory / 'tables.md').write_text(tables(summary))
    if args.plots:
        plots(summary, args.directory)


if __name__ == '__main__':
    main()
