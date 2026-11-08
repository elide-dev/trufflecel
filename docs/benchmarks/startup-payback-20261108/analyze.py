#!/usr/bin/env python3
"""Validate the archived run and regenerate its tables without rerunning benchmarks."""
import argparse
import hashlib
import importlib
import json
from pathlib import Path
import statistics
import sys
import tarfile
import tempfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def stats(values):
    return dict(median=statistics.median(values), minimum=min(values), maximum=max(values),
                forks=values)


def display(value):
    return f"{value['median'] / 1e6:.3f} [{value['minimum'] / 1e6:.3f}, {value['maximum'] / 1e6:.3f}]"


def analyze(archive):
    with tarfile.open(archive) as raw, tempfile.TemporaryDirectory() as scratch:
        def read(name):
            return raw.extractfile(name).read()

        source_bytes = read('source.json')
        source = json.loads(source_bytes)
        startup = json.loads(read('startup-results/startup.json'))
        payback = json.loads(read('payback-results/payback.json'))
        assert startup['complete'] and payback['complete']
        assert startup['source_manifest'] == payback['source_manifest'] == source
        assert startup['source_manifest_sha256'] == payback['source_manifest_sha256'] == digest(source_bytes)
        assert startup['java_sha256'] == payback['java_sha256']
        assert startup['runtime_sha256'] == payback['runtime_sha256']
        artifacts = dict(line.split()[::-1] for line in read('artifact-sha256.txt').decode().splitlines())
        for metadata, keys in (
                (startup, {'jar_sha256': 'startup.jar', 'native_sha256': 'startup-runtime-native',
                           'native_prepared_sha256': 'startup-image-native'}),
                (payback, {'jar_sha256': 'prepared-lifecycle.jar', 'native_sha256': 'prepared-native'})):
            for key, artifact in keys.items():
                assert metadata[key] == artifacts[artifact]

        # Use only the four archived Python source files, never archived bytecode.
        for name in ('startup', 'focused_payback', 'prepared_lifecycle', 'representative'):
            data = read(f'tools/{name}.py')
            assert digest(data) == source['file_sha256'][f'tools/bench/{name}.py']
            (Path(scratch) / f'{name}.py').write_bytes(data)
        assert startup['driver_sha256'] == digest(read('tools/startup.py'))
        assert payback['driver_sha256'] == digest(read('tools/focused_payback.py'))
        assert payback['validation_driver_sha256'] == digest(read('tools/prepared_lifecycle.py'))
        assert startup['shared_driver_sha256'] == payback['shared_driver_sha256'] == digest(read('tools/representative.py'))
        sys.path.insert(0, scratch)
        startup_driver = importlib.import_module('startup')
        payback_driver = importlib.import_module('focused_payback')
        startup_driver.validate_results(startup['results'], True, startup['forks'])
        payback_driver.validate_results(payback['results'], payback['forks'], payback['maximum'])
        sys.path.pop(0)

    assert len(startup['results']) == 285 and startup['forks'] == 5
    assert len(payback['results']) == 54 and payback['forks'] == 3
    result = dict(
        schema=1, raw_sha256=digest(archive.read_bytes()), source_manifest_sha256=digest(source_bytes),
        production_revision=source['production_revision'], production_tree=source['production_tree'],
        measured_source_commit=source['commit'], units='nanoseconds', startup=[], controls=[], payback=[],
        startup_count=len(startup['results']), payback_count=len(payback['results']),
        range_definition='minimum and maximum of independent processes, not confidence intervals',
        payback_criterion='first sampled checkpoint where every guest-JIT fork is last-tier compiled '
                          'and its maximum cumulative time is below the interpreter minimum, '
                          'with both conditions also true at every later sampled checkpoint')
    engines = ('truffle-interpreter', 'truffle-jit', 'cel-java-optimized')
    workloads = ('scalar', 'scalar-wide', 'proto-policy', 'proto-repeated', 'map-regex', 'optimizer-policy')
    for runtime, mode in (('jvm', 'runtime'), ('svm', 'runtime'), ('svm', 'image')):
        control_rows = sorted((r for r in startup['results'] if r['runtime'] == runtime
                               and r['engine'] == 'control'
                               and (runtime == 'jvm' or r['mode'] == mode)), key=lambda r: r['fork'])
        result['controls'].append(dict(runtime=runtime, mode=mode,
                                       launch_to_verified_ns=stats([r['launch_to_verified_ns'] for r in control_rows])))
        for workload in workloads:
            cell = dict(runtime=runtime, mode=mode, workload=workload, engines={})
            for engine in engines:
                rows = sorted((r for r in startup['results'] if (r['runtime'], r['mode'], r['workload'], r['engine'])
                               == (runtime, mode, workload, engine)), key=lambda r: r['fork'])
                assert len(rows) == 5
                cell['engines'][engine] = dict(
                    launch_to_verified_ns=stats([r['launch_to_verified_ns'] for r in rows]),
                    launch_to_exit_ns=stats([r['launch_to_exit_ns'] for r in rows]),
                    guest_last_tier_forks=sum(r['record']['guest_compiled_at_first_result'] for r in rows),
                    phases_ns={phase: stats([r['record'][phase] for r in rows])
                               for phase in (*startup_driver.PHASES, 'main_to_verified_ns')})
            result['startup'].append(cell)

    for runtime, mode in payback_driver.MODES:
        for workload in payback_driver.WORKLOADS:
            rows = {engine: sorted((r for r in payback['results'] if (r['runtime'], r['mode'], r['workload'], r['engine'])
                                    == (runtime, mode, workload, engine)), key=lambda r: r['fork'])
                    for engine in payback_driver.ENGINES}
            points = []
            for count in sorted(map(int, rows['truffle-jit'][0]['total_ns_through_evaluations'])):
                point = dict(evaluations=count, engines={})
                for engine, forks in rows.items():
                    assert len(forks) == 3
                    point['engines'][engine] = stats([r['total_ns_through_evaluations'][str(count)] for r in forks])
                point['all_jit_last_tier'] = all(r['guest_compiled_at_checkpoint'][str(count)] for r in rows['truffle-jit'])
                point['disjoint_jit_win'] = (point['engines']['truffle-jit']['maximum']
                                             < point['engines']['truffle-interpreter']['minimum'])
                points.append(point)
            qualifying = next((p['evaluations'] for i, p in enumerate(points)
                               if all(q['all_jit_last_tier'] and q['disjoint_jit_win'] for q in points[i:])), None)
            result['payback'].append(dict(runtime=runtime, mode=mode, workload=workload, checkpoints=points,
                                          first_sampled_post_compilation_disjoint_win=qualifying))
    return result


def tables(result):
    lines = ['# Startup and natural compilation payback', '',
             'Generated by `analyze.py` from `raw.tar.gz`. Times are milliseconds: median [minimum, maximum].',
             'Ranges describe observed independent processes; they are not confidence intervals.', '',
             '## Launch to first independently verified result', '',
             'Five fresh processes per cell; each fork uses one of four input variants.', '',
             '| Runtime / preparation | Workload | Interpreter | Guest JIT | Optimized cel-java |',
             '|---|---|---:|---:|---:|']
    for row in result['startup']:
        cells = [display(row['engines'][e]['launch_to_verified_ns'])
                 for e in ('truffle-interpreter', 'truffle-jit', 'cel-java-optimized')]
        lines.append(f"| {row['runtime']} / {row['mode']} | {row['workload']} | " + ' | '.join(cells) + ' |')
    lines += ['', 'Separate empty-engine controls; these are never subtracted:', '',
              '| Runtime / preparation | Launch to marker |', '|---|---:|']
    for row in result['controls']:
        lines.append(f"| {row['runtime']} / {row['mode']} | {display(row['launch_to_verified_ns'])} |")
    lines += ['', '## Natural guest-JIT payback', '',
              'Three fresh processes per cell, full map activation, all four input variants. The cumulative',
              'interval starts at engine configuration and excludes VM launch, fixture creation and ingestion.',
              'It cannot be added to the startup table. All guest-JIT forks have valid last-tier code by 10,000',
              'evaluations. The reported win requires every guest-JIT fork to be compiled and faster than every',
              'interpreter fork at that checkpoint and every later sampled checkpoint. Checkpoints are powers',
              'of ten; this is not a precise crossover or a universal recommendation.', '',
              '| Runtime / preparation | Workload | First sampled win | Interpreter at 1M | Guest JIT at 1M |',
              '|---|---|---:|---:|---:|']
    for row in result['payback']:
        last = row['checkpoints'][-1]['engines']
        win = row['first_sampled_post_compilation_disjoint_win']
        lines.append(f"| {row['runtime']} / {row['mode']} | {row['workload']} | "
                     + (f'{win:,}' if win else 'None through 1M')
                     + f" | {display(last['truffle-interpreter'])} | {display(last['truffle-jit'])} |")
    lines += ['', '### Full checkpoint curves', '',
              '| Runtime / preparation | Workload | Evaluations | Interpreter | Guest JIT | All JIT last-tier |',
              '|---|---|---:|---:|---:|---|']
    for row in result['payback']:
        for point in row['checkpoints']:
            lines.append(f"| {row['runtime']} / {row['mode']} | {row['workload']} | {point['evaluations']:,} | "
                         f"{display(point['engines']['truffle-interpreter'])} | {display(point['engines']['truffle-jit'])} | "
                         f"{'yes' if point['all_jit_last_tier'] else 'no'} |")
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New directory for regenerated summaries')
    args = parser.parse_args()
    result = analyze(Path(__file__).with_name('raw.tar.gz'))
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    (args.output / 'tables.md').write_text(tables(result))
    print('Validated 285 startup and 54 payback processes; summaries written to', args.output)


if __name__ == '__main__':
    main()
