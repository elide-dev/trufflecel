#!/usr/bin/env python3
"""Verify the archived measured sources can be reconstructed from the published base."""

import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent
manifest = json.loads((ROOT / 'source.json').read_text())
repository = subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], cwd=ROOT, text=True).strip()
with tempfile.TemporaryDirectory(prefix='trufflecel-measured-source-') as name:
    target = Path(name)
    archive = subprocess.check_output(['git', 'archive', '--format=tar', '4c5df30'], cwd=repository)
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        source.extractall(target, filter='data')
    subprocess.run(['git', 'apply', '-'], cwd=target, check=True,
                   input=gzip.decompress((ROOT / 'source.patch.gz').read_bytes()))
    for path, expected in manifest['files'].items():
        actual = hashlib.sha256((target / path).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError('Reconstructed source mismatch: ' + path)
print('Reconstructed and verified', len(manifest['files']), 'measured source hashes')
