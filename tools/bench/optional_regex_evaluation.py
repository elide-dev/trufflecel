#!/usr/bin/env python3
"""Qualify compatibility versus TruffleRegex-only evaluation without changing timed loops."""
import argparse
import json
from pathlib import Path
import re
import sys
import evaluation_refresh

WORKLOADS = ('scalar-wide', 'map-regex', 'map-regex-dynamic')
ENGINES = ('truffle-interpreter', 'truffle-jit')


def validate_policy(record, policy, workload):
    dynamic = workload == 'map-regex-dynamic'
    matches = int(workload == 'map-regex')
    execution = ('none' if workload == 'scalar-wide' else
                 're2j' if dynamic and policy == 'compatibility' else 'truffle-regex-re2')
    cached = matches or (2 if dynamic and policy == 'truffle-only' else 0)
    expected = ('truffle-regex-re2', matches, matches, policy, execution, cached)
    actual = tuple(record[k] for k in ('backend', 'match_nodes', 'accelerated_nodes',
                                      'regex_policy', 'regex_execution', 'scoped_regexes'))
    if actual != expected or not isinstance(record['ast_nodes'], int) or record['ast_nodes'] <= 0:
        raise ValueError('Incorrect regex policy/backend/cache proof')


def qualify(output, metadata, rows, policy, engine, workload):
    if metadata['phase'] == 'jvm-timing':
        log = (output / (engine + '-' + workload + '.log')).read_text()
        markers = re.findall(r'REGEX_PATH (\S+) (\d+) (\d+) (\d+) (\S+) (\S+) (\d+)', log)
        if len(markers) != 3:
            raise ValueError('Every JMH fork must report its actual regex policy')
        records = [dict(backend=b, match_nodes=int(m), accelerated_nodes=int(a), ast_nodes=int(n),
                        regex_policy=p, regex_execution=e, scoped_regexes=int(c))
                   for b, m, a, n, p, e, c in markers]
    else:
        records = [row['regex_path'] for row in rows]
    for record in records:
        validate_policy(record, policy, workload)
    metadata.update(regex_path_verified=True, regex_policy_verified=True, regex_policy=policy)


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('engine', choices=ENGINES)
    parser.add_argument('workload', choices=WORKLOADS)
    parser.add_argument('--policy', choices=('compatibility', 'truffle-only'), required=True)
    args, rest = parser.parse_known_args()
    flag = '-Dtrufflecel.benchmark.regexOnly=' + str(args.policy == 'truffle-only').lower()
    evaluation_refresh.JVM_FLAGS = [*evaluation_refresh.JVM_FLAGS, flag]
    evaluation_refresh.NATIVE_FLAGS = [*evaluation_refresh.NATIVE_FLAGS, flag]
    sys.argv = [sys.argv[0], *rest]
    evaluation_refresh.main(engines=(args.engine,), workloads=(args.workload,),
                            experiment='optional regex dependency/policy qualification', entrypoint=__file__,
                            qualify=lambda o, m, r: qualify(o, m, r, args.policy, args.engine, args.workload))


if __name__ == '__main__':
    main()
