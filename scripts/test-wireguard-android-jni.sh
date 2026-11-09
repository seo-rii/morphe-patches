#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Runs public synthetic JNI fixtures through Android app_process; does not install an APK.
set -euo pipefail

wireguard_repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
wireguard_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
wireguard_jdk="${WIREGUARD_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ $# -ne 1 || ! "$1" =~ ^[A-Za-z0-9._:-]+$ ]]; then
    echo "Usage: $0 <explicit-adb-serial>" >&2
    echo "Set ANDROID_HOME and WIREGUARD_JAVA_HOME; build the native payload first." >&2
    exit 2
fi
wireguard_serial="$1"
wireguard_adb="$wireguard_sdk/platform-tools/adb"
wireguard_build_tools="${WIREGUARD_ANDROID_BUILD_TOOLS:-36.0.0}"
wireguard_d8="$wireguard_sdk/build-tools/$wireguard_build_tools/d8"
if [[ ! -x "$wireguard_adb" || ! -x "$wireguard_d8" || ! -x "$wireguard_jdk/bin/javac" ]]; then
    echo "Android SDK platform-tools/build-tools and a JDK are required." >&2
    exit 1
fi
if [[ "$("$wireguard_adb" -s "$wireguard_serial" get-state)" != device ]]; then
    echo "The explicitly selected Android device is not available." >&2
    exit 1
fi
wireguard_abi="$("$wireguard_adb" -s "$wireguard_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
wireguard_api="$("$wireguard_adb" -s "$wireguard_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
case "$wireguard_abi" in
    arm64-v8a|x86_64) ;;
    *) echo "JNI smoke supports arm64-v8a and x86_64 Android only." >&2; exit 1 ;;
esac
if [[ ! "$wireguard_api" =~ ^[0-9]+$ || "$wireguard_api" -lt 26 ]]; then
    echo "Android API 26 or newer is required." >&2
    exit 1
fi
wireguard_library="$wireguard_repo/native/wireguard/build/android/$wireguard_abi/libmorphewireguard.so"
wireguard_android_jar="$wireguard_sdk/platforms/android-36/android.jar"
if [[ ! -f "$wireguard_library" || ! -f "$wireguard_android_jar" ]]; then
    echo "Build the native payload and install SDK platform 36 before this smoke test." >&2
    exit 1
fi
wireguard_local="$(mktemp -d "${TMPDIR:-/tmp}/morphe-wireguard-android-jni.XXXXXX")"
wireguard_remote="/data/local/tmp/morphe-wireguard-jni-$RANDOM-$RANDOM"
wireguard_remote_created=false
cleanup() {
    if [[ "$wireguard_remote_created" == true ]]; then
        "$wireguard_adb" -s "$wireguard_serial" shell rm -rf "$wireguard_remote" >/dev/null 2>&1 || true
    fi
    rm -rf "$wireguard_local"
}
trap cleanup EXIT
mkdir -p "$wireguard_local/classes"
"$wireguard_jdk/bin/javac" --release 8 -encoding UTF-8 -d "$wireguard_local/classes" \
    "$wireguard_repo/extensions/shared-youtube/library/src/main/java/app/morphe/extension/shared/wireguard/NativeTunnel.java" \
    "$wireguard_repo/tests/wireguard-android/WireGuardAndroidJniSmoke.java"
"$wireguard_jdk/bin/jar" cf "$wireguard_local/classes.jar" -C "$wireguard_local/classes" .
JAVA_HOME="$wireguard_jdk" "$wireguard_d8" --min-api 26 --lib "$wireguard_android_jar" \
    --output "$wireguard_local/checks.jar" "$wireguard_local/classes.jar"
"$wireguard_adb" -s "$wireguard_serial" shell mkdir "$wireguard_remote"
wireguard_remote_created=true
"$wireguard_adb" -s "$wireguard_serial" push "$wireguard_local/checks.jar" "$wireguard_remote/checks.jar"
"$wireguard_adb" -s "$wireguard_serial" push "$wireguard_library" "$wireguard_remote/libmorphewireguard.so"
echo "Running Android/ART JNI smoke on $wireguard_serial ($wireguard_abi, API $wireguard_api)."
"$wireguard_adb" -s "$wireguard_serial" shell \
    "CLASSPATH=$wireguard_remote/checks.jar LD_LIBRARY_PATH=$wireguard_remote app_process -Djava.library.path=$wireguard_remote /system/bin WireGuardAndroidJniSmoke"
