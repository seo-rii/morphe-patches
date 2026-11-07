#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# See the included NOTICE file for additional GPLv3 Section 7 requirements.
"""Patch supplied APKs with Desktop --validate, or inspect existing patched APKs.

This never installs an APK or claims Android connectivity. Missing inputs produce
NOT RUN and exit 2; failed checks produce FAIL and exit 1. Matrix outputs are
unsigned to avoid creating or handling signing credentials.
"""

import argparse
import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


PACKAGE = "app.morphe.extension.shared.wireguard."
ACTIVITY = PACKAGE + "WireGuardSettingsActivity"
SUPPORTED_ABIS = {"arm64-v8a", "x86_64"}
APP_PACKAGES = {"com.google.android.youtube", "com.google.android.apps.youtube.music"}
JNI_PREFIX = "Java_app_morphe_extension_shared_wireguard_NativeTunnel_"
REPO = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def run_tool(command):
    result = subprocess.run([str(arg) for arg in command], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE)
    require(result.returncode == 0,
            f"{Path(command[0]).name} failed with exit {result.returncode}")
    return result.stdout


def check_zip(archive):
    names = archive.namelist()
    require(len(names) == len(set(names)), "Duplicate ZIP entries")
    for name in names:
        path = PurePosixPath(name)
        require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
                "Unsafe ZIP entry path")
    require(archive.testzip() is None, "ZIP CRC validation failed")
    return names


def license_paths(data):
    paths = data.decode("ascii").splitlines()
    require(paths and len(paths) == len(set(paths)), "Empty or duplicate license index")
    for path in paths:
        parts = path.split("/")
        require(re.fullmatch(r"[A-Za-z0-9._+@/-]+", path)
                and all(part not in ("", ".", "..") for part in parts)
                and not path.startswith("/") and path != "INDEX",
                "Unsafe license index path")
    return paths


def parse_xmltree(data):
    """Read aapt2's scoped element/attribute tree, never match a global bool."""
    roots, stack = [], []
    for line in data.decode("utf-8").splitlines():
        element = re.match(r"^(\s*)E: ([\w.-]+)(?:\s|$)", line)
        if element:
            indent, name = len(element[1]), element[2]
            while stack and indent <= stack[-1][0]:
                stack.pop()
            node = {"name": name, "attributes": {}, "children": []}
            (stack[-1][1]["children"] if stack else roots).append(node)
            stack.append((indent, node))
            continue
        attribute = re.match(r"^\s*A: ([^\s(=]+)(?:\([^)]*\))?=(.*)$", line)
        if attribute:
            require(stack, "Manifest attribute has no element")
            key, value = attribute[1], attribute[2].strip()
            key = key.replace("http://schemas.android.com/apk/res/android:", "android:", 1)
            require(key not in stack[-1][1]["attributes"], "Duplicate manifest attribute")
            if value.startswith('"'):
                value, _ = json.JSONDecoder().raw_decode(value)
            elif re.fullmatch(r"\(type 0x12\)0x(?:ffffffff|00000000|0)", value):
                value = value.endswith("ffffffff")
            elif value in ("true", "false"):
                value = value == "true"
            stack[-1][1]["attributes"][key] = value
    require(len(roots) == 1 and roots[0]["name"] == "manifest", "Manifest tree missing")
    return roots[0]


def walk(node):
    yield node
    for child in node["children"]:
        yield from walk(child)


def check_manifest(patched, stock):
    applications = [node for node in patched["children"] if node["name"] == "application"]
    require(len(applications) == 1, "Expected one application")
    application = applications[0]
    require(application["attributes"].get("android:extractNativeLibs") is True,
            "Application native extraction is not enabled")
    activities = [node for node in application["children"] if node["name"] == "activity"
                  and node["attributes"].get("android:name") == ACTIVITY]
    require(len(activities) == 1, "WireGuard settings Activity missing or duplicated")
    require(activities[0]["attributes"].get("android:exported") is False,
            "WireGuard settings Activity is exported or unspecified")
    require(activities[0]["attributes"].get("android:excludeFromRecents") is True,
            "WireGuard settings Activity is not excluded from recents")

    def vpn_entries(tree):
        return {(node["name"], str(node["attributes"].get("android:name", "")))
                for node in walk(tree)
                if node["attributes"].get("android:permission") == "android.permission.BIND_VPN_SERVICE"
                or node["attributes"].get("android:name") in
                ("android.net.VpnService", "android.permission.BIND_VPN_SERVICE")}

    require(vpn_entries(patched) <= vpn_entries(stock), "A VPN service declaration was added")
    broad_storage = {"android.permission.READ_EXTERNAL_STORAGE",
                     "android.permission.WRITE_EXTERNAL_STORAGE",
                     "android.permission.MANAGE_EXTERNAL_STORAGE"}
    def permissions(tree):
        return {node["attributes"].get("android:name") for node in walk(tree)
                if node["name"].startswith("uses-permission")}
    require(not ((permissions(patched) - permissions(stock)) & broad_storage),
            "A broad storage permission was added")


def default_string_keys(data):
    keys, current = set(), None
    for line in data.splitlines():
        resource = re.match(rb"\s*resource 0x[0-9a-fA-F]+ (?:[\w.]+:)?([^\s]+)", line)
        if resource:
            current = resource[1][7:] if resource[1].startswith(b"string/") else None
        elif re.match(rb"\s*(type|Package|package)\b", line):
            current = None
        elif current and re.match(rb"\s*\((?:default)?\)\s+", line):
            keys.add(current.decode("ascii"))
    return keys


def marker_is_true(disassembly):
    header = re.compile(rb"(?m)^[0-9a-f]+:[^\n]*\|\[[0-9a-f]+\] "
                        rb"app\.morphe\.extension\.shared\.wireguard\.WireGuardProxy"
                        rb"\.isPatchIncluded:\(\)Z\r?$")
    matches = list(header.finditer(disassembly))
    require(len(matches) == 1, "WireGuard inclusion method missing or duplicated")
    instructions = []
    for line in disassembly[matches[0].end():].splitlines():
        instruction = re.search(rb"\|[0-9a-f]+: (.+)$", line)
        if not instruction:
            if instructions:
                break
            continue
        instructions.append(instruction[1])
        if len(instructions) == 2:
            break
    require(len(instructions) == 2, "WireGuard inclusion method has no usable body")
    constant = re.fullmatch(rb"const(?:/4|/16)? (v\d+), #int 1(?:\s+//.*)?", instructions[0])
    require(constant is not None and instructions[1] == b"return " + constant[1],
            "WireGuard inclusion marker is not an unconditional true return")


def network_hook_calls(lines):
    """Count injected invocations from app classes, excluding extension self-calls."""
    methods = ("applyProxyOptions", "recordCronetEngine", "setMainCronetEngine", "initialize")
    calls = {name: 0 for name in methods}
    classes = {name: set() for name in methods}
    current_class = b""
    for line in lines:
        descriptor = re.search(rb"Class descriptor\s*: '([^']+)'", line)
        if descriptor:
            current_class = descriptor[1]
        if not current_class or current_class.startswith(b"Lapp/morphe/extension/"):
            continue
        invocation = re.search(rb"\|[0-9a-f]+: invoke-static(?:/range)?\s+[^\n]*"
                               rb"Lapp/morphe/extension/shared/patches/NetworkProxyPatch;\."
                               rb"(applyProxyOptions|recordCronetEngine|setMainCronetEngine|initialize):", line)
        if invocation:
            name = invocation[1].decode("ascii")
            calls[name] += 1
            classes[name].add(current_class.decode("ascii"))
    return calls, classes


def dex_network_hook_calls(dexdump, dex):
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen([str(dexdump), "-d", str(dex)], stdout=subprocess.PIPE, stderr=errors)
        try:
            result = network_hook_calls(process.stdout)
        finally:
            process.stdout.close()
            exit_code = process.wait()
        require(exit_code == 0, f"dexdump failed while inspecting app hooks: exit {exit_code}")
        return result


def bundle_helpers():
    spec = importlib.util.spec_from_file_location("wireguard_bundle_inspector",
                                                 REPO / "scripts/verify-wireguard-bundle.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def native_abis(names):
    return {match[1] for name in names
            if (match := re.fullmatch(r"lib/([^/]+)/[^/]+\.so", name))}


def parse_abis(text):
    values = text.split(",")
    if not values or len(values) != len(set(values)) or not set(values) <= SUPPORTED_ABIS:
        raise argparse.ArgumentTypeError("ABIs must be a unique comma-list of arm64-v8a,x86_64")
    return set(values)


def select_abis(available, requested=None, allow_universal=False):
    require(available, "Stock APK has no complete native architecture")
    chosen = set(available if requested is None else requested)
    require(chosen and chosen <= SUPPORTED_ABIS, "Selected architectures must be supported 64-bit ABIs")
    require(chosen <= available, "Selected architecture is absent from the original APK")
    require(allow_universal or available <= SUPPORTED_ABIS,
            "Stock APK contains unsupported architectures; explicit derived-input mode is required")
    return chosen


def signer_digests(output):
    # Modern APKs rotate certificates by Android SDK range. Source-stamp and
    # public-key digests are different identities and must not satisfy this check.
    selector = r"(?:#\d+|\(minSdkVersion=\d+, maxSdkVersion=\d+\))"
    matches = re.findall(r"(?m)^Signer (" + selector
                         + r") certificate SHA-256 digest: ([0-9a-fA-F:]+)$", output)
    require(matches and len(matches) == len({number for number, _ in matches}),
            "Source APK signer report is missing or ambiguous")
    digests = {digest.replace(":", "").lower() for _, digest in matches}
    require(all(re.fullmatch(r"[0-9a-f]{64}", digest) for digest in digests),
            "Source APK certificate fingerprint is malformed")
    return digests


def source_signers(package):
    app = {"com.google.android.youtube": "youtube",
           "com.google.android.apps.youtube.music": "music"}.get(package)
    require(app, "Source APK package is unsupported")
    relative = f"patches/src/main/kotlin/app/morphe/patches/{app}/shared/Constants.kt"
    declaration = re.search(r"\bsignatures\s*=\s*setOf\((.*?)\)",
                            (REPO / relative).read_text(), re.DOTALL)
    require(declaration is not None, "Cannot read upstream signer allowlist")
    allowed = set(re.findall(r'"([0-9a-f]{64})"', declaration[1]))
    require(allowed, "Upstream signer allowlist is empty")
    return allowed, relative


def verify_source(apk, build_tools, metadata):
    with zipfile.ZipFile(apk) as archive:
        check_zip(archive)
    result = subprocess.run([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs",
                             str(apk)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(result.returncode == 0, f"Original APK signature verification failed: exit {result.returncode}")
    output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
    actual = signer_digests(output)
    allowed, relative = source_signers(metadata["package"])
    require(actual <= allowed, "Original APK signer is not in upstream's Google certificate allowlist")
    return {"status": "PASS", "sha256": sha256(apk), "metadata": metadata,
            "zip_crc": "PASS", "apksigner_exit_code": result.returncode,
            "certificate_sha256": sorted(actual), "signer_allowlist_source": relative,
            "signer_allowlist_source_sha256": sha256(REPO / relative),
            "signature_report": output}


def signature_entry(name):
    upper = name.upper()
    if not upper.startswith("META-INF/") or upper.count("/") != 1:
        return False
    basename = upper[len("META-INF/"):]
    return basename == "MANIFEST.MF" or basename.startswith("SIG-") or basename.endswith(
        (".SF", ".RSA", ".DSA", ".EC"))


def has_apk_signing_block(path):
    with zipfile.ZipFile(path) as archive, path.open("rb") as stream:
        if archive.start_dir < 24:
            return False
        stream.seek(archive.start_dir - 16)
        return stream.read(16) == b"APK Sig Block 42"


def derive_64bit_input(original, destination, requested, verified_source):
    """Build an unsigned ABI-only fixture; never change the signed original."""
    require(verified_source.get("status") == "PASS" and verified_source.get("zip_crc") == "PASS"
            and verified_source.get("apksigner_exit_code") == 0
            and verified_source.get("certificate_sha256"), "Verified original APK is required")
    original_hash = sha256(original)
    require(original_hash == verified_source.get("sha256"), "Verified source APK changed before derivation")
    removed_native, removed_signatures, retained = [], [], {}
    with zipfile.ZipFile(original) as source:
        names = check_zip(source)
        available = native_abis(names)
        selected = select_abis(available, requested, allow_universal=True)
        require(selected != available, "Derived mode must remove unselected native architectures")
        with destination.open("xb") as output:
            os.chmod(destination, 0o600)
            with zipfile.ZipFile(output, "w") as derived:
                for info in source.infolist():
                    parts = info.filename.split("/")
                    if parts[0] == "lib" and len(parts) > 1 and parts[1] and parts[1] not in selected:
                        removed_native.append(info.filename)
                        continue
                    if signature_entry(info.filename):
                        removed_signatures.append(info.filename)
                        continue
                    digest = hashlib.sha256()
                    with source.open(info) as reader, derived.open(copy.copy(info), "w") as writer:
                        for data in iter(lambda: reader.read(1024 * 1024), b""):
                            digest.update(data)
                            writer.write(data)
                    retained[info.filename] = {"bytes": info.file_size, "sha256": digest.hexdigest()}
    with zipfile.ZipFile(destination) as derived:
        require(set(check_zip(derived)) == set(retained), "Derived APK entry set changed unexpectedly")
        require(native_abis(derived.namelist()) == selected, "Derived APK architecture set is incorrect")
        for name, expected in retained.items():
            digest = hashlib.sha256()
            with derived.open(name) as reader:
                for data in iter(lambda: reader.read(1024 * 1024), b""):
                    digest.update(data)
            require(digest.hexdigest() == expected["sha256"], "Retained APK entry content changed")
    require(not has_apk_signing_block(destination), "Derived APK unexpectedly retains a signing block")
    require(sha256(original) == original_hash, "Original APK changed during derivation")
    manifest = destination.with_suffix(".retained-entries.json")
    write_report({"original_sha256": original_hash, "derived_sha256": sha256(destination),
                  "retained_entries": retained}, manifest, print_output=False)
    return {"input_kind": "unsigned-derived-64-bit-fixture", "original_sha256": original_hash,
            "derived_sha256": sha256(destination), "original_abis": sorted(available),
            "selected_abis": sorted(selected), "signature": "UNSIGNED; original Google signature removed",
            "original_apk_signing_block_present": has_apk_signing_block(original),
            "removed_native_entries": removed_native, "removed_signature_entries": removed_signatures,
            "retained_entry_count": len(retained), "retained_entry_content_verified": True,
            "retained_entry_manifest": str(manifest), "retained_entry_manifest_sha256": sha256(manifest),
            "operation": "Remove unselected native directories and invalidated APK/JAR signature metadata; "
                         "retain every other entry's uncompressed content unchanged",
            "force_compatibility": False}


def apk_metadata(aapt2, apk):
    badging = run_tool([aapt2, "dump", "badging", apk]).decode("utf-8")
    line = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    values = dict(part.split("=", 1) for part in shlex.split(line.removeprefix("package: "))
                  if "=" in part)
    require(values.get("name") and values.get("versionName"), "APK package/version missing")
    return {"package": values["name"], "version": values["versionName"],
            "version_code": values.get("versionCode")}


def inspect_apk(apk, stock, bundle_path, build_tools, readelf, selected_abis=None):
    aapt2, dexdump = build_tools / "aapt2", build_tools / "dexdump"
    helpers = bundle_helpers()
    with zipfile.ZipFile(apk) as patched, zipfile.ZipFile(stock) as original, \
            zipfile.ZipFile(bundle_path) as bundle, tempfile.TemporaryDirectory(
                prefix="wireguard-apk-inspection-") as temporary:
        names, stock_names = check_zip(patched), check_zip(original)
        check_zip(bundle)
        original_abis = native_abis(stock_names)
        abis = select_abis(original_abis, selected_abis, allow_universal=selected_abis is not None)
        require(native_abis(names) == abis, "Patched APK architecture set differs from stock APK")
        native = {}
        for abi in sorted(abis):
            data = patched.read(f"lib/{abi}/libmorphewireguard.so")
            require(data == bundle.read(f"wireguard/{abi}/libmorphewireguard.so"),
                    f"APK native payload differs from bundle: {abi}")
            helpers.check_elf(data, abi)
            path = Path(temporary) / (abi + ".so")
            path.write_bytes(data)
            symbols = run_tool([readelf, "--dyn-syms", "--wide", path]).decode("ascii")
            for method in ("start", "stop", "validate"):
                require(any(len(words := line.split()) >= 8 and words[-1] == JNI_PREFIX + method
                            and words[6] != "UND" for line in symbols.splitlines()),
                        f"Native JNI export missing: {abi}/{method}")
            native[abi] = hashlib.sha256(data).hexdigest()

        paths = license_paths(bundle.read("wireguard/licenses/INDEX"))
        for path in paths:
            data = bundle.read("wireguard/licenses/" + path)
            require(data and patched.read("assets/wireguard/licenses/" + path) == data,
                    f"APK license missing or different from bundle: {path}")

        patched_tree = parse_xmltree(run_tool([aapt2, "dump", "xmltree", "--file",
                                               "AndroidManifest.xml", apk]))
        stock_tree = parse_xmltree(run_tool([aapt2, "dump", "xmltree", "--file",
                                            "AndroidManifest.xml", stock]))
        check_manifest(patched_tree, stock_tree)
        resources = ET.fromstring(bundle.read("addresources/values/shared-youtube/strings.xml"))
        required_keys = {item.attrib["name"] for item in resources.findall("string")
                         if item.attrib["name"].startswith("morphe_wireguard_")}
        require(required_keys and required_keys <= default_string_keys(
            run_tool([aapt2, "dump", "resources", apk])), "Default English WireGuard resources missing")

        runtime = {}
        network_calls = {name: 0 for name in
                         ("applyProxyOptions", "recordCronetEngine", "setMainCronetEngine", "initialize")}
        network_classes = {name: set() for name in network_calls}
        dex_names = sorted(name for name in names
                           if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name))
        require(dex_names, "APK has no DEX files")
        for index, name in enumerate(dex_names):
            dex = Path(temporary) / (str(index) + ".dex")
            dex.write_bytes(patched.read(name))
            classes = helpers.dex_classes(dexdump, dex)
            calls, callers = dex_network_hook_calls(dexdump, dex)
            for method in network_calls:
                network_calls[method] += calls[method]
                network_classes[method].update(callers[method])
            for key, value in classes.items():
                if key.startswith(PACKAGE):
                    require(key not in runtime, "Duplicate WireGuard runtime class across DEX files")
                    runtime[key] = value
            if PACKAGE + "WireGuardProxy" in classes:
                marker_is_true(run_tool([dexdump, "-d", dex]))
        for name in ("ConfigText", "NativeTunnel", "WireGuardConfigStore", "WireGuardProxy",
                     "WireGuardPreference", "WireGuardSettingsActivity"):
            require(PACKAGE + name in runtime, f"APK runtime class missing: {name}")
        require(all(network_calls.values()), "Network proxy hooks are missing from target app code")
        methods = runtime[PACKAGE + "NativeTunnel"].findall("method")
        for name, result_type, parameters in (
                ("start", "int", ["java.lang.String"] * 3 + ["int"]),
                ("stop", "void", []), ("validate", "void", ["java.lang.String"])):
            candidates = [method for method in methods if method.attrib["name"] == name]
            require(len(candidates) == 1, f"JNI method missing or overloaded: {name}")
            method = candidates[0]
            require(method.attrib.get("native") == "true" and method.attrib.get("static") == "true"
                    and method.attrib.get("return") == result_type
                    and [p.attrib["type"] for p in method.findall("parameter")] == parameters,
                    f"JNI method contract changed: {name}")

    run_tool([build_tools / "zipalign", "-c", "-P", "16", "4", apk])
    return {"status": "PASS", "apk_sha256": sha256(apk),
            "stock_sha256": sha256(stock), "bundle_sha256": sha256(bundle_path),
            "stock": apk_metadata(aapt2, stock), "patched": apk_metadata(aapt2, apk),
            "original_abis": sorted(original_abis), "selected_abis": sorted(abis),
            "native_sha256": native, "license_files": len(paths),
            "network_hook_calls_from_app_code": network_calls,
            "network_hook_caller_classes": {name: sorted(values) for name, values in network_classes.items()},
            "default_english_resources": len(required_keys), "dex_files": len(dex_names),
            "wireguard_included_marker": True, "alignment": "PASS", "signature": "NOT CHECKED",
            "android_execution": "NOT RUN", "connectivity": "NOT RUN"}


def write_report(report, path=None, print_output=True):
    report["recorded_at"] = datetime.now(timezone.utc).isoformat()
    text = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if path:
        with path.open("x", encoding="utf-8") as output:
            os.chmod(path, 0o600)
            output.write(text)
    if print_output:
        print(text, end="")


def prerequisites(args):
    missing = []
    for path in (args.bundle, args.build_tools / "aapt2", args.build_tools / "dexdump",
                 args.build_tools / "zipalign", args.readelf):
        if path is None or not path.is_file():
            missing.append(str(path) if path else "readelf/llvm-readelf")
    executables = [args.build_tools / name for name in ("aapt2", "dexdump", "zipalign")]
    if args.readelf is not None:
        executables.append(args.readelf)
    if args.mode == "matrix":
        executables.append(args.java)
        if args.derive_64bit_input:
            executables.append(args.build_tools / "apksigner")
            if not (args.build_tools / "apksigner").is_file():
                missing.append(str(args.build_tools / "apksigner"))
    for path in executables:
        if path.is_file() and not os.access(path, os.X_OK):
            missing.append(f"not executable: {path}")
    if args.mode == "inspect":
        missing += [str(path) for path in (args.apk, args.stock_apk) if not path.is_file()]
    else:
        missing += [str(path) for path in (args.desktop, args.java) if not path.is_file()]
        if not args.target:
            missing.append("at least one --target LABEL=APK")
        for label, path in args.target:
            if not path.is_file():
                missing.append(f"{label}: {path}")
    return missing


def parse_target(text):
    label, separator, path = text.partition("=")
    if not separator or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,100}", label) or not path:
        raise argparse.ArgumentTypeError("Target must be a safe LABEL=/path/to/stock.apk")
    return label, Path(path).expanduser().resolve()


def check_desktop_result(result):
    require(result.get("success") is True, "Desktop result does not report success")
    require(not result.get("failedPatches"), "Desktop reports failed patches")
    require("WireGuard proxy (experimental)" in result.get("appliedPatches", []),
            "Desktop does not report the WireGuard patch as applied")
    require(all(step.get("success") is True for step in result.get("stepResults", [])),
            "Desktop reports a failed patching step")


def matrix(args):
    labels = [label for label, _ in args.target]
    require(len(labels) == len(set(labels)), "Duplicate target labels")
    require(not args.output_dir.exists(), "Output directory already exists; use a fresh path")
    args.output_dir.mkdir(mode=0o700, parents=True)
    java_version = subprocess.run([str(args.java), "-version"], stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE)
    match = re.search(rb'version "(\d+)', java_version.stdout + java_version.stderr)
    require(java_version.returncode == 0 and match and int(match[1]) >= 21,
            "Desktop requires Java 21 or newer")
    report = {"status": "PASS", "scope": "Host APK patching/static inspection only",
              "connectivity": "NOT RUN", "android_execution": "NOT RUN", "runs": [],
              "desktop_sha256": sha256(args.desktop), "bundle_sha256": sha256(args.bundle)}
    for label, apk in args.target:
        metadata = apk_metadata(args.build_tools / "aapt2", apk)
        require(metadata["package"] in APP_PACKAGES, "Target is neither stock YouTube nor Music")
        with zipfile.ZipFile(apk) as archive:
            original_abis = native_abis(check_zip(archive))
        abis = select_abis(original_abis, args.abis, allow_universal=args.derive_64bit_input)
        patch_input, derivation, verification = apk, None, None
        if args.derive_64bit_input:
            input_directory = args.output_dir / (label + "-input")
            input_directory.mkdir(mode=0o700)
            verification = verify_source(apk, args.build_tools, metadata)
            write_report(verification, input_directory / "source-verification.json", print_output=False)
            if abis != original_abis:
                patch_input = input_directory / "unsigned-derived64.apk"
                derivation = derive_64bit_input(apk, patch_input, abis, verification)
                require(apk_metadata(args.build_tools / "aapt2", patch_input) == metadata,
                        "ABI-only derivation changed source package/version metadata")
                write_report(derivation, input_directory / "derivation.json", print_output=False)
        for selection in (["focused", "defaults"] if args.default_selection else ["focused"]):
            directory = args.output_dir / (label + "-" + selection)
            directory.mkdir(mode=0o700)
            output, result = directory / "patched-unsigned.apk", directory / "desktop-result.json"
            command = [str(args.java), "-jar", str(args.desktop), "patch", "-p", str(args.bundle),
                       "--validate", "--unsigned", "--striplibs", ",".join(sorted(abis)),
                       "-t", str(directory / "scratch"), "-o", str(output), "-r", str(result)]
            if selection == "focused":
                command += ["--exclusive", "-e", "WireGuard proxy (experimental)"]
            command.append(str(patch_input))
            record = {"label": label, "selection": selection, "command": command,
                      "stock_sha256": sha256(apk), "stock": metadata, "status": "FAIL",
                      "original_abis": sorted(original_abis), "selected_abis": sorted(abis),
                      "patch_input": str(patch_input), "patch_input_sha256": sha256(patch_input),
                      "input_kind": "unsigned-derived-64-bit-fixture" if derivation else "stock-original",
                      "force_compatibility": False, "derivation": derivation,
                      "original_signature_verification": verification or "NOT CHECKED"}
            log = directory / "desktop.log"
            with log.open("xb") as stream:
                os.chmod(log, 0o600)
                process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
                record["desktop_exit_code"] = process.wait()
            record["log"] = str(log)
            try:
                require(record["desktop_exit_code"] == 0, "Desktop patch/--validate failed")
                require(result.is_file(), "Desktop result report is missing")
                desktop_report = json.loads(result.read_text())
                check_desktop_result(desktop_report)
                record["inspection"] = inspect_apk(output, apk, args.bundle, args.build_tools,
                                                   args.readelf, abis)
                require(record["inspection"]["bundle_sha256"] == report["bundle_sha256"]
                        and record["inspection"]["stock_sha256"] == record["stock_sha256"]
                        and sha256(patch_input) == record["patch_input_sha256"],
                        "Source APK or bundle changed during validation")
                record["status"] = "PASS"
            except (OSError, RuntimeError, ValueError, KeyError, zipfile.BadZipFile,
                    subprocess.SubprocessError, ET.ParseError) as error:
                record["error"] = str(error)
                report["status"] = "FAIL"
            report["runs"].append(record)
            write_report(record, directory / "inspection-result.json")
    write_report(report, args.output_dir / "summary.json")
    return 0 if report["status"] == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="mode", required=True)
    for name in ("inspect", "matrix"):
        command = subcommands.add_parser(name)
        command.add_argument("--bundle", required=True, type=Path)
        command.add_argument("--build-tools", required=True, type=Path)
        command.add_argument("--readelf", type=Path,
                             default=Path(value) if (value := shutil.which("llvm-readelf")
                                                      or shutil.which("readelf")) else None)
        command.add_argument("--abis", type=parse_abis,
                             help="Exact supported ABI subset to inspect/keep, e.g. arm64-v8a")
        if name == "inspect":
            command.add_argument("--apk", required=True, type=Path)
            command.add_argument("--stock-apk", required=True, type=Path)
            command.add_argument("--report", type=Path)
        else:
            command.add_argument("--desktop", required=True, type=Path)
            command.add_argument("--java", required=True, type=Path)
            command.add_argument("--target", action="append", type=parse_target, default=[])
            command.add_argument("--output-dir", required=True, type=Path)
            command.add_argument("--default-selection", action="store_true",
                                 help="Also patch with the complete normal default selection")
            command.add_argument("--derive-64bit-input", action="store_true",
                                 help="Verify original Google signature, then derive an unsigned ABI-only "
                                      "fixture when filtering is needed; requires --abis")
    args = parser.parse_args()
    if args.mode == "matrix" and args.derive_64bit_input and args.abis is None:
        parser.error("--derive-64bit-input requires an explicit --abis selection")
    for key, value in vars(args).items():
        if isinstance(value, Path):
            setattr(args, key, value.expanduser().resolve())
    try:
        missing = prerequisites(args)
        if missing:
            write_report({"status": "NOT RUN", "missing_prerequisites": missing,
                          "android_execution": "NOT RUN", "connectivity": "NOT RUN"},
                         args.report if args.mode == "inspect" else None)
            return 2
        if args.mode == "matrix":
            return matrix(args)
        write_report(inspect_apk(args.apk, args.stock_apk, args.bundle, args.build_tools,
                                 args.readelf, args.abis),
                     args.report)
        return 0
    except (OSError, RuntimeError, ValueError, KeyError, zipfile.BadZipFile,
            subprocess.SubprocessError, ET.ParseError) as error:
        write_report({"status": "FAIL", "error": str(error),
                      "android_execution": "NOT RUN", "connectivity": "NOT RUN"})
        return 1


if __name__ == "__main__":
    sys.exit(main())
