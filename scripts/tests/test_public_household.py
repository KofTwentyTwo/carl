# Copyright (C) 2026 KofTwentyTwo
"""From-scratch public fixture privacy, independent ledger and mutation checks."""
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('public_household', Path(__file__).parents[1] / 'generate-public-household.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PublicHouseholdTests(unittest.TestCase):
    def setUp(self):
        self.dataset = MODULE.build()

    def test_deterministic_without_source_inputs(self):
        self.assertEqual(MODULE.serialize(self.dataset), MODULE.serialize(MODULE.build()))
        MODULE.validate(self.dataset)

    def test_merchant_outside_allowlist_fails(self):
        self.dataset['transactions'][0]['Merchant'] = 'UNAPPROVED_PRIVATE_CANARY'
        with self.assertRaisesRegex(ValueError, 'allowlist'):
            MODULE.validate(self.dataset)

    def test_imported_note_outside_public_allowlist_fails(self):
        self.dataset['transactions'][0]['Notes'] = 'UNAPPROVED_PRIVATE_CANARY'
        with self.assertRaisesRegex(ValueError, 'allowlist'):
            MODULE.validate(self.dataset)

    def test_manifest_account_rename_cannot_expand_allowlist(self):
        old = self.dataset['manifest']['accounts'][0]['label']
        self.dataset['manifest']['accounts'][0]['label'] = 'UNAPPROVED_PRIVATE_CANARY'
        for row in self.dataset['transactions']:
            if row['Account'] == old:
                row['Account'] = 'UNAPPROVED_PRIVATE_CANARY'
        with self.assertRaisesRegex(ValueError, 'allowlist'):
            MODULE.validate(self.dataset)

    def test_private_text_in_tag_fails(self):
        self.dataset['transactions'][0]['Tags'] = 'UNAPPROVED_PRIVATE_CANARY'
        with self.assertRaisesRegex(ValueError, 'allowlist'):
            MODULE.validate(self.dataset)

    def test_corrupt_expected_closing_balance_fails(self):
        self.dataset['manifest']['expected']['closingByAccount']['Fictional Household Cash'] = '0.00'
        with self.assertRaisesRegex(ValueError, 'Expected closing balance'):
            MODULE.validate(self.dataset)

    def test_wrong_cent_balance_fails(self):
        self.dataset['balances'][-1]['Balance'] = str(MODULE.money(self.dataset['balances'][-1]['Balance']) + MODULE.money('0.01'))
        with self.assertRaisesRegex(ValueError, 'balance'):
            MODULE.validate(self.dataset)

    def test_unbalanced_transfer_fails(self):
        row = next(r for r in self.dataset['transactions'] if r['Tags'].startswith('pair:'))
        row['Amount'] = str(MODULE.money(row['Amount']) + MODULE.money('0.01'))
        with self.assertRaisesRegex(ValueError, 'transfer'):
            MODULE.validate(self.dataset)

    def test_private_scope_and_credit_are_not_shared_cash(self):
        expected = self.dataset['manifest']['expected']
        self.assertNotEqual(expected['liquidSharedUSD'], expected['availableCreditUSD'])
        self.assertIn('Fictional Aster Wallet', expected['excludedSharedAccounts'])
        self.assertNotIn('Fictional Aster Wallet', expected['sharedAccountLabels'])

    def test_source_overlap_correction_and_conflicts_are_distinct(self):
        extras = self.dataset['cases']
        self.assertEqual(extras['overlap'][0], self.dataset['transactions'][0])
        self.assertEqual(extras['correction'][0]['Id'], next(r for r in self.dataset['transactions'] if r['Category'] == 'Salary')['Id'])
        self.assertNotEqual(extras['correction'][0]['Notes'], next(r for r in self.dataset['transactions'] if r['Category'] == 'Salary')['Notes'])
        self.assertEqual(extras['balanceConflict'][0]['Account'], extras['balanceConflict'][1]['Account'])
        self.assertNotEqual(extras['balanceConflict'][0]['Balance'], extras['balanceConflict'][1]['Balance'])


if __name__ == '__main__':
    unittest.main()
