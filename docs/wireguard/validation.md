# Validation record

This file separates new execution evidence, historical release documentation and
unrun Android acceptance. Expected results are in
[device-acceptance.md](device-acceptance.md).

Follow-up APKPure execution is recorded in [apkpure-validation.md](apkpure-validation.md).
Five acquired originals were signature-verified; the arm64 matrix had nine PASS
and one FAIL across ten focused/default-selection cases. YouTube's universal
originals required explicitly labeled unsigned 64-bit fixtures. Newest
recommended YouTube 21.30.216 was unavailable from APKPure in that run. The author
later supplied an APKMirror original for the separate
[uploaded-APK follow-up](#uploaded-apkmirror-original-follow-up-2026-11-08).

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
sources for these initial results match those commits. Contribution documents
did not change those inputs. GitHub Actions had not yet been executed for this
initial record; the equivalent local build/test commands below used freshly
fetched pinned public dependencies. Later revisions are recorded separately.

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
| Desktop `--validate` target matrix | Historical APKPure matrix PARTIAL: five focused cases passed; four default cases passed; older YouTube 20.51.39 default selection failed in unchanged Captions fingerprint code, reproduced with WireGuard disabled. YouTube 21.30.216 was then NOT RUN (APKPure 403); its later uploaded-input follow-up is recorded separately below |
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

For the pre-review build recorded above, the production Go/C engine and module
pins were unchanged from the release. Those native bytes differ from v2.1.1
because of deterministic compiler/build-ID flags; they are newly built source
artifacts, not copied release binaries.

## PR review follow-up (2026-11-08)

The review found that the plaintext HTTP proxy discarded response-body copy
errors. An origin closing a chunked response without its terminating chunk
could therefore produce a successful, truncated client response. The proxy now
aborts the downstream response with `http.ErrAbortHandler` on a copy error;
deferred upstream cleanup still runs. This is a focused change to `proxy.go`,
with no dependency-pin, JNI, routing or CONNECT implementation changes.

The new `TestProxyResponseBodyCompletion` uses an encrypted in-memory WireGuard
peer and eight cases: HTTP/HTTPS, complete/truncated chunk framing, and small/
streamed bodies. Before the fix, the two truncated HTTP cases failed while the
six controls passed. After the fix, all eight passed, including ten consecutive
runs under `-race`. The full `go test -race -count=1 ./...` and `go vet ./...`
passed with Go 1.26.10; the rebuilt host JNI smoke passed all 31 checks.

The incremental Gradle source build passed in 1m12s (6 tasks executed, 269
up-to-date). Both Android ABIs were rebuilt and checked for ELF protections,
16 KiB LOAD alignment and JNI exports. The real Morphe loader again discovered
203 patches and both default-selected WireGuard patches. Final bundle
inspection passed for native payloads, DEX/JNI, routing, resources and notices.
Local build log: `~/logs/morphe-wireguard-http-fix-bundle-qh4fg_0g.log`, exit 0.

| Follow-up artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| arm64-v8a | 10,090,248 | `6bb1fdc3d350556dae72c532d9e2363a69bfc5a0a21c0f99ddc29705e86ce4cb` |
| x86_64 | 10,801,928 | `5d6aff73d632d23cb8e65ab9337c13a5cbfad1394310690f8efeff7518e56e70` |
| `patches-2.3.0-wireguard-dev.mpp` | 24,205,410 | `b3b69ab2cf654618f2a323680a86d5ae3efa264f267ab7db16a6beffbe927bf4` |

The earlier APKPure matrix, Android JNI execution, reproducibility comparison
and earlier artifact hashes describe the pre-review build. That complete matrix,
Android execution and reproducibility comparison have not been rerun for this
correction. The uploaded-input check below is a separate target/version run.
Target-app Android acceptance remains unrun.

## Uploaded APKMirror original follow-up (2026-11-08)

After APKPure access retries remained unsuccessful, the author supplied the
standalone universal APK from the
[APKMirror YouTube 21.30.216 release](https://www.apkmirror.com/apk/google-inc/youtube/youtube-21-30-216-release/).
This is an author-uploaded input, not a successful APKPure download. Its
198,948,665-byte size and SHA-256
`3775a15129dcc4e5b80fc17b7e0841b7fdd1ca89a6e72baa798806f666c74d02`
match the published APKMirror metadata. SDK `aapt2` confirms
`com.google.android.youtube`, version `21.30.216`, version code `1561187872`,
minSdk 29 and all four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
The original's ZIP integrity and both Android-version-scoped Google APK signers
were verified against the upstream allowlist before deriving the input:

| Signer SDK range | Certificate SHA-256 |
| --- | --- |
| API 24-32 | `3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a` |
| API 33+ | `5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6` |

The Source Stamp certificate is recorded separately and is not an APK signer
allowlist input. The uploaded original and its stable snapshot remain unchanged.

This R2 cohort used runtime/bundle `bf67c82f2`, including the reviewed HTTP
truncation fix. Its unsigned source-built `.mpp` was 24,205,410 bytes, SHA-256
`b3b69ab2cf654618f2a323680a86d5ae3efa264f267ab7db16a6beffbe927bf4`.
The checker source is `3923ee3c67ab098e539b83e6bfdd32a078d81e57`.
The original universal APK is preserved. Explicit
`--derive-64bit-input --abis arm64-v8a` produces a separately labeled unsigned
fixture; every retained DEX/resource/manifest/native entry is checked for byte
equality. No compatibility force, version-gate change or fingerprint relaxation
is used. The fixture SHA-256 is
`c18b272cc8c0b168aee3f8f504ab89f25a1a018bba6e6fb91b6de8d44530064a`;
16,655 retained entries passed content verification.

The first matrix attempt exited 1: Desktop `--validate` succeeded for focused
selection (1 named patch) and complete defaults (113 patches), but both final
inspections failed. The checker resolved NDK's `llvm-readelf` symlink to its
`llvm-readobj` multicall target, changing the output format and falsely reporting
a missing JNI export. The correction preserves the executable alias; it does
not change a native payload or relax export validation. Two CLI regressions,
covering inspect and matrix, failed before the correction and passed afterward;
all 33 verification-script tests then passed.

The corrected full matrix rerun passed with exit 0 using Desktop 2.1.1, Temurin
21.0.13+9, SDK Build-Tools 36.0.0 and the NDK r28c `llvm-readelf` alias. Both
Desktop `--validate` runs and both complete final APK inspections passed:

| Selection | Applied patches | Desktop / inspection | DEX files | Unsigned APK bytes | APK SHA-256 |
| --- | ---: | --- | ---: | ---: | --- |
| WireGuard focused, including dependencies | 1 named patch | PASS / PASS | 12 | 116,110,326 | `185830f5fbeab5d87ddfef8212f38b07ea17e45e450a30bb803702177cf2b4af` |
| Complete normal defaults | 113 | PASS / PASS | 15 | 122,514,880 | `e1a4b6811e24cfee0db8a474416c05a96c68c4a1164a97fea1f8c6420eb36dd5` |

Both outputs contain the exact arm64 native payload from the bundle, SHA-256
`6bb1fdc3d350556dae72c532d9e2363a69bfc5a0a21c0f99ddc29705e86ce4cb`.
They passed ELF/JNI checks, DEX/JNI contracts, the true inclusion marker,
30 English resource keys, 34 indexed notices, private settings Activity and
manifest checks, and 16 KiB APK zip alignment. Calls from target-app code were
found for `applyProxyOptions` (1), `recordCronetEngine` (1),
`setMainCronetEngine` (1) and `initialize` (2).

The local result directory is
`morphe-patches/.git/wireguard-reference/apkmirror/matrix-youtube-max-arm64-r2`;
it retains source verification, derivation/retained-entry manifests, Desktop
results and per-APK inspection reports. Completed matrix log:
`~/logs/morphe-wireguard-youtube-max-apkmirror-validate-r2-r_ibqi_1.log`, exit 0.
The earlier failed R1 attempt remains recorded separately with exit 1.

The command below is the shared procedure for a target run; select the bundle
matching the intended cohort's recorded hash. To run it from the project root,
set `YOUTUBE_MAX_APK` to the unchanged
uploaded original, `MORPHE_DESKTOP` to the Desktop 2.1.1 JAR,
`JAVA_HOME`/`ANDROID_HOME` to the toolchains above and
`WIREGUARD_VALIDATION_OUTPUT` to a new output directory. Confirm the original
SHA-256 is `3775a15129dcc4e5b80fc17b7e0841b7fdd1ca89a6e72baa798806f666c74d02`
before running:

```sh
sha256sum "$YOUTUBE_MAX_APK"
python3 scripts/validate-wireguard-apk.py matrix \
  --bundle patches/build/libs/patches-2.3.0-wireguard-dev.mpp \
  --desktop "$MORPHE_DESKTOP" --java "$JAVA_HOME/bin/java" \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
  --readelf "$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  --target "youtube-max=$YOUTUBE_MAX_APK" \
  --output-dir "$WIREGUARD_VALIDATION_OUTPUT" \
  --default-selection --derive-64bit-input --abis arm64-v8a
```

This is host patching/static inspection evidence for this one target and
runtime bundle. It does not rerun the older APKPure versions with the R2
bundle. Outputs are unsigned. Android installation, UI, profile import,
handshake, playback, DNS, failure/handoff and concurrent-app execution remain
NOT RUN.

## Native shutdown and final-artifact follow-up (2026-11-08)

[CI run 42450379295](https://github.com/seo-rii/morphe-patches/actions/runs/42450379295)
at `d0ac62944` failed the native race test. A peer receiver could still be inside
netstack `TUN.Write` when `Device.Close` closed the TUN's channel. Earlier local
passes and the R2 static APK checks do not negate that failed CI result.

Runtime correction `d91a6609029b31d20e19ea83132c8d600137ea5e` removes and drains
all WireGuard peers before closing the device/TUN. The shared
`closeWireGuardDevice` helper is used by `Engine.Close`, all three startup-error
cleanup paths and the test-peer cleanup. Removing peers also prevents a delayed
TUN-up event from restarting those receivers. Dependency pins, JNI signatures,
routing policy and profile storage are unchanged.

| Check | Actual result |
| --- | --- |
| `TestShutdownWaitsForPeerReceiverBeforeClosingTUN` | Failed before the correction; passed 20 repetitions under `-race` afterward, 3.519s |
| Existing memory-peer lifecycle stress | PASS under `-race`: 100 iterations / 300 shutdown rounds, 24.288s |
| Full native `go test -race -count=1 ./...` | PASS: 23 top-level tests plus fuzz seeds, 9.335s |
| Native `go vet ./...` | PASS |
| Rebuilt host JNI lifecycle/validation smoke | PASS, 31 checks |

The new regression holds an actual encrypted peer receiver inside `TUN.Write`
while shutdown is requested, then verifies that closing the TUN waits for that
write to finish. Its gated wrapper protects failed assertions from triggering
the same upstream race. These host checks do not establish Android network
handoff or target-app shutdown behavior.

Both Android native payloads were rebuilt from this correction. The Gradle
source bundle/real-loader task passed in 1m6s (6 executed, 269 up-to-date), and
native ELF protections, 16 KiB LOAD alignment, JNI exports, final DEX/JNI,
resources and notice checks passed. Completed build log:
`~/logs/morphe-wireguard-shutdown-fix-bundle-90e5ix19.log`, exit 0.

| Corrected artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| arm64-v8a native payload | 10,090,472 | `665ea92eb96b0b30c3f8d1b862e58e18369ae1ad1529356f94e4a00409dc453b` |
| x86_64 native payload | 10,802,120 | `0c66ee9c6fa20eedb1af1906e18cddfbc445e25a0b084d5696713f05917cbddd` |
| `patches-2.3.0-wireguard-dev.mpp` | 24,205,686 | `705738552dce5ce575884062df9f5da90bd29b11e4984428e3ac92ad8fc8e3fc` |

A separate R3 run with this bundle passed both YouTube 21.30.216 focused and
complete-default Desktop `--validate` runs and all final APK inspections. The
matrix exited 0. The unchanged verified original and explicit unsigned arm64
input are the same as R2: fixture SHA-256
`c18b272cc8c0b168aee3f8f504ab89f25a1a018bba6e6fb91b6de8d44530064a`,
16,655 retained entries content-verified, no compatibility force.

| R3 selection | Applied patches | Desktop / inspection | DEX files | Unsigned APK bytes | APK SHA-256 |
| --- | ---: | --- | ---: | ---: | --- |
| WireGuard focused, including dependencies | 1 named patch | PASS / PASS | 12 | 116,110,234 | `aeb434d33cf36fc9d758242faa4d6c2efda6f269dc195f898213f3541004fc9c` |
| Complete normal defaults | 113 | PASS / PASS | 15 | 122,514,788 | `6c19209c3c95fb01bc981c7328827f51583e083c59fa81ae7c3a575c03bbcb77` |

Both outputs passed exact corrected arm64 payload equality, ELF/JNI and DEX/JNI
checks, 30 English resource keys, 34 notices, the true inclusion marker,
private Activity/manifest checks and 16 KiB APK alignment. Their target-app hook
counts remain `applyProxyOptions` 1, `recordCronetEngine` 1,
`setMainCronetEngine` 1 and `initialize` 2. Runtime source, checker/test, bundle
and input snapshot hashes remained unchanged throughout the run.

Results are retained in
`morphe-patches/.git/wireguard-reference/apkmirror/matrix-youtube-max-arm64-r3`.
Completed matrix log:
`~/logs/morphe-wireguard-youtube-max-apkmirror-validate-r3-fzn7if2e.log`, exit 0.
Use the uploaded-input command above with this cohort's rebuilt bundle to repeat
the same focused/default host checks.

The R2 `b3b69ab2...` artifacts remain a previous cohort; neither their results
nor the historical APKPure cases are relabeled as this rebuilt bundle's results.
The failed CI run remains failed evidence; these corrected results are local
checks. This run covers one target/version and arm64 fixtures. Android target-app
execution, handshake/playback, network handoff and device shutdown remain
NOT RUN.

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
- Recheck required version cases when the final artifact changes. Keep the
  observed older-target Captions failure and other unrerun version cases visible.
- Record Android acceptance results; disclose every unrun row and uncovered path.
- Review native notice distribution, dependency ownership and default selection
  with maintainers.
- Update the Issue/PR drafts and obtain the author's approval before posting.
