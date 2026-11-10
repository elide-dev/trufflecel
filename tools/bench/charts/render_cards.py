#!/usr/bin/env python3
# Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Render a retained release report as light/dark benchmark cards; never runs a benchmark.

  render_cards.py --report docs/benchmarks/release-X-DATE --output docs/images/benchmark-X

The report directory must hold `summary.json`, `prepared-inputs.json` and `source.json`.
Every plotted value is copied from those files after validation: exactly 48 ordinary-map
cells and 16 prepared-input control cells, three forks each, and every aggregate inside
its observed fork range. Comparison counts are recomputed and must equal the recorded
counts. Cards use TruffleData's renderer (cards.py), so both projects share one design.
"""
import argparse
import hashlib
import itertools
import json
import math
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from cards import Card, Panel, Row, render as render_card  # noqa: E402

ENGINES = ('truffle-interpreter', 'truffle-jit', 'cel-java', 'cel-java-optimized')
LABELS = {'truffle-interpreter': 'TruffleCEL interpreter', 'truffle-jit': 'TruffleCEL guest JIT',
          'cel-java': 'cel-java default', 'cel-java-optimized': 'cel-java optimized'}
OURS = ENGINES[:2]
WORKLOADS = ('scalar', 'scalar-wide', 'proto-policy', 'proto-repeated', 'map-regex', 'optimizer-policy')
TITLES = dict(zip(WORKLOADS, ('Scalar', 'Wide activation', 'Protobuf policy', 'Repeated protobuf',
                              'Map + regex', 'Optimizer policy')))
RUNTIMES = {'jvm': 'JVM', 'svm': 'Native Image'}
PREPARED = {'cel-java-prepared-map': 'Ordinary map · default',
            'cel-java-optimized-prepared-map': 'Ordinary map · optimized',
            'cel-java-immutable': 'ImmutableMap · default',
            'cel-java-optimized-immutable': 'ImmutableMap · optimized'}
PREPARED_WORKLOADS = ('scalar-wide', 'proto-policy')
# metric, fork key, unit, kind
METRICS = {
    'time_ns': ('time_forks_ns', 'ns / evaluation', 'time'),
    'allocation_bytes': ('allocation_forks_bytes', 'bytes allocated / evaluation', 'allocation'),
    'rss_mib': ('rss_forks_mib', 'MiB current process RSS', 'memory'),
    'peak_rss_mib': ('peak_rss_forks_mib', 'MiB lifetime peak process RSS', 'memory'),
}
WHISKERS = 'Whiskers: minimum–maximum of three forks, not confidence intervals.'
TIME_BARS = ('Bars: JVM mean of three JMH fork means (5 × 1 s warmup, 5 × 1 s measurement, C2 host compiler); '
             'Native Image median of three fresh-process medians (Serial GC). ' + WHISKERS)
MEMORY_BARS = 'Bars: median of three fresh processes, 5 warmup and 7 measured batches of 65,536 evaluations. ' + WHISKERS
ORDINARY = ('Ordinary Map inputs; every fixture except scalar binds all 69 variables, and repeated protobuf '
            'materializes every output element. Chips compare with the lower cel-java planner aggregate in '
            'that panel. Prepared immutable inputs are a separate control, not pooled here.')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def check_cell(cell, metrics):
    for metric in metrics:
        forks = cell[METRICS[metric][0]]
        values = [cell[metric], *forks]
        if len(forks) != 3 or not all(isinstance(v, (int, float)) and math.isfinite(v) and v > 0 for v in values):
            raise ValueError(f'Invalid {metric} measurements for {cell["runtime"]}/{cell["engine"]}/{cell["workload"]}')
        if not min(forks) <= cell[metric] <= max(forks):
            raise ValueError(f'{metric} aggregate falls outside its fork range: {cell["engine"]}/{cell["workload"]}')


def index(cells, engines, workloads, metrics):
    found = {(c['runtime'], c['engine'], c['workload']): c for c in cells}
    expected = set(itertools.product(RUNTIMES, engines, workloads))
    if set(found) != expected or len(cells) != len(expected):
        raise ValueError(f'Expected exactly {len(expected)} distinct cells, found {len(cells)}')
    for cell in found.values():
        check_cell(cell, metrics)
    return found


def outcome(value, baseline):
    if math.isclose(value, baseline, rel_tol=1e-9):
        return 'tied'
    return 'lower' if value < baseline else 'higher'


def counts(cells, metric, engine):
    result = {'lower': 0, 'tied': 0, 'higher': 0}
    for runtime, workload in itertools.product(RUNTIMES, WORKLOADS):
        baseline = min(cells[runtime, e, workload][metric] for e in ENGINES[2:])
        result[outcome(cells[runtime, engine, workload][metric], baseline)] += 1
    return result


def verify_counts(summary, cells):
    for runtime, engine, metric in itertools.product(RUNTIMES, OURS, METRICS):
        recorded = summary['counts'][f'{runtime}/{engine}'][metric]
        computed = {'lower': 0, 'tied': 0, 'higher': 0}
        for workload in WORKLOADS:
            baseline = min(cells[runtime, e, workload][metric] for e in ENGINES[2:])
            computed[outcome(cells[runtime, engine, workload][metric], baseline)] += 1
        if any(recorded[k] != v for k, v in computed.items()):
            raise ValueError(f'Recomputed {runtime}/{engine}/{metric} counts differ from summary.json')


def verdict(value, baseline, kind):
    """Plain-language comparison; the words carry the meaning without relying on color."""
    state = outcome(value, baseline)
    if state == 'tied':
        return 'tied', state
    if kind == 'memory':
        return f'{abs(value / baseline - 1) * 100:.0f}% {"less" if state == "lower" else "more"}', state
    ratio = baseline / value if state == 'lower' else value / baseline
    word = {'time': ('faster', 'slower'), 'allocation': ('less', 'more')}[kind][state == 'higher']
    return f'{ratio:.2f}× {word}', state


def chip(rows, kind):
    """Compare each TruffleCEL row with the best cel-java row in the same panel."""
    ours = [r for r in rows if r.ours]
    others = [r for r in rows if not r.ours]
    if not ours or not others:
        return None
    best = min(others, key=lambda r: r.value)
    parts, states = [], set()
    for r in ours:
        text, state = verdict(r.value, best.value, kind)
        states.add(state)
        name = r.label.replace('TruffleCEL ', '')
        parts.append(f'{name[:1].upper()}{name[1:]} {text}' if len(ours) > 1 else text)
    tone = 'good' if states == {'lower'} else 'bad' if states == {'higher'} else 'neutral'
    return ' · '.join(parts) + f' vs {best.label}', tone


def value_text(value, metric):
    if metric == 'allocation_bytes':
        return f'{value:,.0f}'
    if metric in ('rss_mib', 'peak_rss_mib'):
        return f'{value:,.1f}'
    return f'{value:,.1f}' if value >= 10 else f'{value:.2f}'


def row(cell, metric, label, ours):
    forks = cell[METRICS[metric][0]]
    return Row(label, cell[metric], min(forks), max(forks), ours, value_text(cell[metric], metric))


def panels(cells, metric, engines):
    _, unit, kind = METRICS[metric]
    result = []
    for workload, runtime in itertools.product(WORKLOADS, RUNTIMES):
        rows = [row(cells[runtime, e, workload], metric, LABELS[e], e in OURS) for e in engines]
        result.append(Panel(f'{TITLES[workload]} · {RUNTIMES[runtime]}', unit, rows, 'lower', chip(rows, kind)))
    return result


def tally(cells, metric, engines):
    parts = []
    for engine in engines:
        c = counts(cells, metric, engine)
        name = 'Interpreter' if engine == 'truffle-interpreter' else 'Guest JIT'
        text = f'{name} lower in {c["lower"]} of 12 comparisons'
        if c['tied']:
            text += f', tied in {c["tied"]}'
        if c['higher']:
            text += f', higher in {c["higher"]}'
        parts.append(text)
    return '; '.join(parts) + '.'


def build_cards(summary, prepared, identity, caveat):
    cells = index(summary['cells'], ENGINES, WORKLOADS, METRICS)
    verify_counts(summary, cells)
    controls = index(prepared['cells'], tuple(PREPARED), PREPARED_WORKLOADS, ('time_ns', 'allocation_bytes'))
    release = identity['versions']['TruffleCEL']
    common = dict(ours_label='TruffleCEL', other_label=f'cel-java {identity["versions"]["cel-java"]}', columns=2)
    extra = [caveat] if caveat else []
    both = list(ENGINES)
    cards = [
        Card('interpreter-time', f'TruffleCEL {release} · Interpreter evaluation', 'Interpreter against interpreter',
             'Warm evaluation of six CEL fixtures with guest JIT excluded; both engines interpret the expression. '
             + tally(cells, 'time_ns', OURS[:1]),
             panels(cells, 'time_ns', ('truffle-interpreter', 'cel-java', 'cel-java-optimized')),
             notes=extra + [TIME_BARS, ORDINARY, 'Hot timing excludes preparation and warmup; it is not first-request '
                            'latency. The host may still compile both engines’ Java code on the JVM.'], **common),
        Card('evaluation-time', f'TruffleCEL {release} · Hot evaluation', 'Interpreter and guest JIT',
             'Guest JIT is a separate choice for hot, reused expressions; compilation is forced before timing and '
             'its cost is excluded. ' + tally(cells, 'time_ns', OURS),
             panels(cells, 'time_ns', both),
             notes=extra + [TIME_BARS, ORDINARY, 'Guest compilation and warmup cost are not shown; see the report’s '
                            'startup and cumulative-cost limits before using this for short-lived expressions.'],
             **common),
        Card('evaluation-allocation', f'TruffleCEL {release} · Allocation', 'Allocation per evaluation',
             'Heap bytes allocated by the evaluating thread, from a calibrated per-thread counter. '
             + tally(cells, 'allocation_bytes', OURS),
             panels(cells, 'allocation_bytes', both),
             notes=extra + [MEMORY_BARS, ORDINARY, 'Compiler and background-thread allocation are excluded. '
                            'This is not retained heap or process size.'], **common),
        Card('evaluation-rss', f'TruffleCEL {release} · Process memory', 'Current process RSS',
             'Resident memory of the whole process after fixed work, with program and inputs live. '
             + tally(cells, 'rss_mib', OURS),
             panels(cells, 'rss_mib', both),
             notes=extra + [MEMORY_BARS, ORDINARY, 'RSS (smaps_rollup) includes runtime, setup, compilation and '
                            'calibration at an uncontrolled GC position. It is not retained heap or a standalone '
                            'deployment footprint; both engines share one comparison binary.'], **common),
        Card('evaluation-peak-rss', f'TruffleCEL {release} · Peak process memory', 'Lifetime peak process RSS',
             'High-water resident memory (VmHWM) including setup, calibration, warmup and compilation. '
             + tally(cells, 'peak_rss_mib', OURS),
             panels(cells, 'peak_rss_mib', both),
             notes=extra + [MEMORY_BARS, ORDINARY, 'Do not add current and peak RSS; they are two observations of '
                            'the same process. Peak RSS is not retained heap.'], **common),
    ]
    for metric, slug, title in (('time_ns', 'prepared-time', 'Evaluation time'),
                                ('allocation_bytes', 'prepared-allocation', 'Allocation per evaluation')):
        result = []
        for workload, runtime in itertools.product(PREPARED_WORKLOADS, RUNTIMES):
            rows = [row(controls[runtime, e, workload], metric, label, False) for e, label in PREPARED.items()]
            result.append(Panel(f'{TITLES[workload]} · {RUNTIMES[runtime]}', METRICS[metric][1], rows))
        cards.append(Card(
            slug, f'TruffleCEL {release} · Separate control', f'cel-java input preparation · {title.lower()}',
            'cel-java copies ordinary maps but can reuse caller-prepared Guava ImmutableMaps. Same full 69-binding '
            'inputs, both planners; caller preparation excluded.',
            result, notes=extra + [TIME_BARS if metric == 'time_ns' else MEMORY_BARS,
                                   'Only these two fixtures have prepared-input controls. These values are not '
                                   'pooled with the ordinary-map cards and do not change their counts.'], **common))
    return cards, cells, controls


def markdown(cards, identity, measured):
    versions = identity['versions']
    lines = [f'# TruffleCEL {versions["TruffleCEL"]} benchmark card values', '',
             f'Generated by `tools/bench/charts/render_cards.py` from the retained report; measured {measured} '
             f'at `{identity["commit"]}`. Each value is the plotted aggregate with its three-fork range.', '']
    for card in cards:
        lines += [f'## {card.title}', '', card.subtitle, '']
        for p in card.panels:
            lines += [f'### {p.title}', '', f'{p.unit}, lower is better' + (f'. Chip: {p.chip[0]}' if p.chip else ''),
                      '', '| Engine | Aggregate | Fork range |', '| --- | ---: | ---: |']
            lines += [f'| {r.label} | {r.text} | {value_text(r.lo, METRIC_OF[p.unit])}–'
                      f'{value_text(r.hi, METRIC_OF[p.unit])} |' for r in p.rows]
            lines.append('')
    return '\n'.join(lines)


METRIC_OF = {unit: metric for metric, (_, unit, _) in METRICS.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--measured', required=True, help='measurement date, YYYY-MM-DD')
    parser.add_argument('--host', default='Linux x86-64 · AMD Ryzen Threadripper PRO 9965WX')
    args = parser.parse_args()
    report = args.report
    summary = json.loads((report / 'summary.json').read_text())
    prepared = json.loads((report / 'prepared-inputs.json').read_text())
    identity = json.loads((report / 'source.json').read_text())
    if args.output.exists() and any(args.output.iterdir()) and not (args.output / 'manifest.json').exists():
        parser.error('Output directory holds files this renderer did not write; preserve archived charts')
    caveat = ''
    audit = report / 'host-audit.json'
    if audit.is_file() and json.loads(audit.read_text()).get('concurrent_workload_observation'):
        caveat = 'Shared-host round: competing work was observed during measurement. See the report for load and drift.'
    cards, cells, controls = build_cards(summary, prepared, identity, caveat)
    versions = identity['versions']
    args.output.mkdir(parents=True, exist_ok=True)
    artifacts = {}
    for card in cards:
        card.source = (f'TruffleCEL {versions["TruffleCEL"]} ({identity["commit"][:12]}) · TruffleData '
                       f'{versions["TruffleData"]} · GraalVM {versions["GraalVM"]} · measured {args.measured} · '
                       f'{args.host}')
        for theme in ('light', 'dark'):
            path = args.output / f'{card.slug}-{theme}.svg'
            path.write_text(render_card(card, theme))
            artifacts[path.name] = digest(path)
    (args.output / 'cards.md').write_text(markdown(cards, identity, args.measured))
    artifacts['cards.md'] = digest(args.output / 'cards.md')
    manifest = dict(
        release=versions['TruffleCEL'], commit=identity['commit'], measured=args.measured,
        source_sha256={n: digest(report / n) for n in ('summary.json', 'prepared-inputs.json', 'source.json')},
        renderer_sha256={n: digest(HERE / n) for n in ('render_cards.py', 'cards.py')},
        font_sha256={p.name: digest(p) for p in sorted((HERE / 'fonts').glob('*.woff2'))},
        artifact_sha256=artifacts)
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Validated {len(cells)} evaluation cells and {len(controls)} separate input controls; '
          f'rendered {len(cards)} cards ({len(artifacts) - 1} SVGs) in {args.output}')


if __name__ == '__main__':
    main()
