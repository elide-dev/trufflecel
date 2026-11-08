import unittest

from regex_evaluation import validate_path


class RegexEvaluationTest(unittest.TestCase):
    def test_candidate_rejects_fallback_even_when_outputs_are_correct(self):
        fallback = dict(backend='truffle-regex-re2', match_nodes=1, accelerated_nodes=0, ast_nodes=20)
        with self.assertRaises(ValueError):
            validate_path(fallback, 'truffle-regex-re2', 'map-regex')
        fallback['accelerated_nodes'] = 1
        validate_path(fallback, 'truffle-regex-re2', 'map-regex')

    def test_control_and_baseline_cannot_be_relabeled_as_accelerated(self):
        baseline = dict(backend='re2j', match_nodes=0, accelerated_nodes=0, ast_nodes=20)
        validate_path(baseline, 're2j', 'map-regex')
        with self.assertRaises(ValueError):
            validate_path(baseline, 'truffle-regex-re2', 'map-regex')
        control = dict(backend='truffle-regex-re2', match_nodes=0, accelerated_nodes=0, ast_nodes=5)
        validate_path(control, 'truffle-regex-re2', 'scalar-wide')
        with self.assertRaises(ValueError):
            validate_path(control, 'truffle-regex-re2', 'map-regex')
