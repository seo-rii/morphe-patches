# WireGuard prototype verification — 2026-11-07 (Asia/Seoul)

This is the historical **YouTube-only** delivery record. The current source also
includes YouTube Music; see [wireguard-music-verification.md](wireguard-music-verification.md)
for that extension's separate build and test evidence. Do not apply the binary
hash or patch count below to the newer combined bundle.

## Delivery status

**The final `.mpp` was built successfully after the user authorized Android SDK
license acceptance and installation.** Full Kotlin and Android Java compilation,
actual Morphe JVM archive loading, and SDK DEX/native/resource inspection passed.
The earlier SDK license blocker is resolved, not an outstanding build failure.

Delivered bundle: `morphe-wireguard-preview-2.1.0.mpp` (the unmodified build output
`patches/build/libs/patches-2.1.0.mpp`, renamed for clarity).

- Size: 24,123,158 bytes.
- SHA-256: `3eec162923b554000c8cc7c21982c756710048ad32a962b6f5e04c12f9117ad2`.
- Unofficial and **unsigned**; no official signing key or endorsement is claimed.
- Source ZIP includes the corresponding modified source, public source build
  dependencies, vendored Go dependencies and both compiled Android native files.

**No target YouTube APK was patched and no Android device test was performed.**
The successful JVM loader and static Android DEX checks do not prove Android DEX
execution, fingerprint matching, Cronet interception, provider connectivity, or
Morphe Manager's acceptance of an unsigned local bundle.

The implementation is an unofficial experiment based on Morphe Patches commit
`8fa2c7a1a832498de8f179748998a3ca87e198eb`. All application behavior that has not
actually been executed on a target APK remains an **expected result (예상 결과)**.

## Executed checks

Native Go/JNI and standalone Kotlin checks below were actually executed during
the initial implementation. They were not re-run in the SDK-authorized build
continuation. The restored native binaries were SHA-256 checked against that
delivery, then inspected again inside the final bundle. Full Gradle compilation,
eight Java tests, archive loading and final bundle inspection were executed in
the continuation. Device behavior remains unexecuted.

| Check | Actual result | What it establishes / does not establish |
| --- | --- | --- |
| Android `arm64-v8a` native compile | PASS | Real API 26 ELF shared object; not an Android execution test |
| Android `x86_64` native compile | PASS | Real API 26 ELF shared object; not an emulator execution test |
| ELF/JNI inspection | PASS | Correct architectures; 16 KiB LOAD alignment; 3 JNI exports; NX stack; RELRO/NOW |
| Selected Go race suite | PASS | 13 test functions plus 3 fuzz seeds; includes in-memory encrypted-peer integration |
| Repeated race testing | PASS | Earlier selected suite passed three consecutive runs |
| Parser fuzzing | PASS | 31,377 executions in 10 seconds; independent review run also passed 67,306 executions |
| `go vet ./...` | PASS | Static Go checks |
| Java → JNI → Go validation smoke | PASS | 19 assertions; actual native library loading and validation on host JVM |
| Java `ConfigTextTest` | PASS | 8 tests, actually compiled/run both standalone and through AGP `testDebugUnitTest`; zero skipped, failures or errors |
| Kotlin `WireGuardNativePayloadTest` | PASS | 3 JUnit tests using actual ABI/ELF validation source |
| Android platform `javac` subset | PASS | `NativeTunnel`, `ConfigText`, `WireGuardConfigStore` against actual Android 36 jar |
| Full Android release Java compilation | PASS | Actual `compileReleaseJavaWithJavac`, including all six WireGuard classes and NetworkProxyPatch, with real SDK/dependencies |
| WireGuard resource keys and `git diff --check` | PASS | All referenced added string keys present; no whitespace errors |
| Morphe Gradle plugin from public source | PASS | Actual plugin Kotlin compile and jar creation |
| Full Gradle task discovery | PASS | `:patches:tasks --all`, 2 minutes 33 seconds |
| Full patch Kotlin Gradle compile | PASS | Actual `:patches:compileKotlin`, including WireGuard patch and upstream integration |
| Android extension DEX/R8 and `.mpp` | PASS | Actual `:patches:buildAndroid` completed; unsigned bundle produced |
| Real Morphe archive loader | PASS | 201 patches loaded; WireGuard occurs once, opt-in, with compatibility and Network proxy dependency |
| Final bundle inspection | PASS | CRC/no duplicates; two native files identical to tested payloads; ELF/16 KiB; 28 UI keys; notices; SDK DEX checksums/classes and three exact native signatures |
| Default UDP integration suite | BLOCKED | Six real-UDP tests encounter host `AF_NETLINK` permission denial |
| Full JNI startup/network lifecycle | BLOCKED | Same host native-start restriction; validation-only success must not be counted as full JNI success |
| APK patching, Manager import, Android playback | NOT RUN | Needs a supported target APK and a device/emulator; no real provider profile was supplied |

### Native engine suite

The passing selection was actually run with:

```sh
go test -race -count=1 -v \
  -run '^(TestMemory|TestIdle|TestValidate|TestAllowedIPs|FuzzValidate)' ./...
go vet ./...
GOMAXPROCS=2 go test -run '^$' -fuzz FuzzValidate -fuzztime=10s -parallel=2
```

The `TestMemory*` cases use two genuine WireGuard engines connected with
upstream's explicitly test-only in-memory `ChannelBind`. The encrypted packet
exchange, inner IP stack, DNS, HTTP and verified TLS are real; the outer UDP
transport and Android OS integration are **not** exercised by these cases.
Production always uses the official default UDP bind, not the test transport.

Coverage includes inner IPv4 and IPv6, POST bodies, roughly 1 MiB streaming,
proxy authentication before request-triggered DNS/dial, header isolation,
restricted routes, no host-network fallback, pipelined CONNECT bytes,
repeated/concurrent shutdown and active-versus-stalled stream deadlines.
The parser has 23 invalid-profile subcases and five valid variants.

### Host restriction, explicitly separated

The retained full suite `go test -race -count=1 ./...` was attempted and did not
pass in this environment. An independent diagnostic produced:

```text
upstream UDP Bind.Open: success
Linux route-monitor socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE): operation not permitted
```

No privilege elevation, OS restriction bypass or production transport workaround
was used. Upstream's Android build already disables this Linux-only sticky-socket
route monitor. That explains why the host failure does not by itself prove an
Android failure, but it does **not** establish Android success either.

The validation-only JNI command passed 19 assertions, including Korean/emoji
comments, BOM, NUL/unpaired surrogate rejection, size limits and sanitized errors:

```sh
WIREGUARD_JAVA_HOME=/path/to/jdk17 bash scripts/test-wireguard-jni.sh --validate-only
```

The full JNI command also was attempted: validation passed, then `start` threw
the sanitized `IllegalStateException` at the known host restriction. Its
authentication and restart assertions after `start` were not reached.

## Successful full build and provenance

The initial attempt stopped at the SDK license gate (exit 130), without accepting
it or producing a bundle. Following the user's explicit approval, official
`sdkmanager` installed only the required stable packages after one displayed
`android-sdk-license` acceptance: platform-tools 37.0.1, Android platform 36 r2,
and build-tools 36.0.0. No global license acceptance or manual license marker was
used. That authorization resolves the previous blocker.

The continuation used clean public source checkouts, not the previous temporary
smali composite. All four Morphe/ARSCLib inputs were fetched at exact commits:

| Source | Revision |
| --- | --- |
| Gradle plugin v2.1.0 | `5b7e4619931a8c78b7858e598e002222d37021b9` |
| Patches library v2.1.1 | `11f8c7e4d9dd64e18b0594d2c7cbf09176a65327` |
| Patcher v2.3.0 | `d19545e8505e0329cc640723cb8ff91eddd3cd05` |
| ARSCLib | `675bed2de397097400ba6f0c808e28005ae5aa53` |

The plugin's optional-GitHub-credentials adaptation is included as an exact patch.
No dummy credentials or private repository access was used. Original smali module
declarations were preserved: Gradle's actual dependencyInsight selected
`4220ae75f7` for smali, smali-dexlib2 and smali-util by normal conflict resolution
against patcher's request for `ac700e1942`. No smali source checkout or forced
version participated in the successful build.

The full production compile completed with exit 0 in 4m4s (64 actionable tasks).
This supersedes the earlier failed manual classpath experiment: all actual Java
UI/proxy integration and full WireGuard Kotlin patch sources now compile.
The subsequent final build completed with exit 0 in 2m42s (326 actionable tasks),
including Android extension DEX/R8, the bundle and real archive-loader test.

Two non-product issues were corrected during the continuation: the AGP build
exposes `testDebugUnitTest`, not `testReleaseUnitTest`; and the newly added loader
verification task needed an explicit project task-container receiver in its
Kotlin DSL. The final successful commands and provenance are recorded in
[PUBLIC_SOURCE_BUILD.md](../build-support/PUBLIC_SOURCE_BUILD.md).

The ConfigText AGP XML reports 8 tests, 0 skipped, 0 failures and 0 errors. The
real JVM loader found 201 total patches and verified the WireGuard opt-in and
dependency metadata. The SDK's `dexdump` then checked the actual Android output:
all six runtime classes, the patch class, presence marker and exact three JNI
signatures survived packaging/R8. The bundled native payloads equal the original
tested files byte-for-byte; notices and all 28 referenced UI keys are included.

The archive contains no SDK/JDK/Gradle caches, task-local proxy settings,
credentials, real VPN profile or proprietary YouTube APK. It includes source
snapshots of the four public build dependencies and the plugin modification,
plus the public-source init script; standard build tools are separately installed.

## Toolchain and delivered native files

- Go 1.26.10; NDK r28c; Android API 26 target.
- Temurin JDK 17.0.21+9; Android platform jar API 36.
- Gradle 9.8.2; standalone Kotlin compiler 2.4.10 for the ABI helper test.
- Official `wireguard-go` pin:
  `v0.0.0-20261024220952-b3bc704ef0ee`.
- Exact transitive Go versions/checksums: `native/wireguard/go.mod`, `go.sum`.
  Corresponding vendored Go source and license notices accompany the native files.

| ABI | Bytes | SHA-256 |
| --- | ---: | --- |
| arm64-v8a | 10,090,512 | `15e4489d9ffabe0dd05d3f42a2e87df7067c86a2a028d6d016f2c2cd4a0ab5be` |
| x86_64 | 10,802,192 | `cccd484e98079daa16f432338351c38125ced7ff6a0d828df7aaa7ab407cfcb0` |

The shared library name is `libmorphewireguard.so` in each ABI folder. ELF
inspection found only Android `liblog.so`, `libdl.so` and `libc.so` as external
dynamic dependencies. This does not measure runtime memory, latency or battery use.

## Review findings fixed before delivery

1. Bound stalled hijacked CONNECT socket operations.
2. Correct the initial timeout implementation so active one-way streaming is not
   terminated merely because the opposite direction is idle.
3. Convert JNI UTF-16 to standard UTF-8 rather than Modified UTF-8, with explicit
   validation and temporary-buffer clearing.
4. Qualify UI readiness as local readiness, not a verified remote handshake.

The review found no remaining high-severity issue in the inspected intercepted
path. It was a limited implementation review, not a third-party security audit.
In particular, it does not certify coverage of all YouTube networking.

## Next acceptance gate

The build gate is complete. Next use a supported 64-bit YouTube APK, an Android
device/emulator and a controlled WireGuard peer. Execute
[wireguard-device-acceptance.md](wireguard-device-acceptance.md), including real
Cronet authentication, profile encryption/restore, playback, IPv6/DNS and traffic
coverage, background/network transitions, and existing-system-VPN interaction.
Keep `NativeTunnel.stop()` unused by production Java unless cached proxy clients
are first blocked/reset; otherwise freeing a cached loopback port could allow
port reuse. The current production code never calls it.
