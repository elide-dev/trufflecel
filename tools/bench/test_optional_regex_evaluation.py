import unittest
from optional_regex_evaluation import validate_policy


class OptionalRegexProofTest(unittest.TestCase):
    def test_actual_policy_and_execution_are_required(self):
        for policy in ('compatibility', 'truffle-only'):
            for workload in ('scalar-wide', 'map-regex', 'map-regex-dynamic'):
                constant = workload == 'map-regex'
                dynamic = workload == 'map-regex-dynamic'
                row = dict(backend='truffle-regex-re2', match_nodes=int(constant),
                           accelerated_nodes=int(constant), ast_nodes=20, regex_policy=policy,
                           regex_execution='none' if workload == 'scalar-wide' else
                           're2j' if dynamic and policy == 'compatibility' else 'truffle-regex-re2',
                           scoped_regexes=1 if constant else 2 if dynamic and policy == 'truffle-only' else 0)
                validate_policy(row, policy, workload)
                for key in ('backend', 'regex_policy', 'regex_execution', 'scoped_regexes', 'accelerated_nodes'):
                    bad = dict(row); bad[key] = 'wrong'
                    with self.assertRaises(ValueError): validate_policy(bad, policy, workload)
