#!/usr/bin/env python3
"""Verify immutable optional regex policy evidence and regenerate descriptive evaluation tables."""
import argparse
import hashlib
import importlib
import itertools
import json
import re
from pathlib import Path
import statistics
import sys

REVISIONS = ('baseline', 'candidate', 'only')
POLICY = lambda r: 'truffle-only' if r == 'only' else 'compatibility'
SOURCE = lambda r: 'candidate' if r == 'only' else r
RUNTIMES = ('jvm', 'svm')
ENGINES = ('truffle-interpreter', 'truffle-jit')
WORKLOADS = ('scalar-wide', 'map-regex', 'map-regex-dynamic')


def read(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stats(values):
    if len(values) != 3:
        raise ValueError('Expected three independent fork values')
    return dict(median=statistics.median(values), minimum=min(values), maximum=max(values), forks=values)


def drift(samples):
    return 100 * (statistics.mean(samples[-2:]) / statistics.mean(samples[:2]) - 1)


def replay_memory(directory, row, driver, extra_fields=()):
    raw = driver.parse_record((directory / 'stdout.json').read_text())
    expected = {key: value for key, value in row.items() if key not in ('process_memory', *extra_fields)}
    if raw != expected:
        raise ValueError('Copied memory samples differ from raw child JSON')
    for name in ('status', 'smaps_rollup'):
        fields = {key: int(number) * 1024 for key, number in
                  re.findall(r'^(\w+):\s+(\d+) kB$', (directory / name).read_text(), re.MULTILINE)}
        if fields != row['process_memory'][name]:
            raise ValueError('Copied memory checkpoint differs from raw /proc data')


def replay_rows(directory, phase, rows, engine, workload, driver):
    if phase == 'jvm-timing':
        if rows != read(directory / (engine + '-' + workload + '.json')):
            raise ValueError('Copied JMH cells differ from raw JMH JSON')
        return
    for row in rows:
        key = '-'.join(map(str, (row['runtime'], engine, workload, row['fork'])))
        if phase == 'memory':
            replay_memory(directory / key, row, driver, ('fork',))
        elif driver.parse_record((directory / (key + '.json')).read_text()) != {
                key: value for key, value in row.items() if key != 'fork'}:
            raise ValueError('Copied native timing samples differ from raw child JSON')


def summarize(root):
    orchestration = read(root / 'orchestration.json')
    if orchestration['complete'] is not True or orchestration.get('preflight_complete') is not True:
        raise ValueError('Incomplete orchestration')
    phases = ('memory', 'jvm-timing', 'native-timing')
    expected_cells = set(itertools.product(phases, REVISIONS, ENGINES, WORKLOADS))
    actual_cells = [(c['phase'], c['revision'], c['engine'], c['workload']) for c in orchestration['completed_cells']]
    if len(actual_cells) != 54 or set(actual_cells) != expected_cells:
        raise ValueError('Incomplete or duplicate orchestration cells')
    if read(root / 'staged-sha256.json') != orchestration['staged_sha256']:
        raise ValueError('Staged digest inventory differs')
    for name, expected in orchestration['staged_sha256'].items():
        archived = root / name
        if archived.exists() and sha(archived) != expected:
            raise ValueError('Archived source/driver digest differs: ' + name)
    sources = {r: read(root / SOURCE(r) / 'source.json') for r in REVISIONS}
    if {r: s['commit'] for r, s in sources.items()} != orchestration['source_revisions']:
        raise ValueError('Orchestration source identities differ')
    timed_sources = ('Workloads.java', 'SteadyState.java', 'Lifecycle.java',
                     'EvaluationMemory.java', 'RegexQualification.java')
    for name in timed_sources:
        path = 'benchmarks/representative/' + name
        if sources['baseline']['file_sha256'][path] != sources['candidate']['file_sha256'][path]:
            raise ValueError('Timed protocol/fixture differs between revisions: ' + name)
    expected_engines = {'baseline': '7dcdc76acc95250a3883b19bae5e41123f955af3c1415625894cfd9ddd24c3d3', 'candidate': 'ddb8fa0c891a412d3818428b6b5e5de90a1f85d56be85e8ea4a07ec44dbcc850'}
    for revision, digest in expected_engines.items():
        if sources[revision]['file_sha256']['benchmarks/representative/Engines.java'] != digest:
            raise ValueError('Untimed mode-selection helper differs from qualified overlay')
    for path in ('.bazelversion', 'MODULE.bazel', 'MODULE.bazel.lock', 'maven_install.json'):
        if sources['baseline']['file_sha256'][path] != sources['candidate']['file_sha256'][path]:
            raise ValueError('Dependency/toolchain inputs differ: ' + path)
    sizes = read(root / 'artifact-sizes.json')
    for name in ('evaluation_refresh.py', 'regex_evaluation.py', 'optional_regex_evaluation.py', 'representative.py'):
        digest = sha(root / 'scripts' / name)
        for source in sources.values():
            if source['file_sha256']['tools/bench/' + name] != digest:
                raise ValueError('Archived driver differs from measured source: ' + name)
        if orchestration['staged_sha256']['scripts/' + name] != digest:
            raise ValueError('Staging driver digest differs')
    if sha(root / 'run.py') != orchestration['script_sha256']:
        raise ValueError('Orchestration script changed')
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(root / 'scripts'))
    driver = importlib.import_module('evaluation_refresh')
    regex = importlib.import_module('optional_regex_evaluation')
    if orchestration['qualified_smoke_processes'] != 36:
        raise ValueError('Incomplete backend smoke inventory')
    for revision, runtime, engine, workload in itertools.product(REVISIONS, RUNTIMES, ENGINES, WORKLOADS):
        smoke = root / 'qualification-smokes' / ('-'.join((revision, runtime, engine, workload)) + '.json')
        record = driver.parse_record(smoke.read_text())
        regex.validate_policy(record['regex_path'], POLICY(revision), workload)
        if (record['input_variants'], record['bindings_per_input']) != (4, 69):
            raise ValueError('Smoke fixture was narrowed')
    cells, metadata = {}, []
    counts = dict(memory=0, jvm_timing_forks=0, native_timing_forks=0)
    for revision, engine, workload in itertools.product(REVISIONS, ENGINES, WORKLOADS):
        phase_rows = {}
        for phase in ('memory', 'jvm-timing', 'native-timing'):
            directory = root / 'evaluation' / revision / phase / (engine + '-' + workload)
            meta = read(directory / 'metadata.json')
            rows = read(directory / 'results.json')
            if meta['complete'] is not True or meta.get('regex_path_verified') is not True or meta.get('regex_policy_verified') is not True:
                raise ValueError('Unqualified backend must not count as a measurement')
            if meta['phase'] != phase or meta['source_manifest'] != sources[revision]:
                raise ValueError('Phase/source identity mismatch')
            if meta['source_manifest_sha256'] != sha(root / SOURCE(revision) / 'source.json'):
                raise ValueError('Source manifest digest differs')
            if meta['driver_sha256'] != sha(root / 'scripts/evaluation_refresh.py'):
                raise ValueError('Driver digest differs')
            if meta['entrypoint_sha256'] != sha(root / 'scripts/optional_regex_evaluation.py'):
                raise ValueError('Entrypoint digest differs')
            driver.validate_inventory(rows, phase, (engine,), (workload,))
            replay_rows(directory, phase, rows, engine, workload, driver)
            for artifact, filename in [('jmh', 'jmh_deploy.jar'), ('memory_jar', 'evaluation_memory_deploy.jar'),
                                       ('native', 'representative'), ('memory_native', 'evaluation-memory')]:
                if meta['sha256'][artifact] != orchestration['staged_sha256'][SOURCE(revision) + '/' + filename]:
                    raise ValueError('Measured artifact differs from staged artifact')
                if meta['artifact_bytes'][artifact] != sizes[SOURCE(revision) + '/' + filename]:
                    raise ValueError('Measured artifact size differs from staged artifact')
            regex.qualify(directory, dict(meta), rows, POLICY(revision), engine, workload)
            phase_rows[phase] = rows
            metadata.append(meta)
        jvm = phase_rows['jvm-timing'][0]['primaryMetric']['rawData']
        if phase_rows['jvm-timing'][0]['primaryMetric']['scoreUnit'] != 'ns/op':
            raise ValueError('Unexpected timing units')
        for runtime in RUNTIMES:
            key = (revision, runtime, engine, workload)
            memory = sorted((r for r in phase_rows['memory'] if r['runtime'] == runtime), key=lambda r: r['fork'])
            if runtime == 'jvm':
                samples = jvm
                counts['jvm_timing_forks'] += len(samples)
            else:
                samples = [r['ns_per_op'] for r in sorted(phase_rows['native-timing'], key=lambda r: r['fork'])]
                counts['native_timing_forks'] += len(samples)
            counts['memory'] += len(memory)
            cells[key] = dict(revision=revision, runtime=runtime, engine=engine, workload=workload,
                time_ns=stats([statistics.mean(f) for f in samples]),
                allocation_bytes=stats([statistics.mean(r['allocated_bytes_per_op']) for r in memory]),
                rss_mib=stats([r['process_memory']['smaps_rollup']['Rss']/1048576 for r in memory]),
                peak_rss_mib=stats([r['process_memory']['status']['VmHWM']/1048576 for r in memory]),
                measurement_drift_percent=[drift(f) for f in samples],
                ast_node_counts=[r['regex_path']['ast_nodes'] for r in memory])
    if counts != dict(memory=108, jvm_timing_forks=54, native_timing_forks=54):
        raise ValueError('Incomplete evaluation process inventory')
    for field in ('runtime_sha256', 'fixed_work_jvm_extra_flags', 'cpus'):
        if any(m[field] != metadata[0][field] for m in metadata):
            raise ValueError('Runtime/flag/affinity mismatch: ' + field)
    preflight = read(root / 'preflight.json')
    if (preflight['complete'] is not True or preflight['allocation_processes'] != 12
            or preflight['qualified_smoke_processes'] != 36
            or sha(root / 'preflight.json') != orchestration['preflight_sha256']
            or preflight['staged_sha256'] != orchestration['staged_sha256']
            or preflight['script_sha256'] != orchestration['script_sha256']):
        raise ValueError('Incomplete or mismatched preflight')
    preflight_rows = read(root / 'preflight-allocation/results.json')
    keys = [(r['revision'], r['runtime'], r['engine']) for r in preflight_rows]
    if len(keys) != 12 or set(keys) != set(itertools.product(REVISIONS, RUNTIMES, ENGINES)):
        raise ValueError('Incomplete or duplicated preflight allocation cells')
    for row in preflight_rows:
        replay_memory(root / 'preflight-allocation' / '-'.join((row['revision'], row['runtime'], row['engine'])),
                      row, driver, ('revision', 'pair_index'))
        driver.validate_result(row, row['runtime'], row['engine'], 'map-regex', True)
        regex.validate_policy(row['regex_path'], POLICY(row['revision']), 'map-regex')
    # The only flag difference allowed across variants is the measured policy.
    for m in metadata:
        flag = '-Dtrufflecel.benchmark.regexOnly=' + str(m['regex_policy'] == 'truffle-only').lower()
        if m['jvm_flags'] != [*driver.JVM_FLAGS, flag] or m['native_flags'] != [*driver.NATIVE_FLAGS, flag]:
            raise ValueError('Unexpected flag difference')
    pairs = []
    for comparison, runtime, engine, workload in itertools.product((('baseline', 'candidate'), ('baseline', 'only'), ('candidate', 'only')), RUNTIMES, ENGINES, WORKLOADS):
        reference, variant = comparison
        baseline = cells[(reference, runtime, engine, workload)]
        candidate = cells[(variant, runtime, engine, workload)]
        row = dict(reference=reference, variant=variant, runtime=runtime, engine=engine, workload=workload)
        for metric in ('time_ns', 'allocation_bytes', 'rss_mib', 'peak_rss_mib'):
            b, c = baseline[metric], candidate[metric]
            row[metric] = dict(baseline=b, candidate=c,
                              change_percent=100*(c['median']/b['median']-1),
                              observed_ranges_overlap=max(b['minimum'],c['minimum'])<=min(b['maximum'],c['maximum']))
        pairs.append(row)
    return dict(schema=1, sources={r: s['commit'] for r,s in sources.items()},
                production_trees={r: s['production_tree'] for r,s in sources.items()},
                process_counts=dict(**counts, preflight_allocation=len(preflight_rows), backend_smokes=36), cells=list(cells.values()), pairs=pairs,
                maximum_absolute_measurement_drift_percent=max(abs(x) for c in cells.values() for x in c['measurement_drift_percent']),
                statistic='median and observed range of three fork means for timing/allocation; three process RSS checkpoints',
                limits='Descriptive paired point estimates, not confidence intervals or retained heap. RSS includes setup, guest compilation, calibration and untimed probe/traversal overhead that need not allocate identically.')


def scalar_confirmation(root):
    """Replay the balanced scalar follow-up without replacing primary measurements."""
    meta = read(root / 'scalar-confirmation/metadata.json')
    staged = read(root / 'staged-sha256.json')
    if (meta['complete'] is not True or meta['script_sha256'] != sha(root / 'scalar-confirm.py')
            or meta['staged_sha256'] != staged or meta['cpus'] != list(range(2, 10))):
        raise ValueError('Incomplete or mismatched scalar confirmation')
    expected = [(block, position, revision) for block, order in
                enumerate(itertools.permutations(REVISIONS)) for position, revision in enumerate(order)]
    actual = [(r['block'], r['position'], r['variant']) for r in meta['results']]
    commands = [(r['block'], r['position'], r['variant']) for r in meta['commands']]
    if actual != expected or commands != expected:
        raise ValueError('Incorrect balanced scalar inventory/order')
    regex = importlib.import_module('optional_regex_evaluation')
    values = {r: [] for r in REVISIONS}
    for r in meta['results']:
        raw = read(root / 'scalar-confirmation' / r['file'])
        if len(raw) != 1:
            raise ValueError('Unexpected supplemental JMH inventory')
        row = raw[0]
        metric = row['primaryMetric']
        if (row['benchmark'], row['params'], row['forks'], metric['scoreUnit']) != (
                'dev.trufflecel.SteadyState.evaluate', dict(engine='truffle-jit', workload='scalar-wide'), 1, 'ns/op'):
            raise ValueError('Incorrect supplemental JMH label')
        samples = metric['rawData']
        if len(samples) != 1:
            raise ValueError('Unexpected supplemental forks')
        driver = importlib.import_module('evaluation_refresh')
        driver.finite(samples[0], 5)
        mean = sum(samples[0]) / 5
        if r['mean_ns'] != mean:
            raise ValueError('Copied scalar mean differs from raw JMH')
        markers = re.findall(r'REGEX_PATH (\S+) (\d+) (\d+) (\d+) (\S+) (\S+) (\d+)',
                             (root / 'scalar-confirmation' / r['log']).read_text())
        if len(markers) != 1:
            raise ValueError('Missing supplemental policy proof')
        b, m, a, n, p, e, c = markers[0]
        proof = dict(backend=b, match_nodes=int(m), accelerated_nodes=int(a), ast_nodes=int(n),
                     regex_policy=p, regex_execution=e, scoped_regexes=int(c))
        if proof != r['regex_path']:
            raise ValueError('Copied supplemental policy differs from raw log')
        regex.validate_policy(proof, POLICY(r['variant']), 'scalar-wide')
        values[r['variant']].append(mean)
    stats = {r: dict(median=statistics.median(v), minimum=min(v), maximum=max(v), forks=v)
             for r, v in values.items()}
    comparisons = []
    for reference, variant in (('baseline', 'candidate'), ('baseline', 'only'), ('candidate', 'only')):
        b, c = stats[reference], stats[variant]
        comparisons.append(dict(reference=reference, variant=variant, change_percent=100*(c['median']/b['median']-1),
                                observed_ranges_overlap=max(b['minimum'],c['minimum'])<=min(b['maximum'],c['maximum'])))
    return dict(processes=18, fork_means_ns=stats, comparisons=comparisons,
                statistic='median and range of six independent interleaved fork means; supplements, never replaces, primary scalar cells')


def display(stat):
    return f"{stat['median']:,.2f} [{stat['minimum']:,.2f}, {stat['maximum']:,.2f}]"


def tables(summary):
    lines = ['# Optional RE2/J policy evaluation', '', 'Values are median [minimum, maximum] across three independent forks. Lower is better.', '']
    for metric, title in [('time_ns','Evaluation time (ns/op)'),('allocation_bytes','Current-thread allocation (B/op)'),
                          ('rss_mib','Current process RSS (MiB)'),('peak_rss_mib','Lifetime peak process RSS (MiB)')]:
        lines += ['## '+title,'','| Comparison | Runtime | Mode | Fixture | Reference | Result | Change |',
                  '| --- | --- | --- | --- | ---: | ---: | ---: |']
        for row in summary['pairs']:
            data=row[metric]
            lines.append(f"| {row['reference']} → {row['variant']} | {row['runtime']} | {row['engine']} | {row['workload']} | {display(data['baseline'])} | {display(data['candidate'])} | {data['change_percent']:+.2f}% |")
        lines.append('')
    lines += ['## Supplemental JVM scalar-JIT confirmation', '', '| Variant | ns/op, median [minimum, maximum] |', '| --- | ---: |']
    for revision, values in summary['scalar_confirmation']['fork_means_ns'].items():
        lines.append(f'| {revision} | {display(values)} |')
    lines.append('')
    return '\n'.join(lines)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    summary=summarize(args.raw)
    summary["scalar_confirmation"] = scalar_confirmation(args.raw)
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    (args.output/'tables.md').write_text(tables(summary))
    print(json.dumps(summary['process_counts']), 'max drift',summary['maximum_absolute_measurement_drift_percent'])


if __name__=='__main__':
    main()
