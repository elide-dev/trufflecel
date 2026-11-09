#!/usr/bin/env python3
"""Render final-dependency measurements with observed fork ranges (not confidence intervals)."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

ENGINES = ('truffle-interpreter', 'truffle-jit', 'cel-java', 'cel-java-optimized')
LABELS = ('TruffleCEL interpreter', 'TruffleCEL guest JIT', 'cel-java default', 'cel-java optimized')
COLORS = ('#007f86', '#2450a4', '#d57519', '#9e477c')
WORKLOADS = ('scalar', 'scalar-wide', 'proto-policy', 'proto-repeated', 'map-regex', 'optimizer-policy')
METRICS = (
    ('time_ns', 'time_forks_ns', 'Evaluation time (ns/op; logarithmic scale)', 'evaluation-time.svg'),
    ('allocation_bytes', 'allocation_forks_bytes', 'Current-thread allocation (B/op; logarithmic scale)', 'evaluation-allocation.svg'),
    ('rss_mib', 'rss_forks_mib', 'Current process RSS after fixed work (MiB)', 'evaluation-rss.svg'),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('summary', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    cells = {(c['runtime'], c['engine'], c['workload']): c
             for c in json.loads(args.summary.read_text())['cells']}
    args.output.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'svg.hashsalt': 'trufflecel-0.10.0'})
    for metric, forks, label, filename in METRICS:
        fig, axes = plt.subplots(1, 2, figsize=(16, 7.5))
        for ax, runtime in zip(axes, ('jvm', 'svm')):
            for i, (engine, name, color) in enumerate(zip(ENGINES, LABELS, COLORS)):
                rows = [cells[(runtime, engine, w)] for w in WORKLOADS]
                values = np.array([r[metric] for r in rows])
                lower = values - np.array([min(r[forks]) for r in rows])
                upper = np.array([max(r[forks]) for r in rows]) - values
                ax.errorbar(values, np.arange(6) + (i - 1.5) * .19,
                            color=color, label=name, xerr=np.array([lower, upper]),
                            fmt='o', markersize=5, elinewidth=1, capsize=2)
            if metric in ('time_ns', 'allocation_bytes'):
                ax.set_xscale('log')
            ax.set_yticks(np.arange(6), WORKLOADS)
            ax.invert_yaxis()
            ax.set_xlabel(label)
            ax.set_title('JVM / C2 host compiler' if runtime == 'jvm' else 'Native Image / Serial GC')
            ax.grid(axis='x', alpha=.18)
            ax.set_axisbelow(True)
            ax.spines[['top', 'right']].set_visible(False)
        handles, labels = axes[0].get_legend_handles_labels()
        fig.legend(handles, labels, loc='lower center', bbox_to_anchor=(.5, .085), ncol=4, frameon=False)
        fig.suptitle('TruffleCEL 0.10.0 candidate · TruffleData 0.10.0 · cel-java 0.15.0', y=.98, fontsize=16)
        fig.text(.5, .025,
                 'Six fixtures · ordinary Map inputs · three forks per cell · points: recorded aggregates; whiskers: observed fork ranges\n'
                 'Prepared-input controls are separate. RSS includes setup and compilation; it is not retained heap or standalone footprint.',
                 ha='center', fontsize=10)
        fig.tight_layout(rect=(0, .15, 1, .94))
        fig.savefig(args.output / filename, metadata={'Date': None})
        svg = args.output / filename
        svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines()) + '\n')
        fig.savefig(args.output / filename.replace('.svg', '.png'), dpi=160)
        plt.close(fig)


if __name__ == '__main__':
    main()
