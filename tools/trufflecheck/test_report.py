# Copyright (c) 2026 Elide Technologies, Inc. and TruffleCEL contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
import importlib.util
import json
import pathlib
import unittest

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("report", HERE / "report.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

LOG = """\
INFO: From Building src/main/java/dev/trufflecel/libtrufflecel.jar (63 source files):
src/main/java/dev/trufflecel/ExprNode.java:41: warning: [TruffleMissingBoundary] 'Map.get' must not be inlined into compiled code. Call it from a @TruffleBoundary method.
      return cache.get(key);
                      ^
    (see https://github.com/elide-dev/trufflecheck#trufflemissingboundary)
src/main/java/dev/trufflecel/ExprNode.java:41: warning: [TruffleMissingBoundary] 'Map.get' must not be inlined into compiled code. Call it from a @TruffleBoundary method.
      return cache.get(key);
                      ^
src/test/java/dev/trufflecel/ParserTest.java:9: warning: [MissingOverride] run implements method in Runnable; expected @Override
  public void run() {}
              ^
bazel-out/cfg/bin/third_party/Gen.java:3: warning: [deprecation] old() in Gen has been deprecated
src/main/java/dev/trufflecel/CelLanguage.java:216: warning: [deprecation] asHostObject(Object) in Env has been deprecated
"""
SEVERITY = {"TruffleMissingBoundary": "ERROR", "MissingOverride": "WARN"}
META = {"errorprone": "2.50.0", "trufflecheck": "0.1.1", "nullaway": "0.14.3"}


class ParseTest(unittest.TestCase):
    def setUp(self):
        self.findings = {(f["path"], f["line"], f["check"]): f for f in report.parse(LOG)}

    def test_deduplicates_and_skips_generated_sources(self):
        self.assertEqual(len(self.findings), 3)
        self.assertFalse(any(path.startswith("bazel-out/") for path, _, _ in self.findings))

    def test_reads_column_from_caret(self):
        finding = self.findings[("src/main/java/dev/trufflecel/ExprNode.java", 41, "TruffleMissingBoundary")]
        self.assertEqual(finding["column"], 23)
        self.assertEqual(finding["snippet"], "return cache.get(key);")
        javac = self.findings[("src/main/java/dev/trufflecel/CelLanguage.java", 216, "deprecation")]
        self.assertIsNone(javac["column"])

    def test_severity_and_priority(self):
        self.assertEqual(report.effective("deprecation", SEVERITY), "WARN")
        self.assertEqual(report.effective("DeadException", SEVERITY), "ERROR")
        boundary = self.findings[("src/main/java/dev/trufflecel/ExprNode.java", 41, "TruffleMissingBoundary")]
        override = self.findings[("src/test/java/dev/trufflecel/ParserTest.java", 9, "MissingOverride")]
        self.assertEqual(report.priority(boundary, "ERROR"), 1)
        self.assertEqual(report.priority(override, "WARN"), 4)


class SarifTest(unittest.TestCase):
    def test_document(self):
        document = json.loads(json.dumps(report.sarif(report.parse(LOG), SEVERITY, META)))
        self.assertEqual(document["version"], "2.1.0")
        run = document["runs"][0]
        rules = run["tool"]["driver"]["rules"]
        self.assertEqual([rule["id"] for rule in rules], ["MissingOverride", "TruffleMissingBoundary", "deprecation"])
        self.assertEqual(rules[1]["helpUri"], "https://github.com/elide-dev/trufflecheck#trufflemissingboundary")
        self.assertNotIn("helpUri", rules[2])
        self.assertEqual(len(run["results"]), 3)
        for result in run["results"]:
            self.assertEqual(rules[result["ruleIndex"]]["id"], result["ruleId"])
            location = result["locations"][0]["physicalLocation"]
            self.assertEqual(location["artifactLocation"]["uriBaseId"], "%SRCROOT%")
            self.assertFalse(location["artifactLocation"]["uri"].startswith("/"))
        boundary = next(r for r in run["results"] if r["ruleId"] == "TruffleMissingBoundary")
        self.assertEqual(boundary["level"], "error")
        self.assertEqual(boundary["locations"][0]["physicalLocation"]["region"], {"startLine": 41, "startColumn": 23})
        self.assertEqual(boundary["properties"], {"priority": "P1", "area": "main"})


class ProfileTest(unittest.TestCase):
    def test_reads_enforced_severities(self):
        severity = report.severities(HERE.parents[1])
        self.assertEqual(severity["TruffleMissingBoundary"], "ERROR")
        self.assertEqual(severity["FieldCanBeFinal"], "OFF")
        self.assertEqual(severity["NullAway"], "ERROR")


if __name__ == "__main__":
    unittest.main()
