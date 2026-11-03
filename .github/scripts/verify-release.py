"""Require a release tag on main with matching version files before accessing R2."""
import json
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/release'))
from maven_repo import validate_version


def verify(root, version, git):
    validate_version(version)
    if 'SNAPSHOT' in version.upper():
        raise ValueError('Snapshots are not releases')
    if (root / 'VERSION').read_text().strip() != version:
        raise ValueError('VERSION disagrees with release')
    if json.loads((root / '.release-please-manifest.json').read_text())['.'] != version:
        raise ValueError('Release Please manifest disagrees with release')
    head = git('rev-parse', 'HEAD').strip()
    if git('rev-parse', 'refs/tags/v' + version + '^{commit}').strip() != head:
        raise ValueError('Checkout is not the release tag')
    git('merge-base', '--is-ancestor', head, 'refs/remotes/origin/main')


if __name__ == '__main__':
    verify(Path.cwd(), os.environ['RELEASE_VERSION'],
           lambda *args: subprocess.check_output(['git', *args], text=True))
