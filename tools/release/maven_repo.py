#!/usr/bin/env python3
"""Stage and validate thin Bazel artifacts; merge existing Maven metadata."""
import datetime
import hashlib
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile

MODULES = ('core', 'protobuf', 'json', 'processor')
NAMESPACE = {'m': 'http://maven.apache.org/POM/4.0.0'}


def validate_version(version):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[.-][A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid version: ' + version)


def checksums(path):
    for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
        Path(str(path) + '.' + algorithm).write_text(
            hashlib.new(algorithm, path.read_bytes()).hexdigest() + '\n')


def stage(publications, repository, version):
    validate_version(version)
    for module in MODULES:
        artifact = 'trufflecel-' + module
        relative = Path('dev/elide') / artifact
        source = publications / module / relative / version
        target = repository / relative / version
        target.mkdir(parents=True, exist_ok=True)
        for suffix in ('.pom', '.jar', '-sources.jar'):
            name = artifact + '-' + version + suffix
            path = source / name
            if suffix == '.pom':
                root = ET.parse(path).getroot()
                for key, expected in [('groupId', 'dev.elide'), ('artifactId', artifact), ('version', version)]:
                    if root.findtext('m:' + key, namespaces=NAMESPACE) != expected:
                        raise ValueError('Incorrect POM coordinates: ' + str(path))
            else:
                with zipfile.ZipFile(path) as jar:
                    names = jar.namelist()
                    if not any(n.endswith('.java' if suffix == '-sources.jar' else '.class') for n in names):
                        raise ValueError('Empty artifact: ' + str(path))
                    if any(n.startswith(('org/', 'com/')) for n in names):
                        raise ValueError('Bundled third-party classes: ' + str(path))
            shutil.copyfile(path, target / name)
            checksums(target / name)
        metadata = target.parent / 'maven-metadata.xml'
        if metadata.exists():
            root = ET.parse(metadata).getroot()
            if root.findtext('groupId') != 'dev.elide' or root.findtext('artifactId') != artifact:
                raise ValueError('Mismatched existing metadata')
        else:
            root = ET.Element('metadata')
            ET.SubElement(root, 'groupId').text = 'dev.elide'
            ET.SubElement(root, 'artifactId').text = artifact
        versioning = root.find('versioning')
        if versioning is None:
            versioning = ET.SubElement(root, 'versioning')
        versions = versioning.find('versions')
        if versions is None:
            versions = ET.SubElement(versioning, 'versions')
        if version not in [v.text for v in versions]:
            ET.SubElement(versions, 'version').text = version
        # Releases are serialized and latest/release identify the most recently published release.
        for key, value in [('latest', version), ('release', version), ('lastUpdated', datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S'))]:
            element = versioning.find(key)
            if element is None:
                element = ET.SubElement(versioning, key)
            element.text = value
        ET.indent(root)
        ET.ElementTree(root).write(metadata, encoding='utf-8', xml_declaration=True)
        checksums(metadata)


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit('usage: maven_repo.py <Bazel publication directory> <repository> <version>')
    stage(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
