# SPDX-License-Identifier: GPL-3.0-only
# See the included NOTICE file for additional GPLv3 Section 7 requirements.
"""Cross-language diagnostic/resource contracts; no Android or Crowdin execution."""

from collections import Counter
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
RESOURCE_GROUP = "shared-youtube/strings.xml"
RESOURCE_ROOT = ROOT / "patches/src/main/resources/addresources"
PROFILE_OPERATIONS = (ROOT / "extensions/shared-youtube/library/src/main/java"
                      / "app/morphe/extension/shared/wireguard/ProfileOperations.java")


def native_diagnostics():
    """Read the finite wire-protocol table at its native error construction sites."""
    source = (ROOT / "native/wireguard/config.go").read_text(encoding="utf-8")
    return re.findall(r'\binvalid\(\s*"([a-z0-9_]+)"\s*,\s*"([^"\n]*)"\s*\)', source)


def java_diagnostic_resources():
    """Read only the code-to-resource binding table, not workflow implementation."""
    source = PROFILE_OPERATIONS.read_text(encoding="utf-8")
    return re.findall(r'case\s+"([a-z0-9_]+)"\s*->\s*"(morphe_wireguard_error_[a-z0-9_]+)"', source)


class WireGuardI18nContractTest(unittest.TestCase):
    def test_every_native_error_has_one_machine_code_and_localized_binding(self):
        native = native_diagnostics()
        source = (ROOT / "native/wireguard/config.go").read_text(encoding="utf-8")
        self.assertTrue(native, "No native diagnostic codes found")
        self.assertEqual(len(native), len(re.findall(r'\breturn\s+nil,\s*invalid\(', source)),
                         "A native error lacks a fixed code/reason pair")
        codes = [code for code, _ in native]
        self.assertEqual(len(codes), len(set(codes)), "Native diagnostic codes are duplicated")
        bindings = java_diagnostic_resources()
        self.assertEqual(set(codes), {code for code, _ in bindings},
                         "A native error would lose its translated explanation")
        self.assertEqual(len(bindings), len(codes), "A diagnostic code has multiple bindings")

    def test_all_bound_resources_exist_once_and_are_translatable(self):
        resources = ET.parse(RESOURCE_ROOT / "values" / RESOURCE_GROUP).getroot().findall("string")
        names = Counter(item.attrib["name"] for item in resources)
        by_name = {item.attrib["name"]: item for item in resources}
        for code, key in java_diagnostic_resources():
            with self.subTest(code=code):
                self.assertEqual(names[key], 1, key)
                self.assertTrue("".join(by_name[key].itertext()).strip(), key)
                self.assertNotEqual(by_name[key].get("translatable"), "false", key)
        for key in ("morphe_wireguard_validation_failed", "morphe_wireguard_profile_error_title"):
            self.assertEqual(names[key], 1, key)

    def test_crowdin_uses_existing_android_resource_group(self):
        config = (ROOT / "crowdin.yml").read_text(encoding="utf-8")
        source = "patches/src/main/resources/addresources/values/" + RESOURCE_GROUP
        blocks = re.split(r"(?m)^\s*-\s+source:\s*", config)[1:]
        matching = [block for block in blocks if block.splitlines()[0].strip().strip('"') == source]
        self.assertEqual(len(matching), 1, "WireGuard must share the established resource group")
        target = re.search(r"(?m)^\s*translation:\s*(.+)$", matching[0])
        self.assertIsNotNone(target)
        self.assertEqual(target.group(1).strip().strip('"'),
                         "patches/src/main/resources/addresources/values-%android_code%/" + RESOURCE_GROUP)

    def test_existing_locale_entries_have_english_source_keys(self):
        english = ET.parse(RESOURCE_ROOT / "values" / RESOURCE_GROUP).getroot().findall("string")
        known = {item.attrib["name"] for item in english if item.attrib["name"].startswith("morphe_wireguard_")}
        self.assertTrue(known)
        # Zero translated entries is allowed: translation-ready code is not proof of translation.
        for path in RESOURCE_ROOT.glob("values-*/" + RESOURCE_GROUP):
            if b"morphe_wireguard_" not in path.read_bytes():
                continue
            entries = [item for item in ET.parse(path).getroot().findall("string")
                       if item.attrib["name"].startswith("morphe_wireguard_")]
            names = [item.attrib["name"] for item in entries]
            with self.subTest(locale=path.parent.parent.name):
                self.assertEqual(len(names), len(set(names)), "Duplicate translated WireGuard keys")
                self.assertLessEqual(set(names), known, "Translated key has no English fallback")
                self.assertTrue(all("".join(item.itertext()).strip() for item in entries))


if __name__ == "__main__":
    unittest.main()
