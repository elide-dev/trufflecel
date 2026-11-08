import copy
import itertools
import unittest

import cel_java_inputs as control
from evaluation_refresh import validate_inventory


class InputControlTest(unittest.TestCase):
    def test_jvm_inventory_requires_each_input_form_and_optimizer_mode(self):
        rows = [dict(benchmark='dev.trufflecel.SteadyState.evaluate',
                     params=dict(engine=engine, workload=workload), forks=3,
                     primaryMetric=dict(rawData=[[1.0] * 5 for _ in range(3)]))
                for engine, workload in itertools.product(control.ENGINES, control.WORKLOADS)]
        validate_inventory(rows, 'jvm-timing', control.ENGINES, control.WORKLOADS)
        for broken in (rows[:-1], rows + rows[:1],
                       [r for r in rows if not r['params']['engine'].endswith('-immutable')]):
            with self.assertRaises(ValueError):
                validate_inventory(broken, 'jvm-timing', control.ENGINES, control.WORKLOADS)

    def test_native_inventory_requires_all_forks_and_finite_samples(self):
        rows = [dict(runtime='svm', engine=engine, workload=workload, fork=fork,
                     mode='steady', image_prepared=False, warmup_ns_per_op=[1.0] * 5,
                     ns_per_op=[1.0] * 7)
                for engine, workload, fork in itertools.product(control.ENGINES, control.WORKLOADS, range(3))]
        validate_inventory(rows, 'native-timing', control.ENGINES, control.WORKLOADS)
        broken = copy.deepcopy(rows)
        broken[0]['ns_per_op'][0] = float('nan')
        for invalid in (rows[:-1], rows + rows[:1], broken):
            with self.assertRaises(ValueError):
                validate_inventory(invalid, 'native-timing', control.ENGINES, control.WORKLOADS)
