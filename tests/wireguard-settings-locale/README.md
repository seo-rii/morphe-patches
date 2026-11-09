# Standalone Android settings locale regression

This optional test builds the production `WireGuardSettingsActivity` and
`BaseActivityHook`, plus the public dependency's actual `StringRef`,
`ResourceUtils` and `ResourceType`. Android preferences, resources, toolbars and
dialogs run on a disposable emulator. Transport, stored settings, theme helpers
and profile operations are explicit test collaborators, not a live backend.
It does not install or validate YouTube/Music, use a provider profile, or test
AndroidKeyStore, native networking, playback or system VPN coexistence.

The two APKs differ only in the production Activity/base-hook source snapshot.
Each runs four fresh-process scenarios: Korean Activity with English host and
cached English strings; the same without priming the cache; Morphe Korean with
cached English; and an unsupported locale's English fallback with cached Korean.
The runner requires the old implementation to fail and the fixed one to pass.
It checks the XML entry, internal titles/summaries/states, toolbar/navigation
description, validation details and enable/disable/removal dialogs. Toast display,
license contents, target-app themes and text wrapping remain outside this test.

## Build

Use JDK 21, Android platform 36 and Build-Tools 36.0.0. No Gradle or new Maven
dependency is needed. Run from the source repository root in Bash, using fresh
output paths. `wireguard_library` is the pinned `morphe-patches-library` source
checkout from the public source build. The Korean XML must provide all 60 keys;
the builder rejects missing keys or an English-only substitute. The contribution
can use an external custom-source/Crowdin file without changing product locale
files. Generated test resources and APKs never enter the patch bundle.

| Source stream | Pre-fix Git commit |
| --- | --- |
| Custom distribution | `8a87d95bf51a90b2206c5fa1b414316b06c539cb` |
| Contribution branch | `dc3819a6920bef55b71bb8708378e3dcb01f6497` |

```sh
wireguard_ui_out=/path/to/new-ui-validation
wireguard_library=/path/to/morphe-patches-library
wireguard_korean=/path/to/custom-source/patches/src/main/resources/addresources/values-ko-rKR/shared-youtube/strings.xml
wireguard_baseline=8a87d95bf51a90b2206c5fa1b414316b06c539cb
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

for wireguard_variant in baseline fixed; do
  wireguard_ui_args=()
  if [ "$wireguard_variant" = baseline ]; then
    wireguard_ui_args=(--baseline-commit "$wireguard_baseline")
  fi
  python3 tests/wireguard-settings-locale/build_harness.py \
    --source "$PWD" --library "$wireguard_library" \
    --korean-resources "$wireguard_korean" \
    --sdk "$ANDROID_HOME" --jdk "$JAVA_HOME" \
    --variant "$wireguard_variant" "${wireguard_ui_args[@]}" \
    --output "$wireguard_ui_out/builds/$wireguard_variant"
done
```

For source ZIPs without Git metadata, build the baseline with `--source` pointing
to the extracted v2.1.4 source and omit `--baseline-commit`; use the fixed source
for the second build. Public dependency sources are supplied separately in the
corresponding source ZIP. Each APK uses a newly generated, short-lived test key;
keep generated outputs outside the repository and release assets.

## Run

Create and boot a disposable API 30 or newer emulator using normal SDK tooling.
The caller owns its lifecycle and storage. The runner accepts an explicit
`emulator-*` serial, uninstalls only its two disposable test package IDs, and
does not create/reset an AVD or change system locale. Allow sufficient disk space
for the emulator's normal image requirements. No emulator is started by CI.

```sh
python3 tests/wireguard-settings-locale/run_harness.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5584 \
  --builds "$wireguard_ui_out/builds" --output "$wireguard_ui_out/results"
```

The expected result is four baseline failures and four fixed passes, with 31
checks per scenario. These are expected results until the runner records them.
An emulator/build failure, missing instrumentation output or unexpected baseline
pass fails the run. Save `build-result.json`, source/resource hashes, per-case
instrumentation output and the final `result.json`. This remains standalone
Activity evidence, distinct from acceptance testing in patched target apps.
