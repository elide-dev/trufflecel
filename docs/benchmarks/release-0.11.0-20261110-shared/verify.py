#!/usr/bin/env python3
"""Verify final source identities, complete measurement inventories and actual regex execution."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools/bench'))
import evaluation_refresh
import cel_java_inputs
import regex_evaluation


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('raw', type=Path)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    args = parser.parse_args()
    manifest = json.loads((args.raw / 'source.json').read_text())
    for name, digest in manifest['files'].items():
        if hashlib.sha256((args.source_root / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Measured source differs: ' + name)
    receipt = json.loads(Path(__file__).with_name('build.json').read_text())
    if manifest['commit'] != receipt['source_commit']:
        raise ValueError('Source revision differs from build receipt')
    expected = {key: receipt['artifacts'][name]['sha256'] for key, name in (
        ('jmh', 'jmh.jar'), ('memory_jar', 'memory.jar'),
        ('native', 'timing-native'), ('memory_native', 'memory-native'))}
    archive = json.loads((args.raw / 'runtime-verification.json').read_text())
    if archive['archive_sha256'] != manifest['graalvm_archive_sha256']:
        raise ValueError('Runtime archive differs from source manifest')
    runtime = None
    for suite, engines, workloads in (
            ('evaluation', evaluation_refresh.ENGINES, evaluation_refresh.WORKLOADS),
            ('prepared-inputs', cel_java_inputs.ENGINES, cel_java_inputs.WORKLOADS)):
        for phase in ('memory', 'jvm-timing', 'native-timing'):
            directory = args.raw / suite / phase
            meta = json.loads((directory / 'metadata.json').read_text())
            rows = json.loads((directory / 'results.json').read_text())
            if not meta['complete'] or meta['source_manifest'] != manifest:
                raise ValueError('Incomplete or mismatched provenance')
            if any(meta['sha256'][key] != digest for key, digest in expected.items()):
                raise ValueError('Measured artifact differs from build receipt')
            if archive['members'] != {'bin/java': meta['sha256']['java'], **meta['runtime_sha256']}:
                raise ValueError('Measured runtime differs from pinned archive members')
            identity = (meta['host'], meta['kernel'], meta['java_version'],
                        meta['runtime_sha256'], meta['sha256']['java'], meta['cpus'])
            if runtime is not None and runtime != identity:
                raise ValueError('Different host or runtime across measured phases')
            runtime = identity
            evaluation_refresh.validate_inventory(rows, phase, engines, workloads)
            if suite == 'evaluation':
                if phase == 'jvm-timing':
                    for engine in engines[:2]:
                        for workload in workloads:
                            log = (directory / f'{engine}-{workload}.log').read_text()
                            markers = re.findall(r'REGEX_PATH (\S+) (\d+) (\d+) (\d+)', log)
                            if len(markers) != 3:
                                raise ValueError('Missing per-fork regex qualification')
                            for backend, matches, accelerated, nodes in markers:
                                regex_evaluation.validate_path(
                                    dict(backend=backend, match_nodes=int(matches),
                                         accelerated_nodes=int(accelerated), ast_nodes=int(nodes)),
                                    manifest['regex_backend'], workload)
                else:
                    for row in rows:
                        if row['engine'].startswith('truffle-'):
                            regex_evaluation.validate_path(row['regex_path'], manifest['regex_backend'], row['workload'])
    ingestion_hashes = dict((name, digest) for digest, name in (
        line.split() for line in (args.raw / 'ingestion/artifacts.sha256').read_text().splitlines()))
    if ingestion_hashes != {name: receipt['artifacts'][name]['sha256']
                           for name in ('ingestion.jar', 'ingestion-native')}:
        raise ValueError('Ingestion artifacts differ from build receipt')
    if (args.raw / 'exit-status').read_text().strip() != '0' or not (args.raw / 'completed.txt').is_file():
        raise ValueError('Measurement round did not finish successfully')
    print('Source and artifact hashes, six complete phase inventories, shared runtime identity,')
    print('per-fork regex backend checks and successful run completion pass.')


if __name__ == '__main__':
    main()
