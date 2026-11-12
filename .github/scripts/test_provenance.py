# SPDX-License-Identifier: Apache-2.0 OR MIT
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('provenance', Path(__file__).with_name('release-provenance.py'))
provenance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provenance)


class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.stage = self.root / 'build/release-repo.test'
        self.version = '0.11.0'
        (self.root / 'VERSION').write_text(self.version)
        (self.root / '.release-please-manifest.json').write_text(json.dumps({'.': self.version}))
        self.env = {
            'GITHUB_REPOSITORY': 'elide-dev/trufflecel', 'GITHUB_REF': 'refs/heads/main',
            'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2',
            'GITHUB_WORKFLOW_REF': 'elide-dev/trufflecel/.github/workflows/ci.yml@refs/heads/main',
            'GITHUB_WORKFLOW_SHA': 'b' * 40,
        }
        self.paths = []
        for module in ('core', 'protobuf', 'json', 'processor'):
            directory = self.stage / f'dev/elide/trufflecel-{module}/{self.version}'
            directory.mkdir(parents=True)
            for suffix in ('.jar', '-sources.jar', '.pom'):
                path = directory / f'trufflecel-{module}-{self.version}{suffix}'
                path.write_bytes(path.name.encode())
                path.with_name(path.name + '.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest() + '\n')
                self.paths.append(path)

    def describe(self, git=lambda *args: 'a' * 40):
        return provenance.describe(self.root, self.stage, self.version, self.env, git)

    def test_binds_all_artifacts_to_actual_tag_not_workflow_commit(self):
        predicate, checksums = self.describe()
        definition = predicate['buildDefinition']
        self.assertEqual(definition['resolvedDependencies'][0]['digest'], {'gitCommit': 'a' * 40})
        self.assertEqual(definition['internalParameters']['workflowSha'], 'b' * 40)
        self.assertEqual(definition['externalParameters'], {'version': '0.11.0', 'ref': 'refs/tags/v0.11.0'})
        self.assertEqual(predicate['runDetails']['metadata']['invocationId'],
                         'https://github.com/elide-dev/trufflecel/actions/runs/123/attempts/2')
        self.assertEqual(len(checksums.splitlines()), 12)
        for path in self.paths:
            self.assertIn(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n', checksums)

    def test_rejects_missing_changed_or_symlinked_subject(self):
        path = self.paths[0]
        original = path.read_bytes()
        path.write_bytes(b'changed after publication')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'): self.describe()
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing or unsafe'): self.describe()
        outside = self.root / 'outside.jar'
        outside.write_bytes(original)
        path.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'Missing or unsafe'): self.describe()

    def test_rejects_wrong_release_or_source(self):
        with self.assertRaisesRegex(ValueError, 'not the release tag'):
            self.describe(lambda *args: 'a' * 40 if args[-1] == 'HEAD' else 'b' * 40)
        (self.root / 'VERSION').write_text('0.10.0')
        with self.assertRaisesRegex(ValueError, 'VERSION disagrees'): self.describe()

    def test_rejects_other_repository_or_pull_request_context(self):
        for key, value in [('GITHUB_REPOSITORY', 'fork/trufflecel'),
                           ('GITHUB_REF', 'refs/pull/1/merge'), ('GITHUB_RUN_ID', '../123')]:
            with self.subTest(key=key):
                previous = self.env[key]
                self.env[key] = value
                with self.assertRaises(ValueError): self.describe()
                self.env[key] = previous

    def test_workflow_signs_the_predicate_type_the_script_defines(self):
        workflow = (Path(__file__).parents[2] / '.github/workflows/publish.maven.yml').read_text()
        self.assertIn('predicate-type: ' + provenance.PREDICATE_TYPE + '\n', workflow)
        # GitHub rejects slsa.dev/provenance/v1 for project-defined build types.
        self.assertNotIn('slsa.dev/provenance', workflow)
        self.assertNotEqual(provenance.PREDICATE_TYPE, 'https://slsa.dev/provenance/v1')

    def test_rejects_staging_outside_build_directory(self):
        self.stage = self.root
        with self.assertRaisesRegex(ValueError, 'staged by publish.sh'): self.describe()


if __name__ == '__main__':
    unittest.main()
