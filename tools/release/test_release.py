"""Checks the real Bazel publication outputs, dependencies and metadata merging."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from maven_repo import MODULES, NAMESPACE, stage, validate_version


class PublicationTest(unittest.TestCase):
    def test_publications(self):
        base = Path('tools/release')
        pom = next((base / 'core/dev/elide/trufflecel-core').glob('*/*.pom'))
        version = pom.parent.name
        lock = json.loads(Path('maven_install.json').read_text())
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            stage(base, repo, version)
            for module in MODULES:
                artifact = 'trufflecel-' + module
                directory = repo / 'dev/elide' / artifact
                prefix = directory / version / (artifact + '-' + version)
                root = ET.parse(str(prefix) + '.pom').getroot()
                licenses = root.findall('m:licenses/m:license', NAMESPACE)
                self.assertEqual({entry.findtext('m:name', namespaces=NAMESPACE) for entry in licenses},
                                 {'MIT License', 'Apache License, Version 2.0'})
                for entry in licenses:
                    self.assertIn('MIT OR Apache-2.0', entry.findtext('m:comments', namespaces=NAMESPACE))
                for suffix in ('.jar', '-sources.jar'):
                    with zipfile.ZipFile(str(prefix) + suffix) as jar:
                        for name in ('LICENSE', 'LICENSE-MIT', 'LICENSE-APACHE', 'NOTICE'):
                            self.assertEqual(jar.read('META-INF/' + name), Path(name).read_bytes())
                expected = {
                    'core': {'org.graalvm.polyglot:polyglot', 'org.graalvm.truffle:truffle-api', 'dev.elide:truffledata-core', 'com.google.re2j:re2j', 'com.google.code.gson:gson', 'org.snakeyaml:snakeyaml-engine'},
                    'protobuf': {'dev.elide:trufflecel-core', 'org.graalvm.truffle:truffle-api', 'dev.elide:truffledata-core', 'dev.elide:truffledata-protobuf', 'dev.elide:truffledata-protobuf-runtime', 'com.google.protobuf:protobuf-java'},
                    'json': {'dev.elide:trufflecel-core', 'org.graalvm.shadowed:json'},
                }[module]
                actual = set()
                for dep in root.findall('m:dependencies/m:dependency', NAMESPACE):
                    get = lambda key: dep.findtext('m:' + key, namespaces=NAMESPACE)
                    coordinate = get('groupId') + ':' + get('artifactId')
                    actual.add(coordinate)
                    self.assertEqual(get('scope'), 'compile')
                    self.assertEqual(get('version'), version if coordinate == 'dev.elide:trufflecel-core' else lock['artifacts'][coordinate]['version'])
                self.assertEqual(actual, expected)
                with zipfile.ZipFile(str(prefix) + '.jar') as jar:
                    names = jar.namelist()
                    self.assertTrue(any(n.endswith('.class') for n in names))
                    if module == 'core':
                        self.assertIn('META-INF/services/com.oracle.truffle.api.provider.TruffleLanguageProvider', names)
                        self.assertIn('dev/trufflecel/CelLanguageProvider.class', names)
                        self.assertNotIn('dev/trufflecel/ProtobufType.class', names)
                        self.assertNotIn('dev/trufflecel/JsonValues.class', names)
                for path in (directory / version).iterdir():
                    if path.suffix in ('.pom', '.jar'):
                        self.assertEqual(Path(str(path) + '.sha256').read_text().strip(), hashlib.sha256(path.read_bytes()).hexdigest())
                metadata = directory / 'maven-metadata.xml'
                tree = ET.parse(metadata)
                ET.SubElement(tree.getroot().find('versioning/versions'), 'version').text = '0.0.1'
                tree.write(metadata)
            stage(base, repo, version)
            for module in MODULES:
                versions = ET.parse(repo / 'dev/elide' / ('trufflecel-' + module) / 'maven-metadata.xml').findall('versioning/versions/version')
                self.assertEqual([v.text for v in versions], [version, '0.0.1'])

    def test_rejects_mismatched_existing_metadata(self):
        base = Path('tools/release')
        version = next((base / 'core/dev/elide/trufflecel-core').glob('*/*.pom')).parent.name
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            stage(base, repo, version)
            metadata = repo / 'dev/elide/trufflecel-core/maven-metadata.xml'
            tree = ET.parse(metadata)
            tree.getroot().find('artifactId').text = 'another-artifact'
            tree.write(metadata)
            with self.assertRaises(ValueError):
                stage(base, repo, version)

    def test_unsafe_versions(self):
        for version in ('../escape', '1.0.0/<tag>', '', 'latest'):
            with self.assertRaises(ValueError):
                validate_version(version)


if __name__ == '__main__':
    unittest.main()
