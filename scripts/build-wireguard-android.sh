#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Build only the app-local WireGuard native payloads. Does not patch/install any APK.
set -euo pipefail

wireguard_repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
wireguard_go_version="1.26.10"
wireguard_ndk_version="28.2.13676358"
wireguard_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
wireguard_ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${wireguard_sdk:+$wireguard_sdk/ndk/$wireguard_ndk_version}}}"
wireguard_go="${WIREGUARD_GO_BIN:-go}"
wireguard_api="${WIREGUARD_ANDROID_API:-26}"

if [[ -z "$wireguard_ndk" ]]; then
    echo "Install NDK $wireguard_ndk_version and set ANDROID_HOME or ANDROID_NDK_HOME." >&2
    exit 1
fi
if [[ "$wireguard_api" != 26 ]]; then
    echo "This payload targets Android API 26; WIREGUARD_ANDROID_API must be 26." >&2
    exit 1
fi
if [[ ! -f "$wireguard_ndk/source.properties" ]] ||
        ! sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$wireguard_ndk/source.properties" \
            | tr -d '\r' | awk -v expected="$wireguard_ndk_version" '$0 == expected { ok = 1 } END { exit !ok }'; then
    echo "WireGuard requires Android NDK $wireguard_ndk_version (r28c)." >&2
    exit 1
fi
if [[ "$(GOTOOLCHAIN=local "$wireguard_go" env GOVERSION)" != "go$wireguard_go_version" ]]; then
    echo "WireGuard requires Go $wireguard_go_version; set WIREGUARD_GO_BIN to that compiler." >&2
    exit 1
fi
case "$(uname -s)" in
    Linux) wireguard_host="linux-x86_64" ;;
    Darwin) wireguard_host="darwin-x86_64" ;;
    *) echo "This build script supports Linux or macOS hosts." >&2; exit 1 ;;
esac
wireguard_toolchain="$wireguard_ndk/toolchains/llvm/prebuilt/$wireguard_host/bin"
wireguard_output="${WIREGUARD_NATIVE_OUTPUT:-$wireguard_repo/native/wireguard/build/android}"
wireguard_abis=("${@:-}")
if [[ $# -eq 0 ]]; then wireguard_abis=(arm64-v8a x86_64); fi

"$wireguard_go" version
cd "$wireguard_repo/native/wireguard"
GOWORK=off GOTOOLCHAIN=local "$wireguard_go" mod download
GOWORK=off GOTOOLCHAIN=local "$wireguard_go" mod verify

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
    env GOWORK=off GOTOOLCHAIN=local GOFLAGS= GOEXPERIMENT= \
        CGO_ENABLED=1 GOOS=android GOARCH="$wireguard_arch" CC="$wireguard_cc" \
        CGO_CFLAGS="-O2 -g0" CGO_CPPFLAGS= CGO_CXXFLAGS="-O2 -g0" CGO_FFLAGS="-O2 -g0" \
        CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now" \
        "$wireguard_go" build -mod=readonly -trimpath -buildvcs=false -buildmode=c-shared \
        -ldflags="-s -w -buildid=" \
        -o "$wireguard_output/$wireguard_abi/libmorphewireguard.so" ./cmd/jni

    # Check the actual payload, not merely a successful cross-compiler exit code.
    "$wireguard_toolchain/llvm-readelf" -h "$wireguard_output/$wireguard_abi/libmorphewireguard.so"
    python3 "$wireguard_repo/native/wireguard/verify_payload.py" \
        "$wireguard_abi" "$wireguard_output/$wireguard_abi/libmorphewireguard.so"
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
