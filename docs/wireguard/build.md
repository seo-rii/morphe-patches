# Building the contribution from source

The build uses the normal Morphe Gradle tasks. It does not modify compiled patch
classes, reuse a release `.mpp`, or require committed native binaries/vendor code.
Gradle builds both Android native payloads before packaging resources. A build
failure stops packaging; the patch also rejects absent or invalid ABI payloads.

## Toolchains

| Input | Version used for this port |
| --- | --- |
| Gradle | Checked-in wrapper, 9.8.2 with distribution SHA-256 |
| Gradle JVM | Temurin 21.0.13+9; upstream patch Java target remains 17 |
| Android platform / Build-Tools | 36 / 36.0.0 |
| Native compiler | Go 1.26.10 |
| Android NDK | r28c, `28.2.13676358` |
| Native minimum API / ABIs | 26 / arm64-v8a, x86_64 |

Install the tools and set `JAVA_HOME` and `ANDROID_HOME` to their locations.
`ANDROID_SDK_ROOT` is also supported. Install only the needed SDK packages and
review their license prompts if they have not already been accepted:

```sh
sdkmanager --install 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358'
```

`WIREGUARD_GO_BIN` can point to Go 1.26.10 when it is not on PATH.
`ANDROID_NDK_HOME` can explicitly select the pinned NDK. The native builder
rejects a different Go version, NDK revision or Android API target. Set
`WIREGUARD_JAVA_HOME` for the standalone JNI checks.

All new Go module versions remain those of v2.1.1, recorded in
[`go.mod`](../../native/wireguard/go.mod) and `go.sum`. Existing upstream Gradle
dependency versions are unchanged. Dependencies still require network access
on an uncached machine; this is not an offline build.

## Normal upstream build

Use upstream's normal dependency access for GitHub Packages. The CI changes
install the pinned native tools, run native/Python/Java/Kotlin tests, build the
Android bundle last, and inspect/load the result. Local equivalents:

```sh
./gradlew :patches:test :extensions:shared-youtube:library:testDebugUnitTest \
  :extensions:youtube:testDebugUnitTest :extensions:music:testDebugUnitTest \
  :patches:checkStringResources --no-daemon
./gradlew :patches:verifyWireGuardBundleLoading --no-daemon
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/verify-wireguard-bundle.py "$BUNDLE" \
  --dexdump "$ANDROID_HOME/build-tools/36.0.0/dexdump"
```

`verifyWireGuardBundleLoading` depends on `buildAndroid`. Keep Android packaging
after other JVM archive tasks: upstream `buildAndroid` adds DEX to the same
archive in place, so later JVM tasks can recreate it without DEX.

## Optional credential-free public dependency build

This path was used locally. It fetches four exact public source revisions into
a **new** directory outside the project and uses Gradle composite builds. The
preparation script refuses to replace an existing directory. It applies one
recorded optional-auth patch to the task-local plugin clone because that plugin
otherwise requires a token even when its dependencies come from source.
It does not change the project's default dependency repositories or publish
anything. Original dependency LICENSE/NOTICE files remain in those clones.

From this project's root, using Bash and an unused destination:

```sh
wireguard_sources=/path/to/new-public-sources
python3 scripts/prepare-wireguard-public-sources.py "$wireguard_sources"
wireguard_gradle=(bash ./gradlew
  --init-script build-support/wireguard-public-sources.init.gradle
  "-Dmorphe.publicSources=$wireguard_sources"
  "-Dmorphe.patchRoot=$PWD"
  -Pversion=2.3.0-wireguard-dev --no-daemon)

"${wireguard_gradle[@]}" :patches:test \
  :extensions:shared-youtube:library:testDebugUnitTest \
  :extensions:youtube:testDebugUnitTest :extensions:music:testDebugUnitTest \
  :patches:checkStringResources
"${wireguard_gradle[@]}" :patches:verifyWireGuardBundleLoading
```

The explicit canonical patch root prevents included builds from including the
plugin recursively. Public source revisions are pinned in the preparation
script. Its Git diff contains only the documented plugin optional-auth patch.
No account token is required for this path. Maven Central, Google Maven,
Plugin Portal and JitPack still supply other declared dependencies.

The preview version suffix identifies locally built modified artifacts; no
official release number or upstream release metadata is changed in source.
Preserve Morphe's mandatory NOTICE when sharing any preview and identify its
modified origin.

## Native inputs, notices and repeatability

The standalone native builder can build into a separate directory using
`WIREGUARD_NATIVE_OUTPUT` for comparisons. Gradle deliberately fixes its output
to `native/wireguard/build/android`, the exact directory it packages, so an
inherited comparison-output override cannot select stale libraries.

The native build checks ELF architecture, 16 KiB LOAD alignment, stack/linker
protections and JNI exports. Gradle packages the reviewed notice files and
generates their sorted index. Patch installation copies each indexed notice
into APK assets for the settings screen to display.

See [native provenance](../../native/wireguard/licenses/SOURCES.md) and
[actual results](validation.md) for output hashes and the same-host repeatability
comparison. A source build and matching hashes on this host do not establish
independent reproduction on another host, Android compatibility, fingerprint
uniqueness or remote VPN connectivity.
