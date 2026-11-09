# SPDX-License-Identifier: GPL-3.0-only
"""Compiled locale/value verification, using small aapt2 fixtures only."""
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / 'verify-wireguard-locales.py'
spec = importlib.util.spec_from_file_location('wireguard_locales', path)
CHECK = importlib.util.module_from_spec(spec)
spec.loader.exec_module(CHECK)


class WireGuardLocalesTest(unittest.TestCase):
    def test_locale_configs_and_android_apostrophes(self):
        text = '''    resource 0x7f140001 string/morphe_wireguard_error_invalid_peer
      () "Only one peer."
      (ko) "피어는 하나여야 합니다."
      (fr) "L'application exige un seul pair."
    resource 0x7f140002 string/unrelated
      (ko) "Unrelated"
'''
        expected = {'': {'morphe_wireguard_error_invalid_peer': 'Only one peer.'},
                    'ko': {'morphe_wireguard_error_invalid_peer': '피어는 하나여야 합니다.'},
                    'fr': {'morphe_wireguard_error_invalid_peer': "L\\'application exige un seul pair."}}
        self.assertEqual(CHECK.verify_tables(CHECK.parsed_resources(text), expected),
                         {'default': 1, 'ko': 1, 'fr': 1})

    def test_english_only_cannot_pass_as_localized(self):
        tables = {'morphe_wireguard_settings_title': {'': 'WireGuard proxy'}}
        with self.assertRaisesRegex(ValueError, 'Missing or changed'):
            CHECK.verify_tables(tables, {'ko': {'morphe_wireguard_settings_title': 'WireGuard 프록시'}})

    def test_wrong_locale_value_is_rejected(self):
        tables = {'morphe_wireguard_settings_title': {'ko': 'Wrong'}}
        with self.assertRaisesRegex(ValueError, 'Missing or changed'):
            CHECK.verify_tables(tables, {'ko': {'morphe_wireguard_settings_title': 'WireGuard 프록시'}})

    def test_duplicate_compiled_locale_is_rejected(self):
        text = '''resource 0x7f140001 string/morphe_wireguard_settings_title
 () "First"
 () "Second"
'''
        with self.assertRaisesRegex(ValueError, 'Duplicate compiled locale'):
            CHECK.parsed_resources(text)


if __name__ == '__main__':
    unittest.main()
