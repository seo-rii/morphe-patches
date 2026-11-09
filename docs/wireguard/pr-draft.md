# Upstream pull request draft (not submitted)

Title: `feat(YouTube / Music): Add experimental app-local WireGuard proxy`

### Description

Adds `WireGuard proxy (experimental)` for YouTube and YouTube Music using their
existing Network proxy hooks. Users import a supported WireGuard `.conf` and
explicitly enable/restart to route intercepted requests through an authenticated
loopback HTTP proxy and the pinned `wireguard-go`/gVisor userspace stack. Android
`VpnService`, root, a system TUN and an external VPN app are not required.

Both app patches are selected by default, preserving the existing v2.1.1 custom
patch policy. The connection setting remains **off** until explicitly enabled.
Each app keeps its own encrypted profile. Disabled processes do not start the
engine, listeners, network workers or keepalives. Intercepted requests do not use
direct fallback when WireGuard fails.

This ports the existing implementation to upstream `dev`, retaining its transport
and shared runtime. It replaces the custom release's compiled-flag repackaging
with ordinary Kotlin/Java/native source builds, adds dependency notices, and
provides host/bundle tests and a separate Android acceptance procedure.

Supported scope: `arm64-v8a`/`x86_64`, ordinary APK installation, single-peer
profiles, numeric endpoints and explicit tunneled DNS. YouTube keeps Network
proxy's 20.47.62 version gate; Music follows its existing proxy API version
branches. This is **not** complete app traffic interception or a system-wide VPN
or kill switch. Unhooked paths and other processes require separate evaluation.

Related: #2827 (proxy protocol expansion). This PR does not add SOCKS5 or claim to
close that request. Add the approved WireGuard feature-request link here if one
is published.

### Additional context

The custom v2.1.1 release's author reports successful real-device use; that is
separate from automated checks for this upstream port. Source baseline:
[seo-rii/morphe-wireguard-patches v2.1.1](https://github.com/seo-rii/morphe-wireguard-patches/releases/tag/v2.1.1).
Upstream port base: `6bdf46e6f12da2a73481b4ba4a4bda2e74dd4be9`.

Separate app patches reuse Network proxy rather than duplicating interception.
The shared Java runtime and settings UI are used by both extensions. The engine
is a pinned Go module with native builds for two ABIs. This adds Go/NDK build and
dependency-update responsibilities; see [design](design.md) and
[security/dependency review](security.md).

The current branch has a separate Music Requester commit (`f3877f7dd`) followed
by the complete WireGuard feature (`b98219e90`). The Music changes preserve the
released patch's extension routing coverage and normal proxy consistency;
no unrelated playback or lyrics logic is rewritten. The prerequisite can be
reviewed separately if preferred.

Third-party notices are retained in the bundle and copied into APK assets for
the WireGuard settings screen. Dependency advisories, JNI callgraph limits and
the existing same-app multiprocess storage limitation are disclosed in
[security.md](security.md) and [dependency-audit.md](dependency-audit.md). Module
pins remain unchanged from the working baseline; a zero symbol finding is not a
claim that all required dependency modules are free of advisories.

### Test results

Latest UI/import follow-up: the normal source build passed 1,476 of 1,481
Java/Kotlin cases (5 existing APK-dependent skips), including 24 new import
workflow cases. Go race/vet (26 top-level tests), 42 host JNI checks and 33
Python checks passed. Both app JVM/Android DEX defaults are true; routing remains
off until explicitly enabled. The exact native exception class/String constructor,
ABI payloads, resources and notices are present.

YouTube 21.30.216 and Music 9.30.53 each passed focused and complete-default
Desktop `--validate` plus final APK inspection: four cases for bundle SHA-256
`58c1728705754ff117aac037addbf420ee887fa4b1101731a51ac1e0242f8675`.
The signed originals were verified; universal YouTube used an explicit unsigned
arm64-only fixture with retained content checked. No force flag was used.
Target-app UI/import, AndroidKeyStore, handshake/playback/DNS and network
transitions remain unverified. The reporting user's import failure has not
been reproduced on their device. This is still a fork draft before any official
upstream submission. See the latest section of [validation.md](validation.md).

The records below retain earlier source/artifact cohorts; their counts are not
additional results for the new bundle.

Initial checks executed on 2026-11-07; exact commands, tool versions and limits are in
[validation.md](validation.md) and [build.md](build.md):

- Pristine release baseline: Gradle 5 Kotlin + 8 Java tests, native race suite and
  host JNI tests passed before changes.
- Current shared-library Gradle tests: 34 passed, no failures/errors/skips.
- Full Gradle graph: 1,452 passed, 5 upstream Jam APK-dependent cases skipped,
  no failures; 195,106 strings checked. All 5 WireGuard Kotlin tests ran.
- Pinned Go 1.26.10 native race/integration suite: 21 top-level tests plus fuzz
  seeds passed; vet and a 10-second fuzz run passed; host JNI smoke: 31 checks.
- Both Android native ABIs built from source; a second isolated-output build
  produced identical hashes on the same host/toolchain.
- Forced source bundle rebuild: 275 tasks executed with no Gradle build cache;
  loader found 203 patches; Desktop 2.1.1 listed both patches as default-selected;
  final DEX/JNI/native/resources/notices inspection passed.
- Android/ART JNI smoke: 19 checks passed on an isolated API 30 x86_64 emulator
  using shell `app_process`; this did not run a patched YouTube/Music app.
- Initial APK-wrapper parser/integrity tests: 20 host fixtures passed before
  the later APKPure acquisition and matrix described below.

APKPure follow-up (2026-11-08 KST): five acquired originals passed actual Google
signature/package/version/integrity checks. All five arm64 WireGuard-focused
cases passed `--validate` and final APK inspection; four complete-default cases
passed (Music 9.15.51/9.30.53/9.45.51 and YouTube 21.44.528). YouTube 20.51.39
default selection failed in the unchanged Captions legacy authentication
fingerprint with two matches; disabling WireGuard reproduced the failure.
The matrix correctly exited 1 for its nine PASS/one FAIL result.

YouTube inputs were explicitly derived unsigned 64-bit fixtures from verified
universal originals with every retained DEX/resource entry unchanged; those
original universal APKs are rejected by the existing guard even when Desktop
`--striplibs` is supplied. Music used original signed arm64 inputs. No force,
gate removal or validation bypass was used. At that point the verification suite
had 31 passing tests. [Full input hashes, scope and evidence](apkpure-validation.md).

APKPure-matrix unsigned artifact: `patches-2.3.0-wireguard-dev.mpp` (24,205,503 bytes),
SHA-256 `fa272d0d6f49b0b65b6d252c353659a24318bac68fefdbb71ca43350702e2d94`.

The reviewed runtime propagates interrupted plaintext HTTP responses and drains
peer receivers before closing the userspace TUN (`d91a66090`). Eight encrypted
HTTP/HTTPS body cases, the gated shutdown regression (20 race repetitions),
300 repeated lifecycle shutdown rounds, the full native suite (23 tests plus
fuzz seeds), vet and 31 host JNI checks passed. Both native ABIs and the rebuilt
24,205,686-byte source bundle passed native/DEX/JNI/resource inspections;
the bundle SHA-256 is
`705738552dce5ce575884062df9f5da90bd29b11e4984428e3ac92ad8fc8e3fc`.
The verifier preserves NDK readelf's multicall alias and has 33 passing Python
tests.

Using the author's verified APKMirror YouTube 21.30.216 original, this final
bundle passed focused (1 named patch, including dependencies) and normal-default
(113 patches) Desktop `--validate` plus both final APK inspections, matrix exit
0. Both outputs are unsigned arm64 APKs made from an explicit ABI-only fixture
of the Google-signed universal original. The earlier R2 run used `bf67c82f2` and
bundle `b3b69ab2cf654618f2a323680a86d5ae3efa264f267ab7db16a6beffbe927bf4`;
it remains a distinct previous cohort. Other APKPure-version results above were
not rerun with the final bundle. Exact artifacts, the failed CI race result and
corrected local verification are in
[shutdown validation](validation.md#native-shutdown-and-final-artifact-follow-up-2026-11-08).

**Not run:** target-app installation/settings/import/handshake/playback/DNS/
failure/handoff/concurrent-app acceptance. Two isolated emulator attempts had
framework ANRs/restarts before target app installation. A stable Android
environment and controlled test peer are needed. No target-app runtime result is
established by host JNI tests,
APK validation, metadata listing or shell-UID Android JNI smoke.

- [ ] Tested on both the **minimum** and **maximum** supported versions
- [ ] Tested on **experimental** supported versions (Optional)
- [ ] Desktop `--validate` passed for latest recommended and experimental targets
  of both apps with no ambiguous fingerprints
- [ ] Android handshake, playback, DNS, failure and network-transition evidence
  recorded for this port

The remaining Android/version cases and expected results are in
[device-acceptance.md](device-acceptance.md). Do not mark these boxes complete
using the author's earlier, unspecified v2.1.1 device report.

### Submission notes (remove before posting)

No official upstream PR has been submitted. A
[personal-fork draft PR](https://github.com/seo-rii/morphe-patches/pull/1) exists
for review and CI. Complete the outstanding target-APK/device tests or explicitly
agree their scope with maintainers, refresh tested commit/artifact links, and
obtain the author's approval before posting to the official upstream.
Keep upstream's three primary template sections above in the posted body.
