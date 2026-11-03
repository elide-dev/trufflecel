import copy
import itertools
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from compare import LEGACY_PANELS, PANELS, WORKLOADS, ENGINES, render, validate


def fixture():
    return {'schema': 3, 'forks': 3, 'captured_at': 'test', 'commit': '0' * 40,
            'host': {'system': 'Linux', 'machine': 'x86_64', 'cpu': 'test <cpu> & name'},
            'results': [dict(runtime=p[0], stage=p[1], workload=w, engine=e, fork=f,
                             ns_per_op=[10.0 + f] * 7, image_prepare_ns=100, image_build_ast=p[0] == 'svm',
                             guest_compiled=e == 'trufflecel' and p[1] not in ('compile', 'interpret', 'image-interpret'),
                             interpreter=p[1] in ('interpret', 'image-interpret'), peak_rss_kib=1024 * (f+1),
                             timed_peak_rss_kib=2048, allocated_bytes_per_op=[64.0] * 7,
                             memory_operations_per_sample=1024 if p[1] == 'compile' else 65536)
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
            render(data, path, 'peak-rss')
            self.assertIn('2.0 MiB', path.read_text())
            ET.parse(path)
            render(data, path, 'allocations')
            self.assertIn('64.0 B/op', path.read_text())
            for row in data['results']:
                row['allocated_bytes_per_op'] = [0.0] * 7
            render(data, path, 'allocations')
            self.assertIn('0.0 B/op', path.read_text())
            data['schema'] = 2
            render(data, path, 'peak-rss')
            with self.assertRaises(ValueError):
                render(data, path, 'allocations')
            data['schema'] = 1
            data['results'] = [r for r in data['results'] if (r['runtime'], r['stage']) in LEGACY_PANELS]
            render(data, path)
            with self.assertRaises(ValueError):
                render(data, path, 'peak-rss')

    def test_rejects_incomplete_duplicate_invalid_and_interpreted_results(self):
        good = fixture()
        mutations = [lambda d: d['results'].pop(),
                     lambda d: d['results'][0].update(allocated_bytes_per_op=[-1.0] * 7),
                     lambda d: d['results'][0].update(allocated_bytes_per_op=[float('nan')] * 7),
                     lambda d: d['results'][0].update(allocated_bytes_per_op=[]),
                     lambda d: d['results'][0].update(memory_operations_per_sample=1),
                     lambda d: d['results'][0].update(timed_peak_rss_kib=0),
                     lambda d: d['results'].append(d['results'][0]),
                     lambda d: d['results'][0].update(ns_per_op=[float('nan')] * 7),
                     lambda d: d['results'][0].update(ns_per_op=[0.0] * 7),
                     lambda d: d['results'][0].update(ns_per_op=[1.0]),
                     lambda d: d.update(forks=0),
                     lambda d: d['results'][0].update(peak_rss_kib=0),
                     lambda d: d['results'][0].update(peak_rss_kib=float('nan')),
                     lambda d: next(r for r in d['results'] if r['interpreter']).update(guest_compiled=True),
                     lambda d: next(r for r in d['results'] if r['interpreter']).update(interpreter=False),
                     lambda d: next(r for r in d['results'] if r['runtime'] == 'svm').update(image_build_ast=False),
                     lambda d: next(r for r in d['results'] if r['guest_compiled']).update(guest_compiled=False)]
        for mutate in mutations:
            data = copy.deepcopy(good)
            mutate(data)
            with self.assertRaises(ValueError):
                validate(data)
