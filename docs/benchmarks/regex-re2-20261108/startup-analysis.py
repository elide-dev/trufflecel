#!/usr/bin/env python3
"""Independently verify paired first-result evidence and emit descriptive startup summaries."""
import argparse
import hashlib
import importlib
import itertools
import json
from pathlib import Path
import random
import statistics
import sys

REVISIONS = ('baseline', 'candidate')
RUNTIMES = ('jvm', 'svm')
ENGINES = ('truffle-interpreter', 'truffle-jit')
PHASES = ('fixture_and_wire_ns', 'adapter_ns', 'decode_ns', 'configure_ns',
          'prepare_ns', 'plan_ns', 'evaluate_and_verify_ns')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text())


def stats(values):
    assert len(values) == 4
    return dict(median=statistics.median(values), minimum=min(values), maximum=max(values),
                by_input_index=values)


def analyze(root):
    staged = read(root / 'staged-sha256.json')
    orchestration = read(root / 'orchestration.json')
    assert orchestration['complete'] is True and orchestration['startup_complete'] is True
    assert orchestration['staged_sha256'] == staged
    assert orchestration['script_sha256'] == sha(root / 'run.py') == staged['run.py']
    sources = {revision: read(root / revision / 'source.json') for revision in REVISIONS}
    data = {revision: read(root / 'startup' / revision / 'startup.json') for revision in REVISIONS}
    for name in ('startup_control.py', 'startup.py', 'representative.py'):
        digest = sha(root / 'scripts' / name)
        assert digest == staged['scripts/' + name]
        for source in sources.values():
            assert digest == source['file_sha256']['tools/bench/' + name]
    assert sources['baseline']['commit'] != sources['candidate']['commit']
    for source in sources.values():
        assert source['dirty'] is False
        assert source['toolchain_archive_sha256'] == 'b6d316ed0b81354949704afe632e1e2a1551d637736df5b6b03088cc5b09c852'
    for name in ('Startup.java', 'Workloads.java'):
        path = 'benchmarks/representative/' + name
        assert sources['baseline']['file_sha256'][path] == sources['candidate']['file_sha256'][path]
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(root / 'scripts'))
    driver = importlib.import_module('startup_control')
    observer = importlib.import_module('startup')
    driver.validate_sources(sources)
    expected = set(itertools.product(REVISIONS, RUNTIMES, ENGINES, range(4)))
    rows = {}
    pair_rows = {}
    command_rows = {}
    for revision in REVISIONS:
        d = data[revision]
        assert d['complete'] is True and d['revision'] == revision
        assert d['source_manifest'] == sources[revision]
        assert d['source_manifest_sha256'] == sha(root / revision / 'source.json') == staged[revision + '/source.json']
        assert d['jar_sha256'] == staged[revision + '/startup_deploy.jar']
        assert d['native_sha256'] == staged[revision + '/startup-representative']
        for key, name in [('driver_sha256', 'startup_control.py'), ('observer_driver_sha256', 'startup.py'),
                          ('shared_driver_sha256', 'representative.py')]:
            assert d[key] == sha(root / 'scripts' / name)
        assert d['forks'] == 4 and d['input_indices'] == [0, 1, 2, 3] and d['bindings_per_input'] == 69
        assert len(d['results']) == len(d['commands']) == 16
        for row, command in zip(d['results'], d['commands']):
            observer.validate(row)
            assert row['revision'] == revision and row['input_index'] == row['fork']
            assert row['workload'] == 'scalar-wide' and row['mode'] == 'runtime'
            assert row['record']['image_prepared'] is False and row['record']['verified'] is True
            assert row['record']['guest_compiled_at_first_result'] is False
            key = (revision, row['runtime'], row['engine'], row['input_index'])
            assert key in expected and key not in rows
            rows[key] = row
            pair_rows.setdefault(row['pair_index'], []).append(row)
            command_rows[key] = command
            suffix = [row['engine'], 'scalar-wide', 'runtime', str(row['input_index'])]
            if row['runtime'] == 'jvm':
                assert command == [command[0], *d['jvm_flags'], '-jar', d['artifacts']['jar'], *suffix]
            else:
                assert command == [d['artifacts']['native'], *d['native_flags'], *suffix]
            name = '-'.join(map(str, (row['runtime'], row['engine'], row['workload'], row['mode'], row['fork'])))
            directory = root / 'startup' / revision
            output = (directory / (name + '.stdout')).read_bytes().splitlines()
            assert sum(line.rstrip(b'\r') == b'TRUFFLECEL_STARTUP_READY' for line in output) == 1
            records = [json.loads(line) for line in output if line.startswith(b'{')]
            assert records == [row['record']]
            assert (directory / (name + '.stderr')).exists()
    assert set(rows) == expected and set(pair_rows) == set(range(16))
    driver.validate_results(list(rows.values()))
    jobs = sorted(driver.cells())
    random.Random(20261108).shuffle(jobs)
    for pair, group in pair_rows.items():
        assert len(group) == 2
        assert {r['revision_position'] for r in group} == {0, 1}
        group.sort(key=lambda r: r['revision_position'])
        assert [r['revision'] for r in group] == list(REVISIONS if pair % 2 == 0 else REVISIONS[::-1])
        for row in group:
            assert tuple(row[k] for k in ('runtime', 'engine', 'workload', 'mode', 'fork')) == jobs[pair]
    for field in ('java_sha256', 'java_version', 'java_home_release', 'runtime_sha256', 'vm_flags',
                  'jvm_flags', 'native_flags', 'cpus', 'host', 'kernel', 'cpuinfo'):
        assert data['baseline'][field] == data['candidate'][field], field
    evaluation = [read(path) for path in sorted((root / 'evaluation').glob('*/*/*/metadata.json'))]
    assert len(evaluation) == 24
    for meta in evaluation:
        assert meta['complete'] is True and meta['regex_path_verified'] is True
        assert meta['sha256']['java'] == data['baseline']['java_sha256']
        for field in ('runtime_sha256', 'jvm_flags', 'native_flags', 'cpus', 'host', 'kernel'):
            assert meta[field] == data['baseline'][field], field
        for key, command in command_rows.items():
            if key[1] == 'jvm':
                assert command[0] == meta['artifacts']['java']
    assert data['baseline']['jar_sha256'] != data['candidate']['jar_sha256']
    assert data['baseline']['native_sha256'] != data['candidate']['native_sha256']
    result = dict(schema=1, measured_launches=32, matched_pairs=16, forks=4,
                  units='nanoseconds', source_commits={r: sources[r]['commit'] for r in REVISIONS},
                  production_revisions={r: sources[r]['production_revision'] for r in REVISIONS},
                  source_manifest_sha256={r: sha(root / r / 'source.json') for r in REVISIONS},
                  startup_json_sha256={r: sha(root / 'startup' / r / 'startup.json') for r in REVISIONS},
                  runtime_sha256=data['baseline']['runtime_sha256'], java_sha256=data['baseline']['java_sha256'],
                  startup_artifacts_sha256={r: dict(jar=data[r]['jar_sha256'], native=data[r]['native_sha256']) for r in REVISIONS},
                  input_indices=[0, 1, 2, 3], bindings_per_input=69,
                  fixture='scalar-wide; shared protobuf fixture/ingestion precedes arithmetic over the full map',
                  range_definition='minimum and maximum of four fresh-process observations; not confidence intervals',
                  pair_definition='candidate minus baseline for the same runtime, engine and input variant; adjacent launches',
                  guest_last_tier_at_first_result=False, cells=[])
    for runtime, engine in itertools.product(RUNTIMES, ENGINES):
        cell = dict(runtime=runtime, engine=engine, revisions={})
        for revision in REVISIONS:
            group = [rows[(revision, runtime, engine, i)] for i in range(4)]
            cell['revisions'][revision] = dict(
                launch_to_verified_ns=stats([r['launch_to_verified_ns'] for r in group]),
                launch_to_exit_ns=stats([r['launch_to_exit_ns'] for r in group]),
                phases_ns={phase: stats([r['record'][phase] for r in group])
                           for phase in (*PHASES, 'main_to_verified_ns')})
        b = cell['revisions']['baseline']['launch_to_verified_ns']
        c = cell['revisions']['candidate']['launch_to_verified_ns']
        delta = [candidate - baseline for baseline, candidate in zip(b['by_input_index'], c['by_input_index'])]
        ratios = [100 * (candidate / baseline - 1) for baseline, candidate in zip(b['by_input_index'], c['by_input_index'])]
        cell.update(paired_delta_ns=stats(delta), paired_change_percent=stats(ratios),
                    difference_of_medians_ns=c['median'] - b['median'],
                    change_of_medians_percent=100 * (c['median'] / b['median'] - 1),
                    slower_candidate_pairs=sum(v > 0 for v in delta),
                    observed_ranges_overlap=max(b['minimum'], c['minimum']) <= min(b['maximum'], c['maximum']))
        result['cells'].append(cell)
    return result


def display(s, scale=1e6):
    return f"{s['median']/scale:.3f} [{s['minimum']/scale:.3f}, {s['maximum']/scale:.3f}]"


def tables(summary):
    lines = ['# Unrelated scalar-wide startup control', '',
             'Four fresh processes per revision/runtime/engine: one first verified result for each input variant.',
             'All 69 bindings and shared protobuf fixture/ingestion remain present. Runtime-prepared native only.',
             'Times are milliseconds. Ranges are observed minima/maxima, not confidence intervals.', '',
             '| Runtime | Engine | RE2/J baseline | TruffleRegex candidate | Difference of medians | Median paired difference | Candidate slower pairs |',
             '|---|---|---:|---:|---:|---:|---:|']
    for row in summary['cells']:
        b = row['revisions']['baseline']['launch_to_verified_ns']
        c = row['revisions']['candidate']['launch_to_verified_ns']
        lines.append(f"| {row['runtime']} | {row['engine']} | {display(b)} | {display(c)} | "
                     f"{row['difference_of_medians_ns']/1e6:+.3f} | {row['paired_delta_ns']['median']/1e6:+.3f} | {row['slower_candidate_pairs']}/4 |")
    lines += ['', 'The paired difference is candidate minus baseline for the same input variant. Its median',
              'need not equal the difference of the two unpaired medians. All four unpaired process ranges overlap.',
              'No launch-to-first-result sample had valid last-tier guest code. This fixture contains no regex call;',
              'it checks startup effects on unrelated work, not the first-use cost of compiling a regex.', '',
              '| Runtime | Engine | Input variant | Baseline ms | Candidate ms | Paired difference ms |',
              '|---|---|---:|---:|---:|---:|']
    for row in summary['cells']:
        b = row['revisions']['baseline']['launch_to_verified_ns']['by_input_index']
        c = row['revisions']['candidate']['launch_to_verified_ns']['by_input_index']
        for index, (baseline, candidate) in enumerate(zip(b, c)):
            lines.append(f"| {row['runtime']} | {row['engine']} | {index} | {baseline/1e6:.3f} | {candidate/1e6:.3f} | {(candidate-baseline)/1e6:+.3f} |")
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    summary = analyze(args.raw)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'startup-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    (args.output / 'startup-tables.md').write_text(tables(summary))
    print('Verified 32 launches, 16 adjacent revision pairs, all four input variants and shared runtime provenance')


if __name__ == '__main__':
    main()
