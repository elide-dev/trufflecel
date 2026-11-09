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
    args = parser.parse_args()
    manifest = json.loads((args.raw / 'source.json').read_text())
    for name, digest in manifest['files'].items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Measured source differs: ' + name)
    for suite, engines, workloads in (
            ('evaluation', evaluation_refresh.ENGINES, evaluation_refresh.WORKLOADS),
            ('prepared-inputs', cel_java_inputs.ENGINES, cel_java_inputs.WORKLOADS)):
        for phase in ('memory', 'jvm-timing', 'native-timing'):
            directory = args.raw / suite / phase
            meta = json.loads((directory / 'metadata.json').read_text())
            rows = json.loads((directory / 'results.json').read_text())
            if not meta['complete'] or meta['source_manifest'] != manifest:
                raise ValueError('Incomplete or mismatched provenance')
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
    print('Source hashes, six complete phase inventories and per-fork regex backend checks pass.')


if __name__ == '__main__':
    main()
