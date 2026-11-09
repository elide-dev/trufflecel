# Copyright 2026 The TruffleCEL Authors
# SPDX-License-Identifier: Apache-2.0 OR MIT
import copy
import hashlib
import itertools
from pathlib import Path
import unittest

import startup_embeddings as e
import test_startup


class StartupEmbeddingsTest(unittest.TestCase):
    def rows(self):
        result = []
        helper = test_startup.StartupTest()
        for engine, workload, mode, fork, embedding in e.inventory(4):
            row = helper.row(('svm', engine, workload, mode, fork))
            row['embedding'] = embedding
            row['record'].update(embedding=embedding, context_created=engine.startswith('truffle-') and
                                 (embedding == 'context' or workload == 'map-regex'))
            result.append(row)
        result += [helper.row(('svm', 'control', 'control', mode, fork))
                   for mode, fork in itertools.product(('runtime', 'image'), range(4))]
        return result

    def test_hashes_actual_driver_sources(self):
        expected = dict(driver_sha256=Path(e.__file__), startup_driver_sha256=Path(e.startup.__file__),
                        shared_driver_sha256=Path(e.__file__).with_name('representative.py'))
        self.assertEqual({name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in expected.items()},
                         e.driver_provenance())

    def test_requires_full_matrix_and_empty_controls(self):
        rows = self.rows()
        e.validate(rows, 4)
        for broken in (rows[:-1], rows[1:], rows + rows[:1], rows + rows[-1:]):
            with self.assertRaises(ValueError):
                e.validate(broken, 4)

    def test_rejects_wrong_embedding_and_regex_backend_context(self):
        rows = self.rows()
        row = next(r for r in rows if r['engine'] == 'truffle-interpreter' and
                   r['embedding'] == 'direct' and r['workload'] == 'map-regex')
        for field, value in (('context_created', False), ('embedding', 'context')):
            bad = copy.deepcopy(rows)
            bad[rows.index(row)]['record'][field] = value
            with self.assertRaises(ValueError):
                e.validate(bad, 4)


if __name__ == '__main__':
    unittest.main()
