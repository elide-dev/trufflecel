import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('verify_release', Path(__file__).with_name('verify-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def test_release_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'VERSION').write_text('0.1.0-alpha.1\n')
            (root / '.release-please-manifest.json').write_text('{".":"0.1.0-alpha.1"}')
            calls = []
            def git(*args):
                calls.append(args)
                return 'abc\n'
            release.verify(root, '0.1.0-alpha.1', git)
            self.assertEqual(calls[-1], ('merge-base', '--is-ancestor', 'abc', 'refs/remotes/origin/main'))
            for version in ('../evil', '0.1.0-SNAPSHOT', '0.2.0'):
                with self.assertRaises(ValueError):
                    release.verify(root, version, git)
            (root / '.release-please-manifest.json').write_text('{".":"0.2.0"}')
            with self.assertRaises(ValueError): release.verify(root, '0.1.0-alpha.1', git)
            (root / '.release-please-manifest.json').write_text('{".":"0.1.0-alpha.1"}')
            with self.assertRaises(ValueError):
                release.verify(root, '0.1.0-alpha.1', lambda *args: 'head' if args[-1] == 'HEAD' else 'other')
            def unrelated(*args):
                if args[0] == 'merge-base': raise RuntimeError('Not on main')
                return 'abc'
            with self.assertRaises(RuntimeError): release.verify(root, '0.1.0-alpha.1', unrelated)

    def test_release_manifest(self):
        self.assertEqual((ROOT / 'VERSION').read_text().strip(), json.loads((ROOT / '.release-please-manifest.json').read_text())['.'])
        config = json.loads((ROOT / 'release-please-config.json').read_text())['packages']['.']
        self.assertEqual(config['release-type'], 'simple')
        self.assertFalse(config['include-component-in-tag'])
        self.assertIn('MODULE.bazel', config['extra-files'])
        module_version = re.search(r'module\(name = "trufflecel", version = "([^"]+)"', (ROOT / 'MODULE.bazel').read_text()).group(1)
        self.assertEqual((ROOT / 'VERSION').read_text().strip(), module_version)

    def test_workflow_pins_and_credentials(self):
        for path in (ROOT / '.github/workflows').glob('*.yml'):
            text = path.read_text()
            self.assertNotIn('pull_request_target:', text)
            for action in re.findall(r'uses:\s*(\S+)', text):
                if action.startswith('./'): continue
                self.assertRegex(action, r'^[^@]+@[0-9a-f]{40}$', path.name)
            if 'runs-on:' in text:
                self.assertIn('step-security/harden-runner@', text)
            if 'actions/checkout@' in text:
                self.assertIn('persist-credentials: false', text)
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('needs: [bazel, benchmarks]', ci)
        self.assertIn("needs.release.outputs.release_created == 'true'", ci)


if __name__ == '__main__':
    unittest.main()
