import copy
from pathlib import Path
import sys
import tempfile
import unittest

import startup as s


class StartupTest(unittest.TestCase):
    def row(self, key):
        runtime, engine, workload, mode, fork = key
        record = dict(control=engine == 'control', verified=True)
        if engine != 'control':
            record.update(runtime=runtime, engine=engine, workload=workload, mode=mode,
                          input_index=fork % 4, image_prepared=mode == 'image',
                          guest_compiled_at_first_result=False, main_to_verified_ns=7,
                          **{phase: 1 for phase in s.PHASES})
        return dict(runtime=runtime, engine=engine, workload=workload, mode=mode, fork=fork,
                    input_index=fork % 4, record=record, launch_to_verified_ns=100, launch_to_exit_ns=200)

    def test_complete_matrix_requires_controls_modes_and_all_forks(self):
        for native in (False, True):
            rows = [self.row(key) for key in s.inventory(native, 3)]
            s.validate_results(rows, native, 3)
            for broken in (rows[:-1], rows + rows[:1], [r for r in rows if r['engine'] != 'control']):
                with self.assertRaises(ValueError):
                    s.validate_results(broken, native, 3)

    def test_forks_must_use_paired_input_variants(self):
        rows = [self.row(key) for key in s.inventory(False, 3)]
        rows[0]['input_index'] = 3
        with self.assertRaises(ValueError):
            s.validate_results(rows, False, 3)

    def test_rejects_labels_phases_and_tier_mismatches(self):
        row = self.row(('jvm', 'truffle-interpreter', 'scalar', 'runtime', 0))
        for field, value in (('verified', False), ('engine', 'cel-java-optimized'),
                             ('image_prepared', True), ('guest_compiled_at_first_result', True),
                             ('decode_ns', -1), ('main_to_verified_ns', 1000)):
            bad = copy.deepcopy(row)
            bad['record'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                s.validate(bad)
        for field, value in (('launch_to_verified_ns', 0), ('launch_to_exit_ns', 99),
                             ('launch_to_exit_ns', 200.0)):
            bad = copy.deepcopy(row)
            bad[field] = value
            with self.assertRaises(ValueError):
                s.validate(bad)

    def observe(self, source, timeout=5):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            return s.observe([sys.executable, '-c', source], root / 'out', root / 'err', timeout)

    def test_external_marker_and_exit_are_distinct_observations(self):
        result = self.observe("import time; print('TRUFFLECEL_STARTUP_READY', flush=True); "
                              "time.sleep(.1); print('{\"control\":true,\"verified\":true}')")
        self.assertGreater(result['launch_to_verified_ns'], 0)
        self.assertGreater(result['launch_to_exit_ns'], result['launch_to_verified_ns'])
        self.assertEqual({'control': True, 'verified': True}, result['record'])

    def test_missing_duplicate_unverified_and_failed_children_are_rejected(self):
        marker = "print('TRUFFLECEL_STARTUP_READY', flush=True); "
        record = "print('{\"control\":true,\"verified\":true}')"
        for source in (record, marker + marker + record, marker + "raise SystemExit(2)",
                       marker + "print('{\"verified\":false}')"):
            with self.subTest(source=source), self.assertRaises(ValueError):
                self.observe(source)
        with self.assertRaises(TimeoutError):
            self.observe('import time; time.sleep(2)', timeout=.05)


if __name__ == '__main__':
    unittest.main()
