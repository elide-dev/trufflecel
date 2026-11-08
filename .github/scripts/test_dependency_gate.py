import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('dependency_gate', ROOT / 'tools/release/dependency_gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class DependencyGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'tools/release').mkdir(parents=True)
        (self.root / 'tools/release/dependency_gate.json').write_text(json.dumps({
            'from_version': '0.10.0', 'truffledata': '0.10.0'}))
        pins = {f'dev.elide:truffledata-{m}': {'version': '0.10.0'} for m in gate.MODULES}
        (self.root / 'maven_install.json').write_text(json.dumps({'artifacts': pins}))
        (self.root / 'MODULE.bazel').write_text('\n'.join(f'"{p}:0.10.0"' for p in pins))
        (self.root / 'tools/release/BUILD.bazel').write_text('\n'.join(
            f'"{p}:0.10.0"' for p in pins if not p.endswith('-jdbc')))
        self.fetch = Mock(return_value={'tag_name': 'v0.10.0', 'draft': False,
                                       'prerelease': False, 'published_at': '2026-11-09'})

    def test_current_release_and_fully_adopted_next_release(self):
        gate.check(self.root, '0.9.1', self.fetch)
        self.fetch.assert_not_called()
        gate.check(self.root, '0.10.0', self.fetch)
        self.fetch.assert_called_once_with('0.10.0')
        gate.check(self.root, '0.10.1', self.fetch)

    def test_old_lock_holds_next_release_without_network(self):
        path = self.root / 'maven_install.json'
        path.write_text(path.read_text().replace('0.10.0', '0.9.0'))
        with self.assertRaisesRegex(ValueError, 'must adopt 0.10.0'):
            gate.check(self.root, '0.10.0', self.fetch)
        self.fetch.assert_not_called()

    def test_mixed_dependency_sources_are_rejected(self):
        for name in ('MODULE.bazel', 'maven_install.json', 'tools/release/BUILD.bazel'):
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_text()
                path.write_text(original.replace('0.10.0', '0.9.0', 1))
                with self.assertRaises(ValueError):
                    gate.check(self.root, '0.10.0', self.fetch)
                path.write_text(original)
        self.fetch.assert_not_called()

    def test_missing_dependency_and_jdbc_publication_are_rejected(self):
        path = self.root / 'tools/release/BUILD.bazel'
        original = path.read_text()
        path.write_text('')
        with self.assertRaises(ValueError):
            gate.check(self.root, '0.10.0', self.fetch)
        path.write_text(original + '\n"dev.elide:truffledata-jdbc:0.10.0"')
        with self.assertRaisesRegex(ValueError, 'JDBC'):
            gate.check(self.root, '0.10.0', self.fetch)

    def test_unreleased_or_prerelease_upstream_is_rejected(self):
        for change in ({'draft': True}, {'prerelease': True}, {'published_at': None},
                       {'tag_name': 'v0.10.0-rc1'}, {'draft': None}):
            release = dict(self.fetch.return_value, **change)
            if change == {'draft': None}:
                release.pop('draft')
            with self.subTest(change=change), self.assertRaises(ValueError):
                gate.check(self.root, '0.10.0', lambda version: release)
        self.fetch.side_effect = OSError('network unavailable')
        with self.assertRaises(OSError):
            gate.check(self.root, '0.10.0', self.fetch)

    def test_upstream_not_found_holds_release_and_other_failures_propagate(self):
        for code in (404, 503):
            failure = urllib.error.HTTPError('https://api.github.com/', code, 'failure', {}, None)
            with self.subTest(code=code), patch.object(gate.urllib.request, 'urlopen', side_effect=failure):
                expected = ValueError if code == 404 else urllib.error.HTTPError
                with self.assertRaises(expected):
                    gate.fetch_release('0.10.0')

    def test_unstable_versions_are_rejected(self):
        for version in ('0.10.0-SNAPSHOT', '0.10.0-rc1', 'garbage'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                gate.check(self.root, version, self.fetch)


if __name__ == '__main__':
    unittest.main()
