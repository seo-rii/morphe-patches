# YouTube Music WireGuard extension — 2026-11-07 (Asia/Seoul)

## Scope and source changes

This extends the existing unofficial Morphe app-local WireGuard preview to
YouTube Music. One bundle contains separate, opt-in patches for
`com.google.android.youtube` and `com.google.android.apps.youtube.music`, both
displayed as `WireGuard proxy (experimental)` for their respective target app.
The original YouTube path and its version gate are retained.

- Music depends on its own `networkProxyPatch`, not the YouTube dependency.
- Native payload validation/copying, the presence-marker injection, non-exported
  settings Activity registration and preference entry are now one shared Kotlin
  installer. The marker fingerprint match is cleared before each installation.
- The Java WireGuard runtime and the two compiled native engines are unchanged.
  Music already includes the shared extension and shared settings/resources.
- No `VpnService`, system TUN, root or new VPN-consent flow is introduced.

Music target metadata is inherited from the pinned Morphe source, not newly
inferred from release numbering:

| Target classification | Declared versions |
| --- | --- |
| Normal targets | 9.15.51, 9.20.53, 9.30.53 |
| Experimental targets | 9.42.52, 9.43.51, 9.44.52 |

All Music targets declare minSdk 26. The existing network patch selects the
Cronet `ProxyOptions.fromProxyList` overload using Music's own 9.20 version
branch. No arbitrary 9.20 minimum was added. These are **declared targets, not
successful APK-patching or playback results**. Only complete `arm64-v8a` or
`x86_64` APKs are accepted; 32-bit ABIs and root mounting remain unsupported.

## Music extension requests

The source review found direct `URL.openConnection()` calls bypassing Requester.
Sixteen connection-creation calls in eight Music files were moved to the
existing `Requester.openConnection(String/URL)` entry point:

| Component | Changed calls |
| --- | ---: |
| EnableAudioVideoSwitchPatch | 1 |
| MetadataCleaner | 1 |
| SpotifyProvider | 5 |
| RomanizationDict | 1 |
| LyricifyProvider | 2 |
| QuickLRCClient | 2 |
| LunaProvider | 1 |
| OpenAIClient | 3 |

HTTP methods, headers, request bodies, timeouts, redirects and cleanup code were
not rewritten. With no registered Requester provider, its original implementation
uses `URL.openConnection()` as before. With the network patch initialized,
these requests follow that provider; this also makes them honor the ordinary
Network proxy setting when it is used instead of WireGuard.

This change affects extension-source calls, not arbitrary unhooked code in the
original Music APK. Source scanning and compiled-call inspection cannot prove
that all target-app traffic is covered. Native paths, external GmsCore, Cast
and other processes still need separate device-level evidence.

## Actual verification status

The following checks actually completed successfully on 2026-11-07 (Asia/Seoul).
They ran from a fresh source snapshot, with build-cache reuse and Kotlin
incremental compilation disabled. They are build/host inspections, not Android
device results.

| Executed check | Actual result |
| --- | --- |
| Full Kotlin and Android Java compilation | Passed, including Music and shared-youtube extensions |
| Shared native-payload Kotlin tests | 3 passed |
| App-specific patch metadata Kotlin tests | 2 passed |
| Android ConfigText unit tests | 8 passed |
| Test totals | 13 passed; 0 skipped, failures or errors |
| Real Morphe JVM archive loader | 202 patches loaded, including two distinct opt-in WireGuard patches |
| Loader metadata assertions | Correct app-specific Network proxy dependencies and all six declared Music versions |
| Music extension DEX calls | 36 Requester connection calls; 0 direct URL.openConnection/openStream calls |
| Refactor hygiene | Old YouTube NativePayload and IncludedFingerprint classes absent from JVM and Android DEX |
| SDK/bundle validation | Both app patch classes, shared/Music extension DEX, three exact JNI signatures, 28 UI keys and notices passed |
| Native payload validation | Both 64-bit ELF files matched the previously verified payload bytes; ABI, alignment and non-executable-stack checks passed |

The final unsigned bundle is
`morphe-wireguard-youtube-music-preview-2.1.0.mpp`:

- Size: **24,127,235 bytes**.
- SHA-256: `c9fb32a42ff7d6228c86d3bd113398f2e21b253b0e3b292d741a4d3b5bbbbf19`.

The 36 compiled Requester calls include previously routed calls as well as the
16 calls changed in this extension. The absence assertion covers the Music
extension DEX only; it does not inspect every networking path in an original APK.

### Reproducibility and inspection notes

The inherited workspace retained old source/output entries after the shared
installer refactor. A new snapshot was therefore created from the explicit
current-source list, omitting only the two obsolete YouTube NativePayload source
and test files that had moved to the shared package. No compilation exclusion or
placeholder class was introduced. The final checks explicitly reject the old
JVM and DEX helper classes.

The first fresh build was interrupted by an execution-server disconnect. After
confirming that no build process remained, the build was resumed using identical
sources. The successful invocation reported 1m55s, 328 actionable tasks
(233 executed and 95 up-to-date from the interrupted attempt). The complete
5,433-file source manifest and 17 core-input hashes matched after compilation.

One standalone Python inspector correction followed: Android dexdump can emit
Modified UTF-8 strings that cannot be decoded as strict standard UTF-8. The
inspector now retains the complete raw output and matches the same ASCII method
descriptors with bytes regular expressions. It does not drop, replace or ignore
bytes. No product source or compiled bundle changed. The updated inspector and
source manifests were checked again, then all SDK/bundle assertions passed.
The inspector's final SHA-256 is
`f523667e9c540abc6996970e5d96300242df08696f852b55f937aac9cc0269d2`.

Only delivery documentation was updated after those compiled-input checks. The
source ZIP includes per-file final SHA-256 checksums and a patch checked with
`git apply --check --cached` against the pinned Morphe base.

The previous YouTube-only build/native tests are documented separately in
[wireguard-verification.md](wireguard-verification.md). Native source and payload
bytes were reused, not rebuilt or re-run against a real VPN provider for this
extension.

## Settings and simultaneous use

The two apps keep independent enable flags, encrypted profile files, Keystore
entries and process-local proxy credentials. Importing a profile into YouTube
does not automatically configure Music. The shared Activity uses the current
application context rather than a hard-coded YouTube package.

If running both apps concurrently, obtain separate server/provider-issued
profiles with distinct client private keys. WireGuard sends to a peer's latest
authenticated endpoint; using one client key from two independent app engines
against the same server can cause endpoint roaming between them. This is an
inference from the official [WireGuard roaming design](https://www.wireguard.com/#built-in-roaming),
not an observed Android failure. Do not merely edit the private key in an
existing profile: the server must know the corresponding client identity.

Configuration limits are unchanged: one peer, bounded UTF-8 `.conf`, numeric
Endpoint, explicit numeric tunneled DNS, AllowedIPs enforcement, and no shell
hooks. An existing system VPN may carry the outer WireGuard UDP sockets.

## Not executed / expected results (예상 결과)

No original Music APK, emulator/device or provider profile was supplied.
Therefore APK fingerprint matching, Morphe Manager import, Android class loading,
real Cronet proxy authentication, Keystore lifecycle, real DNS/IPv6/QUIC paths,
audio/video playback and background/network-transition behavior are untested.

Expected UI path after a successful APK patch/install is Music's Morphe settings
→ Miscellaneous → WireGuard proxy. Import the profile, enable and restart. UI
`local proxy ready` means a local listener is ready, not a successful remote
handshake. Cache-only playback is not proof of tunnel connectivity.

Execute [wireguard-device-acceptance.md](wireguard-device-acceptance.md) before
treating the preview as reliable. This is not a system-wide VPN or kill switch.
