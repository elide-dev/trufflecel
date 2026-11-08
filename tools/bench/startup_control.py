#!/usr/bin/env python3
"""Paired scalar-wide first-result control: four variants, two engines, JVM/runtime native."""
import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import random
import subprocess

import startup
from representative import FLAGS, NATIVE_FLAGS, sha

REVISIONS = ('baseline', 'candidate')
FORKS = 4
FIXTURE_SOURCES = ('benchmarks/representative/Startup.java', 'benchmarks/representative/Workloads.java')


def cells():
    return {cell for cell in startup.inventory(True, FORKS)
            if cell[1] in ('truffle-interpreter', 'truffle-jit')
            and cell[2:4] == ('scalar-wide', 'runtime')}


def source_revision(manifest):
    revision = manifest.get('commit', manifest.get('source_revision'))
    if not isinstance(revision, str) or not revision or manifest.get('dirty') is not False:
        raise ValueError('Use an explicitly identified clean source manifest')
    return revision


def validate_sources(manifests):
    if source_revision(manifests['baseline']) == source_revision(manifests['candidate']):
        raise ValueError('Baseline and candidate must identify separate source revisions')
    files = {revision: value.get('file_sha256', value.get('files', {}))
             for revision, value in manifests.items()}
    for name in FIXTURE_SOURCES:
        baseline = files['baseline'].get(name)
        if not isinstance(baseline, str) or len(baseline) != 64 or baseline != files['candidate'].get(name):
            raise ValueError('Startup fixture or process protocol differs between revisions: ' + name)


def validate_results(rows):
    expected = {(revision, *cell) for revision in REVISIONS for cell in cells()}
    seen = set()
    for row in rows:
        key = tuple(row[k] for k in ('revision', 'runtime', 'engine', 'workload', 'mode', 'fork'))
        if key not in expected or key in seen or row['input_index'] != row['fork']:
            raise ValueError('Unexpected, duplicate or unpaired startup-control cell')
        startup.validate(row)
        seen.add(key)
    if seen != expected:
        raise ValueError('Incomplete paired startup-control matrix')


def driver_provenance():
    observer = Path(startup.__file__)
    return dict(driver_sha256=sha(Path(__file__)), observer_driver_sha256=sha(observer),
                shared_driver_sha256=sha(observer.with_name('representative.py')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    for revision in REVISIONS:
        for name in ('jar', 'native', 'source-manifest'):
            parser.add_argument(f'--{revision}-{name}', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cpus', required=True)
    parser.add_argument('--host-conditions', required=True)
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('Use pinned Linux x86_64 GraalVM artifacts')
    artifacts = {revision: {name: getattr(args, revision + '_' + name).resolve()
                             for name in ('jar', 'native', 'source_manifest')} for revision in REVISIONS}
    manifests = {revision: json.loads(paths['source_manifest'].read_text())
                 for revision, paths in artifacts.items()}
    validate_sources(manifests)
    hashes = {revision: {name: sha(path) for name, path in paths.items()}
              for revision, paths in artifacts.items()}
    if any(hashes['baseline'][kind] == hashes['candidate'][kind] for kind in ('jar', 'native')):
        raise ValueError('Separate baseline/candidate artifacts are required')
    os.sched_setaffinity(0, set(map(int, args.cpus.split(','))))
    args.output.mkdir(parents=True, exist_ok=False)
    java = args.java.resolve()
    java_home = java.parent.parent
    runtime_paths = {name: java_home / name for name in ('release', 'lib/modules', 'lib/server/libjvm.so')}
    runtime_hashes = {name: sha(path) for name, path in runtime_paths.items()}
    java_hash = sha(java)

    def capture(command):
        return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)

    java_version = capture([str(java), '-version'])
    if 'GraalVM' not in java_version:
        raise ValueError('Use the pinned GraalVM distribution')
    common = dict(schema=1, complete=False, captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  host=platform.node(), kernel=platform.release(), cpuinfo=Path('/proc/cpuinfo').read_text(),
                  cpus=sorted(os.sched_getaffinity(0)), host_conditions=args.host_conditions,
                  load_start=os.getloadavg(), java_version=java_version, java_sha256=java_hash,
                  java_home_release=(java_home / 'release').read_text(), runtime_sha256=runtime_hashes,
                  vm_flags=capture([str(java), *FLAGS, '-XX:+PrintFlagsFinal', '-version']),
                  **driver_provenance(),
                  forks=FORKS, input_indices=list(range(FORKS)), bindings_per_input=69,
                  jvm_flags=FLAGS, native_flags=NATIVE_FLAGS,
                  clock='existing startup.observe: before Popen through flushed independently verified first result',
                  preparation='runtime AST and adapter; native generated schema may be initialized at image build',
                  controls='paired scalar-wide only; no empty-engine controls or image-prepared artifacts',
                  random_seed=20261108,
                  order='adjacent revision pairs in shuffled cell order; revision-first order alternates')
    outputs = {}
    for revision in REVISIONS:
        (args.output / revision).mkdir()
        outputs[revision] = dict(common, revision=revision, source_manifest=manifests[revision],
                                 source_manifest_sha256=hashes[revision]['source_manifest'],
                                 artifacts={name: str(path) for name, path in artifacts[revision].items()},
                                 jar_sha256=hashes[revision]['jar'], native_sha256=hashes[revision]['native'],
                                 commands=[], results=[])

    def save():
        for revision, value in outputs.items():
            (args.output / revision / 'startup.json').write_text(json.dumps(value, indent=2) + '\n')

    jobs = sorted(cells())
    random.Random(20261108).shuffle(jobs)
    save()
    for pair, (runtime, engine, workload, mode, fork) in enumerate(jobs):
        for position, revision in enumerate(REVISIONS if pair % 2 == 0 else REVISIONS[::-1]):
            paths = artifacts[revision]
            prefix = ([str(java), *FLAGS, '-jar', str(paths['jar'])] if runtime == 'jvm'
                      else [str(paths['native']), *NATIVE_FLAGS])
            command = [*prefix, engine, workload, mode, str(fork)]
            name = '-'.join(map(str, (runtime, engine, workload, mode, fork)))
            outputs[revision]['commands'].append(command)
            save()
            print(f'{pair + 1}/{len(jobs)} {revision} {name}', flush=True)
            directory = args.output / revision
            row = startup.observe(command, directory / (name + '.stdout'), directory / (name + '.stderr'), 180)
            row.update(revision=revision, runtime=runtime, engine=engine, workload=workload,
                       mode=mode, fork=fork, input_index=fork, pair_index=pair, revision_position=position)
            startup.validate(row)
            outputs[revision]['results'].append(row)
            save()
    validate_results([row for revision in REVISIONS for row in outputs[revision]['results']])
    if hashes != {revision: {name: sha(path) for name, path in paths.items()}
                  for revision, paths in artifacts.items()}:
        raise ValueError('Source manifest or artifact changed during measurement')
    if java_hash != sha(java) or runtime_hashes != {name: sha(path) for name, path in runtime_paths.items()}:
        raise ValueError('Runtime changed during measurement')
    for output in outputs.values():
        output.update(complete=True, load_end=os.getloadavg())
    save()


if __name__ == '__main__':
    main()
