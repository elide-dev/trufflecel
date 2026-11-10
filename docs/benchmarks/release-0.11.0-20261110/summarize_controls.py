#!/usr/bin/env python3
"""Summarize prepared-input controls and separately sampled ingestion allocation."""
import argparse
import json
from pathlib import Path
import statistics

import summarize_evaluation as evaluation


def prepared(root, output):
    evaluation.ENGINES = ('cel-java-prepared-map', 'cel-java-optimized-prepared-map',
                          'cel-java-immutable', 'cel-java-optimized-immutable')
    evaluation.WORKLOADS = ('scalar-wide', 'proto-policy')
    summary = evaluation.summarize(root)
    del summary['counts']
    cells = {(r['runtime'], r['engine'], r['workload']): r for r in summary['cells']}
    lines = ['# Caller-prepared input controls', '',
             'Same four full 69-binding inputs and captured-array lookup; preparation excluded.', '',
             '| Runtime | Fixture | Planner | Ordinary map ns/op | Immutable map ns/op | Ordinary B/op | Immutable B/op |',
             '| --- | --- | --- | ---: | ---: | ---: | ---: |']
    for runtime in evaluation.RUNTIMES:
        for workload in evaluation.WORKLOADS:
            for planner, base in (('default', 'cel-java'), ('optimized', 'cel-java-optimized')):
                a = cells[(runtime, base + '-prepared-map', workload)]
                b = cells[(runtime, base + '-immutable', workload)]
                lines.append(f"| {runtime} | {workload} | {planner} | {a['time_ns']:,.2f} | {b['time_ns']:,.2f} | {a['allocation_bytes']:,.2f} | {b['allocation_bytes']:,.2f} |")
    (output / 'prepared-inputs.json').write_text(json.dumps(summary, indent=2) + '\n')
    (output / 'prepared-inputs.md').write_text('\n'.join(lines) + '\n')


def ingestion(root, output):
    cells = []
    lines = ['# Ingestion allocation', '',
             'Current-thread bytes/op; median of three fork medians, each with seven fixed-work windows. No timing claim.', '',
             '| Runtime | Fixture | Operation | B/op | Fork range |',
             '| --- | --- | --- | ---: | ---: |']
    for runtime in ('jvm', 'svm'):
        for fixture in ('ordinary', 'unknown64k', 'messageset'):
            samples = {}
            stages = None
            for fork in range(3):
                rows = [json.loads(line) for line in (root / f'{runtime}-{fixture}-{fork}.jsonl').read_text().splitlines() if line.startswith('{')]
                meta = rows.pop(0)
                if meta['fixture'] != fixture or meta['guest_compilation'] is not False:
                    raise ValueError('Incorrect ingestion fixture/mode')
                if stages is not None and stages != meta['stages']:
                    raise ValueError('Inconsistent ingestion stage inventory')
                stages = meta['stages']
                if len(rows) != len(stages) * 7:
                    raise ValueError('Incomplete ingestion matrix')
                for stage in stages:
                    data = [r for r in rows if r['stage'] == stage]
                    if [r['sample'] for r in data] != list(range(7)):
                        raise ValueError('Incorrect sample inventory')
                    if any(r['operations'] != (500 if fixture == 'unknown64k' else 10000)
                           or r['allocated_bytes'] < 0
                           or r['bytes_per_operation'] != r['allocated_bytes'] / r['operations'] for r in data):
                        raise ValueError('Incorrect allocation sample')
                    samples.setdefault(stage, []).append([r['bytes_per_operation'] for r in data])
            for stage, windows in samples.items():
                forks = [statistics.median(w) for w in windows]
                value = statistics.median(forks)
                cells.append(dict(runtime=runtime, fixture=fixture, stage=stage,
                                  bytes_per_operation=value, fork_medians=forks, samples=windows))
                lines.append(f'| {runtime} | {fixture} | {stage} | {value:,.2f} | {min(forks):,.2f}–{max(forks):,.2f} |')
    (output / 'ingestion.json').write_text(json.dumps(dict(cells=cells), indent=2) + '\n')
    (output / 'ingestion.md').write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('raw', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    prepared(args.raw / 'prepared-inputs', args.output)
    ingestion(args.raw / 'ingestion', args.output)
