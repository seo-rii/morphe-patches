# Feature request draft (not published)

Title: `feat(YouTube / Music): Add experimental app-local WireGuard transport`

### Feature description

I would like to contribute an existing WireGuard transport for YouTube and
YouTube Music. It reuses Morphe's Network proxy interception and routes those
requests through an authenticated in-process HTTP proxy, a userspace TCP/IP
stack and `wireguard-go` to a user-configured peer.

It uses no Android `VpnService`, root, system TUN or separate VPN app and does
not occupy the system VPN slot. Users import a supported single-peer WireGuard
`.conf` and explicitly enable the connection in the app. Profiles are stored
using AndroidKeyStore-backed encryption and are independent between the apps.
The connection is off by default. The current released custom patch is selected
by default for both apps; I would preserve that selection policy independently
of connection enablement unless maintainers request a policy discussion.

The current scope is 64-bit `arm64-v8a`/`x86_64` APKs, normal APK installation,
one peer, numeric endpoint and explicit in-tunnel DNS. It disables QUIC for the
hooked Cronet builders and forbids direct fallback for intercepted requests.
Unhooked native/network paths, other processes and Cast are not guaranteed to
be covered. This is not a system-wide VPN or complete kill switch.

The existing implementation and corresponding source are available in
[the custom repository](https://github.com/seo-rii/morphe-wireguard-patches) and
[v2.1.1 release](https://github.com/seo-rii/morphe-wireguard-patches/releases/tag/v2.1.1).
I have used v2.1.1 successfully on my Android device. That experience is separate
from the build/automated results and is not a completed device/version/leak-test
matrix for the new upstream port.

I propose keeping small app-specific WireGuard patches depending on their
existing Network proxy patches. This preserves working code, shares hooks and
runtime, and keeps profile/key/native lifecycle concerns distinct from remote
HTTP proxy settings. A small prerequisite change routes Music extension HTTP
calls through the existing Requester, preserving the released patch's coverage
and improving ordinary Network proxy consistency.

Before opening a large PR, are maintainers willing to maintain the embedded
Go/WireGuard/gVisor dependency and native CI builds? Would you prefer the proposed
separate patch structure or integration into Network proxy? I can present the
changes as a focused Requester prerequisite followed by the WireGuard PR, or one
PR with those reviewable commit boundaries.

Issue [#2827](https://github.com/MorpheApp/morphe-patches/issues/2827) asks for
SOCKS5/other proxy protocols. This proposal shares its interception context but
does not implement SOCKS5 and has additional native-build, secret-storage and
lifecycle requirements.

### Motivation

Users with a compatible WireGuard peer can route supported YouTube/Music requests
through it without using the device's system VPN slot or installing a second VPN
app. Each app can have its own profile. Reusing the existing proxy hooks avoids
building a parallel app interception mechanism.

The existing prototype provides a working starting point. The contribution
focuses on preserving that behavior, normal source builds, explicit dependency
notices and reproducible failure/coverage testing, rather than expanding it into
a full VPN product.

### Acknowledgements

- [x] I checked upstream issue search for `wireguard` in open and closed issue
  titles/bodies on 2026-11-07; it returned no matches. I reviewed #2827 separately.
  Recheck before posting because the result can change.
- [x] This is a feature request for YouTube and YouTube Music.

### Submission notes (remove before posting)

Update the test status from [validation.md](validation.md), link the final source
branch/commits only after the author approves publishing, and re-run the duplicate
search. No issue has been created from this draft.
