# Validation record

This file separates new execution evidence, historical release documentation and
unrun Android acceptance. Expected results are in
[device-acceptance.md](device-acceptance.md).

Follow-up APKPure execution is recorded in [apkpure-validation.md](apkpure-validation.md).
Five acquired originals were signature-verified; the arm64 matrix had nine PASS
and one FAIL across ten focused/default-selection cases. YouTube's universal
originals required explicitly labeled unsigned 64-bit fixtures. Newest
recommended YouTube 21.30.216 remains unavailable behind APKPure HTTP 403.

## Inputs

Baseline: complete v2.1.1 release source and `.mpp`, verified against the SHA-256
values in [README.md](README.md). The original patch uses the v2.1.0 Morphe base;
the new port is based on upstream `dev`
`6bdf46e6f12da2a73481b4ba4a4bda2e74dd4be9`.

The old ZIP's reports include v2.1.0 build results, a v2.1.1 bytecode-selection
update, and restricted-host netlink failures. Those are historical records.
They must not be copied as successful new-port checks or assumed to describe
the current host. The author separately reports successful v2.1.1 device use;
exact device/app/network test evidence has not been supplied.

## Baseline checks executed before engine changes

| Check | Actual observation |
| --- | --- |
| Source ZIP and released `.mpp` checksums | Match the recorded v2.1.1 SHA-256 values |
| Pristine release engine: `go test -race -count=1 ./...` | PASS with system Go 1.25.3; 19 top-level tests and FuzzValidate seed corpus, 8.106 seconds; includes real local UDP peer tests |
| Pristine release host JNI smoke test | PASS; 31 checks; Linux JNI execution, not Android ART |
| Pristine release Gradle WireGuard tests | PASS, exit 0, `BUILD SUCCESSFUL` in 11m18s; 5 Kotlin and 8 Java tests, no failures. Local log: `~/logs/morphe-wireguard-baseline-java-y2zgw2cm.log` |
| Android device enumeration | `adb devices -l` reports no connected device |

The native baseline success is host execution, not Android execution. It does not
prove provider interoperability or Wi-Fi/mobile recovery. Record other baseline
checks as they complete; do not infer them from the old ZIP's reports.

## New-port results (2026-11-07)

Implementation commits: `f3877f7dd` (Music transport substitutions) and
`b98219e90` (WireGuard runtime/native/build/verification). The tested production
sources match these commits. Later contribution documents do not change those
inputs. GitHub Actions itself was not executed; the equivalent local build/test
commands below were executed using freshly fetched pinned public dependencies.

| Check | Status / evidence |
| --- | --- |
| Full Kotlin/Android Java compilation | PASS; ordinary source bundle rebuild with `--rerun-tasks --no-build-cache`, 275 tasks executed, exit 0, 2m37s |
| Full Gradle unit-test graph | PASS, exit 0, 2m31s: 1,457 cases, 1,452 passed, 5 skipped, no failures/errors. Patches 46 (5 skipped), shared library 34, YouTube 374, Music 1,003 |
| Shared library Java unit tests | 34 passed, 0 failures/errors/skips (8 parser, 9 network, 8 lifecycle, 9 storage); also separately passed real Gradle execution in 43s |
| String resources | PASS; 195,106 existing/new strings checked, no issues |
| Native Go unit/integration/race tests | PASS with pinned Go 1.26.10: 21 top-level tests and FuzzValidate seed corpus, 9.292s; earlier Go 1.25.3 run also passed |
| Native `go vet ./...` | PASS with pinned Go 1.26.10, exit 0 |
| Parser fuzzing | PASS: Go 1.26.10, 10s budget, 92,272 executions, 10.469s wall time, exit 0 |
| JNI host lifecycle/validation tests | PASS with Go 1.26.10 and JDK 17: 19 validation/idle-stop checks, 31 checks total, exit 0 |
| Both Android native ABIs built from source | PASS, exit 0: Go 1.26.10, NDK 28.2.13676358, API 26; ELF64/machine, 16 KiB LOAD alignment, NX stack, RELRO/NOW and three JNI exports checked |
| Native reproducibility comparison | PASS on this host: second build into isolated output directories produced byte-identical `.so` files for both ABIs. This is not an independent cross-host/toolchain reproducibility result |
| Normal `.mpp` source build | PASS; `patches-2.3.0-wireguard-dev.mpp`, 24,205,503 bytes, unsigned preview; no compiled-class rewriting or release payload reuse |
| Morphe archive loader / default-selection metadata | PASS; actual Morphe JAR loader discovered 203 patches, including two distinct default-selected WireGuard patches with matching app-local Network proxy dependencies |
| Morphe Desktop 2.1.1 metadata loading | PASS; `list-patches` loaded the newly built bundle and listed both patches as enabled by default. This is not `--validate` |
| DEX/JNI/resource/native-notice bundle inspection | PASS; both patch DEX classes, shared/Music extension DEX, three JNI signatures, both exact native payloads, English resources and indexed notices. Music has 36 Requester calls and no direct URL connection/stream calls |
| Dependency vulnerability scans | 38 module advisories / 1 imported Android package / 0 tool-reported reachable symbols; see [qualified analysis](dependency-audit.md). Not a clean module scan or safety proof |
| APK validation-wrapper host parser/integrity tests | Initial suite: 20 fixtures passed. APKPure follow-up: 31 passed, adding signer rotation, ABI-only derivation/content preservation and target-app hook proofs. Missing inputs remain NOT RUN/exit 2 |
| Android/ART JNI smoke | PASS: 19 checks, exit 0, isolated AOSP Android 11/API 30 x86_64 software emulator; native loading/start/auth/routing-rejection/stop/restart under shell UID. Local log: `~/logs/morphe-wireguard-android-jni-7t4s8xmy.log` |
| Patched APK native/resources/manifest/signature inspection | PASS for nine successful arm64 cases and one extra x86_64 focused case; source Google signatures verified, patched outputs unsigned except one verified ephemeral test-signed x86_64 APK. See APKPure input/derivation scope |
| Desktop `--validate` target matrix | PARTIAL: five focused cases passed; four default cases passed; older YouTube 20.51.39 default selection failed in unchanged Captions fingerprint code, reproduced with WireGuard disabled. Latest recommended YouTube 21.30.216 NOT RUN (APKPure 403) |
| Android install/UI/import/handshake/playback | NOT RUN for target apps; two isolated TCG emulator attempts had framework ANRs/restarts before installation. No app result is inferred from those environment failures |
| Android DNS/failure/network-transition/lifecycle/concurrency | NOT RUN; device/network evidence needed |

For every completed build, record source commit or source manifest, tool versions,
exact command, exit code, test counts, artifact hashes and a bounded redacted log
reference. If source changes afterward, rerun affected checks. Build-cache hits
are not proof that an independently clean environment reproduced the artifact.
The final bundle rebuild forced all 275 tasks and disabled the Gradle build
cache; downloaded toolchains and dependency caches were retained. It was not an
independent clean-machine/offline reproduction.

The five skipped tests belong to upstream `JamPatchRegressionTest` and require
`-PjamApk` to exercise actual APK resolution/layouts. All five new WireGuard
Kotlin tests and all 34 shared Java tests ran successfully. No WireGuard test was
silently skipped. The initial Android-unit-test compilation failed because the
host fixture used JDK APIs absent from Android's compile bootclasspath; the
fixture was corrected before the successful full suite. Initial public-source
init-script configuration failures were also corrected; failed attempts are not
counted as passed builds.

Completed build logs (all exit 0, under the local user's private log directory):

- Baseline: `~/logs/morphe-wireguard-baseline-java-y2zgw2cm.log` (11m18s).
- Final unit/string checks: `~/logs/morphe-wireguard-upstream-tests-r5-q38ysv8d.log` (2m31s).
- Forced source bundle/loader: `~/logs/morphe-wireguard-source-bundle-t6g41par.log` (2m37s).
- Separate shared-library tests: `~/logs/morphe-wireguard-android-runtime-tests-ebr7x1yh.log` (43s).
- Android JNI: `~/logs/morphe-wireguard-android-jni-7t4s8xmy.log`.

Bundle SHA-256:
`fa272d0d6f49b0b65b6d252c353659a24318bac68fefdbb71ca43350702e2d94`.
Toolchains and the public-source reproduction procedure are in [build.md](build.md).

The two additional native regression tests exercise encrypted in-memory peers
through packet loss/recovery and closing while a dial is pending, including
listener release. They preserve the existing production transport. The recovery
test is not a Wi-Fi/mobile transition test or a test of Android socket rebinding.

The Java boundary tests use stubbed Android/Cronet/JNI types and a fake
AndroidKeyStore provider. They test the production routing/storage logic and
failure boundaries, but do not prove AndroidKeyStore security, real Cronet
callbacks, Android startup or playback.

The Android JNI smoke used `app_process` on an isolated API 30 x86_64 emulator
with the newly built x86_64 native library. It verified UTF-8/config rejection,
loading/start, duplicate-start rejection, unauthenticated 407, out-of-route 502,
stop/listener release and three restarts. It used shell UID 2000 and public
synthetic fixtures, not an installed target app or remote WireGuard server.
This is Android/ART execution evidence for that bridge and ABI, but does not
test YouTube/Music app UID, R8 integration, Cronet, AndroidKeyStore, settings UI,
profile import, playback, tunneled DNS or a remote handshake. API 26 and arm64
device execution remain untested. The emulator was stopped after the test.

The APK-wrapper parsers were additionally checked against an actual
`aapt2`-generated synthetic resource APK and the released extension DEX's
unpatched false inclusion marker. The latter was correctly rejected as an
applied patch. Neither check patches or executes YouTube/Music.

Native source-build outputs (same in both output directories):

| ABI | Bytes | SHA-256 |
| --- | ---: | --- |
| arm64-v8a | 10,090,216 | `dad7e0389ac653d88af06761b5572499a781f9d0601cbecf272f3f08a0891581` |
| x86_64 | 10,801,896 | `f98a8288f196864ae08989a40a89c637075317564450d34f4d7e3e62f29a0902` |

The production Go/C engine and module pins are unchanged from the release.
Native bytes differ from v2.1.1 because of deterministic compiler/build-ID flags;
they are newly built source artifacts, not copied release binaries.

## Reproduction commands

Run from the project root after installing the upstream Java/Android SDK build
prerequisites and making upstream Gradle dependencies available. The native
toolchain policy is Go 1.26.10, NDK `28.2.13676358` (r28c), API 26. Exact setup and
overrides are described by the native build script/README. Do not substitute the
old ZIP's prebuilt payloads when validating source builds.

```sh
bash scripts/build-wireguard-android.sh
(cd native/wireguard && go test -race -count=1 ./...)
bash scripts/test-wireguard-jni.sh

./gradlew :patches:test \
  :extensions:shared-youtube:library:testDebugUnitTest \
  :extensions:youtube:testDebugUnitTest \
  :extensions:music:testDebugUnitTest --no-daemon

# Android packaging runs after JVM tasks because buildAndroid mutates its archive.
./gradlew :patches:verifyWireGuardBundleLoading --no-daemon
python3 scripts/verify-wireguard-bundle.py "$BUNDLE" \
  --dexdump "$BUILD_TOOLS/dexdump"
python3 -m unittest discover -s scripts/tests -p 'test_validate_wireguard_apk.py'
```

These commands are a reproduction procedure, not a result assertion. The host
JNI suite executes the Linux native bridge, not Bionic/ART; cross-compilation
executes neither. APK/device cases use the separate acceptance procedure.

An optional public-source Gradle dependency setup is provided by
`scripts/prepare-wireguard-public-sources.py` and
`build-support/wireguard-public-sources.init.gradle`. Follow their instructions
and pass both `-Dmorphe.publicSources=/path/to/dependencies` and
`-Dmorphe.patchRoot="$PWD"` with that init script. These select pinned public
source dependencies without replacing upstream's normal CI repository setup.
They do not bypass APK/device testing.

## Before public submission

- Refresh `dev` comparison and preserve unrelated upstream changes.
- Recheck source/artifact identifiers if implementation changes.
- Obtain YouTube 21.30.216 and complete the missing newest-recommended Desktop
  validation. Keep the observed older-target Captions failure visible.
- Record Android acceptance results; disclose every unrun row and uncovered path.
- Review native notice distribution, dependency ownership and default selection
  with maintainers.
- Update the Issue/PR drafts and obtain the author's approval before posting.
