# App-local WireGuard contribution

This contribution ports the existing experimental WireGuard transport to Morphe
`dev`. It preserves the working implementation rather than replacing its network
stack. It supports requests routed through Morphe's Network proxy hooks in
YouTube and YouTube Music. It is not a system VPN and does not promise complete
app traffic coverage or a system-wide kill switch.

Review material:

- [Design and integration](design.md)
- [Source build and CI](build.md)
- [Security, dependency and license review](security.md)
- [Executed validation and remaining checks](validation.md)
- [APKPure target APK validation and controls](apkpure-validation.md)
- [Device and app-version acceptance procedure](device-acceptance.md)
- [Feature request draft](issue-draft.md)
- [Pull request draft](pr-draft.md)

The Issue and PR documents are unpublished drafts. Publishing either requires the
author's review and approval.

## Source and regression baseline

| Input | Exact reference |
| --- | --- |
| Original implementation | [seo-rii/morphe-wireguard-patches](https://github.com/seo-rii/morphe-wireguard-patches) |
| Released regression baseline | [v2.1.1](https://github.com/seo-rii/morphe-wireguard-patches/releases/tag/v2.1.1) |
| Original Morphe base | `8fa2c7a1a832498de8f179748998a3ca87e198eb` (v2.1.0) |
| Upstream comparison/port base | `6bdf46e6f12da2a73481b4ba4a4bda2e74dd4be9` (`dev`, 2026-11-07) |
| Full source ZIP SHA-256 | `c7095259401f3752e685712f21ef7b14c25e08cc35ea323fac3e08b8de014437` |
| Released `.mpp` SHA-256 | `9905a6a9a57d281532c94a9b53215a5414ccc4185b880031c0f67d0361fd69ca` |

The author reports successful use of v2.1.1 on a physical Android device. That is
first-hand evidence for the author's setup. Exact APK/device versions, handshake
records and packet-capture results were not supplied with that report. It is not
an automated result or a device acceptance result for this new upstream port.

The published `wireguard-prototype.patch` and the release source ZIP differ:
the patch still has `default = false`, while the v2.1.1 source ZIP has
`default = true`. The released v2.1.1 bundle was made by changing the selection flags in
previously compiled classes. This contribution builds those flags normally from
Kotlin source; it does not carry forward the bytecode-repackaging script.

Both app patches remain named `WireGuard proxy (experimental)` and selected by
default. The connection setting remains `false`. Importing a configuration alone
does not enable a connection; the user must explicitly enable it and restart.

## Contribution order

Discuss the embedded native dependency and build cost in the feature request
first. Issue [#2827](https://github.com/MorpheApp/morphe-patches/issues/2827)
requests SOCKS5 and other proxy protocols. It is relevant context, but embedded
WireGuard additionally needs a native engine, userspace IP stack, private-key
storage and lifecycle ownership. This contribution does not implement SOCKS5 or
close that issue.

A small prerequisite PR can route the existing Music extension HTTP connections
through the existing `Requester` abstraction. This independently benefits Network
proxy and preserves the routing coverage of v2.1.1. It should have its own focused
review and tests, without introducing a new generic network framework.

The WireGuard implementation itself fits one subsequent PR with coherent commits
for the engine/build, shared runtime, app declarations and verification/docs.
Splitting its engine, JNI, APK packaging and runtime into independently merged
PRs would temporarily expose a selectable patch without a usable transport.
Maintainers may prefer a single PR containing both units; the commit boundaries
allow either arrangement.

Before posting a PR, complete or explicitly agree on the unrun Android/version
checks in [validation.md](validation.md), and update the draft with the final
commit IDs, artifact hashes and actual results.
