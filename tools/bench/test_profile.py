from pathlib import Path
import tempfile
import unittest
from codspeed_profile import regions


class ProfileTest(unittest.TestCase):
    def test_self_costs_exclude_call_edges_and_startup(self):
        text = '''part: 1
desc: Trigger: startup
positions: line
events: Ir Dr
fn=(1) caller
1 999 0
totals: 999 0
part: 2
desc: Trigger: Client Request: benchmarks/cases.json::native-aot/test[iterations=2]
positions: line
events: Ir Dr
fn=(1)
1 6 0
cfn=(2) callee
calls=2 1
1 14 0
fn=(2)
1 14 0
totals: 20 0
'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'profile.out'
            path.write_text(text)
            rows = list(regions(path))
            self.assertEqual(len(rows), 1)
            self.assertEqual(rows[0][1], 20)
            self.assertEqual(rows[0][2], {'caller': 6, 'callee': 14})
            path.write_text(text.replace('totals: 20', 'totals: 21'))
            with self.assertRaises(ValueError):
                list(regions(path))
