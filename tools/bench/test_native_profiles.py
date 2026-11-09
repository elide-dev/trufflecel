# Copyright 2026 The TruffleCEL Authors
# SPDX-License-Identifier: Apache-2.0 OR MIT
import copy
import unittest

import native_profiles as p


class NativeProfilesTest(unittest.TestCase):
    def test_requires_every_engine_profile_and_fork(self):
        rows = [dict(fork=f, workload=w, engine=e, profile=v, runtime='svm',
                     mode='steady', image_prepared=False, warmup_ns_per_op=[1.] * 5,
                     ns_per_op=[1.] * 7)
                for f, w, e, v in p.inventory(3, p.WORKLOADS, p.PROFILES)]
        p.validate_inventory(rows, 3, p.WORKLOADS, p.PROFILES, False)
        for broken in (rows[:-1], rows + rows[:1], [r for r in rows if r['profile'] != 'default']):
            with self.assertRaises(ValueError):
                p.validate_inventory(broken, 3, p.WORKLOADS, p.PROFILES, False)
        for field, value in (('runtime', 'jvm'), ('image_prepared', True), ('ns_per_op', [float('nan')] * 7)):
            broken = copy.deepcopy(rows)
            broken[0][field] = value
            with self.assertRaises(ValueError):
                p.validate_inventory(broken, 3, p.WORKLOADS, p.PROFILES, False)


if __name__ == '__main__':
    unittest.main()
