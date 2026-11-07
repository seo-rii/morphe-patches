# SPDX-License-Identifier: GPL-3.0-only
# See the included NOTICE file for additional GPLv3 Section 7 requirements.
"""Host parser/integrity fixtures only; these do not patch a target app APK."""

import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "validate-wireguard-apk.py"
SPEC = importlib.util.spec_from_file_location("wireguard_apk_inspector", SCRIPT)
INSPECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INSPECTOR)


def manifest(application_attributes, activity_attributes, extra=""):
    return INSPECTOR.parse_xmltree(("""N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=1)
    A: package="com.google.android.youtube" (Raw: "com.google.android.youtube")
    E: application (line=2)
""" + application_attributes + """      E: activity (line=3)
        A: android:name(0x01010003)="app.morphe.extension.shared.wireguard.WireGuardSettingsActivity"
""" + activity_attributes + extra).encode())


APPLICATION = "      A: android:extractNativeLibs(0x010104ea)=(type 0x12)0xffffffff\n"
ACTIVITY = """        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:excludeFromRecents(0x01010017)=(type 0x12)0xffffffff
"""


class ManifestIntegrityTest(unittest.TestCase):
    def test_current_aapt2_namespace_uri_and_boolean_output(self):
        text = ("""N: android=http://schemas.android.com/apk/res/android (line=1)
  E: manifest (line=1)
    A: package="fixture.wireguard" (Raw: "fixture.wireguard")
      E: application (line=1)
        A: http://schemas.android.com/apk/res/android:extractNativeLibs(0x010104ea)=true
          E: activity (line=1)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.morphe.extension.shared.wireguard.WireGuardSettingsActivity" (Raw: "app.morphe.extension.shared.wireguard.WireGuardSettingsActivity")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
            A: http://schemas.android.com/apk/res/android:excludeFromRecents(0x01010017)=true
""").encode()
        tree = INSPECTOR.parse_xmltree(text)
        INSPECTOR.check_manifest(tree, tree)

    def test_correct_activity_and_application_scopes(self):
        tree = manifest(APPLICATION, ACTIVITY)
        INSPECTOR.check_manifest(tree, tree)

    def test_boolean_on_wrong_element_cannot_satisfy_application(self):
        tree = manifest("", ACTIVITY + "        A: android:extractNativeLibs=(type 0x12)0xffffffff\n")
        with self.assertRaisesRegex(RuntimeError, "Application native extraction"):
            INSPECTOR.check_manifest(tree, tree)

    def test_another_nonexported_activity_cannot_hide_exported_wireguard(self):
        tree = manifest(APPLICATION, ACTIVITY.replace("=(type 0x12)0x0", "=(type 0x12)0xffffffff"),
                        """      E: activity (line=5)
        A: android:name="other.Activity"
        A: android:exported=(type 0x12)0x0
""")
        with self.assertRaisesRegex(RuntimeError, "exported"):
            INSPECTOR.check_manifest(tree, tree)

    def test_added_vpn_service_is_rejected(self):
        stock = manifest(APPLICATION, ACTIVITY)
        patched = manifest(APPLICATION, ACTIVITY, """      E: service (line=5)
        A: android:name="hidden.Service"
        A: android:permission="android.permission.BIND_VPN_SERVICE"
""")
        with self.assertRaisesRegex(RuntimeError, "VPN service"):
            INSPECTOR.check_manifest(patched, stock)

    def test_added_broad_storage_permission_is_rejected(self):
        stock = manifest(APPLICATION, ACTIVITY)
        patched = manifest(APPLICATION, ACTIVITY, """    E: uses-permission (line=5)
      A: android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
""")
        with self.assertRaisesRegex(RuntimeError, "storage permission"):
            INSPECTOR.check_manifest(patched, stock)

    def test_duplicate_attribute_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Duplicate manifest attribute"):
            manifest(APPLICATION, ACTIVITY + "        A: android:exported=(type 0x12)0x0\n")


class InputIntegrityTest(unittest.TestCase):
    def test_license_index_rejects_traversal_ambiguity_and_duplicates(self):
        for text in (b"../secret\n", b"/secret\n", b"a/../../b\n", b"a//b\n",
                     b"a\\b\n", b"a/./b\n", b"INDEX\n", b"a\na\n", b"\n"):
            with self.subTest(text=text), self.assertRaises((RuntimeError, UnicodeDecodeError)):
                INSPECTOR.license_paths(text)
        self.assertEqual(INSPECTOR.license_paths(b"dependencies/go/LICENSE\nMORPHE-NOTICE.txt\n"),
                         ["dependencies/go/LICENSE", "MORPHE-NOTICE.txt"])

    def test_matrix_label_cannot_escape_output_directory(self):
        for target in ("../escape=app.apk", "/escape=app.apk", "a/b=app.apk", "=app.apk"):
            with self.subTest(target=target), self.assertRaises(argparse.ArgumentTypeError):
                INSPECTOR.parse_target(target)

    def test_missing_matrix_inputs_never_report_success_or_start_a_run(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result = subprocess.run([sys.executable, str(SCRIPT), "matrix",
                                     "--bundle", str(directory / "missing.mpp"),
                                     "--desktop", str(directory / "missing.jar"),
                                     "--java", str(directory / "missing-java"),
                                     "--build-tools", str(directory / "missing-tools"),
                                     "--output-dir", str(directory / "runs")],
                                    capture_output=True, check=False)
            self.assertEqual(result.returncode, 2)
            report = json.loads(result.stdout)
            self.assertEqual(report["status"], "NOT RUN")
            self.assertEqual(report["connectivity"], "NOT RUN")
            self.assertTrue(report["missing_prerequisites"])
            self.assertFalse((directory / "runs").exists())

    def test_zip_duplicate_and_traversal_are_rejected(self):
        for names in (("classes.dex", "classes.dex"), ("../classes.dex",)):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "fixture.zip"
                with warnings.catch_warnings(), zipfile.ZipFile(path, "w") as archive:
                    warnings.simplefilter("ignore", UserWarning)
                    for name in names:
                        archive.writestr(name, b"fixture")
                with zipfile.ZipFile(path) as archive, self.assertRaises(RuntimeError):
                    INSPECTOR.check_zip(archive)

    def test_translated_value_cannot_satisfy_default_resource_requirement(self):
        output = b"""    resource 0x7f010001 string/morphe_wireguard_english
      () "Default English value"
    resource 0x7f010002 string/morphe_wireguard_translated_only
      (ko) "Translated value"
    resource 0x7f010003 drawable/other
      () (file) other.xml
"""
        self.assertEqual(INSPECTOR.default_string_keys(output), {"morphe_wireguard_english"})


class InjectionMarkerTest(unittest.TestCase):
    HEADER = (b"001234: |[001234] app.morphe.extension.shared.wireguard."
              b"WireGuardProxy.isPatchIncluded:()Z\n")

    def test_unconditional_true_marker(self):
        INSPECTOR.marker_is_true(self.HEADER + b"001244: |0000: const/4 v0, #int 1 // #1\n"
                                b"001246: |0001: return v0\n")

    def test_unpatched_false_marker_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "not an unconditional true"):
            INSPECTOR.marker_is_true(self.HEADER + b"001244: |0000: const/4 v0, #int 0 // #0\n"
                                    b"001246: |0001: return v0\n")

    def test_call_site_or_other_class_does_not_satisfy_marker(self):
        with self.assertRaisesRegex(RuntimeError, "missing or duplicated"):
            INSPECTOR.marker_is_true(b"001234: |0000: invoke-static {}, Lapp/morphe/extension/"
                                    b"shared/wireguard/WireGuardProxy;.isPatchIncluded:()Z\n")

    def test_returning_another_register_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "not an unconditional true"):
            INSPECTOR.marker_is_true(self.HEADER + b"001244: |0000: const/4 v0, #int 1 // #1\n"
                                    b"001246: |0001: return v1\n")


class DesktopResultIntegrityTest(unittest.TestCase):
    def result(self):
        return {"success": True, "appliedPatches": ["WireGuard proxy (experimental)"],
                "failedPatches": [], "stepResults": [{"step": "PATCHING", "success": True}]}

    def test_successful_desktop_report(self):
        INSPECTOR.check_desktop_result(self.result())

    def test_success_flag_cannot_hide_a_failed_patch(self):
        result = self.result()
        result["failedPatches"] = [{"name": "WireGuard proxy (experimental)", "error": "fixture"}]
        with self.assertRaisesRegex(RuntimeError, "failed patches"):
            INSPECTOR.check_desktop_result(result)

    def test_success_flag_cannot_hide_an_unapplied_wireguard_patch(self):
        result = self.result()
        result["appliedPatches"] = ["Network proxy"]
        with self.assertRaisesRegex(RuntimeError, "as applied"):
            INSPECTOR.check_desktop_result(result)

    def test_success_flag_cannot_hide_a_failed_step(self):
        result = self.result()
        result["stepResults"] = [{"step": "PATCHING", "success": False}]
        with self.assertRaisesRegex(RuntimeError, "failed patching step"):
            INSPECTOR.check_desktop_result(result)


if __name__ == "__main__":
    unittest.main()
