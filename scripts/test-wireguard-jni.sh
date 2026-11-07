#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
go_bin="${WIREGUARD_GO_BIN:-go}"
jdk_dir="${WIREGUARD_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "$jdk_dir" || ! -x "$jdk_dir/bin/javac" ]]; then
    echo "Set WIREGUARD_JAVA_HOME (or JAVA_HOME) to a JDK 17+ installation." >&2
    exit 1
fi
case "$(uname -s)" in
    Linux) jni_platform=linux; library_name=libmorphewireguard.so ;;
    Darwin) jni_platform=darwin; library_name=libmorphewireguard.dylib ;;
    *) echo "Host JNI tests support Linux and macOS." >&2; exit 1 ;;
esac
out_dir="$repo_dir/native/wireguard/build/host-jni"
mkdir -p "$out_dir/classes"
(
    cd "$repo_dir/native/wireguard"
    CGO_ENABLED=1 CGO_CFLAGS="\"-I$jdk_dir/include\" \"-I$jdk_dir/include/$jni_platform\"" \
        "$go_bin" build -tags=jni -trimpath -buildvcs=false -buildmode=c-shared \
        -o "$out_dir/$library_name" ./cmd/jni
)
"$jdk_dir/bin/javac" -encoding UTF-8 -d "$out_dir/classes" \
    "$repo_dir/extensions/shared-youtube/library/src/main/java/app/morphe/extension/shared/wireguard/NativeTunnel.java" \
    "$repo_dir/tests/wireguard-jni/WireGuardJniSmoke.java"
"$jdk_dir/bin/java" -Djava.library.path="$out_dir" -cp "$out_dir/classes" WireGuardJniSmoke "$@"
