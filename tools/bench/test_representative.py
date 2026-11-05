import copy
import itertools
import unittest
import representative as r


class ResultsTest(unittest.TestCase):
    def rows(self):
        rows = []
        for stage, engine, workload in itertools.product(('compile', 'evaluate'), r.ENGINES, r.WORKLOADS):
            rows.append(dict(benchmark='dev.trufflecel.SteadyState.' + stage,
                             params=dict(engine=engine, workload=workload), forks=3,
                             primaryMetric=dict(rawData=[[1.] * 5 for _ in range(3)])))
        for stage, engine, source in itertools.product(('parse', 'check', 'optimize', 'plan'), r.ENGINES[-2:], ('true', 'x * 2 + 1')):
            rows.append(dict(benchmark='dev.trufflecel.FrontendStages.' + stage,
                             params=dict(engine=engine, source=source), forks=3,
                             primaryMetric=dict(rawData=[[1.] * 5 for _ in range(3)])))
        return rows

    def test_complete_inventory_and_failed_or_duplicate_forks(self):
        rows = self.rows()
        r.validate_jmh(rows)
        for broken in (rows[:-1], rows[1:], rows + rows[:1]):
            with self.assertRaises(ValueError): r.validate_jmh(broken)
        for bad in (float('nan'), 0, -1, float('inf')):
            broken = copy.deepcopy(rows)
            broken[0]['primaryMetric']['rawData'][0][0] = bad
            with self.assertRaises(ValueError): r.validate_jmh(broken)
        rows[0]['primaryMetric']['rawData'].pop()
        with self.assertRaises(ValueError): r.validate_jmh(rows)

    def test_cold_curves_cannot_omit_or_reverse_checkpoints(self):
        row = dict(runtime='jvm', engine='cel-java', workload='scalar', mode='cold',
                   configure_ns=1, prepare_ns=2, plan_ns=3,
                   total_ns_through_evaluations={'1': 10, '10': 20, '100': 30, '1000': 40, '10000': 50})
        args = ('jvm', 'cel-java', 'scalar', 'cold')
        r.validate_lifecycle(row, *args)
        row['total_ns_through_evaluations']['100'] = 19
        with self.assertRaises(ValueError): r.validate_lifecycle(row, *args)
        del row['total_ns_through_evaluations']['100']
        with self.assertRaises(ValueError): r.validate_lifecycle(row, *args)


if __name__ == '__main__': unittest.main()
