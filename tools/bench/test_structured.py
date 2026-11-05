import copy
import unittest
import structured


class StructuredResultsTest(unittest.TestCase):
    def test_paired_diagnostics_reject_incomplete_or_invalid_measurements(self):
        metric = {'rawData': [[1.0] * 5 for _ in range(3)]}
        rows = [dict(benchmark='dev.trufflecel.StructuredAccess.evaluate',
                     params=dict(length=str(length), selectedPercent='50', engine='truffle-jit'),
                     primaryMetric=copy.deepcopy(metric),
                     secondaryMetrics={'gc.alloc.rate.norm': copy.deepcopy(metric)})
                for length in (0, 16)]

        def validate(value):
            structured.validate(value, ['evaluate'], [0, 16], [50], 'truffle-jit')

        validate(rows)
        for invalid in (rows[:-1], rows + rows[:1]):
            with self.assertRaises(ValueError):
                validate(invalid)
        broken = copy.deepcopy(rows)
        broken[0]['secondaryMetrics']['gc.alloc.rate.norm']['rawData'].pop()
        with self.assertRaises(ValueError):
            validate(broken)
        for value in (float('nan'), float('inf'), -1, 0):
            broken = copy.deepcopy(rows)
            broken[0]['primaryMetric']['rawData'][0][0] = value
            with self.assertRaises(ValueError):
                validate(broken)
        broken = copy.deepcopy(rows)
        broken[0]['benchmark'] = 'another.Class.evaluate'
        with self.assertRaises(ValueError):
            validate(broken)
