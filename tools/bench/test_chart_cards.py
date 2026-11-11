# Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Card decisions a reader sees: chips, validation and recomputed counts.

Drawing needs fontTools; these tests cover the data path and run with the standard library.
"""
import copy
import json
from pathlib import Path
import sys
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / 'charts'))
from cards import Row  # noqa: E402
import render_cards  # noqa: E402

REPORT = HERE.parents[1] / 'docs/benchmarks/release-0.11.0-20261110-shared'


def load(name):
    return json.loads((REPORT / name).read_text())


class VerdictTest(unittest.TestCase):
    def test_time_ratio_words(self):
        self.assertEqual(render_cards.verdict(50, 100, 'time'), ('2.00× faster', 'lower'))
        self.assertEqual(render_cards.verdict(150, 100, 'time'), ('1.50× slower', 'higher'))

    def test_allocation_and_memory_words(self):
        self.assertEqual(render_cards.verdict(25, 100, 'allocation'), ('4.00× less', 'lower'))
        self.assertEqual(render_cards.verdict(110, 100, 'memory'), ('10% more', 'higher'))

    def test_tie_is_not_a_win(self):
        self.assertEqual(render_cards.verdict(96, 96, 'allocation'), ('tied', 'tied'))


class ChipTest(unittest.TestCase):
    def rows(self, *ours):
        names = ('TruffleCEL interpreter', 'TruffleCEL guest JIT')
        return [Row(n, v, v, v, True) for n, v in zip(names, ours)] + [
            Row('cel-java default', 200, 200, 200), Row('cel-java optimized', 100, 100, 100)]

    def test_compares_with_the_better_cel_java_planner(self):
        self.assertEqual(render_cards.chip(self.rows(50), 'time'), ('2.00× faster vs cel-java optimized', 'good'))

    def test_mixed_outcomes_are_neutral_and_name_each_mode(self):
        text, tone = render_cards.chip(self.rows(90, 120), 'memory')
        self.assertEqual(text, 'Interpreter 10% less · Guest JIT 20% more vs cel-java optimized')
        self.assertEqual(tone, 'neutral')

    def test_losses_are_reported(self):
        self.assertEqual(render_cards.chip(self.rows(150, 300), 'time')[1], 'bad')

    def test_no_chip_without_both_classes(self):
        self.assertIsNone(render_cards.chip([Row('cel-java default', 1, 1, 1)], 'time'))


class ValidationTest(unittest.TestCase):
    def test_retained_report_builds_every_card(self):
        cards, cells, controls = render_cards.build_cards(load('summary.json'), load('prepared-inputs.json'),
                                                          load('source.json'), '')
        self.assertEqual((len(cards), len(cells), len(controls)), (7, 48, 16))
        self.assertTrue(all(len(c.panels) == 12 for c in cards[:5]))
        self.assertIn('Interpreter lower in 12 of 12 comparisons', cards[0].subtitle)

    def test_missing_cell_is_rejected(self):
        summary = load('summary.json')
        summary['cells'].pop()
        with self.assertRaisesRegex(ValueError, 'Expected exactly 48'):
            render_cards.index(summary['cells'], render_cards.ENGINES, render_cards.WORKLOADS, render_cards.METRICS)

    def test_aggregate_outside_fork_range_is_rejected(self):
        summary = load('summary.json')
        summary['cells'][0]['time_ns'] = max(summary['cells'][0]['time_forks_ns']) * 2
        with self.assertRaisesRegex(ValueError, 'outside its fork range'):
            render_cards.index(summary['cells'], render_cards.ENGINES, render_cards.WORKLOADS, render_cards.METRICS)

    def test_recorded_counts_must_match(self):
        summary = load('summary.json')
        cells = render_cards.index(summary['cells'], render_cards.ENGINES, render_cards.WORKLOADS,
                                   render_cards.METRICS)
        tampered = copy.deepcopy(summary)
        tampered['counts']['jvm/truffle-interpreter']['time_ns']['lower'] -= 1
        with self.assertRaisesRegex(ValueError, 'counts differ'):
            render_cards.verify_counts(tampered, cells)


if __name__ == '__main__':
    unittest.main()
