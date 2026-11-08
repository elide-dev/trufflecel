#!/usr/bin/env python3
"""One matched regex/control cell; alternate baseline/candidate blocks externally."""

import argparse
import json
from pathlib import Path
import re
import sys

import evaluation_refresh

ENGINES = ('truffle-interpreter', 'truffle-jit')
WORKLOADS = ('scalar-wide', 'map-regex')
BACKENDS = ('re2j', 'truffle-regex-re2')


def validate_path(record, backend, workload):
    expected = int(backend == 'truffle-regex-re2' and workload == 'map-regex')
    if (record['backend'], record['match_nodes'], record['accelerated_nodes']) != (backend, expected, expected):
        raise ValueError('Regex backend/count mismatch; fallback is not an accelerated measurement')
    if not isinstance(record['ast_nodes'], int) or record['ast_nodes'] <= 0:
        raise ValueError('Missing evaluated AST inventory')


def qualify(output, metadata, rows, backend, engine, workload):
    try:
        if metadata['phase'] == 'jvm-timing':
            log = (output / (engine + '-' + workload + '.log')).read_text()
            markers = re.findall(r'REGEX_PATH (\S+) (\d+) (\d+) (\d+)', log)
            if len(markers) != 3:
                raise ValueError('Each JMH fork must verify its final regex path')
            for observed, matches, accelerated, nodes in markers:
                validate_path(dict(backend=observed, match_nodes=int(matches),
                                   accelerated_nodes=int(accelerated), ast_nodes=int(nodes)), backend, workload)
        else:
            for row in rows:
                validate_path(row['regex_path'], backend, workload)
        metadata['regex_path_verified'] = True
    except BaseException as error:
        metadata.update(complete=False, regex_path_verified=False, regex_path_error=str(error))
        (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        raise


if __name__ == '__main__':
    if len(sys.argv) < 4 or sys.argv[1] not in ENGINES or sys.argv[2] not in WORKLOADS:
        raise SystemExit('usage: regex_evaluation.py ENGINE WORKLOAD PHASE [evaluation_refresh options]')
    engine, workload = sys.argv[1:3]
    del sys.argv[1:3]
    if '--help' in sys.argv or '-h' in sys.argv:
        evaluation_refresh.main(engines=(engine,), workloads=(workload,), entrypoint=__file__)
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--source-manifest', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args, _ = parser.parse_known_args()
    backend = json.loads(args.source_manifest.read_text())['regex_backend']
    if backend not in BACKENDS:
        raise ValueError('Source manifest must identify the expected regex backend')
    evaluation_refresh.main(
        engines=(engine,), workloads=(workload,),
        experiment='paired regex qualification: one full-input evaluation cell',
        entrypoint=__file__,
        qualify=lambda output, metadata, rows: qualify(output, metadata, rows, backend, engine, workload))
