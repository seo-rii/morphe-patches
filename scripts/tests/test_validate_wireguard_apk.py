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


class AbiDerivationIntegrityTest(unittest.TestCase):
    def fixture(self, directory):
        original = directory / "signed-source-fixture.apk"
        payloads = {"AndroidManifest.xml": b"original binary manifest fixture",
                    "resources.arsc": b"original resource table fixture",
                    "classes.dex": b"original DEX fixture",
                    "META-INF/NOTICE": b"retain third-party notices",
                    "META-INF/services/example.RSA": b"retain nested resource",
                    "META-INF/MANIFEST.MF": b"invalidated signature manifest",
                    "META-INF/GOOGLE.SF": b"invalidated signature digest",
                    "META-INF/GOOGLE.RSA": b"invalidated signing certificate",
                    "lib/arm64-v8a/libapp.so": b"original arm64 library",
                    "lib/x86_64/libapp.so": b"original x86_64 library",
                    "lib/armeabi-v7a/libapp.so": b"original arm32 library",
                    "lib/x86/libapp.so": b"original x86 library"}
        with zipfile.ZipFile(original, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in payloads.items():
                archive.writestr(name, data)
        verified = {"status": "PASS", "zip_crc": "PASS", "apksigner_exit_code": 0,
                    "certificate_sha256": ["a" * 64], "sha256": INSPECTOR.sha256(original)}
        # This is an integrity fixture, not a claim that these synthetic bytes
        # carry Google's signature. CLI derivation obtains this report via apksigner.
        return original, payloads, verified

    def test_default_rejects_universal_input(self):
        with self.assertRaisesRegex(RuntimeError, "supported 64-bit"):
            INSPECTOR.select_abis({"arm64-v8a", "armeabi-v7a", "x86_64", "x86"})
        with self.assertRaisesRegex(RuntimeError, "explicit derived-input"):
            INSPECTOR.select_abis({"arm64-v8a", "armeabi-v7a"}, {"arm64-v8a"})

    def test_existing_64bit_default_and_explicit_subset(self):
        self.assertEqual(INSPECTOR.select_abis({"arm64-v8a", "x86_64"}),
                         {"arm64-v8a", "x86_64"})
        self.assertEqual(INSPECTOR.select_abis({"arm64-v8a", "x86_64"}, {"x86_64"}), {"x86_64"})

    def test_missing_or_unsupported_architecture_is_rejected(self):
        for selected in ({"x86_64"}, {"armeabi-v7a"}, set()):
            with self.subTest(selected=selected), self.assertRaises(RuntimeError):
                INSPECTOR.select_abis({"arm64-v8a"}, selected, allow_universal=True)

    def test_cli_abi_parser_rejects_empty_duplicates_and_32bit(self):
        for text in ("", "arm64-v8a,", "arm64-v8a,arm64-v8a", "x86", "armeabi-v7a"):
            with self.subTest(text=text), self.assertRaises(argparse.ArgumentTypeError):
                INSPECTOR.parse_abis(text)
        self.assertEqual(INSPECTOR.parse_abis("x86_64,arm64-v8a"), {"x86_64", "arm64-v8a"})

    def test_derivation_preserves_dex_resources_kept_libs_and_origin(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            original, payloads, verified = self.fixture(directory)
            original_bytes = original.read_bytes()
            destination = directory / "unsigned-derived64.apk"
            report = INSPECTOR.derive_64bit_input(original, destination, {"arm64-v8a"}, verified)
            self.assertEqual(original.read_bytes(), original_bytes)
            self.assertEqual(report["original_sha256"], verified["sha256"])
            self.assertEqual(report["derived_sha256"], INSPECTOR.sha256(destination))
            self.assertEqual(report["selected_abis"], ["arm64-v8a"])
            self.assertEqual(set(report["original_abis"]), {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"})
            self.assertFalse(report["force_compatibility"])
            self.assertIn("UNSIGNED", report["signature"])
            with zipfile.ZipFile(destination) as derived:
                names = set(derived.namelist())
                self.assertNotIn("META-INF/GOOGLE.RSA", names)
                self.assertNotIn("META-INF/MANIFEST.MF", names)
                self.assertNotIn("lib/armeabi-v7a/libapp.so", names)
                self.assertNotIn("lib/x86_64/libapp.so", names)
                for name in names:
                    self.assertEqual(derived.read(name), payloads[name])
                self.assertTrue({"classes.dex", "resources.arsc", "AndroidManifest.xml",
                                 "META-INF/NOTICE", "META-INF/services/example.RSA"} <= names)
            manifest = json.loads(Path(report["retained_entry_manifest"]).read_text())
            self.assertEqual(set(manifest["retained_entries"]), names)
            self.assertTrue(report["retained_entry_content_verified"])

    def test_derivation_rejects_missing_abi_unverified_or_changed_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            original, _, verified = self.fixture(directory)
            with self.assertRaisesRegex(RuntimeError, "supported 64-bit"):
                INSPECTOR.derive_64bit_input(original, directory / "missing.apk", {"not-present"}, verified)
            with self.assertRaisesRegex(RuntimeError, "Verified original"):
                INSPECTOR.derive_64bit_input(original, directory / "unverified.apk", {"arm64-v8a"}, {})
            verified["sha256"] = "0" * 64
            with self.assertRaisesRegex(RuntimeError, "changed before"):
                INSPECTOR.derive_64bit_input(original, directory / "changed.apk", {"arm64-v8a"}, verified)
            self.assertFalse((directory / "changed.apk").exists())

    def test_signature_metadata_selection_preserves_unrelated_notices(self):
        self.assertTrue(INSPECTOR.signature_entry("meta-inf/Google.rsa"))
        self.assertTrue(INSPECTOR.signature_entry("META-INF/SIG-EXAMPLE"))
        self.assertFalse(INSPECTOR.signature_entry("META-INF/NOTICE"))
        self.assertFalse(INSPECTOR.signature_entry("META-INF/services/example.SF"))

    def test_certificate_parser_rejects_missing_ambiguous_or_bad_digest(self):
        for text in ("", "Signer #1 certificate SHA-256 digest: bad",
                     ("Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n") * 2):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                INSPECTOR.signer_digests(text)
        self.assertEqual(INSPECTOR.signer_digests("Signer #1 certificate SHA-256 digest: " + "a" * 64),
                         {"a" * 64})

    def test_sdk_ranged_certificate_rotation_ignores_stamp_and_public_key(self):
        modern = "6a2f65ec694a6a632acdcb5080912a565f903d4b8d83f0eb8e44fbdf2660d8e1"
        legacy = "a2a1ad7ba7f41dfca4514e2afeb90691719af6d0fdbed4b09bbf0ed897701ceb"
        output = ("Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: "
                  + modern + "\nSigner (minSdkVersion=33, maxSdkVersion=2147483647) public key SHA-256 digest: "
                  + "b" * 64 + "\nSigner (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: "
                  + legacy + "\nSource Stamp Signer certificate SHA-256 digest: " + "c" * 64 + "\n")
        self.assertEqual(INSPECTOR.signer_digests(output), {modern, legacy})


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


class AppHookIntegrityTest(unittest.TestCase):
    TARGET = b"Lapp/morphe/extension/shared/patches/NetworkProxyPatch;."

    def test_only_real_invocations_from_app_classes_are_counted(self):
        lines = [b"Class descriptor  : 'Lapp/morphe/extension/shared/patches/NetworkProxyPatch;'\n",
                 b"00120: |0000: invoke-static {}, " + self.TARGET + b"initialize:()V\n",
                 b"Class descriptor  : 'Lorg/chromium/net/CronetEngine$Builder;'\n",
                 b"00122: |0000: invoke-static {v0}, " + self.TARGET + b"applyProxyOptions:(LB;)V\n",
                 b"00124: |0001: invoke-static/range {v0 .. v1}, " + self.TARGET + b"recordCronetEngine:(LE;)V\n",
                 b"00014: |[00014] app.morphe.extension.shared.patches.NetworkProxyPatch.initialize:()V\n",
                 b"Class descriptor  : 'La/b/c;'\n",
                 b"00128: |0000: invoke-static {v0}, " + self.TARGET + b"setMainCronetEngine:(LE;)V\n",
                 b"0012a: |0001: invoke-static {}, " + self.TARGET + b"initialize:()V\n"]
        calls, classes = INSPECTOR.network_hook_calls(iter(lines))
        self.assertEqual(calls, {"applyProxyOptions": 1, "recordCronetEngine": 1,
                                 "setMainCronetEngine": 1, "initialize": 1})
        self.assertEqual(classes["initialize"], {"La/b/c;"})

    def test_definition_or_call_before_a_class_does_not_count(self):
        calls, _ = INSPECTOR.network_hook_calls([
            b"00100: |0000: invoke-static {}, " + self.TARGET + b"initialize:()V\n",
            b"00120: |[00120] app.morphe.extension.shared.patches.NetworkProxyPatch.initialize:()V\n"])
        self.assertFalse(any(calls.values()))


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
