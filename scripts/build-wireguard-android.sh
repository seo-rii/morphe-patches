#!/usr/bin/env bash
# Build only the app-local WireGuard native payloads. Does not patch/install any APK.
set -euo pipefail

wireguard_repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
wireguard_ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
wireguard_go="${WIREGUARD_GO_BIN:-go}"
wireguard_api="${WIREGUARD_ANDROID_API:-26}"

if [[ -z "$wireguard_ndk" ]]; then
    echo "Set ANDROID_NDK_HOME to an installed Android NDK (r28c or newer recommended)." >&2
    exit 1
fi
case "$(uname -s)" in
    Linux) wireguard_host="linux-x86_64" ;;
    Darwin) wireguard_host="darwin-x86_64" ;;
    *) echo "This build script supports Linux or macOS hosts." >&2; exit 1 ;;
esac
wireguard_toolchain="$wireguard_ndk/toolchains/llvm/prebuilt/$wireguard_host/bin"
wireguard_output="$wireguard_repo/native/wireguard/build/android"
wireguard_abis=("${@:-}")
if [[ $# -eq 0 ]]; then wireguard_abis=(arm64-v8a x86_64); fi

"$wireguard_go" version
cd "$wireguard_repo/native/wireguard"
"$wireguard_go" mod verify

for wireguard_abi in "${wireguard_abis[@]}"; do
    case "$wireguard_abi" in
        arm64-v8a) wireguard_arch=arm64; wireguard_target=aarch64-linux-android ;;
        x86_64) wireguard_arch=amd64; wireguard_target=x86_64-linux-android ;;
        *) echo "Unsupported ABI: $wireguard_abi (choose arm64-v8a or x86_64)." >&2; exit 1 ;;
    esac
    wireguard_cc="$wireguard_toolchain/${wireguard_target}${wireguard_api}-clang"
    if [[ ! -x "$wireguard_cc" ]]; then
        echo "NDK compiler not found: $wireguard_cc" >&2
        exit 1
    fi
    mkdir -p "$wireguard_output/$wireguard_abi"
    echo "Building $wireguard_abi (Android API $wireguard_api)"
    env CGO_ENABLED=1 GOOS=android GOARCH="$wireguard_arch" CC="$wireguard_cc" \
        CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now" \
        "$wireguard_go" build -trimpath -buildvcs=false -buildmode=c-shared \
        -ldflags="-s -w" \
        -o "$wireguard_output/$wireguard_abi/libmorphewireguard.so" ./cmd/jni

    # Check the actual payload, not merely a successful cross-compiler exit code.
    "$wireguard_toolchain/llvm-readelf" -h "$wireguard_output/$wireguard_abi/libmorphewireguard.so"
    "$wireguard_toolchain/llvm-nm" -D --defined-only \
        "$wireguard_output/$wireguard_abi/libmorphewireguard.so" \
        | sed -n '/Java_app_morphe_extension_shared_wireguard_NativeTunnel_/p'
    for wireguard_method in start stop validate; do
        "$wireguard_toolchain/llvm-nm" -D --defined-only \
            "$wireguard_output/$wireguard_abi/libmorphewireguard.so" \
            | awk -v expected="Java_app_morphe_extension_shared_wireguard_NativeTunnel_$wireguard_method" \
                '$NF == expected { found = 1 } END { exit !found }'
    done
done

echo "Native payloads are ready under native/wireguard/build/android."
echo "Build the .mpp with the repository Gradle buildAndroid task next."
