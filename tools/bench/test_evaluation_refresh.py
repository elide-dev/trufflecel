import copy
import unittest
import evaluation_refresh as r


class ResultsTest(unittest.TestCase):
    def test_jmh_rejects_wrong_configuration_and_incomplete_forks(self):
        row = dict(benchmark='dev.trufflecel.SteadyState.evaluate', params=dict(engine='truffle-jit', workload='scalar'),
                   forks=3, primaryMetric=dict(rawData=[[1.] * 5 for _ in range(3)]))
        r.validate_jmh(row, 'truffle-jit', 'scalar')
        for key, value in [('benchmark', 'dev.trufflecel.SteadyState.compile'), ('forks', 2),
                           ('params', dict(engine='truffle-interpreter', workload='scalar'))]:
            broken = copy.deepcopy(row); broken[key] = value
            with self.assertRaises(ValueError): r.validate_jmh(broken, 'truffle-jit', 'scalar')
        row['primaryMetric']['rawData'][0].pop()
        with self.assertRaises(ValueError): r.validate_jmh(row, 'truffle-jit', 'scalar')

    def test_memory_requires_all_inputs_fixed_work_and_compiler_gates(self):
        row = dict(runtime='svm', engine='truffle-jit', workload='proto-repeated', input_variants=4,
                   bindings_per_input=69, operations_per_sample=65536, warmup_operations=327680,
                   allocation_calibration_bytes=1048592, allocated_bytes_per_op=[10.] * 7,
                   guest_last_tier=True, compilation_enabled=True, background_compilation=False,
                   compilation_failure_action='Throw', compiler_options={'compiler.TreatPerformanceWarningsAsErrors': 'all'})
        args = ('svm', 'truffle-jit', 'proto-repeated', True)
        r.validate_result(row, *args)
        for key, value in [('input_variants', 1), ('bindings_per_input', 5), ('guest_last_tier', False),
                           ('background_compilation', True), ('allocation_calibration_bytes', 0),
                           ('allocated_bytes_per_op', [float('nan')] * 7), ('compilation_failure_action', 'Silent')]:
            broken = copy.deepcopy(row); broken[key] = value
            with self.assertRaises(ValueError): r.validate_result(broken, *args)
        row['compiler_options'] = {}
        with self.assertRaises(ValueError): r.validate_result(row, *args)

    def test_native_timing_rejects_unpaired_preparation_and_invalid_windows(self):
        row = dict(runtime='svm', engine='cel-java', workload='scalar', mode='steady', image_prepared=False,
                   warmup_ns_per_op=[1.] * 5, ns_per_op=[1.] * 7)
        args = ('svm', 'cel-java', 'scalar', False)
        r.validate_result(row, *args)
        row['image_prepared'] = True
        with self.assertRaises(ValueError): r.validate_result(row, *args)
        for values in ([0.] * 5, [1.] * 4, [float('inf')] * 5):
            row.update(image_prepared=False, warmup_ns_per_op=values)
            with self.assertRaises(ValueError): r.validate_result(row, *args)


if __name__ == '__main__':
    unittest.main()
