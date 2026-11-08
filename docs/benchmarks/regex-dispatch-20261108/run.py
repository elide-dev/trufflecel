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
from regex_evaluation import validate_path

JAVA = Path('/home/sam/trufflecel-010-bench-20261108/toolchain/graalvm-ce-25.5.5-elide.27-linux-amd64/bin/java')
REVISIONS = ('baseline', 'candidate')
ENGINES = ('truffle-interpreter', 'truffle-jit')
WORKLOADS = ('scalar-wide', 'map-regex')
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
sources = {r: json.loads((BASE / r / 'source.json').read_text()) for r in REVISIONS}
for source in sources.values():
    if source['regex_backend'] != 'truffle-regex-re2' or source['dirty'] is not False:
        raise ValueError('Both sources must be clean, qualified TruffleRegex builds')
for name in ('Engines.java', 'Workloads.java', 'SteadyState.java', 'Lifecycle.java',
             'EvaluationMemory.java', 'RegexQualification.java'):
    path = 'benchmarks/representative/' + name
    if sources['baseline']['file_sha256'][path] != sources['candidate']['file_sha256'][path]:
        raise ValueError('Timed loops/fixtures/probes differ: ' + name)
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
                expected_evaluation_processes=96, expected_preflight_allocation_processes=8,
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
        directory = BASE / revision
        prefix = ([str(JAVA), *JVM_FLAGS, '-Xbatch', '-jar', str(directory / 'evaluation_memory_deploy.jar')]
                  if runtime == 'jvm' else [str(directory / 'evaluation-memory'), *NATIVE_FLAGS])
        command = [*prefix, engine, workload, 'smoke']
        key = '-'.join((revision, runtime, engine, workload))
        metadata['commands'].append(dict(argv=command, load_before=os.getloadavg(), smoke=True))
        save()
        with (smokes / (key + '.stderr')).open('w') as error:
            output = subprocess.check_output(command, text=True, stderr=error)
        (smokes / (key + '.json')).write_text(output)
        row = parse_record(output)
        validate_path(row['regex_path'], sources[revision]['regex_backend'], workload)
        if (row['input_variants'], row['bindings_per_input'], row['operations_per_sample'], row['warmup_operations']) != (4, 69, 16, 16):
            raise ValueError('Incorrect smoke inputs/counts')
        if row['allocation_calibration_bytes'] < 1024 * 1024 or len(row['allocated_bytes_per_op']) != 2:
            raise ValueError('Smoke allocation calibration failed')
        print('qualified', key, flush=True)
    metadata['qualified_smoke_processes'] = 16
    records = []
    preflight = BASE / 'preflight-allocation'
    preflight.mkdir()
    for pair, (runtime, engine) in enumerate(itertools.product(('jvm', 'svm'), ENGINES)):
        revisions = REVISIONS if pair % 2 == 0 else REVISIONS[::-1]
        for revision in revisions:
            directory = BASE / revision
            prefix = ([str(JAVA), *JVM_FLAGS, '-Xbatch', '-jar', str(directory / 'evaluation_memory_deploy.jar')]
                      if runtime == 'jvm' else [str(directory / 'evaluation-memory'), *NATIVE_FLAGS])
            command = [*prefix, engine, 'map-regex', 'measure']
            metadata['commands'].append(dict(argv=command, load_before=os.getloadavg(), preflight_allocation=True))
            save()
            row = fixed_memory(command, preflight / '-'.join((revision, runtime, engine)))
            validate_result(row, runtime, engine, 'map-regex', True)
            validate_path(row['regex_path'], sources[revision]['regex_backend'], 'map-regex')
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
if (preflight['complete'] is not True or preflight['qualified_smoke_processes'] != 16
        or preflight['allocation_processes'] != 8 or preflight['staged_sha256'] != expected
        or preflight['script_sha256'] != sha(Path(__file__))):
    raise ValueError('Full matrix requires a complete matching preflight')
metadata.update(preflight_complete=True, preflight_sha256=sha(BASE / 'preflight.json'),
                qualified_smoke_processes=16)
save()

for phase_index, phase in enumerate(('memory', 'jvm-timing', 'native-timing')):
    cells = list(itertools.product(ENGINES, WORKLOADS))
    random.Random(20261108 + phase_index).shuffle(cells)
    for pair, (engine, workload) in enumerate(cells):
        revisions = REVISIONS if (pair + phase_index) % 2 == 0 else REVISIONS[::-1]
        for revision in revisions:
            directory = BASE / revision
            output = BASE / 'evaluation' / revision / phase / (engine + '-' + workload)
            command = [sys.executable, str(SCRIPTS / 'regex_evaluation.py'), engine, workload, phase,
                       '--java', str(JAVA), '--jmh', str(directory / 'jmh_deploy.jar'),
                       '--memory-jar', str(directory / 'evaluation_memory_deploy.jar'),
                       '--native', str(directory / 'representative'),
                       '--memory-native', str(directory / 'evaluation-memory'),
                       '--source-manifest', str(directory / 'source.json'), '--output', str(output),
                       '--cpus', '2,3,4,5,6,7,8,9']
            run(command)
            proof = json.loads((output / 'metadata.json').read_text())
            if proof['complete'] is not True or proof.get('regex_path_verified') is not True:
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
