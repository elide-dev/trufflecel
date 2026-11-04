#!/usr/bin/env python3
"""Collect Bazel reports before a subsequent invocation replaces testlog symlinks."""
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

MODULES = ('core', 'jdbc', 'jdbc-sqlite', 'jdbc-mysql', 'jdbc-postgresql', 'protobuf', 'protobuf-runtime', 'protobuf-buffa')


def junit(root=Path('.')):
    destination = root / 'build/reports'
    destination.mkdir(parents=True, exist_ok=True)
    combined = ET.Element('testsuites')
    reports = sorted((root / 'bazel-testlogs').rglob('test.xml'))
    cases = 0
    for report in reports:
        tree = ET.parse(report).getroot()
        suites = [tree] if tree.tag == 'testsuite' else list(tree.findall('testsuite'))
        for suite in suites:
            target = str(report.parent.relative_to(root / 'bazel-testlogs'))
            suite.set('name', target + ':' + suite.get('name', 'suite'))
            for case in suite.findall('.//testcase'):
                case.set('classname', target.replace('/', '.') + '.' + case.get('classname', 'test'))
            cases += len(suite.findall('.//testcase'))
            combined.append(suite)
        output = destination / 'junit' / report.relative_to(root / 'bazel-testlogs')
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(report, output)
    if not cases:
        raise ValueError('No JUnit test cases found')
    ET.ElementTree(combined).write(destination / 'junit.xml', encoding='utf-8', xml_declaration=True)
    print(f'Collected {cases} test cases from {len(reports)} Bazel reports')


def coverage(root=Path('.')):
    source = root / 'bazel-out/_coverage/_coverage_report.dat'
    report = source.read_text()
    sources = [line[3:] for line in report.splitlines() if line.startswith('SF:')]
    if not sources:
        raise ValueError('LCOV has no source records')
    for path in sources:
        if not path.startswith('src/main/java/') or '..' in Path(path).parts or not (root / path).is_file():
            raise ValueError('Unresolved or non-production LCOV source: ' + path)
    if not any(line.startswith('DA:') and int(line.split(',')[1]) > 0 for line in report.splitlines()):
        raise ValueError('LCOV has no covered lines')
    destination = root / 'build/reports/coverage/lcov.info'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(report)
    print(f'Validated coverage for {len(sources)} production source files')


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('junit', 'coverage'):
        sys.exit('usage: reports.py junit|coverage')
    globals()[sys.argv[1]]()
