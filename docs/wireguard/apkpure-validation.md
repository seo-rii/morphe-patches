# APKPure target validation

Execution date: 2026-11-08 KST (2026-11-07 UTC). This extends the earlier
[validation record](validation.md) with APKPure inputs and actual Desktop APK
patching. It does not turn the author's v2.1.1 device report into a new-port
device acceptance result.

The runtime/native implementation remains `b98219e90`, with Music transport
prerequisite `f3877f7dd`. The bundle is the existing source-built
`patches-2.3.0-wireguard-dev.mpp`, SHA-256
`fa272d0d6f49b0b65b6d252c353659a24318bac68fefdbb71ca43350702e2d94`.
No new runtime code, native dependency version, version gate or bundle bytecode
was changed to obtain these results.

## Acquired originals

The actual downloads came from the linked APKPure `d.apkpure.net` endpoints,
redirecting to `data.winudf.com`. Each original's package/version, ZIP CRC,
manifest/resources/DEX/native presence and Google signing certificates were
checked using SDK 36.0.0 `aapt2` and `apksigner verify --verbose --print-certs`.
All five acquired originals passed signature verification with exit 0 and match
the Google certificate allowlists in the pinned upstream target declarations.
Both Android-version-scoped signing certificates were retained in the record;
Source Stamp/public-key digests were not mistaken for APK signer certificates.

| App/version and source page | Original bytes | SHA-256 |
| --- | ---: | --- |
| [YouTube 20.51.39](https://apkpure.net/youtube-app/com.google.android.youtube/download/20.51.39) | 170,753,572 | `4cb1a507dad189b0eed01440cd6dba4c749c897dae2ff169d659c037bdfbda07` |
| [YouTube 21.44.528](https://apkpure.net/youtube-app/com.google.android.youtube/download/21.44.528) | 220,576,405 | `55be430ce82dcedcb737ea27d1b78576737b75d11cde04aa89989e5376d3ae6c` |
| [Music 9.15.51](https://apkpure.net/youtube-music/com.google.android.apps.youtube.music/download/9.15.51) | 74,431,248 | `a003ac2e673c2979b2268e5acfbfd74f88b93c4b75af5c7d604b3c7fb92768a4` |
| [Music 9.30.53](https://apkpure.net/youtube-music/com.google.android.apps.youtube.music/download/9.30.53) | 68,436,587 | `1fe8d535ae445265c7250ed76a24c340e1dc89f2ab5d265f755800fe23ad334e` |
| [Music 9.45.51](https://apkpure.net/youtube-music/com.google.android.apps.youtube.music/download/9.45.51) | 77,297,565 | `ad8963959890b6b23a0b332cf9829ee7d6ea6f3a0a61e386d7c34c51a2dd99f7` |

Music inputs are standalone `arm64-v8a` APKs; no split merging was needed.
Music 9.15.51/9.30.53 declare minSdk 26; this APKPure 9.45.51 variant declares
minSdk 28. Upstream lists that experimental target with minSdk 26, so this test
does not establish availability or operation of a different API 26 variant.

[YouTube 21.30.216](https://apkpure.net/youtube-app/com.google.android.youtube/download/21.30.216),
the newest declared recommended target, could not be acquired. APKPure returned
Cloudflare HTTP 403/security-verification pages, including in ordinary Chromium.
Other APKPure pages/downloads succeeded from the same environment. No CAPTCHA,
login or alternate-site APK download was used. A candidate version code found
in third-party search metadata was not treated as a verified APKPure input.
This target remains **NOT RUN**; another version does not replace it.

## Universal originals and explicit derived inputs

Both YouTube originals are complete universal APKs containing `arm64-v8a`,
`armeabi-v7a`, `x86` and `x86_64`. They are not standalone 64-bit-only inputs.
The preserved WireGuard guard rejects those original APKs because 32-bit
architectures are present.

An actual negative control passed the signed YouTube 20.51.39 original to
Desktop 2.1.1 with `--striplibs arm64-v8a` and `--validate`. It exited 1 with the
explicit unsupported-architecture error; no patched APK was accepted. Source and
actual JAR inspection explain the ordering: `--striplibs` is applied during
resource encoding after patch execution, while `listApkEntries()` exposes the
original ABI entries to WireGuard. This is an input/tooling limitation, not a
claim that the original universal APK passed.

For bytecode/resource validation, the wrapper's explicit
`--derive-64bit-input --abis arm64-v8a` mode made **unsigned 64-bit test fixtures**
from the already signature-verified originals. It removed unselected native
directories and invalidated signature metadata/signing blocks. Every retained
entry, including every DEX/resource/manifest, was checked byte-for-byte by
SHA-256; original files and hashes remained unchanged. Per-entry manifests and
original/derived hashes are retained with the results.

No `--force`, internal version-gate removal, fingerprint relaxation or
`--continue-on-error` was used. The derived files are not Google-signed originals
and their results must be labeled accordingly. Music retained its original APK
bytes because its selected ABI set already matched the original.

## Desktop and final APK results

Morphe Desktop 2.1.1, Temurin 21.0.13+9 and SDK Build-Tools 36.0.0 were used.
Every case included `--validate`. Focused selection explicitly enabled WireGuard
and its dependencies; default selection used the normal complete default set.
Outputs were unsigned. Each PASS requires successful Desktop JSON plus APK
inspection, not merely a zero shell exit code or a compatibility-gated skip.

| App/version | Input used | WireGuard focused | Complete defaults |
| --- | --- | --- | --- |
| YouTube 20.51.39 | Verified original -> unsigned arm64 fixture | PASS | FAIL: existing Captions fingerprint ambiguity |
| YouTube 21.30.216 | Not acquired (403) | NOT RUN | NOT RUN |
| YouTube 21.44.528 | Verified original -> unsigned arm64 fixture | PASS | PASS (113 patches) |
| Music 9.15.51 | Original signed arm64 APK | PASS | PASS (51 patches) |
| Music 9.30.53 | Original signed arm64 APK | PASS | PASS (51 patches) |
| Music 9.45.51 | Original signed arm64 APK | PASS | PASS (51 patches) |

The ten executed arm64 cases contain **nine PASS and one FAIL**; the matrix
process correctly exited **1**, not 0. The blocked target was not included in
the executed count.

Every successful APK inspection checked exact ABI payload equality with the
bundle, ELF64/machine/16 KiB alignment and JNI exports, DEX integrity and JNI
signatures, the applied `isPatchIncluded() == true` marker, 30 default English
WireGuard keys, 34 indexed notice files, private Activity/manifest settings,
absence of added VPN/broad-storage declarations and APK zip alignment.

Static inspection also found injected calls from actual target-app classes:
`applyProxyOptions`, `recordCronetEngine`, `setMainCronetEngine` and
`initialize`. Extension self-calls and method definitions were excluded. These
show the hooks were installed; they do not prove every request runs through
those hooks or establish DNS/traffic leak behavior on Android.

### Older YouTube default-selection failure

The failing 20.51.39 default run stops in:

```text
Captions -> TranscriptPatch -> ClientContextHookPatch
AuthenticationChangeListenerLegacyFingerprint: expected one match, found two
```

A separate actual `--validate` control disabled `WireGuard proxy (experimental)`
and reproduced the same Captions/fingerprint failure. The relevant context-hook
and fingerprint files are unchanged from the pinned upstream `dev`. WireGuard
focused selection passed on the same derived app bytecode. This isolates an
existing default-patch validation issue; it is not silently counted as a working
complete-default APK, and unrelated Captions code was not modified.

## Android execution scope

The separate x86_64 YouTube 20.51.39 fixture was patched with `--validate` for
an isolated API 30 emulator attempt. Its installation/UI observations are
recorded separately from host patching; a source/DEX/JNI inspection cannot
establish that an app launches or plays media.

The x86_64 fixture passed Desktop `--validate` and the same APK inspection,
including its exact x86_64 native payload and target-app hooks. It was signed
with an ephemeral test certificate outside the repository; final `apksigner`
verification exited 0. This signing preparation does not establish installation
or Android execution. The test private key/password were removed after the
attempt and were not committed or logged.

Android application smoke result: **NOT RUN: emulator framework unusable before
target APK installation**. The first 1-core/1 GiB TCG attempt showed Android
system and System UI ANRs. One 2-core/2 GiB retry briefly reported boot complete,
then restarted its framework and lost input/window/activity services;
`uiautomator` exited 137. No target APK or synthetic profile was installed.
These are environment failures, not observed WireGuard app failures. Both
owned emulator processes were explicitly stopped and no device remains attached;
their exit -10 records deliberate `adb emu kill` termination. The user's
existing AVD was not used or modified. No live account or provider profile was
involved.

Remote server handshake, playback, tunneled DNS, Wi-Fi/mobile handoff, real
device app UID behavior and multi-app concurrency remain unverified unless a
separate observed result is explicitly added. The earlier 19-check Android JNI
shell smoke remains a distinct result.

## Evidence and reproduction

Local evidence is retained outside tracked source under
`morphe-patches/.git/wireguard-reference/apkpure/`:

- `youtube/acquisition-summary.json`, per-version verification records and originals.
- `music/manifest.json`, per-version verification records and originals.
- `matrix-source-state.json`: exact validator/source-bundle hashes.
- `matrix-arm64-r1/summary.json`: all ten cases and final FAIL status.
- Per-case `desktop-result.json`, `desktop.log`, `inspection-result.json`.
- `*-input/source-verification.json`, ABI derivation and retained-entry manifests.
- `runs/youtube-universal-negative-r1/` and
  `runs/youtube-min-defaults-without-wireguard-r1/`: negative/comparison controls.
- `runs/youtube-min-x86-focused-r1/`: extra x86_64 APK and test-signature checks.
- `music/emulator-smoke-attempts.json`: both bounded framework failures.

The matrix log is `~/logs/morphe-wireguard-apkpure-full-matrix-r8wy0g25.log`,
final exit **1**. Signature/source/derivation and final APK data are structured
records; no personal WireGuard key/profile or account credentials are included.
Neither APK binaries nor signing keys belong in the upstream contribution.

```sh
python3 scripts/validate-wireguard-apk.py matrix \
  --java "$JAVA_HOME/bin/java" --desktop "$DESKTOP_JAR" \
  --bundle "$BUNDLE" --build-tools "$BUILD_TOOLS" \
  --derive-64bit-input --abis arm64-v8a \
  --target "music-min=$MUSIC_MIN_APK" \
  --target "music-max=$MUSIC_MAX_APK" \
  --target "music-experimental=$MUSIC_EXPERIMENTAL_APK" \
  --target "youtube-min=$YT_MIN_APK" \
  --target "youtube-experimental=$YT_EXPERIMENTAL_APK" \
  --default-selection --output-dir "$NEW_RESULT_DIRECTORY"
```

Without explicit derived-input mode, the wrapper retains strict standalone
64-bit input checking. Its 31 integrity tests passed, including source signer
rotation, ABI derivation/content preservation and app-hook filtering.
