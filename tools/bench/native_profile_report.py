#!/usr/bin/env python3
# Copyright 2026 The TruffleCEL Authors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Validate retained profile data and generate complete tables without pooling protocols."""

import argparse
import json
from pathlib import Path
import statistics

import native_profiles as profiles
import startup_embeddings as embeddings
import focused_payback


def stats(values):
    return dict(median=statistics.median(values), minimum=min(values), maximum=max(values), forks=values)


def payback_summary(data):
    if not data['complete'] or data['forks'] != 3 or data['maximum'] != 1_000_000:
        raise ValueError('Expected complete three-fork cumulative cost through one million evaluations')
    focused_payback.validate_results(data['results'], data['forks'], data['maximum'])
    result = []
    for runtime, mode in focused_payback.MODES:
        for workload in focused_payback.WORKLOADS:
            rows = {engine: sorted((r for r in data['results'] if (r['runtime'], r['mode'], r['workload'], r['engine']) ==
                                   (runtime, mode, workload, engine)), key=lambda r: r['fork']) for engine in focused_payback.ENGINES}
            points = []
            for power in range(7):
                key = str(10 ** power)
                point = dict(evaluations=int(key), **{e: stats([r['total_ns_through_evaluations'][key] / 1e6 for r in rows[e]])
                                                      for e in focused_payback.ENGINES})
                point['all_jit_last_tier'] = all(r['guest_compiled_at_checkpoint'][key] for r in rows['truffle-jit'])
                point['disjoint_jit_win'] = point['truffle-jit']['maximum'] < point['truffle-interpreter']['minimum']
                points.append(point)
            crossing = next((p['evaluations'] for i, p in enumerate(points)
                             if all(q['all_jit_last_tier'] and q['disjoint_jit_win'] for q in points[i:])), None)
            result.append(dict(runtime=runtime, mode=mode, workload=workload, points=points,
                               first_sustained_post_compilation_checkpoint=crossing))
    return result


def summarize(root, include_startup=True):
    phases = {}
    metadata = {}
    for phase in ('memory', 'timing'):
        meta = json.loads((root / f'{phase}-metadata.json').read_text())
        rows = json.loads((root / f'{phase}.json').read_text())
        if not meta['complete'] or meta['phase'] != phase or meta['forks'] != 3:
            raise ValueError('Expected complete three-fork qualification')
        if set(meta['workloads']) != set(profiles.WORKLOADS) or set(meta['engines']) != set(profiles.ENGINES):
            raise ValueError('All fixtures and engines are required')
        if (len(meta['profiles']) < 2 or 'default' not in meta['profiles'] or
                any(p not in profiles.PROFILES or flags != profiles.PROFILES[p] for p, flags in meta['profiles'].items())):
            raise ValueError('Unexpected profile flags')
        if meta['common_flags'] != profiles.NATIVE_FLAGS:
            raise ValueError('Heap bounds differ from the qualified protocol')
        profiles.validate_inventory(rows, 3, profiles.WORKLOADS, meta['profiles'], phase == 'memory')
        phases[phase] = rows
        metadata[phase] = meta
    for key in ('sha256', 'source_manifest_sha256', 'driver_sha256', 'shared_driver_sha256', 'host', 'cpus', 'kernel', 'profiles'):
        if metadata['memory'][key] != metadata['timing'][key]:
            raise ValueError('Memory/timing provenance differs: ' + key)
    result = dict(schema=1, cells=[], startup=[], controls=[], payback=[])
    for workload in profiles.WORKLOADS:
        for engine in ('truffle-interpreter', 'cel-java', 'cel-java-optimized', 'truffle-jit'):
            for profile in metadata['memory']['profiles']:
                selected = {phase: sorted((r for r in rows if (r['workload'], r['engine'], r['profile']) ==
                                          (workload, engine, profile)), key=lambda r: r['fork'])
                            for phase, rows in phases.items()}
                memory, timing = selected['memory'], selected['timing']
                metrics = dict(ns_per_op=[statistics.median(r['ns_per_op']) for r in timing],
                               bytes_per_op=[statistics.median(r['allocated_bytes_per_op']) for r in memory],
                               rss_mib=[r['process_memory']['smaps_rollup']['Rss'] / 1048576 for r in memory],
                               pss_mib=[r['process_memory']['smaps_rollup']['Pss'] / 1048576 for r in memory],
                               peak_mib=[r['process_memory']['status']['VmHWM'] / 1048576 for r in memory])
                if engine == 'truffle-jit':
                    metrics['forced_guest_preparation_ms'] = [r['forced_guest_preparation_ns'] / 1e6 for r in timing]
                result['cells'].append(dict(workload=workload, engine=engine, profile=profile,
                                            **{key: stats(value) for key, value in metrics.items()}))
    if not include_startup:
        return result
    startup = json.loads((root / 'startup.json').read_text())
    result['payback'] = payback_summary(json.loads((root / 'payback.json').read_text()))
    if not startup['complete']:
        raise ValueError('Incomplete startup qualification')
    embeddings.validate(startup['results'], startup['forks'])
    for mode in ('runtime', 'image'):
        controls = [r['launch_to_verified_ns'] / 1e6 for r in startup['results'] if r['engine'] == 'control' and r['mode'] == mode]
        result['controls'].append(dict(mode=mode, launch_ms=stats(controls)))
        for workload in profiles.WORKLOADS:
            for engine in embeddings.startup.ENGINES:
                for embedding in embeddings.EMBEDDINGS:
                    rows = sorted((r for r in startup['results'] if r['engine'] != 'control' and (r['mode'], r['workload'], r['engine'], r['embedding']) ==
                                   (mode, workload, engine, embedding)), key=lambda r: r['fork'])
                    result['startup'].append(dict(mode=mode, workload=workload, engine=engine, embedding=embedding,
                                                 launch_ms=stats([r['launch_to_verified_ns'] / 1e6 for r in rows]),
                                                 main_ms=stats([r['record']['main_to_verified_ns'] / 1e6 for r in rows])))
    return result


def interval(value):
    return f"{value['median']:.2f} [{value['minimum']:.2f}–{value['maximum']:.2f}]"


def tables(summary):
    lines = ['# Native profile qualification tables', '',
             'Values are process medians with min–max process ranges in brackets. Timing and memory use separate processes; ranges are not confidence intervals.', '',
             '## Evaluation: interpreter first, guest JIT separate', '',
             '| Workload | Engine | Profile | ns/op | B/op | Current RSS MiB | Current PSS MiB | Peak RSS MiB |',
             '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |']
    for r in summary['cells']:
        lines.append('| ' + ' | '.join([r['workload'], r['engine'], r['profile']] +
                                      [interval(r[k]) for k in ('ns_per_op', 'bytes_per_op', 'rss_mib', 'pss_mib', 'peak_mib')]) + ' |')
    lines += ['', '## Forced guest preparation', '',
              'Includes 2,000 warmup evaluations and synchronous last-tier compilation. This is excluded from hot timing and is not natural-JIT payback.', '',
              '| Workload | Profile | Preparation ms |', '| --- | --- | ---: |']
    for r in summary['cells']:
        if r['engine'] == 'truffle-jit':
            lines.append(f"| {r['workload']} | {r['profile']} | {interval(r['forced_guest_preparation_ms'])} |")
    if not summary['startup']:
        return '\n'.join(lines) + '\n'
    lines += ['', '## Fresh-process first verified result', '',
              'Both embeddings use the same artifact for each preparation mode. Direct Java retains a context for regex; cel-java is an unchanged control. No control timing is subtracted.', '',
              '| Preparation | Workload | Engine | Embedding | Launch → result ms | Main → result ms (diagnostic) |',
              '| --- | --- | --- | --- | ---: | ---: |']
    for r in summary['startup']:
        lines.append(f"| {r['mode']} | {r['workload']} | {r['engine']} | {r['embedding']} | {interval(r['launch_ms'])} | {interval(r['main_ms'])} |")
    lines += ['', '| Empty control | Launch → marker ms |', '| --- | ---: |']
    for r in summary['controls']:
        lines.append(f"| {r['mode']} | {interval(r['launch_ms'])} |")
    lines += ['', '## Natural-JIT cumulative cost', '',
              'Default runtime flags, three forks, full map inputs. Includes configuration, preparation and cumulative evaluations; excludes launch, fixture creation and ingestion. Do not add these times to startup.', '',
              'The first sampled win requires all JIT forks to be last-tier compiled and their maximum time below the interpreter minimum at that and every later checkpoint. Powers-of-ten checkpoints do not establish an exact crossover or guarantee.', '',
              '| Runtime | Mode | Workload | First sampled win | Interpreter at 1M ms | Guest JIT at 1M ms |',
              '| --- | --- | --- | ---: | ---: | ---: |']
    for r in summary['payback']:
        last = r['points'][-1]
        lines.append(f"| {r['runtime']} | {r['mode']} | {r['workload']} | {r['first_sustained_post_compilation_checkpoint'] or 'None through 1M'} | {interval(last['truffle-interpreter'])} | {interval(last['truffle-jit'])} |")
    lines += ['', '### All cumulative checkpoints', '',
              '| Runtime | Mode | Workload | Evaluations | Interpreter ms | Guest JIT ms | All JIT last-tier |',
              '| --- | --- | --- | ---: | ---: | ---: | --- |']
    for r in summary['payback']:
        for p in r['points']:
            lines.append(f"| {r['runtime']} | {r['mode']} | {r['workload']} | {p['evaluations']} | {interval(p['truffle-interpreter'])} | {interval(p['truffle-jit'])} | {p['all_jit_last_tier']} |")
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--evaluation-only', action='store_true')
    args = parser.parse_args()
    summary = summarize(args.directory, not args.evaluation_only)
    (args.directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (args.directory / 'tables.md').write_text(tables(summary))


if __name__ == '__main__':
    main()
