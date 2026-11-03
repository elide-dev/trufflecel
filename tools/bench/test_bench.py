import json
from pathlib import Path
import tempfile
import unittest
from bench import instructions, ROOT


class MeasurementTest(unittest.TestCase):
    def test_only_named_region_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            path = folder / "profile.out"
            path.write_text("part: 1\nevents: Ir Dr\ntotals: 9999 5\n"
                            "part: 2\ndesc: Trigger: Client Request: target\nevents: Ir Dr\ntotals: 120 7\n"
                            "part: 3\nevents: Ir Dr\ntotals: 8888 4\n")
            self.assertEqual(instructions(folder, "target"), 120)
            with self.assertRaises(ValueError):
                instructions(folder, "missing")
            (folder / "duplicate.out").write_text(path.read_text())
            with self.assertRaises(ValueError):
                instructions(folder, "target")

    def test_invalid_counters_fail(self):
        for counters in ("0", "-1", "1 2"):
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                (folder / "profile.out").write_text(
                    "part: 1\ndesc: Trigger: Client Request: target\nevents: Ir\ntotals: " + counters + "\n")
                with self.assertRaises(ValueError):
                    instructions(folder, "target")

    def test_case_manifest(self):
        cases = json.loads((ROOT / "benchmarks/cases.json").read_text())
        self.assertEqual(len(cases), 8)
        self.assertEqual(len({case["name"] for case in cases}), 8)
        for case in cases:
            self.assertGreater(case["checksum"], 0)
            self.assertRegex(case["name"], r"^[a-z0-9-]+$")


if __name__ == "__main__":
    unittest.main()
