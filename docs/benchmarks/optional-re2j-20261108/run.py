#!/usr/bin/env python3
"""Paired cached-dispatch qualification; run preflight before the immutable full matrix."""
import argparse
import datetime
import hashlib
import itertools
import json
import os
from pathlib import Path
import random
import statistics
import subprocess
import sys

BASE = Path(__file__).resolve().parent
SCRIPTS = BASE / 'scripts'
sys.path.insert(0, str(SCRIPTS))
from evaluation_refresh import JVM_FLAGS, NATIVE_FLAGS, fixed_memory, parse_record, validate_result
from optional_regex_evaluation import validate_policy

JAVA = Path('/home/sam/trufflecel-010-bench-20261108/toolchain/graalvm-ce-25.5.5-elide.27-linux-amd64/bin/java')
REVISIONS = ('baseline', 'candidate', 'only')
POLICY = lambda r: 'truffle-only' if r == 'only' else 'compatibility'
DIR = lambda r: BASE / ('candidate' if r == 'only' else r)
ENGINES = ('truffle-interpreter', 'truffle-jit')
WORKLOADS = ('scalar-wide', 'map-regex', 'map-regex-dynamic')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('phase', choices=('preflight', 'matrix'))
args = parser.parse_args()
os.sched_setaffinity(0, set(range(2, 10)))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


expected = json.loads((BASE / 'staged-sha256.json').read_text())
for name, digest in expected.items():
    if sha(BASE / name) != digest:
        raise ValueError('Staged artifact/source mismatch: ' + name)
sources = {r: json.loads((DIR(r) / 'source.json').read_text()) for r in REVISIONS}
for source in sources.values():
    if source['regex_backend'] != 'truffle-regex-re2' or source['dirty'] is not False:
        raise ValueError('Both sources must be clean, qualified TruffleRegex builds')
for name in ('Workloads.java', 'SteadyState.java', 'Lifecycle.java',
             'EvaluationMemory.java', 'RegexQualification.java'):
    path = 'benchmarks/representative/' + name
    if sources['baseline']['file_sha256'][path] != sources['candidate']['file_sha256'][path]:
        raise ValueError('Timed loops/fixtures/probes differ: ' + name)
expected_engines = {'baseline': '7dcdc76acc95250a3883b19bae5e41123f955af3c1415625894cfd9ddd24c3d3', 'candidate': 'ddb8fa0c891a412d3818428b6b5e5de90a1f85d56be85e8ea4a07ec44dbcc850'}
for r in ('baseline', 'candidate'):
    if sources[r]['file_sha256']['benchmarks/representative/Engines.java'] != expected_engines[r]:
        raise ValueError('Unexpected untimed helper overlay')
for path in ('.bazelversion', 'MODULE.bazel', 'MODULE.bazel.lock', 'maven_install.json'):
    if sources['baseline']['file_sha256'][path] != sources['candidate']['file_sha256'][path]:
        raise ValueError('Dependency/toolchain inputs differ: ' + path)
for name in ('evaluation_refresh.py', 'regex_evaluation.py', 'representative.py'):
    for source in sources.values():
        if source['file_sha256']['tools/bench/' + name] != sha(SCRIPTS / name):
            raise ValueError('Protocol differs from artifact source: ' + name)
metadata_path = BASE / ('preflight.json' if args.phase == 'preflight' else 'orchestration.json')
metadata = dict(schema=1, complete=False, captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                script_sha256=sha(Path(__file__)), staged_sha256=expected, cpus=sorted(os.sched_getaffinity(0)),
                source_revisions={r: s['commit'] for r, s in sources.items()},
                load_start=os.getloadavg(), commands=[], completed_cells=[],
                phase=args.phase, order='memory/JVM/native phases with alternating revision-first cell blocks',
                expected_evaluation_processes=216, expected_preflight_allocation_processes=12,
                limits='Shared observed low-load host, no CPU isolation/global tuning. Probe traversal and metadata are outside timing/counters but included in RSS; observer implementations/AST sizes can differ.')
if metadata_path.exists():
    raise ValueError('Refuse to overwrite an existing run')


def save():
    metadata_path.write_text(json.dumps(metadata, indent=2) + '\n')


def run(command):
    metadata['commands'].append(dict(argv=list(map(str, command)), load_before=os.getloadavg()))
    save()
    subprocess.run(command, check=True)


save()
if args.phase == 'preflight':
    smokes = BASE / 'qualification-smokes'
    smokes.mkdir()
    for revision, runtime, engine, workload in itertools.product(REVISIONS, ('jvm', 'svm'), ENGINES, WORKLOADS):
        directory = DIR(revision)
        prefix = ([str(JAVA), *JVM_FLAGS, '-Dtrufflecel.benchmark.regexOnly=' + str(revision == 'only').lower(), '-Xbatch', '-jar', str(directory / 'evaluation_memory_deploy.jar')]
                  if runtime == 'jvm' else [str(directory / 'evaluation-memory'), *NATIVE_FLAGS, '-Dtrufflecel.benchmark.regexOnly=' + str(revision == 'only').lower()])
        command = [*prefix, engine, workload, 'smoke']
        key = '-'.join((revision, runtime, engine, workload))
        metadata['commands'].append(dict(argv=command, load_before=os.getloadavg(), smoke=True))
        save()
        with (smokes / (key + '.stderr')).open('w') as error:
            output = subprocess.check_output(command, text=True, stderr=error)
        (smokes / (key + '.json')).write_text(output)
        row = parse_record(output)
        validate_policy(row['regex_path'], POLICY(revision), workload)
        if (row['input_variants'], row['bindings_per_input'], row['operations_per_sample'], row['warmup_operations']) != (4, 69, 16, 16):
            raise ValueError('Incorrect smoke inputs/counts')
        if row['allocation_calibration_bytes'] < 1024 * 1024 or len(row['allocated_bytes_per_op']) != 2:
            raise ValueError('Smoke allocation calibration failed')
        print('qualified', key, flush=True)
    metadata['qualified_smoke_processes'] = 36
    records = []
    preflight = BASE / 'preflight-allocation'
    preflight.mkdir()
    for pair, (runtime, engine) in enumerate(itertools.product(('jvm', 'svm'), ENGINES)):
        revisions = REVISIONS if pair % 2 == 0 else REVISIONS[::-1]
        for revision in revisions:
            directory = DIR(revision)
            prefix = ([str(JAVA), *JVM_FLAGS, '-Dtrufflecel.benchmark.regexOnly=' + str(revision == 'only').lower(), '-Xbatch', '-jar', str(directory / 'evaluation_memory_deploy.jar')]
                      if runtime == 'jvm' else [str(directory / 'evaluation-memory'), *NATIVE_FLAGS, '-Dtrufflecel.benchmark.regexOnly=' + str(revision == 'only').lower()])
            command = [*prefix, engine, 'map-regex', 'measure']
            metadata['commands'].append(dict(argv=command, load_before=os.getloadavg(), preflight_allocation=True))
            save()
            row = fixed_memory(command, preflight / '-'.join((revision, runtime, engine)))
            validate_result(row, runtime, engine, 'map-regex', True)
            validate_policy(row['regex_path'], POLICY(revision), 'map-regex')
            records.append(dict(**row, revision=revision, pair_index=pair))
            (preflight / 'results.json').write_text(json.dumps(records, indent=2) + '\n')
    pairs = []
    for runtime, engine in itertools.product(('jvm', 'svm'), ENGINES):
        values = {r['revision']: statistics.mean(r['allocated_bytes_per_op']) for r in records
                  if r['runtime'] == runtime and r['engine'] == engine}
        pairs.append(dict(runtime=runtime, engine=engine, baseline_bytes=values['baseline'],
                          candidate_bytes=values['candidate'], delta_bytes=values['candidate'] - values['baseline']))
    metadata.update(allocation_pairs=pairs, allocation_processes=len(records), complete=True, load_end=os.getloadavg())
    for name, digest in expected.items():
        if sha(BASE / name) != digest:
            raise ValueError('Artifact/source changed during preflight: ' + name)
    save()
    print(json.dumps(pairs), flush=True)
    sys.exit(0)

preflight = json.loads((BASE / 'preflight.json').read_text())
if (preflight['complete'] is not True or preflight['qualified_smoke_processes'] != 36
        or preflight['allocation_processes'] != 12 or preflight['staged_sha256'] != expected
        or preflight['script_sha256'] != sha(Path(__file__))):
    raise ValueError('Full matrix requires a complete matching preflight')
metadata.update(preflight_complete=True, preflight_sha256=sha(BASE / 'preflight.json'),
                qualified_smoke_processes=36)
save()

for phase_index, phase in enumerate(('memory', 'jvm-timing', 'native-timing')):
    cells = list(itertools.product(ENGINES, WORKLOADS))
    random.Random(20261108 + phase_index).shuffle(cells)
    for pair, (engine, workload) in enumerate(cells):
        offset = (pair + phase_index) % 3
        revisions = REVISIONS[offset:] + REVISIONS[:offset]
        for revision in revisions:
            directory = DIR(revision)
            output = BASE / 'evaluation' / revision / phase / (engine + '-' + workload)
            command = [sys.executable, str(SCRIPTS / 'optional_regex_evaluation.py'), engine, workload, '--policy', POLICY(revision), phase,
                       '--java', str(JAVA), '--jmh', str(directory / 'jmh_deploy.jar'),
                       '--memory-jar', str(directory / 'evaluation_memory_deploy.jar'),
                       '--native', str(directory / 'representative'),
                       '--memory-native', str(directory / 'evaluation-memory'),
                       '--source-manifest', str(directory / 'source.json'), '--output', str(output),
                       '--cpus', '2,3,4,5,6,7,8,9']
            run(command)
            proof = json.loads((output / 'metadata.json').read_text())
            if proof['complete'] is not True or proof.get('regex_path_verified') is not True or proof.get('regex_policy_verified') is not True:
                raise ValueError('Unqualified cell must not count as completed')
            metadata['completed_cells'].append(dict(phase=phase, engine=engine, workload=workload,
                                                    revision=revision, pair_index=pair,
                                                    revision_position=revisions.index(revision)))
            save()
            print('qualified cell', phase, revision, engine, workload, flush=True)
for name, digest in expected.items():
    if sha(BASE / name) != digest:
        raise ValueError('Artifact/source changed during run: ' + name)
metadata.update(complete=True, load_end=os.getloadavg())
save()
