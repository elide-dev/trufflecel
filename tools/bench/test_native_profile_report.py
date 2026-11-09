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
                               process_memory=dict(smaps_rollup=dict(Rss=1048576, Pss=524288), status=dict(VmHWM=2097152)))
                rows.append(row)
            (root / f'{phase}-metadata.json').write_text(json.dumps(meta))
            (root / f'{phase}.json').write_text(json.dumps(rows))
        startup = dict(complete=True, forks=4, results=test_startup_embeddings.StartupEmbeddingsTest().rows())
        (root / 'startup.json').write_text(json.dumps(startup))
        rows = []
        for runtime, engine, workload, entry, mode, fork in report.focused_payback.inventory(3):
            points = {str(10 ** i): 10 ** i * (1 if engine == 'truffle-jit' else 2) for i in range(7)}
            rows.append(dict(runtime=runtime, engine=engine, workload=workload, entry=entry, mode=mode,
                             fork=fork, image_prepared=mode == 'image-cold', configure_ns=1, prepare_ns=1,
                             instantiate_ns=1, snapshot_ns=0, last_tier_at_end=engine == 'truffle-jit',
                             total_ns_through_evaluations=points,
                             guest_compiled_at_checkpoint={k: engine == 'truffle-jit' and int(k) >= 10000 for k in points}))
        (root / 'payback.json').write_text(json.dumps(dict(complete=True, forks=3, maximum=1000000, results=rows)))

    def test_aggregation_preserves_process_ranges_and_all_cells(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.data(root)
            summary = report.summarize(root)
            self.assertEqual(48, len(summary['cells']))
            self.assertEqual(72, len(summary['startup']))
            self.assertEqual(9, len(summary['payback']))
            self.assertTrue(all(r['first_sustained_post_compilation_checkpoint'] == 10000 for r in summary['payback']))
            first = summary['cells'][0]
            self.assertEqual(dict(median=2., minimum=1., maximum=3., forks=[1., 2., 3.]), first['ns_per_op'])
            self.assertEqual(1., first['rss_mib']['median'])
            self.assertEqual(.5, first['pss_mib']['median'])
            self.assertEqual(2., first['peak_mib']['median'])
            self.assertIn('2.00 [1.00–3.00]', report.tables(summary))
            evaluation = report.summarize(root, include_startup=False)
            self.assertEqual([], evaluation['startup'])
            self.assertNotIn('Fresh-process', report.tables(evaluation))
            for key, value in (('complete', False), ('source_manifest_sha256', 'different'), ('common_flags', [])):
                path = root / 'timing-metadata.json'
                original = json.loads(path.read_text())
                path.write_text(json.dumps(dict(original, **{key: value})))
                with self.assertRaises(ValueError):
                    report.summarize(root)
                path.write_text(json.dumps(original))

    def test_payback_requires_compilation_and_a_sustained_win(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.data(root)
            data = json.loads((root / 'payback.json').read_text())
            for row in data['results']:
                if row['engine'] == 'truffle-jit':
                    row['total_ns_through_evaluations']['1000000'] = 3000000
            self.assertTrue(all(r['first_sustained_post_compilation_checkpoint'] is None
                                for r in report.payback_summary(data)))


if __name__ == '__main__':
    unittest.main()
