#!/usr/bin/env python3
"""Rank self instruction costs in the named regions of saved CodSpeed profiles."""
import argparse
from collections import Counter
from pathlib import Path


def regions(path):
    uri, function, events, costs = None, None, [], Counter()
    call = False
    names = {}
    for line in path.read_text().splitlines():
        if line.startswith('part:'):
            uri, function, events, costs, call = None, None, [], Counter(), False
        elif line.startswith('desc: Trigger: Client Request: benchmarks/'):
            uri = line.removeprefix('desc: Trigger: Client Request: ')
        elif line.startswith('positions:') and line != 'positions: line':
            raise ValueError('Expected line-position profiles')
        elif line.startswith('events:'):
            events = line.split()[1:]
        elif line.startswith(('fn=', 'cfn=')):
            name = line.split('=', 1)[1]
            if name.startswith('('):
                key, _, definition = name.partition(')')
                if definition.strip():
                    names[key] = definition.strip()
                name = names[key]
            if line.startswith('fn='):
                function = name
        elif line.startswith('calls='):
            call = True
        elif uri and line and line[0] in '0123456789*+-':
            if not call:
                if function is None:
                    raise ValueError('Cost without function')
                costs[function] += int(line.split()[1 + events.index('Ir')])
            call = False
        elif uri and line.startswith('totals:'):
            total = int(line.split()[1 + events.index('Ir')])
            if total <= 0 or sum(costs.values()) != total:
                raise ValueError('Self costs do not reconcile with region total')
            yield uri, total, costs
            uri = None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--case', help='Case name, e.g. comprehension')
    parser.add_argument('--top', type=int, default=15)
    args = parser.parse_args()
    seen = set()
    for path in sorted(args.folder.glob('*.out')):
        for uri, total, costs in regions(path):
            if args.case and f'/{args.case}[' not in uri:
                continue
            if uri in seen:
                raise ValueError('Duplicate region: ' + uri)
            seen.add(uri)
            iterations = int(uri.split('[iterations=')[1].rstrip(']'))
            print(f'\n{uri}: {total / iterations:,.2f} instructions/op')
            for function, cost in costs.most_common(args.top):
                print(f'{cost / total:6.2%} {cost / iterations:12,.2f}/op  {function}')
    if not seen:
        raise ValueError('No matching benchmark regions')


if __name__ == '__main__':
    main()
