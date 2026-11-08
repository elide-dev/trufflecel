#!/usr/bin/env python3
"""Reconstruct both complete measured trees without requiring unpublished commits."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw', required=True, type=Path)
    args = parser.parse_args()
    repository = subprocess.check_output(
        ['git', 'rev-parse', '--show-toplevel'], cwd=Path(__file__).parent, text=True).strip()
    for revision in ('baseline', 'candidate'):
        source = args.raw / revision
        manifest = json.loads((source / 'source.json').read_text())
        with tempfile.TemporaryDirectory(prefix='trufflecel-regex-source-') as temporary:
            env = dict(os.environ, GIT_INDEX_FILE=str(Path(temporary) / 'index'))

            def git(*argv, data=None):
                return subprocess.check_output(['git', *argv], cwd=repository, env=env, input=data)

            git('read-tree', 'ecb07bfeadfa18dbd6dc5f11c30eb98cc4e628fe')
            git('apply', '--cached', '-', data=(source / 'source.patch').read_bytes())
            tree = git('write-tree').decode().strip()
            if tree != json.loads((args.raw / 'source-provenance.json').read_text())[revision]['tree']:
                raise ValueError('Reconstructed complete tree differs: ' + revision)
            production_tree = git('rev-parse', tree + ':src/main/java').decode().strip()
            if production_tree != manifest['production_tree']:
                raise ValueError('Reconstructed production tree differs: ' + revision)
            for path, expected in manifest['file_sha256'].items():
                if hashlib.sha256(git('show', tree + ':' + path)).hexdigest() != expected:
                    raise ValueError('Reconstructed source digest differs: ' + path)
            print(revision, tree, len(manifest['file_sha256']), 'source hashes verified')


if __name__ == '__main__':
    main()
