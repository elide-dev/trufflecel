#!/usr/bin/env python3
# Copyright 2026 The TruffleCEL Authors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Render qualified native launch profiles with the existing Elide tokens and fonts."""

import hashlib
import itertools
import json
from pathlib import Path
import sys
import tempfile

import render as brand

sys.path.insert(0, str(brand.HERE.parent))
import native_profile_report

SOURCE = brand.ROOT / 'docs/benchmarks/native-profiles-20261109/young-generation'
OUTPUT = brand.ROOT / 'docs/images/native-profiles-20261109'
PROFILES = ('default', 'young-32m', 'shared-young-32m')
LABELS = ('Baseline', '32 MiB young', '32 MiB + shared')
COLORS = (brand.MUTED, brand.PURPLE, brand.PALE)
MODES = ('truffle-interpreter', 'truffle-jit')
METRICS = (
    ('ns_per_op', 'Evaluation time', 'ns/op', 'evaluation-time'),
    ('rss_mib', 'Resident process memory', 'MiB', 'current-rss'),
    ('peak_mib', 'Peak process memory', 'MiB', 'peak-rss'),
)


def draw(cells, font, metric, title, unit, filename):
    width, height = 1280, 1690
    fig = brand.plt.figure(figsize=(width / 100, height / 100), dpi=100, facecolor=brand.BG)
    ax = fig.add_axes((0, 0, 1, 1))
    ax.set(xlim=(0, width), ylim=(height, 0))
    ax.axis('off')

    def text(x, y, value, size=16, color=brand.TEXT, role='body', **kwargs):
        ax.text(x, y, value, fontsize=size * .72, color=color,
                fontproperties=font[role], va='top', **kwargs)

    def line(x1, y1, x2, y2, color=brand.BORDER, weight=1):
        ax.plot((x1, x2), (y1, y2), color=color, linewidth=weight)

    text(36, 28, 'ELIDE  /  TruffleCEL 0.10.0', 16, brand.PALE, 'strong')
    text(width - 36, 28, 'NATIVE PROFILES  ·  09 NOV 2026', 13, brand.MUTED, 'mono', ha='right')
    text(36, 65, title, 43, role='display')
    text(36, 123, f'Lower is better  ·  Process medians  ·  {unit}', 19, role='strong')
    text(36, 161, 'Same evaluator, three launch configurations. Comparisons below use the 512 MiB heap baseline.', 15, brand.MUTED)
    text(36, 189, '32 MiB young: MaxNewSize=32m. “+ shared” also disables CompileInIsolates; see GraalVM issue #227.', 15, brand.MUTED)
    line(36, 225, width - 36, 225)

    for column, mode in enumerate(MODES):
        x = 36 + column * 616
        text(x, 247, 'Interpreter' if column == 0 else 'Guest JIT', 26, role='display')
        text(x, 281, 'Guest compilation disabled' if column == 0 else 'Compilation cost is separate from hot evaluation', 14, brand.MUTED)
        for row, (workload, name) in enumerate(zip(brand.WORKLOADS, brand.TITLES)):
            y, card_width, card_height = 316 + row * 204, 592, 188
            ax.add_patch(brand.FancyBboxPatch((x, y), card_width, card_height,
                         boxstyle='round,pad=0,rounding_size=8', facecolor=brand.CARD,
                         edgecolor=brand.BORDER, linewidth=.8))
            text(x + 16, y + 13, name, 20, role='display')
            text(x + card_width - 16, y + 17, unit, 13, brand.MUTED, 'mono', ha='right')
            values = [cells[mode, workload, p][metric] for p in PROFILES]
            for index, offset in ((1, 16), (2, 302)):
                verdict, color = brand.comparison(values[index]['median'], values[0]['median'],
                                                   'time_ns' if metric == 'ns_per_op' else metric)
                # Percent time differences remain readable when the ratio would round to 1.0x.
                if metric == 'ns_per_op':
                    delta = values[index]['median'] / values[0]['median'] - 1
                    verdict = f'{abs(delta):.1%} {"less time" if delta < 0 else "more time"}'
                text(x + offset, y + 44, ('32 MiB: ' if index == 1 else '+ shared: ') + verdict, 14, color, 'strong')
            # One scale per workload, shared across interpreter/JIT columns.
            maximum = max(cells[e, workload, p][metric]['maximum'] for e in MODES for p in PROFILES)
            bar_x, bar_width = x + 160, 309
            line(bar_x, y + 77, bar_x, y + 160)
            for index, (value, label, color) in enumerate(zip(values, LABELS, COLORS)):
                center = y + 87 + index * 32
                text(x + 16, center - 8, label, 14)
                ax.add_patch(brand.Rectangle((bar_x, center - 6), value['median'] / maximum * bar_width,
                                            12, facecolor=color))
                low, high = (bar_x + value[k] / maximum * bar_width for k in ('minimum', 'maximum'))
                line(low, center, high, center, brand.TEXT, .8)
                line(low, center - 3, low, center + 3, brand.TEXT, .8)
                line(high, center - 3, high, center + 3, brand.TEXT, .8)
                text(x + card_width - 16, center - 8, f"{value['median']:,.2f}", 14, role='mono', ha='right')
            text(bar_x, y + 167, '0', 10, brand.MUTED, 'mono', ha='center')

    line(36, 1550, width - 36, 1550)
    text(36, 1570, 'READING THIS CHART', 12, brand.PALE, 'strong')
    text(36, 1594, 'Each workload shares one zero-based scale across both columns. Ticks show three-process ranges, not confidence intervals.', 14, brand.MUTED)
    text(36, 1618, 'Serial GC · 512 MiB initial/maximum heap · full inputs · materialized lists · shared Linux Threadripper host.', 14, brand.MUTED)
    text(36, 1642, 'Memory includes runtime/compiler pages, not just live objects. Matched cel-java results and compilation costs are in the full tables.', 14, brand.MUTED)
    text(36, 1666, 'TruffleData 0.10.0 / GraalVM 25.5.5-elide.27 · application flags, not a new library implementation', 12, brand.MUTED, 'mono')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUTPUT / f'{filename}.svg', metadata={'Date': None, 'Title': title,
                'Description': 'TruffleCEL native launch profiles; all six workloads, interpreter and guest JIT. Lower is better. Three forks; shared-host observations.'})
    svg = OUTPUT / f'{filename}.svg'
    svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines()) + '\n')
    fig.savefig(OUTPUT / f'{filename}.png', dpi=150)
    brand.plt.close(fig)


def main():
    summary = json.loads((SOURCE / 'summary.json').read_text())
    if summary != native_profile_report.summarize(SOURCE, include_startup=False):
        raise ValueError('Chart summary differs from validated raw measurements')
    cells = {(r['engine'], r['workload'], r['profile']): r for r in summary['cells']}
    expected = set(itertools.product(brand.ENGINES, brand.WORKLOADS, PROFILES))
    if set(cells) != expected or len(summary['cells']) != len(expected):
        raise ValueError('Expected all 72 engine/workload/profile cells')
    brand.plt.rcParams.update({'svg.fonttype': 'path', 'svg.hashsalt': 'trufflecel-native-profiles-20261109'})
    with tempfile.TemporaryDirectory(prefix='trufflecel-profile-fonts-') as temporary:
        font = brand.fonts(Path(temporary))
        for spec in METRICS:
            draw(cells, font, *spec)
    files = sorted(OUTPUT.glob('*.svg')) + sorted(OUTPUT.glob('*.png'))
    (OUTPUT / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in files))
    print(f'Validated 72 cells; rendered {len(files) // 2} chart pairs in {OUTPUT}')


if __name__ == '__main__':
    main()
