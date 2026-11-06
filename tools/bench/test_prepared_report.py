import unittest
import json
import tempfile
from pathlib import Path
from prepared_lifecycle import inventory
from prepared_report import lifecycle
from prepared_report import sustained_crossing


class PaybackReportTest(unittest.TestCase):
    def test_early_or_overlapping_wins_do_not_establish_disjoint_payback(self):
        points = ['1', '10', '100', '1000']
        slower = {p: dict(median=10, min=8, max=12) for p in points}
        faster = {p: dict(median=v, min=v-1, max=v+1)
                  for p, v in zip(points, (5, 20, 9, 6))}
        self.assertEqual(100, sustained_crossing(points, faster, slower))
        self.assertEqual(1000, sustained_crossing(points, faster, slower, strict=True))
        faster['1000'] = dict(median=20, min=19, max=21)
        self.assertIsNone(sustained_crossing(points, faster, slower))
        self.assertIsNone(sustained_crossing(points, faster, slower, strict=True))

    def test_complete_matrix_is_required_before_aggregation(self):
        rows = []
        for runtime, engine, workload, entry, mode, fork in inventory('all'):
            row = dict(runtime=runtime, engine=engine, workload=workload, entry=entry,
                       mode=mode, fork=fork, image_prepared=mode.startswith('image-'),
                       configure_ns=1, prepare_ns=1, instantiate_ns=1, snapshot_ns=1,
                       last_tier_at_end=engine == 'truffle-jit')
            if mode.endswith('cold'):
                row['total_ns_through_evaluations'] = {str(10**i): 10**i * 10 for i in range(7)}
                row['guest_compiled_at_checkpoint'] = {str(10**i): engine == 'truffle-jit' and i >= 4 for i in range(7)}
            else:
                row.update(warmup_ns_per_op=[1.] * 5, ns_per_op=[1.] * 7, forced_guest_preparation_ns=1)
            rows.append(row)
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'metadata.json').write_text(json.dumps(dict(complete=True, kind='all', maximum=1_000_000)))
            results = directory / 'results.json'
            results.write_text(json.dumps(rows))
            summary = lifecycle(directory)
            self.assertEqual(36, len(summary['native']))
            self.assertEqual(40, len(summary['cold']))
            self.assertEqual(20, len(summary['guest_jit_payback']))
            for invalid in (rows[:-1], rows + rows[:1]):
                results.write_text(json.dumps(invalid))
                with self.assertRaises(ValueError):
                    lifecycle(directory)
