import copy
import unittest

import focused_payback as driver


class FocusedPaybackTest(unittest.TestCase):
    def row(self, job):
        runtime, engine, workload, entry, mode, fork = job
        points = {str(10 ** i): 10 ** i for i in range(7)}
        return dict(runtime=runtime, engine=engine, workload=workload, entry=entry,
                    mode=mode, fork=fork, image_prepared=mode.startswith('image-'),
                    configure_ns=1, prepare_ns=1, instantiate_ns=1, snapshot_ns=0,
                    total_ns_through_evaluations=points,
                    guest_compiled_at_checkpoint={key: False for key in points},
                    last_tier_at_end=False)

    def test_matrix_requires_both_engines_all_preparation_modes_and_every_fork(self):
        rows = [self.row(job) for job in driver.inventory(3)]
        self.assertEqual(54, len(rows))
        driver.validate_results(rows, 3, 1_000_000)
        for broken in (rows[:-1], rows + rows[:1],
                       [row for row in rows if row['mode'] != 'image-cold']):
            with self.assertRaises(ValueError):
                driver.validate_results(broken, 3, 1_000_000)

    def test_missing_endpoint_and_accidental_interpreter_compilation_are_rejected(self):
        rows = [self.row(job) for job in driver.inventory(3)]
        broken = copy.deepcopy(rows)
        del broken[0]['total_ns_through_evaluations']['1000000']
        with self.assertRaises(ValueError):
            driver.validate_results(broken, 3, 1_000_000)
        broken = copy.deepcopy(rows)
        row = next(row for row in broken if row['engine'] == 'truffle-interpreter')
        row['guest_compiled_at_checkpoint']['1000000'] = True
        with self.assertRaises(ValueError):
            driver.validate_results(broken, 3, 1_000_000)
