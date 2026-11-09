# Native dependency notices and source provenance

These files are copied from the exact public source releases below. The notices
ship in the patch bundle and in the patched APK under `assets/wireguard/licenses/`.
The WireGuard settings screen exposes them to the software user. Source paths
below identify corresponding upstream sources; dependency implementation is not
vendored in this repository. `go.mod` and `go.sum` pin module versions and hashes.

| Component | Exact source | License |
| --- | --- | --- |
| Go runtime / standard library | https://go.dev/dl/#go1.26.10 | BSD-3-Clause, patent grant |
| WireGuard | https://git.zx2c4.com/wireguard-go/commit/?id=b3bc704ef0ee | MIT |
| gVisor userspace TCP/IP stack | https://github.com/google/gvisor/tree/39ed1f5ac29c | Apache-2.0; additional MIT text retained in LICENSE |
| google/btree | https://github.com/google/btree/tree/v1.1.2 | Apache-2.0 |
| golang.org/x/crypto | https://cs.opensource.google/go/x/crypto/+/refs/tags/v0.37.0 | BSD-3-Clause, patent grant |
| golang.org/x/net | https://cs.opensource.google/go/x/net/+/refs/tags/v0.39.0 | BSD-3-Clause, patent grant |
| golang.org/x/sys | https://cs.opensource.google/go/x/sys/+/refs/tags/v0.32.0 | BSD-3-Clause, patent grant |
| golang.org/x/time | https://cs.opensource.google/go/x/time/+/refs/tags/v0.7.0 | BSD-3-Clause, patent grant |

Module notice files are verbatim upstream files. `COPYRIGHTS` collects copyright
notice lines from source files selected by `go list -deps -json ./cmd/jni` for
Android with the pinned Go toolchain. The surrounding Morphe GPL-3.0 license and
mandatory section 7 NOTICE are included alongside these files by Gradle.

The Go source archive is `go1.26.10.linux-amd64.tar.gz`, SHA-256
`ccfe7ae822d2c2f41f4fe7c956199967a4dac22d0ee0d2b2bfdb440aab3b20c5`.
Android payloads use NDK r28c (`28.2.13676358`), Android API 26, and the source
build command in `scripts/build-wireguard-android.sh`. Go's cross-compiled runtime
is linked into each `.so`; the Go compiler and NDK themselves are build tools.

`golang.zx2c4.com/wintun` is recorded in the cross-platform WireGuard module graph,
but is not compiled or shipped in the Android payloads. Windows-only Wintun source
and its notice are therefore not included in this Android notice set. No VPN
provider SDK, proprietary app APK, credentials, account key, or private profile
is included.

The JNI loader, HTTP proxy and integration source added by this contribution
remain under the repository GPL-3.0 license with its additional NOTICE terms.
Distributing a binary requires providing its corresponding source under the
applicable licenses; a list of dependency links does not replace that obligation.
