#!/usr/bin/env python3
# Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Summarize host load, guard observations and window drift for a guarded round."""
import argparse
import json
from pathlib import Path

PHASES = [f'{suite}/{phase}' for suite in ('evaluation', 'prepared-inputs')
          for phase in ('memory', 'jvm-timing', 'native-timing')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('raw', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    phases = []
    for name in PHASES:
        meta = json.loads((args.raw / name / 'metadata.json').read_text())
        loads = [meta['load_start'][0], meta['load_end'][0]] + [c['load_before'][0] for c in meta['commands']]
        phases.append(dict(phase=name, captured_at=meta['captured_at'], min_one_minute_load=min(loads),
                           max_one_minute_load=max(loads),
                           commands_above_load_5=sum(c['load_before'][0] > 5 for c in meta['commands']),
                           commands=len(meta['commands'])))
    guard = json.loads((args.raw / 'guard-status.json').read_text())
    rows = [json.loads(line) for line in (args.raw / 'host-monitor.jsonl').read_text().splitlines()]
    measured = [r for r in rows if r['stage'] == 'measurement']
    summary = json.loads((args.report / 'summary.json').read_text())
    drift = [dict(runtime=c['runtime'], engine=c['engine'], workload=c['workload'],
                  measurement_drift_percent=c['measurement_drift_percent'])
             for c in summary['cells'] if any(abs(d) > 10 for d in c['measurement_drift_percent'])]
    audit = dict(
        phases=phases,
        guard=dict(status=guard['status'], exit_code=guard['exit_code'], started_at=guard['started_at'],
                   completed_at=guard['completed_at'], interval_seconds=5,
                   measurement_observations=len(measured),
                   observations_with_external_work=sum(bool(r['external_work']) for r in measured),
                   max_one_minute_load=max(r['load'][0] for r in measured)),
        timing_cells_over_10_percent_window_drift=drift,
        caveat='Every five seconds the guard flags other processes above 20% CPU for at least five seconds, '
               'or named compilers/build tools. It cannot detect shorter or lighter work, and it does not '
               'establish exclusive use of the host.')
    (args.report / 'host-audit.json').write_text(json.dumps(audit, indent=2) + '\n')
    print(f'{len(measured)} guarded observations, {audit["guard"]["observations_with_external_work"]} with '
          f'external work; {len(drift)} timing cells over 10% window drift')


if __name__ == '__main__':
    main()
