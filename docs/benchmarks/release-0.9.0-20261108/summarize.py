#!/usr/bin/env python3
"""Reproduce the released artifact's paired medians and process ranges."""
import hashlib
import json
from pathlib import Path
import statistics
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[2] / 'tools/bench'))
import compare


def main():
    source = ROOT / 'comparison.json'
    data = json.loads(source.read_text())
    compare.validate(data)
    summary = dict(commit=data['commit'], artifact_sha256=hashlib.sha256(source.read_bytes()).hexdigest(), metrics={})
    lines = ['# Released 0.9.0 comparison', '',
             'Median of three process medians [minimum–maximum process median]. Lower is better.',
             'RSS uses three fixed-work process peaks. These ranges are descriptive, not confidence intervals.', '']
    for metric, title, unit in [('ns_per_op', 'Time', 'ns/op'),
                                ('allocated_bytes_per_op', 'Current-thread heap allocation', 'B/op'),
                                ('peak_rss_kib', 'Fixed-work peak RSS', 'MiB')]:
        rows = []
        wins = ties = losses = overlaps = 0
        lines += [f'## {title} ({unit})', '',
                  '| Runtime / stage | Fixture | TruffleCEL | cel-java | Lower median |',
                  '| --- | --- | ---: | ---: | --- |']
        for runtime, stage in compare.PANELS:
            for workload in compare.WORKLOADS:
                groups = {}
                for engine in compare.ENGINES:
                    samples = [row[metric] for row in data['results']
                               if (row['runtime'], row['stage'], row['workload'], row['engine']) ==
                               (runtime, stage, workload, engine)]
                    values = [statistics.median(v) if isinstance(v, list) else v / 1024 for v in samples]
                    groups[engine] = dict(median=statistics.median(values), minimum=min(values), maximum=max(values))
                a, b = (groups[e] for e in compare.ENGINES)
                wins += a['median'] < b['median']
                ties += a['median'] == b['median']
                losses += a['median'] > b['median']
                overlap = max(a['minimum'], b['minimum']) <= min(a['maximum'], b['maximum'])
                overlaps += overlap
                rows.append(dict(runtime=runtime, stage=stage, workload=workload, groups=groups, ranges_overlap=overlap))
                cells = [f"{v['median']:,.2f} [{v['minimum']:,.2f}–{v['maximum']:,.2f}]" for v in (a, b)]
                winner = 'TruffleCEL' if a['median'] < b['median'] else 'cel-java' if a['median'] > b['median'] else 'tie'
                lines.append(f"| {runtime} / {stage} | {workload} | {' | '.join(cells)} | {winner}{' †' if overlap else ''} |")
        lines += ['', '† Observed process ranges overlap.', '']
        summary['metrics'][metric] = dict(unit=unit, lower=wins, equal=ties, higher=losses, overlapping_ranges=overlaps, cells=rows)
    (ROOT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (ROOT / 'tables.md').write_text('\n'.join(lines))
    (ROOT / 'SHA256SUMS').write_text(summary['artifact_sha256'] + '  comparison.json\n')


if __name__ == '__main__':
    main()
