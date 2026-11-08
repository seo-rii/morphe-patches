# Rebuilding the unofficial WireGuard patch bundle

The current source includes both YouTube and YouTube Music. Toolchain and public
dependency pins are unchanged. Historical result excerpts below describe the
first YouTube-only bundle; current combined-bundle results and checksums are in
[`../docs/wireguard-music-verification.md`](../docs/wireguard-music-verification.md).

The supplied source package contains the main project and four public dependency
source snapshots. The snapshots already include the one documented plugin
configuration adaptation. **Do not clone over them or apply the plugin patch a
second time.** Git checkout metadata is not included in the snapshots.

SDKs, JDKs, Gradle distributions/caches, credentials, private signing keys, and machine
configuration are not part of the source package. This is a public-source build,
not an offline build: Maven Central, Google Maven, the Gradle Plugin Portal, and
JitPack still supply the declared third-party artifacts.

## Source layout and prerequisites

Keep these directories as siblings under the same extracted parent directory:

- `morphe-wireguard/` — the modified main source.
- `morphe-patches-gradle-plugin/` — plugin v2.1.0 source, adaptation already applied.
- `morphe-patches-library/` — common library v2.1.1 source.
- `morphe-patcher/` — patcher v2.3.0 source.
- `ARSCLib/` — pinned public resource library source.

The main project's `build-support/` directory contains this document,
`public-sources.init.gradle`, and `public-plugin-optional-auth.patch`.
The init script identifies the main directory by its exact name
`morphe-wireguard` and resolves the plugin as a sibling. The existing project
settings similarly recognize the other sibling dependencies. Do not move the
dependency directories inside the main project or add a `smali/` sibling: the
latter would activate an unintended local dependency substitution.

The executed build used Linux x86-64. The commands below target Linux or a Linux
WSL environment and require Bash, Python 3, JDK 17, Android SDK command-line
tools, SDK Platform 36, and Build-Tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME`
to your own installed JDK and SDK. No absolute path from the original build
machine is needed. `bash gradlew` uses the checked-in wrapper and its verified
Gradle 9.8.2 distribution pin; a separate Gradle installation is unnecessary.

Install only the required stable SDK packages, if missing, using your own SDK
manager installation and answer its applicable license prompt interactively:

```sh
sdkmanager --sdk_root="$ANDROID_HOME" --channel=0 --install \
  'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
```

No broad `sdkmanager --licenses`, manually created license files, private
repository login, or GitHub token is needed. If your network requires a proxy or
custom CA, configure Java/Gradle for your own network; proxy settings and trust
certificates from the original environment are not distributed.

## Verified tool versions and downloads

| Tool | Installed version | Verified official archive digest |
| --- | --- | --- |
| Eclipse Temurin JDK | 17.0.21+9 | SHA-256 `8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711` |
| Gradle | 9.8.2 | SHA-256 `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607` (also the project wrapper pin) |
| Android command-line tools | 20.0 | SHA-1 `48833c34b761c10cb20bcd16582129395d121b27` (official repository metadata) |

Sources:

- `https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz`
- `https://services.gradle.org/distributions/gradle-9.8.2-bin.zip`
- `https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip`
- SDK archive/checksum metadata: `https://dl.google.com/android/repository/repository2-3.xml`

During the executed build, the already configured network proxy certificate was
trusted by the task-local JDK. No credentials were generated, extracted, or
printed. This environment-specific trust configuration is not a source input.

## SDK installation and license scope

The user explicitly authorized the required SDK license agreement and component
installation. Official sdkmanager 20.0 was invoked for only the three stable
packages listed in the installation command above, using the configured proxy.

The displayed `android-sdk-license` prompt was accepted once by entering `y`.
The installer completed with exit 0. No global `--licenses` acceptance, preview
license, unrelated package, or manually written license marker was used.

Verified installed versions:

- Platform-Tools 37.0.1 (stable), `adb version` executed successfully.
- Android SDK Platform 36, revision 2.
- Android SDK Build-Tools 36.0.0.

No Go/NDK download or native source rebuild was required in this continuation.
The existing two Android `.so` payloads were restored with the source archive
and checked byte-for-byte in the built bundle. They are included under
`morphe-wireguard/native/wireguard/build/android/`, so the Java/Kotlin bundle
build does not need Go or NDK. Rebuilding the native libraries is a separate
workflow documented in the main project's WireGuard instructions.

## Exact public source inputs

For the executed build, the four dependency clones were created from empty
directories using git init, full-SHA fetch, and first detached checkout. The
provided snapshots are derived from these exact tracked sources, preserving
their original LICENSE/NOTICE files. No stale files from a different checkout
are required.

| Sibling directory | Public repository | Commit |
| --- | --- | --- |
| morphe-patches-gradle-plugin | https://github.com/MorpheApp/morphe-patches-gradle-plugin.git | `5b7e4619931a8c78b7858e598e002222d37021b9` (v2.1.0) |
| morphe-patches-library | https://github.com/MorpheApp/morphe-patches-library.git | `11f8c7e4d9dd64e18b0594d2c7cbf09176a65327` (v2.1.1) |
| morphe-patcher | https://github.com/MorpheApp/morphe-patcher.git | `d19545e8505e0329cc640723cb8ff91eddd3cd05` (v2.3.0) |
| ARSCLib | https://github.com/MorpheApp/ARSCLib.git | `675bed2de397097400ba6f0c808e28005ae5aa53` |

The only dependency source adaptation is captured exactly in
`public-plugin-optional-auth.patch`: omit the authenticated GitHub Maven
repository when actor/token are absent. The original plugin passes a null
environment fallback to `.orElse(...).get()` and throws during configuration,
even if all Morphe artifacts are supplied by public source builds. This local
configuration adaptation neither supplies dummy credentials nor accesses
private repositories.

### Optional: fetch dependency sources when snapshots are not supplied

**Skip this subsection when using the complete source ZIP.** It is an alternative
for users who have only the main project. Run from the main project's parent
directory, with `morphe-wireguard/` already present. The function refuses to
replace any existing directory:

```sh
set -e

fetch_public_source() {
  local source_dir="$1" source_url="$2" source_commit="$3"
  if [ -e "$source_dir" ]; then
    printf 'Refusing to replace existing directory: %s\n' "$source_dir" >&2
    return 1
  fi
  git init --quiet "$source_dir"
  git -C "$source_dir" remote add origin "$source_url"
  git -C "$source_dir" fetch --depth=1 origin "$source_commit"
  git -C "$source_dir" checkout --detach FETCH_HEAD
  test "$(git -C "$source_dir" rev-parse HEAD)" = "$source_commit"
}

fetch_public_source morphe-patches-gradle-plugin \
  https://github.com/MorpheApp/morphe-patches-gradle-plugin.git \
  5b7e4619931a8c78b7858e598e002222d37021b9
fetch_public_source morphe-patches-library \
  https://github.com/MorpheApp/morphe-patches-library.git \
  11f8c7e4d9dd64e18b0594d2c7cbf09176a65327
fetch_public_source morphe-patcher \
  https://github.com/MorpheApp/morphe-patcher.git \
  d19545e8505e0329cc640723cb8ff91eddd3cd05
fetch_public_source ARSCLib \
  https://github.com/MorpheApp/ARSCLib.git \
  675bed2de397097400ba6f0c808e28005ae5aa53

git -C morphe-patches-gradle-plugin apply --check \
  ../morphe-wireguard/build-support/public-plugin-optional-auth.patch
git -C morphe-patches-gradle-plugin apply \
  ../morphe-wireguard/build-support/public-plugin-optional-auth.patch
git -C morphe-patches-gradle-plugin diff --check
```

No publish or push command is involved. The script changes only newly created
local dependency checkouts and the one optional-auth configuration file.

### smali resolution

No `smali/` composite directory was created. The exact public JitPack POMs for
both declared revisions, `4220ae75f7` and `ac700e1942`, were downloaded without
authentication. HEAD requests for their smali, smali-dexlib2, and smali-util JARs
all returned HTTP 200. Gradle resolves these original module declarations using
its normal dependency conflict rules; no smali version override is applied.
Do not infer the chosen hash revision from chronological commit ordering.

The successful final invocation included actual Gradle `dependencyInsight` for
the patch runtime classpath. It selected `4220ae75f7` for smali, smali-dexlib2,
and smali-util by normal conflict resolution between the original declarations;
patcher's request for `ac700e1942` resolved to `4220ae75f7`. No source composite
or forced version participated in this decision.

The resolved coordinates are
`com.github.MorpheApp.smali:smali:4220ae75f7`,
`com.github.MorpheApp.smali:smali-dexlib2:4220ae75f7`, and
`com.github.MorpheApp.smali:smali-util:4220ae75f7`.
Their source revision is `4220ae75f7faf79ec6158d30dcac2e5409881cdf`; the other
declared revision is `ac700e1942d26a7374166dce35f6da049a10e1a5`.

## Build invocation

The original main settings already recognize sibling patcher/library source
builds. `public-sources.init.gradle` additionally uses the plugin source and
omits authenticated binary dependency repositories. It does not modify the
main application's build files. The portable commands below use the project
wrapper and your own JDK/SDK, not any task-local Python runner or compiler cache.

From `morphe-wireguard/`:

```sh
bash gradlew --no-daemon --console=plain --max-workers=2 \
  --no-build-cache -Pkotlin.incremental=false \
  --init-script build-support/public-sources.init.gradle \
  :patches:compileKotlin \
  :extensions:shared-youtube:library:compileReleaseJavaWithJavac \
  :extensions:music:compileReleaseJavaWithJavac

bash gradlew --no-daemon --console=plain --max-workers=2 \
  --no-build-cache -Pkotlin.incremental=false \
  --init-script build-support/public-sources.init.gradle \
  :patches:test --tests 'app.morphe.patches.shared.misc.wireguard.*'

bash gradlew --no-daemon --console=plain --max-workers=2 \
  --no-build-cache -Pkotlin.incremental=false \
  --init-script build-support/public-sources.init.gradle \
  :extensions:shared-youtube:library:testDebugUnitTest \
  :patches:verifyWireGuardBundleLoading \
  :patches:dependencyInsight --configuration runtimeClasspath \
  --dependency com.github.MorpheApp.smali

python3 scripts/verify-wireguard-bundle.py \
  patches/build/libs/patches-2.1.0.mpp \
  --dexdump "$ANDROID_HOME/build-tools/36.0.0/dexdump"
```

The source snapshots do not need a GitHub login. The build scripts were checked
for Git metadata dependencies; the listed build tasks do not require the omitted
`.git` directories. The executed build used full checkouts with the same tracked
source inputs. The final extracted distribution was not independently rebuilt
after packaging, and byte-for-byte identical ZIP output across different host
environments is not promised. Use the checksums below to verify the supplied
binary itself. No publishing, push, PR, or external Maven deployment is part of
this workflow.

The first compile invocation also requested `testReleaseUnitTest`, but this
upstream AGP configuration does not expose that task for the shared library. AGP
9 enables unit tests only for the tested build type by default, which is debug;
`testDebugUnitTest` is the appropriate ordinary task without changing the DSL.
It stopped at task selection, not a source error. The compile-only invocation
above does not request the missing task. Separately, actual javac/JUnit execution of
the eight ConfigText regression tests passed using official JUnit/hamcrest jars;
this focused test result is not a claim about the complete Android test graph.

The final verification task depends on `buildAndroid` and loads the actual
output archive with Morphe's real PatchLoader, not loose source output classes.
It is run in a fresh Gradle invocation to pick up the main agent's final bundle
notice/verification settings.

## Actual production compilation result

The compile-only invocation completed successfully (exit 0, 4m4s, 64 actionable
tasks: 49 executed, 11 from cache, 4 up-to-date). This includes the full actual
`:patches:compileKotlin` and
`:extensions:shared-youtube:library:compileReleaseJavaWithJavac` tasks, using the
real dependency sources/artifacts and Android SDK 36. The new WireGuard Kotlin
and Java production sources compiled without errors. Existing upstream
deprecation warnings remained. This is not by itself an APK/device test or a
claim that the final archive was already built.

## Actual final bundle and verification results

The final fresh Gradle invocation, after correction of the verifier task's
nested Kotlin DSL receiver, completed with exit 0 in 2m42s. It ran the debug
unit-test, archive-loader verification, and smali dependency-insight tasks
shown in the portable command above. The original execution used a local
runner only to select the verified tools and the configured network proxy;
that runner is not needed to rebuild from the supplied sources.

There were 326 actionable tasks (176 executed, 87 from cache, 63 up-to-date).
All release extension DEX/R8 generation, patch archive creation, and
`:patches:buildAndroid` completed. No signing key was configured: the resulting
bundle is explicitly **unsigned**, not an official Morphe release.

Output: `morphe-wireguard/patches/build/libs/patches-2.1.0.mpp`.

- Size: 24,123,158 bytes.
- SHA-256: `3eec162923b554000c8cc7c21982c756710048ad32a962b6f5e04c12f9117ad2`.
- Real Morphe JAR loader: 201 patches discovered from the actual archive.
- WireGuard patch: exactly once, opt-in, app-targeted, with Network proxy dependency.
- Actual AGP debug unit test XML: ConfigTextTest 8 tests, 0 skipped, 0 failures,
  0 errors (0.056 seconds).

The SDK/native/resource bundle inspector command shown above also exited 0.

It verified both Android ELF payloads byte-for-byte against the restored native
build, 16 KiB LOAD alignment, non-executable stack, all 28 UI resource keys,
original Morphe and third-party license notices, actual Android DEX checksums
and required classes, and all three exact JNI method signatures. Native hashes:

- arm64-v8a: `15e4489d9ffabe0dd05d3f42a2e87df7067c86a2a028d6d016f2c2cd4a0ab5be`.
- x86_64: `cccd484e98079daa16f432338351c38125ced7ff6a0d828df7aaa7ab407cfcb0`.

### Preserved result excerpts

Production compile:

```text
BUILD SUCCESSFUL in 4m 4s
64 actionable tasks: 49 executed, 11 from cache, 4 up-to-date
```

Final bundle build and real archive loader:

```text
No signing key is set, patches-2.1.0.mpp is not signed
PASS real Morphe JAR loader: 201 patches discovered
PASS WireGuard is present once, opt-in, targeted and depends on Network proxy
APK application and Android DEX loading were NOT executed by this JVM check
BUILD SUCCESSFUL in 2m 42s
326 actionable tasks: 176 executed, 87 from cache, 63 up-to-date
```

SDK/native/resource inspection and artifact checksum:

```text
PASS native arm64-v8a: 10090512 bytes, SHA256 15e4489d9ffabe0dd05d3f42a2e87df7067c86a2a028d6d016f2c2cd4a0ab5be
PASS native x86_64: 10802192 bytes, SHA256 cccd484e98079daa16f432338351c38125ced7ff6a0d828df7aaa7ab407cfcb0
PASS UI resources: 28 keys; original and dependency notices present
PASS actual Android DEX checksums/classes and three exact JNI signatures
PASS bundle inspection; APK application and device connectivity NOT tested
3eec162923b554000c8cc7c21982c756710048ad32a962b6f5e04c12f9117ad2  patches/build/libs/patches-2.1.0.mpp
```

The actual AGP JUnit XML is under
`extensions/shared-youtube/library/build/test-results/testDebugUnitTest/` after
the build; its ConfigTextTest suite reported `tests="8" skipped="0" failures="0"
errors="0"`. Generated reports and caches are not required source inputs.

These are actual compile/test/inspection results, not predicted results.
**APK application, Android on-device DEX loading, and real VPN-provider
connectivity were not tested.** No target APK or real user configuration was
used in this build continuation.
