# Device and target-version acceptance

Status: target-app runtime cases below remain **NOT RUN**. APKPure host
patching/inspection has now executed; its nine PASS/one FAIL matrix and explicit
YouTube input-derivation limits are recorded in
[apkpure-validation.md](apkpure-validation.md). Two bounded emulator attempts
failed in the Android framework before any target app installation.
All expected runtime outcomes below are **Expected results**, not observations.
The author's successful real-device use of the original v2.1.1 release is
recorded separately in [README.md](README.md).

## Matrix

This matrix comes from the pinned upstream `dev` constants, not a claim that each
APK works. Recheck the target declarations when rebasing.

| App | Target to obtain | Reason | Declared minimum Android API |
| --- | --- | --- | --- |
| YouTube | 20.51.39 | Oldest currently recommended target above the 20.47.62 proxy gate; older proxy-list API | 28 |
| YouTube | 21.30.216 | Newest recommended target; newer proxy-list API | 29 |
| YouTube | 21.44.528 | Latest declared experimental target | 29 |
| YouTube | 21.43.523 | Other declared experimental target | 29 |
| YouTube | 20.26.46 | Negative compatibility check: below the required gate | 26 |
| YouTube Music | 9.15.51 | Oldest recommended target; before Music's 9.20 proxy API switch | 26 |
| YouTube Music | 9.20.53 | First currently recommended target on the newer API branch | 26 |
| YouTube Music | 9.30.53 | Newest recommended target | 26 |
| YouTube Music | 9.45.51 | Latest declared experimental target | 26 |
| YouTube Music | 9.44.52 / 9.43.51 | Other declared experimental targets | 26 |

YouTube's other recommended targets are 21.28.208, 21.23.492 and 20.51.39.
The lower bound 20.47.62 is inherited from Network proxy, not newly established
by device testing. To establish a lower bound experimentally, temporarily remove
the relevant internal version gates in an isolated test checkout and use Desktop
`-f` as [CONTRIBUTING.md](../../CONTRIBUTING.md) directs. A normal run that logs a
warning and skips the patch is not evidence that an older version works.

Use complete APKs containing only `arm64-v8a` and/or `x86_64`, not a base split
without native libraries or a 32-bit APK. Test a 64-bit physical device and, if
available, x86_64 emulator; include the oldest relevant API level and a recent
Android device with 16 KiB page size. Root-mount installation is unsupported.

Desktop 2.1.1 applies `--striplibs` during resource encoding, after the WireGuard
guard reads original ABI entries. That option alone does not make a universal
APK acceptable. For host fingerprint/resource verification only, the wrapper's
explicit `--derive-64bit-input --abis arm64-v8a` verifies the original Google
signature and creates a separately labeled unsigned fixture with retained
DEX/resources unchanged. Never report it as an untouched stock APK or relax the
runtime ABI guard. The APKPure record documents the actual negative control.

## Reproducible patch preparation

Use a legally obtained stock APK, a pinned Morphe Desktop release and the exact
new bundle. Keep APKs and generated signed packages out of the contribution.
Record their SHA-256 hashes, package/version/ABI, Desktop version, device model,
Android/API/build, test time and final source commit.

The following are commands to run, not completed results. Set the variables to
local paths; use Java 21 or newer for Desktop. Confirm `patch --help` for the
chosen Desktop version before running it.

```sh
java -jar "$DESKTOP_JAR" patch --help
java -jar "$DESKTOP_JAR" patch -p "$BUNDLE" \
  --exclusive -e 'WireGuard proxy (experimental)' --validate \
  -o "$PATCHED_APK" -r "$RESULT_JSON" "$STOCK_APK"
```

For repeatable local matrix runs, the repository provides a wrapper. It does not
download APKs or install anything. Pass named stock-APK paths; labels do not
automatically establish that a file is a minimum, maximum or experimental target.
The recorded APK package/version/hash lets reviewers check that classification.
The output directory must be new.

```sh
python3 scripts/validate-wireguard-apk.py matrix \
  --java "$JAVA_HOME/bin/java" --desktop "$DESKTOP_JAR" \
  --bundle "$BUNDLE" --build-tools "$BUILD_TOOLS" \
  --target "youtube-min=$YT_MIN_APK" --target "youtube-max=$YT_MAX_APK" \
  --target "youtube-experimental=$YT_EXPERIMENTAL_APK" \
  --target "music-min=$MUSIC_MIN_APK" --target "music-max=$MUSIC_MAX_APK" \
  --target "music-experimental=$MUSIC_EXPERIMENTAL_APK" \
  --default-selection --output-dir "$NEW_RESULT_DIRECTORY"
```

The wrapper runs focused `--exclusive` WireGuard/dependency selection and,
with `--default-selection`, normal default selection. It requires Desktop's
successful JSON result plus static inspection. It writes each command, stock APK
hash, output hash, Desktop exit status, log and inspection report; the final
`summary.json` describes only host APK patching/static checks. Missing inputs
produce **NOT RUN**, exit 2; failed checks produce **FAIL**, exit 1. It requires
Python 3.9+ and SDK `aapt2`, `dexdump`, `zipalign`, plus `readelf` or `llvm-readelf`.

The wrapper deliberately produces **unsigned APKs** and handles no signing keys.
Sign a chosen result with the tester's existing local signing process before
device installation, or use Desktop's normal signed command above. An unsigned
artifact is not installable merely because static checks pass.

For an explicitly derived host matrix, add
`--derive-64bit-input --abis arm64-v8a`. Retain `source-verification.json`,
`derivation.json` and the per-entry hash manifest with each result. No `-f` is
added; the original package/version and internal version gates remain intact.

Dependencies are included by the patch resolver. Repeat the latest recommended
and latest experimental APK for each app with the normal default patch selection
(omit `--exclusive`) to catch interactions outside this focused patch set.
Keep every patch failure and fingerprint ambiguity visible. Do not enable
`--continue-on-error` to produce an apparently successful validation result.

Expected result: patching exits successfully, no fingerprint matches more than
one method, both the JNI payload and settings resources are present, and the
output verifies/aligns as required by Desktop. Slow-fingerprint warnings are
separate from ambiguity/failure. Verify that the intended WireGuard patch
actually ran; a compatibility-gated skip is not a pass.

Inspect the resulting APK before installing:

```sh
unzip -l "$PATCHED_APK" 'lib/*/libmorphewireguard.so'
"$BUILD_TOOLS/aapt2" dump badging "$PATCHED_APK"
"$BUILD_TOOLS/aapt2" dump xmltree "$PATCHED_APK" --file AndroidManifest.xml
"$BUILD_TOOLS/apksigner" verify "$PATCHED_APK"
```

Expected result: the original supported ABI receives the matching ELF64 shared
object, the settings Activity is non-exported, native extraction is enabled,
native notices are available, and no VPN service/storage permission was added.
For native/DEX/JNI bundle inspection, run the repository's
`scripts/verify-wireguard-bundle.py` with SDK `dexdump`; archive checks alone are
not APK patching or Android execution tests.

To inspect an existing output separately, use:

```sh
python3 scripts/validate-wireguard-apk.py inspect \
  --apk "$PATCHED_APK" --stock-apk "$STOCK_APK" --bundle "$BUNDLE" \
  --build-tools "$BUILD_TOOLS" --report "$NEW_INSPECTION_REPORT"
```

This compares native payloads and every indexed license byte-for-byte with the
bundle, checks ELF/JNI/DEX contracts and the applied inclusion marker, verifies
scoped manifest attributes and default English resource presence, and checks APK
alignment. It does not verify APK signatures or perform Android execution.

Install and launch with ordinary APK installation, using the package name of the
actual patched app. Do not replace unrelated installed app data to run this
procedure. Select the intended device explicitly when multiple devices exist.

```sh
adb -s "$DEVICE_SERIAL" install -r "$PATCHED_APK"
```

## Acceptance cases

For every case, keep an observed result, pass/fail, duration and redacted evidence
reference. Leave it **NOT RUN** until observed. Cache-only playback is not proof
of a tunnel connection. Use a controlled peer and network capture point where
possible; an Android capture app using `VpnService` changes the test network.
Do not commit private profiles, keys, authorization headers or raw secret-bearing
logs/captures. Use distinct server-issued test profiles for simultaneous apps.

| Case | Procedure | Expected result |
| --- | --- | --- |
| Fresh disabled app | Clear only test app data if deliberately using a disposable installation; launch without importing/enabling | Settings connection default is off; no native engine/listener, WireGuard UDP, keepalive or worker starts |
| Imported but disabled | Import a valid profile, then launch/restart without enabling | Profile is present; no tunnel/network workers start |
| Profile import bounds | Try valid UTF-8, BOM/comments, cancel, unavailable provider, malformed UTF-8, over 64 KiB and unsupported fields | Valid supported input imports; invalid/cancelled input never enables routing or destroys the previous valid profile |
| Unsupported profiles | Try a hostname endpoint, extra peer, shell hook, missing DNS, DNS outside AllowedIPs and unsupported family | Import rejects safely without name lookup, shell execution or silent field omission |
| Secret storage | Inspect a controlled debug/test installation, app settings export and backup behavior; corrupt encrypted data or make the key unavailable | At-rest profile is encrypted in noBackupFilesDir and excluded from settings exports; failures block and never store plaintext |
| Explicit enable/restart | Enable from WireGuard settings and restart | Fresh process uses authenticated local proxy; UI distinguishes local readiness from handshake |
| Handshake | Use controlled peer diagnostics after an uncached request | Authenticated peer handshake and tunneled traffic are observed; UI readiness alone is insufficient |
| YouTube playback | Home/search/thumbnails, normal and long video, Shorts/live, seeking and comments | Uncached intercepted requests succeed through peer; TLS validation remains intact |
| Music playback | Songs/albums/playlists/radio, seeking, background/screen-off, audio/video switch | Uncached intercepted requests succeed; no regression in normal player behavior |
| Extension HTTP | Lyrics/metadata/dictionary requests and optional configured providers using a test service | Methods, headers, bodies, redirects, timeouts and errors are preserved through Requester; no real paid credentials are required |
| Authentication | In a disposable test build, connect to local proxy with missing/wrong credentials and inspect callback/header presence without values | HTTP 407 before destination DNS/dial; correct app credentials work; Proxy-Authorization does not reach origin |
| DNS | Resolve controlled names and capture peer DNS traffic on IPv4, IPv6 and dual-stack profiles | Configured DNS is reached through tunnel; DNS failure blocks; no host resolver fallback for proxy requests |
| Restricted routes | Resolve/dial in-route and out-of-route destinations | In-route requests work; out-of-route requests fail without an ordinary direct connection |
| Tunnel failure | Wrong peer key, unreachable peer, dropped UDP and unavailable DNS | Intercepted requests fail; direct fallback setting does not bypass WireGuard |
| Startup failure | Inject missing/broken native library, unavailable/corrupt profile and loopback setup/proxy-option failures in a test build | Rejection-only endpoint blocks, or hooked engine construction fails; settings remain recoverable where app startup permits |
| QUIC and coverage | Capture app traffic while exercising the paths above; separately inventory WebView/native/GmsCore/Cast/other processes | Hooked Cronet traffic has QUIC disabled; record every uncovered path without claiming complete app protection |
| Profile replacement/removal | Replace/remove while enabled and restart | Existing process does not silently reroute; restart uses new mode/profile; removal disables saved setting and removes encrypted profile/key |
| Repeated lifecycle | Force-stop/relaunch, process kill/recreation, external video links; repeatedly import/enable/disable/restart | No stale tokens/ports or duplicate engines; cleanup does not leak sockets/threads; new process honors saved explicit choice |
| Wi-Fi to mobile | Play uncached content; change network and repeat in reverse | Record recovery time or required manual restart; intercepted requests never bypass the failed tunnel |
| Offline/suspension | Airplane mode, sleep/doze, background restriction and resume | Record observed recovery/resource use; no assertion of guaranteed background service continuity |
| Existing system VPN | Repeat success/failure with another VPN and lockdown off/on | No system VPN-slot takeover; outer WireGuard UDP may be carried by existing VPN; record MTU/blocked-UDP effects |
| Two apps | Import independent server-issued identities, run both simultaneously, change/remove only one | Both paths work independently; settings/files/tokens remain app-local; one app's actions do not reconfigure the other |
| Additional app processes | Exercise components known to run separately and inspect process/network ownership | Record whether they receive hooks/profile/engine; do not infer cross-process protection from the main process |

## Evidence record

For each app/version/device run, fill this record with observed facts:

```text
Date / tester:
Source commit / bundle SHA-256 / native SHA-256:
Desktop version / stock APK SHA-256:
Package / app version / ABI:
Device / Android API / OS build / page size:
Profile category (no keys, endpoints or credentials):
Patch command / exit status / result JSON:
Acceptance case / observed result / pass-fail / duration:
Redacted evidence location:
Unrun cases / known uncovered traffic paths:
```

Do not check the upstream minimum/maximum or experimental testing boxes merely
because this document or a build command exists.
