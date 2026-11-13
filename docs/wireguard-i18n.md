# WireGuard settings localization

WireGuard uses the existing `shared-youtube` Android resource group and
`BaseActivityHook` language context. Its standalone settings Activity resolves
resource IDs and string values through its own Context. It adds no separate
locale selector or runtime translation service. Labels and validation details
follow the same Morphe language/restart behavior as other settings pages.

The entry preference is inflated from XML in the Morphe settings Activity.
Using `StringRef.str()` for the separate screen was incorrect: it resolves from
a global activity/application Context and retains values in a process-wide
cache. That can leave the internal screen in English while the entry menu is
localized. Internal titles, summaries, dialogs, toasts and navigation text now
use the owning Activity's resources without changing shared global lookup or
clearing other patches' caches.

The Go parser returns one of 19 stable diagnostic codes through the existing JNI
exception. Java maps only known codes to English XML resource keys, and the
Activity resolves those keys through its own localized resources. Unknown, legacy or malformed
payloads use the localized generic validation failure; raw backend text is never
shown. Go's value-free English `Error()` remains available to non-UI diagnostics.

Crowdin already maps the entire English shared-youtube file to
`values-%android_code%/shared-youtube/strings.xml`. No Crowdin YAML or resource
registration change is needed. Korean source resources use `values-ko-rKR` and
are copied into the target APK's `values-ko` configuration.

The custom distribution contains 60 English WireGuard strings plus
complete manual translations for Korean, Japanese, simplified/traditional
Chinese, Spanish, French, German and Brazilian Portuguese. These custom-only
locale edits were explicitly authorized by the distributor. The official
contribution keeps English source edits and Crowdin-managed locale files.
Existing non-WireGuard translations are preserved. No official Crowdin upload
or translation write was performed.

Host validation includes all-code Go/JNI checks, workflow privacy/fallback tests,
and a cross-language native/Java/XML contract check. An isolated test bundle
simulates Crowdin Korean output with explicit test markers; APK inspection checks
Korean inclusion and the default English fallback. This fixture is not a real
translation and must not be published.

The custom release also verifies the real production translations against actual
compiled APK resources. Run the following after patching the target APKs:

```sh
python3 scripts/verify-wireguard-locales.py patches/build/libs/patches-2.2.0.mpp \
  --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2" \
  --apk /path/to/patched-youtube.apk --apk /path/to/patched-music.apk \
  --output /path/to/compiled-locales.json
```

The checker compares all 60 values in the default configuration and each of the
eight locale configurations. It rejects a missing locale rather than accepting
English fallback as evidence of translation. This verifies compiled values;
Android rendering and language switching remain device checks. See the
[v2.1.4 release record](wireguard-i18n-2.1.4.md) for results. Published v2.1.3
assets and its corresponding source remain unchanged.


The optional [standalone Android locale regression](../tests/wireguard-settings-locale/README.md) compares the
production screen before/after the fix with an English host or stale cache. Its
backend collaborators are explicit stubs; it does not replace target-app testing.
The test source is shared by both streams and accepts external Korean resources
without modifying Crowdin-managed product files.
