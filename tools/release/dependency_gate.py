"""Block releases until the matching stable TruffleData release has been adopted."""
import argparse
import json
from pathlib import Path
import re
import urllib.error
import urllib.request

MODULES = ('core', 'jdbc', 'protobuf', 'protobuf-runtime')


def version_tuple(value):
    if not re.fullmatch(r'\d+\.\d+\.\d+', value):
        raise ValueError('Expected a stable release version: ' + value)
    return tuple(map(int, value.split('.')))


def check(root, version, fetch):
    policy = json.loads((root / 'tools/release/dependency_gate.json').read_text())
    if version_tuple(version) < version_tuple(policy['from_version']):
        return
    required = policy['truffledata']
    version_tuple(required)
    lock = json.loads((root / 'maven_install.json').read_text())['artifacts']
    module_text = (root / 'MODULE.bazel').read_text()
    pom_text = (root / 'tools/release/BUILD.bazel').read_text()
    for module in MODULES:
        coordinate = 'dev.elide:truffledata-' + module
        if lock.get(coordinate, {}).get('version') != required:
            raise ValueError(f'Release held: {coordinate} must adopt {required} in the Maven lock')
        for label, source in (('MODULE.bazel', module_text), ('publication POMs', pom_text)):
            pins = re.findall(r'"' + re.escape(coordinate) + r':([^"\s]+)"', source)
            # JDBC is comparison/integration-only, never a published CEL dependency.
            if label == 'publication POMs' and module == 'jdbc':
                if pins:
                    raise ValueError('JDBC must not leak into publication dependencies')
                continue
            if not pins or any(pin != required for pin in pins):
                raise ValueError(f'Release held: {coordinate} must use {required} in {label}')
    release = fetch(required)
    if (release.get('tag_name') != 'v' + required or release.get('draft') is not False
            or release.get('prerelease') is not False or not release.get('published_at')):
        raise ValueError('Release held: TruffleData ' + required + ' is not a published stable release')


def fetch_release(version):
    url = 'https://api.github.com/repos/elide-dev/truffledata/releases/tags/v' + version
    request = urllib.request.Request(url, headers={'User-Agent': 'TruffleCEL-release-gate'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            raise ValueError('Release held: TruffleData ' + version + ' has not been released') from error
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument('--version')
    choice.add_argument('--next', action='store_true')
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    policy = json.loads((root / 'tools/release/dependency_gate.json').read_text())
    version = policy['from_version'] if args.next else args.version
    try:
        check(root, version, fetch_release)
    except ValueError as error:
        if not args.github_output:
            parser.exit(1, str(error) + '\n')
        print(error)
        ready = False
    else:
        ready = True
        print('Release dependency gate satisfied for ' + version)
    if args.github_output:
        with args.github_output.open('a') as output:
            output.write('ready=' + str(ready).lower() + '\n')


if __name__ == '__main__':
    main()
