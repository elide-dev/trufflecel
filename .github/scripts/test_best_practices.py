# SPDX-License-Identifier: Apache-2.0 OR MIT
from html import unescape
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[2]


class BestPracticesTest(unittest.TestCase):
    def test_complete_valid_evidence_and_bounded_official_proposals(self):
        answers = json.loads((ROOT / '.bestpractices.json').read_text())
        criteria = json.loads((ROOT / 'tools/best-practices-criteria.json').read_text())['passing']
        answered = {key.removesuffix('_status') for key in answers if key.endswith('_status')}
        self.assertEqual(answered, set(criteria))
        for criterion, definition in criteria.items():
            status = answers[criterion + '_status']
            self.assertIn(status, ('Met', 'Unmet', 'N/A', '?'))
            if status == 'N/A': self.assertTrue(definition['na_allowed'], criterion)
            self.assertTrue(answers[criterion + '_justification'].strip(), criterion)
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'proposals.html'
            subprocess.run([sys.executable, str(ROOT / 'tools/best-practices.py'),
                            '--output', str(output)], check=True, capture_output=True)
            html = output.read_text()
        proposed = {}
        for raw in re.findall(r'href="([^"]+)"', html):
            link = unescape(raw)
            parsed = urlparse(link)
            self.assertEqual(parsed.scheme, 'https')
            self.assertEqual(parsed.netloc, 'www.bestpractices.dev')
            if not parsed.query: continue
            self.assertEqual(parsed.path, '/en/projects/16472/passing/edit')
            self.assertLessEqual(len(link), 6000)
            fields = parse_qs(parsed.query)
            self.assertNotIn('overrides', fields)
            for key, value in fields.items():
                if key.endswith('_status'):
                    self.assertIn(key.removesuffix('_status') + '_justification', fields)
                self.assertNotIn(key, proposed)
                proposed[key] = value[0]
        for criterion in criteria:
            key = criterion + '_status'
            if answers[key] == '?':
                self.assertNotIn(key, proposed)
            else:
                self.assertEqual(proposed[key], answers[key])
                evidence = criterion + '_justification'
                self.assertEqual(proposed[evidence], answers[evidence])


if __name__ == '__main__':
    unittest.main()
