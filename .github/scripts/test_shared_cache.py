"""Exercise credential setup using synthetic keys in disposable working directories."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('configure-buildbuddy.sh').resolve()

class SharedCacheSetup(unittest.TestCase):
    def check_setup(self, key, enabled):
        with tempfile.TemporaryDirectory() as scratch:
            env = dict(os.environ, BUILDBUDDY_API_KEY=key, ELIDE_BUILD_CACHE_ENABLED=enabled,
                       GITHUB_OUTPUT='', RUNNER_TEMP='')
            result = subprocess.run(['bash', str(SCRIPT)], cwd=scratch, env=env, text=True, capture_output=True, check=True)
            config = Path(scratch, 'user.bazelrc')
            self.assertEqual(config.stat().st_mode & 0o777, 0o600)
            self.assertNotIn('synthetic-test-key', result.stdout + result.stderr)
            return config.read_text()

    def test_missing_key_stays_local_even_with_cache(self):
        self.assertEqual(self.check_setup('', 'true'), 'common --config=local\n')

    def test_cache_is_explicit_and_key_remains_rpc_header(self):
        self.assertNotIn('--config=shared_cache', self.check_setup('synthetic-test-key', ''))
        configured = self.check_setup('synthetic-test-key', 'true')
        self.assertIn('common --config=shared_cache\n', configured)
        self.assertIn('--remote_header=x-buildbuddy-api-key=synthetic-test-key', configured)
        self.assertNotIn('--action_env', configured)
