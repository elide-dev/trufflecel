# Copyright 2026 The TruffleCEL Authors
# SPDX-License-Identifier: Apache-2.0 OR MIT
import json
from pathlib import Path
import tempfile
import unittest

import native_profile_report as report
import native_profiles as profiles
import test_startup_embeddings


class NativeProfileReportTest(unittest.TestCase):
    def data(self, root):
        for phase in ('memory', 'timing'):
            meta = dict(complete=True, phase=phase, forks=3, workloads=profiles.WORKLOADS,
                        engines=profiles.ENGINES, common_flags=profiles.NATIVE_FLAGS,
                        profiles={p: profiles.PROFILES[p] for p in ('default', 'shared-compiler')})
            for key in ('sha256', 'source_manifest_sha256', 'driver_sha256', 'shared_driver_sha256', 'host', 'cpus', 'kernel'):
                meta[key] = 'same'
            rows = []
            for fork, workload, engine, profile in profiles.inventory(3, profiles.WORKLOADS, meta['profiles']):
                row = dict(fork=fork, workload=workload, engine=engine, profile=profile, runtime='svm')
                if phase == 'timing':
                    row.update(mode='steady', image_prepared=False, warmup_ns_per_op=[1.] * 5,
                               ns_per_op=[fork + 1.] * 7, forced_guest_preparation_ns=1000000)
                else:
                    row.update(input_variants=4, bindings_per_input=1 if workload == 'scalar' else 69,
                               operations_per_sample=65536, warmup_operations=327680,
                               allocation_calibration_bytes=1048592, allocated_bytes_per_op=[40.] * 7,
                               guest_last_tier=engine == 'truffle-jit', compilation_enabled=engine == 'truffle-jit',
                               background_compilation=False, compilation_failure_action='Throw',
                               compiler_options={'compiler.TreatPerformanceWarningsAsErrors': 'all'},
                               process_memory=dict(smaps_rollup=dict(Rss=1048576), status=dict(VmHWM=2097152)))
                rows.append(row)
            (root / f'{phase}-metadata.json').write_text(json.dumps(meta))
            (root / f'{phase}.json').write_text(json.dumps(rows))
        startup = dict(complete=True, forks=4, results=test_startup_embeddings.StartupEmbeddingsTest().rows())
        (root / 'startup.json').write_text(json.dumps(startup))

    def test_aggregation_preserves_process_ranges_and_all_cells(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.data(root)
            summary = report.summarize(root)
            self.assertEqual(48, len(summary['cells']))
            self.assertEqual(72, len(summary['startup']))
            first = summary['cells'][0]
            self.assertEqual(dict(median=2., minimum=1., maximum=3., forks=[1., 2., 3.]), first['ns_per_op'])
            self.assertEqual(1., first['rss_mib']['median'])
            self.assertEqual(2., first['peak_mib']['median'])
            self.assertIn('2.00 [1.00–3.00]', report.tables(summary))
            for key, value in (('complete', False), ('source_manifest_sha256', 'different'), ('common_flags', [])):
                path = root / 'timing-metadata.json'
                original = json.loads(path.read_text())
                path.write_text(json.dumps(dict(original, **{key: value})))
                with self.assertRaises(ValueError):
                    report.summarize(root)
                path.write_text(json.dumps(original))


if __name__ == '__main__':
    unittest.main()
