import copy
import hashlib
from pathlib import Path
import unittest

import startup
import startup_control as driver


class StartupControlTest(unittest.TestCase):
    def test_driver_provenance_hashes_actual_driver_observer_and_shared_sources(self):
        # Exercise the real Path-based hash helper: a str __file__ caused metadata construction
        # to fail before the first measured launch, despite the matrix/row tests passing.
        provenance = driver.driver_provenance()
        sources = dict(driver_sha256=Path(driver.__file__),
                       observer_driver_sha256=Path(startup.__file__),
                       shared_driver_sha256=Path(startup.__file__).with_name('representative.py'))
        self.assertEqual(set(sources), set(provenance))
        for name, path in sources.items():
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), provenance[name])

    def rows(self):
        rows = []
        for revision in driver.REVISIONS:
            for runtime, engine, workload, mode, fork in driver.cells():
                record = dict(control=False, verified=True, runtime=runtime, engine=engine,
                              workload=workload, mode=mode, input_index=fork, image_prepared=False,
                              guest_compiled_at_first_result=False, main_to_verified_ns=len(startup.PHASES))
                record.update({phase: 1 for phase in startup.PHASES})
                rows.append(dict(record=record, launch_to_verified_ns=20, launch_to_exit_ns=30,
                                 revision=revision, runtime=runtime, engine=engine, workload=workload,
                                 mode=mode, fork=fork, input_index=fork))
        return rows

    def test_requires_both_revisions_runtimes_modes_and_all_four_variants(self):
        rows = self.rows()
        self.assertEqual(32, len(rows))
        self.assertEqual({0, 1, 2, 3}, {row['input_index'] for row in rows})
        driver.validate_results(rows)
        for bad in (rows[:-1], rows + rows[:1], [r for r in rows if r['revision'] == 'baseline']):
            with self.assertRaises(ValueError):
                driver.validate_results(bad)
        for field, value in (('input_index', 0), ('mode', 'image')):
            bad = copy.deepcopy(rows)
            next(r for r in bad if r['fork'] == 3)[field] = value
            with self.assertRaises(ValueError):
                driver.validate_results(bad)

    def test_protocol_and_source_validation_reject_invalid_controls(self):
        rows = self.rows()
        rows[0]['record']['verified'] = False
        with self.assertRaises(ValueError):
            driver.validate_results(rows)
        files = {name: 'a' * 64 for name in driver.FIXTURE_SOURCES}
        manifests = dict(baseline=dict(commit='baseline', dirty=False, file_sha256=files),
                         candidate=dict(source_revision='candidate', dirty=False, files=files))
        driver.validate_sources(manifests)
        for bad in (dict(manifests, candidate=manifests['baseline']),
                    dict(manifests, candidate=dict(source_revision='candidate', dirty=True, files=files)),
                    dict(manifests, candidate=dict(source_revision='candidate', dirty=False, files={}))):
            with self.assertRaises(ValueError):
                driver.validate_sources(bad)
