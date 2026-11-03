import copy
import itertools
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from compare import PANELS, WORKLOADS, ENGINES, render, validate


def fixture():
    return {'schema': 1, 'forks': 3, 'captured_at': 'test', 'commit': '0' * 40,
            'host': {'system': 'Linux', 'machine': 'x86_64', 'cpu': 'test <cpu> & name'},
            'results': [dict(runtime=p[0], stage=p[1], workload=w, engine=e, fork=f,
                             ns_per_op=[10.0 + f] * 7, image_prepare_ns=100, image_build_ast=p[0] == 'svm',
                             guest_compiled=e == 'trufflecel' and p[1] != 'compile')
                        for p, w, e, f in itertools.product(PANELS, WORKLOADS, ENGINES, range(3))]}


class ComparisonTest(unittest.TestCase):
    def test_complete_matrix_and_svg(self):
        data = fixture()
        validate(data)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'comparison.svg'
            render(data, path)
            root = ET.parse(path).getroot()
            self.assertEqual(root.tag, '{http://www.w3.org/2000/svg}svg')
            self.assertIn('test &lt;cpu&gt; &amp; name', path.read_text())

    def test_rejects_incomplete_duplicate_invalid_and_interpreted_results(self):
        good = fixture()
        mutations = [lambda d: d['results'].pop(),
                     lambda d: d['results'].append(d['results'][0]),
                     lambda d: d['results'][0].update(ns_per_op=[float('nan')] * 7),
                     lambda d: d['results'][0].update(ns_per_op=[0.0] * 7),
                     lambda d: d['results'][0].update(ns_per_op=[1.0]),
                     lambda d: d.update(forks=0),
                     lambda d: next(r for r in d['results'] if r['runtime'] == 'svm').update(image_build_ast=False),
                     lambda d: next(r for r in d['results'] if r['guest_compiled']).update(guest_compiled=False)]
        for mutate in mutations:
            data = copy.deepcopy(good)
            mutate(data)
            with self.assertRaises(ValueError):
                validate(data)
