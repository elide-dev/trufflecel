"""Describe the actual checked-out release and the Maven bytes just published.

SPDX-License-Identifier: Apache-2.0 OR MIT
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

spec = importlib.util.spec_from_file_location('verify_release', Path(__file__).with_name('verify-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

BUILD_TYPE = 'https://github.com/elide-dev/trufflecel/blob/main/docs/PROVENANCE.md#build-type-v1'
# GitHub's attestation service rejects slsa.dev/provenance/v1 statements whose buildType it
# does not define (v0.11.0 and v0.12.0 failed signing with "unsupported build type"), so the
# SLSA-shaped body below is signed under this project-owned predicate type instead.
PREDICATE_TYPE = 'https://github.com/elide-dev/trufflecel/blob/main/docs/PROVENANCE.md#predicate-v1'


def describe(root, staged, version, env, git):
    release.verify(root, version, git)
    commit = git('rev-parse', 'HEAD').strip()
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Expected a full source commit')
    if env['GITHUB_REPOSITORY'] != 'elide-dev/trufflecel' or env['GITHUB_REF'] != 'refs/heads/main':
        raise ValueError('Only the main-branch publisher may attest releases')
    run_id, attempt = env['GITHUB_RUN_ID'], env['GITHUB_RUN_ATTEMPT']
    if not run_id.isdecimal() or not attempt.isdecimal():
        raise ValueError('Invalid workflow invocation')
    staged = staged.resolve()
    if staged.parent != (root / 'build').resolve() or not staged.name.startswith('release-repo.'):
        raise ValueError('Expected a repository staged by publish.sh')
    subjects = []
    for module in ('core', 'protobuf', 'json', 'processor'):
        artifact = 'trufflecel-' + module
        for suffix in ('.jar', '-sources.jar', '.pom'):
            name = f'{artifact}-{version}{suffix}'
            path = staged / 'dev/elide' / artifact / version / name
            if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(staged):
                raise ValueError(f'Missing or unsafe release subject: {name}')
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if path.with_name(name + '.sha256').read_text().strip() != digest:
                raise ValueError(f'Release checksum mismatch: {name}')
            subjects.append(f'{digest}  {name}\n')
    repository = 'https://github.com/' + env['GITHUB_REPOSITORY']
    predicate = {
        'buildDefinition': {
            'buildType': BUILD_TYPE,
            'externalParameters': {'version': version, 'ref': 'refs/tags/v' + version},
            'internalParameters': {
                'workflowRef': env['GITHUB_WORKFLOW_REF'],
                'workflowSha': env['GITHUB_WORKFLOW_SHA'],
            },
            'resolvedDependencies': [{
                'uri': 'git+' + repository + '@refs/tags/v' + version,
                'digest': {'gitCommit': commit},
            }],
        },
        'runDetails': {
            'builder': {'id': repository + '/.github/workflows/publish.maven.yml@refs/heads/main'},
            'metadata': {'invocationId': f'{repository}/actions/runs/{run_id}/attempts/{attempt}'},
        },
    }
    return predicate, ''.join(subjects)


if __name__ == '__main__':
    root = Path.cwd()
    predicate, subjects = describe(root, Path(os.environ['STAGED_REPOSITORY']),
                                   os.environ['RELEASE_VERSION'], os.environ,
                                   lambda *args: subprocess.check_output(['git', *args], text=True))
    output = root / 'build/release-provenance'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'predicate.json').write_text(json.dumps(predicate, indent=2) + '\n')
    (output / 'subjects.sha256').write_text(subjects)
