import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("diagnostic", Path(__file__).with_name("memory_diagnostic.py"))
diagnostic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostic)


class MappingTests(unittest.TestCase):
    def test_shared_and_private_file_pages_remain_file_backed(self):
        text = """00400000-00401000 r-xp 00000000 08:01 42 /tmp/memory-phases
Rss:                   4 kB
Anonymous:             0 kB
00600000-00602000 rw-p 00000000 08:01 42 /tmp/memory-phases
Rss:                   8 kB
Anonymous:             4 kB
01000000-01003000 rw-p 00000000 00:00 0 [heap]
Rss:                  12 kB
02000000-02004000 rwxp 00000000 00:00 0
Rss:                  16 kB
03000000-03005000 rw-p 00000000 00:00 0
Rss:                  20 kB
"""
        self.assertEqual({"file_executable": 4096, "file_data": 8192,
                          "anonymous_data": 32768, "anonymous_executable": 16384},
                         diagnostic.mapping_totals(text))

    def test_proc_status_ignores_non_memory_fields(self):
        self.assertEqual({"VmRSS": 2048, "VmHWM": 4096}, diagnostic.kb_fields(
            "Name:\tprobe\nPid:\t123\nVmRSS:\t2 kB\nVmHWM:\t4 kB\nThreads:\t3\n"))

    def test_selection_rejects_unknown_and_duplicate_cells(self):
        for value in ("default,default", "missing", ""):
            with patch.dict("os.environ", {"MEMORY_VARIANTS": value}):
                with self.assertRaises(ValueError):
                    diagnostic.selected("MEMORY_VARIANTS", diagnostic.VARIANTS)

    def test_failed_child_cannot_produce_a_successful_partial_report(self):
        with tempfile.TemporaryDirectory() as output:
            with self.assertRaises(RuntimeError):
                diagnostic.run_profile(["/usr/bin/false"], Path(output) / "failed")


if __name__ == "__main__":
    unittest.main()
