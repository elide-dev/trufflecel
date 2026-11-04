import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

from reports import coverage, junit


class ReportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def test_junit_preserves_failure_details_and_distinct_targets(self):
        for name in ('test', 'compiled'):
            self.write(f'bazel-testlogs/tests/{name}/test.xml', '<testsuite name="suite"><testcase name="case" classname="Example"><failure message="failed">trace</failure></testcase></testsuite>')
        junit(self.root)
        result = ET.parse(self.root / 'build/reports/junit.xml')
        self.assertEqual(len(result.findall('.//testcase/failure')), 2)
        classes = [case.get('classname') for case in result.findall('.//testcase')]
        self.assertEqual(len(set(classes)), 2)
        self.assertTrue((self.root / 'build/reports/junit/tests/compiled/test.xml').is_file())

    def test_junit_rejects_empty_reports(self):
        with self.assertRaises(ValueError):
            junit(self.root)

    def report(self):
        records = []
        for name in ('Cel', 'ProtobufType', 'TruffleJson'):
            path = f'src/main/java/dev/trufflecel/{name}.java'
            self.write(path, '// source')
            records.append(f'SF:{path}\nDA:1,1\nend_of_record\n')
        self.write('bazel-out/_coverage/_coverage_report.dat', ''.join(records))

    def test_coverage_requires_real_production_sources(self):
        self.report()
        coverage(self.root)
        self.assertTrue((self.root / 'build/reports/coverage/lcov.info').is_file())
        (self.root / 'src/main/java/dev/trufflecel/ProtobufType.java').unlink()
        with self.assertRaises(ValueError):
            coverage(self.root)

    def test_coverage_rejects_empty_hits(self):
        self.report()
        path = self.root / 'bazel-out/_coverage/_coverage_report.dat'
        path.write_text(path.read_text().replace('DA:1,1', 'DA:1,0'))
        with self.assertRaises(ValueError):
            coverage(self.root)


if __name__ == '__main__':
    unittest.main()
