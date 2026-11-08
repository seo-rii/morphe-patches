#!/usr/bin/env python3
"""Inspect an actually built .mpp; this is not an APK/device connectivity test."""
import argparse
import hashlib
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def check_elf(data, abi):
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", f"Invalid ELF64: {abi}")
    require(struct.unpack_from("<H", data, 16)[0] == 3, f"Not a shared library: {abi}")
    machine = 183 if abi == "arm64-v8a" else 62
    require(struct.unpack_from("<H", data, 18)[0] == machine, f"Wrong machine: {abi}")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, count = struct.unpack_from("<HH", data, 54)
    require(phsize >= 56 and phoff + phsize * count <= len(data), "Invalid ELF headers")
    loads = 0
    for index in range(count):
        offset = phoff + phsize * index
        kind, flags = struct.unpack_from("<II", data, offset)
        if kind == 1:
            loads += 1
            alignment = struct.unpack_from("<Q", data, offset + 48)[0]
            require(alignment >= 16384, f"ELF LOAD is not 16 KiB aligned: {abi}")
        if kind == 0x6474E551:
            require(not flags & 1, f"Executable ELF stack: {abi}")
    require(loads > 0, f"No ELF LOAD segments: {abi}")


def dex_classes(dexdump, dex_path):
    # Android's own tool validates DEX structure/checksum and emits its API view.
    subprocess.run([str(dexdump), "-c", str(dex_path)], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    result = subprocess.run([str(dexdump), "-l", "xml", str(dex_path)], check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    start = result.stdout.find(b"<api")
    require(start >= 0, "dexdump did not return API XML")
    root = ET.fromstring(result.stdout[start:])
    classes = {}
    for package in root.findall("package"):
        for cls in package.findall("class"):
            name = package.attrib["name"] + "." + cls.attrib["name"]
            classes[name] = cls
    return classes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--dexdump", required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    require(args.bundle.is_file(), "Bundle does not exist")
    require(args.dexdump.is_file(), "Android SDK dexdump does not exist")
    with zipfile.ZipFile(args.bundle) as bundle, tempfile.TemporaryDirectory(
            prefix="wireguard-bundle-check-") as tmp:
        require(bundle.testzip() is None, "Bundle ZIP CRC check failed")
        names = bundle.namelist()
        require(len(names) == len(set(names)), "Duplicate bundle ZIP entries")
        for abi in ("arm64-v8a", "x86_64"):
            name = f"wireguard/{abi}/libmorphewireguard.so"
            data = bundle.read(name)
            local = repo / "native/wireguard/build/android" / abi / "libmorphewireguard.so"
            require(data == local.read_bytes(), f"Bundled native library differs: {abi}")
            check_elf(data, abi)
            print(f"PASS native {abi}: {len(data)} bytes, SHA256 {hashlib.sha256(data).hexdigest()}")

        for resource in (
                "wireguard/licenses/MORPHE-NOTICE.txt",
                "wireguard/licenses/MORPHE-LICENSE.txt",
                "wireguard/licenses/dependencies/golang.zx2c4.com/wireguard/LICENSE",
                "wireguard/licenses/dependencies/gvisor.dev/gvisor/LICENSE"):
            require(len(bundle.read(resource)) > 0, f"Missing notice: {resource}")
        require(bundle.read("wireguard/licenses/MORPHE-NOTICE.txt") == (repo / "NOTICE").read_bytes(),
                "Original Morphe NOTICE changed during packaging")
        license_root = "wireguard/licenses/"
        indexed_licenses = bundle.read(license_root + "INDEX").decode("utf-8").splitlines()
        actual_licenses = {name[len(license_root):] for name in names
                           if name.startswith(license_root) and not name.endswith("/")
                           and name != license_root + "INDEX"}
        require(indexed_licenses == sorted(actual_licenses), "License index is incomplete or duplicated")
        for relative in indexed_licenses:
            require(not relative.startswith("/") and ".." not in Path(relative).parts,
                    "Unsafe license index path")
            require(bundle.read(license_root + relative), "Empty license notice")

        resources = ET.fromstring(bundle.read("addresources/values/shared-youtube/strings.xml"))
        resource_names = [item.attrib["name"] for item in resources.findall("string")]
        required = {"morphe_wireguard_settings_title", "morphe_wireguard_settings_summary"}
        for source in (repo / "extensions/shared-youtube/library/src/main/java/app/morphe/extension/shared/wireguard").glob("*.java"):
            required.update(re.findall(r'"(morphe_wireguard_[a-z_]+)"', source.read_text()))
        require(required <= set(resource_names), "Some WireGuard UI strings are missing from bundle")
        require(all(resource_names.count(name) == 1 for name in required), "Duplicate WireGuard strings")
        print(f"PASS UI resources: {len(required)} keys; original and dependency notices present")

        manifest = bundle.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n ", "")
        require(re.search(r"^Version: \S+", manifest, re.MULTILINE), "Bundle version is missing")
        patch_classes = [f"app.morphe.patches.{app}.misc.wireguard.WireGuardPatchKt"
                         for app in ("youtube", "music")]
        for patch_class in patch_classes:
            require(patch_class.replace(".", "/") + ".class" in names,
                    f"Compiled JVM WireGuard patch is missing: {patch_class}")
        require("app/morphe/patches/shared/misc/wireguard/WireGuardRuntimeKt.class" in names,
                "Shared WireGuard installation helper is missing")
        for stale in ("WireGuardNativePayload", "WireGuardIncludedFingerprint"):
            require(f"app/morphe/patches/youtube/misc/wireguard/{stale}.class" not in names,
                    f"Stale pre-refactor JVM class remains: {stale}")

        main_classes = {}
        dex_names = [name for name in names if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)]
        require(dex_names, "No Android patch DEX; buildAndroid has not completed")
        for index, name in enumerate(dex_names):
            path = Path(tmp) / f"patch-{index}.dex"
            path.write_bytes(bundle.read(name))
            main_classes.update(dex_classes(args.dexdump, path))
        for patch_class in patch_classes:
            require(patch_class in main_classes,
                    f"WireGuard patch is absent from the Android patch DEX: {patch_class}")
        require("app.morphe.patches.shared.misc.wireguard.WireGuardRuntimeKt" in main_classes,
                "Shared WireGuard installation helper is absent from Android DEX")
        for stale in ("WireGuardNativePayload", "WireGuardIncludedFingerprint"):
            require(f"app.morphe.patches.youtube.misc.wireguard.{stale}" not in main_classes,
                    f"Stale pre-refactor Android DEX class remains: {stale}")

        extension = Path(tmp) / "shared-youtube.dex"
        extension.write_bytes(bundle.read("extensions/shared-youtube.mpe"))
        classes = dex_classes(args.dexdump, extension)
        package = "app.morphe.extension.shared.wireguard."
        for name in ("ConfigText", "NativeTunnel", "NativeTunnel$InvalidProfileException",
                     "ProfileOperations", "WireGuardConfigStore", "WireGuardProxy",
                     "WireGuardPreference", "WireGuardSettingsActivity"):
            require(package + name in classes, f"Runtime class missing from DEX: {name}")
        methods = {method.attrib["name"]: method for method in classes[package + "NativeTunnel"].findall("method")}
        expected = {
            "start": ("int", ["java.lang.String", "java.lang.String", "java.lang.String", "int"]),
            "stop": ("void", []),
            "validate": ("void", ["java.lang.String"]),
        }
        for name, (result_type, parameter_types) in expected.items():
            method = methods[name]
            require(method.attrib.get("native") == "true" and method.attrib.get("static") == "true",
                    f"JNI method flags changed: {name}")
            require(method.attrib.get("return") == result_type, f"JNI return type changed: {name}")
            require([p.attrib["type"] for p in method.findall("parameter")] == parameter_types,
                    f"JNI parameters changed: {name}")
        marker = [m for m in classes[package + "WireGuardProxy"].findall("method")
                  if m.attrib["name"] == "isPatchIncluded"]
        require(len(marker) == 1, "Patch-presence injection method was removed or duplicated")
        music_extension = Path(tmp) / "music.dex"
        music_extension.write_bytes(bundle.read("extensions/music.mpe"))
        music_classes = dex_classes(args.dexdump, music_extension)
        for name in ("settings.Settings", "settings.preference.MusicPreferenceFragment",
                     "patches.EnableAudioVideoSwitchPatch", "patches.lyrics.derive.OpenAIClient"):
            require("app.morphe.extension.music." + name in music_classes,
                    f"Music runtime class missing from DEX: {name}")
        # DEX string constants may be Modified UTF-8. Keep all bytes intact;
        # the instruction opcodes and method descriptors checked here are ASCII.
        disassembly = subprocess.run([str(args.dexdump), "-d", str(music_extension)],
                                     check=True, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE).stdout
        direct_url_calls = re.findall(br"invoke-\S+[^\n]*Ljava/net/URL;\.(?:openConnection|openStream):",
                                      disassembly)
        require(not direct_url_calls, "Music extension still contains direct URL connection/stream calls")
        routed_calls = re.findall(
            br"invoke-static(?:/range)?[^\n]*Lapp/morphe/extension/shared/requests/Requester;\.openConnection:",
            disassembly)
        require(len(routed_calls) >= 16, "Music Requester routing calls are unexpectedly missing")
        print(f"PASS Music DEX routing: {len(routed_calls)} Requester calls, no direct URL connection/stream calls")
        print("PASS both app patch DEX classes, shared/Music extension DEX and three exact JNI signatures")
    print("PASS bundle inspection; APK application and device connectivity NOT tested")


if __name__ == "__main__":
    main()
