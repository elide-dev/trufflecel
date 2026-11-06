import copy
import unittest
import prepared_lifecycle as driver


class PreparedLifecycleTest(unittest.TestCase):
    def test_cold_validation_rejects_missing_checkpoints_and_tier_mislabels(self):
        job = ('jvm', 'truffle-interpreter', 'proto-policy', 'snapshot', 'cold', 0)
        points = {str(10 ** i): 10 ** i for i in range(7)}
        row = dict(runtime='jvm', engine='truffle-interpreter', workload='proto-policy',
                   entry='snapshot', mode='cold', image_prepared=False,
                   configure_ns=1, prepare_ns=1, instantiate_ns=1, snapshot_ns=1,
                   total_ns_through_evaluations=points,
                   guest_compiled_at_checkpoint={key: False for key in points},
                   last_tier_at_end=False)
        driver.validate(row, job, 1_000_000)
        broken = copy.deepcopy(row)
        del broken['total_ns_through_evaluations']['1000000']
        with self.assertRaises(ValueError):
            driver.validate(broken, job, 1_000_000)
        for key, value in (('entry', 'map'), ('image_prepared', True), ('last_tier_at_end', True)):
            broken = copy.deepcopy(row)
            broken[key] = value
            with self.assertRaises(ValueError):
                driver.validate(broken, job, 1_000_000)
        broken = copy.deepcopy(row)
        broken['guest_compiled_at_checkpoint']['1000'] = True
        with self.assertRaises(ValueError):
            driver.validate(broken, job, 1_000_000)

    def test_native_validation_requires_complete_positive_samples_and_compilation(self):
        job = ('svm', 'truffle-jit', 'proto-repeated', 'fresh', 'steady', 0)
        row = dict(runtime='svm', engine='truffle-jit', workload='proto-repeated',
                   entry='fresh', mode='steady', image_prepared=False,
                   configure_ns=1, prepare_ns=1, instantiate_ns=1, snapshot_ns=1,
                   last_tier_at_end=True, forced_guest_preparation_ns=1, warmup_ns_per_op=[1.] * 5, ns_per_op=[1.] * 7)
        driver.validate(row, job, 1_000_000)
        for key, value in (('last_tier_at_end', False), ('ns_per_op', [1.] * 6),
                           ('warmup_ns_per_op', [float('nan')] * 5), ('ns_per_op', [0.] * 7)):
            broken = copy.deepcopy(row)
            broken[key] = value
            with self.assertRaises(ValueError):
                driver.validate(broken, job, 1_000_000)
        jobs = driver.inventory('all')
        self.assertEqual(len(jobs), len(set(jobs)))
        self.assertEqual(set(jobs), set(driver.inventory('cold')) | set(driver.inventory('native')))
