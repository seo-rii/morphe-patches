# Security and dependency review

## Scope and assumptions

The intended property is that a request intercepted by Network proxy while
WireGuard mode is active cannot fall back to ordinary destination Internet
access when the tunnel fails. This is an implementation boundary to test, not a
claim of protection for every network path in YouTube or Music. The app does not
register Android `VpnService`, occupy the system VPN slot, change system routes,
use root or require a separate WireGuard app.

The host app, Android OS, imported profile provider and selected WireGuard server
are trusted with their respective data. Loopback TCP is not an app permission
boundary: another Android app may reach the listener. The functional proxy
requires fresh process-local credentials. A device compromise, instrumentation
of the host app or another component sharing its UID can expose decrypted data
and is outside the storage protection offered here.

## Controls and limits

| Area | Implemented policy | Limit / validation still needed |
| --- | --- | --- |
| Local proxy | Binds numeric `127.0.0.1`; random process token; authenticates before destination DNS/dial; strips proxy/hop headers | Credentials must not appear in callbacks/logs; Android Cronet callback behavior needs a device check |
| Network failure | One proxy with no direct sentinel; hooked construction fails on setup errors; startup failures use rejection-only endpoint | Does not intercept arbitrary unhooked requests; device captures must inventory uncovered paths |
| DNS | Numeric endpoint and numeric in-route DNS; destination resolution inside userspace tunnel | Hostname endpoints/search domains are unsupported; unrelated app/system DNS is outside scope |
| Routes | Uses imported `AllowedIPs`; out-of-route resolved destinations fail | Restricted routes may intentionally prevent some app services from working |
| Import | UTF-8/size bounds, one interface and peer, supported field whitelist | Original source document remains with its provider; no shell hooks or provider login supported |
| At-rest profile | AES-256-GCM, AndroidKeyStore key, authenticated format, atomic file in `noBackupFilesDir` | Hardware-backed key availability is device-dependent; damage/key loss must block, never save plaintext |
| In-memory profile | Temporary byte arrays are cleared where available; errors are sanitized | Java/Go strings, native/runtime copies and crash-memory exposure cannot be completely erased |
| Threads/sockets | Bounded native connections/timeouts; explicit close tracks CONNECT streams; process-local JNI slot | Android background lifetime, long sessions and failure soak need device testing |
| Multiple apps | Separate app storage/settings and random ports/tokens | Use distinct server-issued client profiles for concurrent apps |
| Multiple processes of one app | JNI instance and Java synchronization are process-local | Profile access/key creation are not serialized across processes; concurrent mutation is not validated |

The UI deliberately distinguishes a local ready listener from a verified remote
connection. No remote handshake status or guaranteed connection health is
inferred from `start()` returning a port.

Using one client key simultaneously in both apps can make the server associate
that peer with alternating outer UDP endpoints. This is an inference from
WireGuard's authenticated endpoint update/roaming design, not an observed result
of this port. Use distinct client keys registered at the server. See
[WireGuard's roaming explanation](https://www.wireguard.com/#built-in-roaming).

With another system VPN enabled, this feature's outer UDP sockets may travel
through it. Avoid claiming independent bypass of that VPN or its lockdown policy.
The double-tunnel combination, blocked UDP and MTU behavior need their own tests.

## Multiple processes

Separate apps have separate app-private profiles and settings under ordinary
Android UID isolation. Several processes belonging to the same app share its
profile file and AndroidKeyStore namespace, while each process has its own Java
monitor and native engine slot. This distinction matters during import,
replacement and deletion of a profile.

`WireGuardConfigStore` synchronizes its methods within one process and uses
`AtomicFile` for interrupted-write recovery. It does not acquire an interprocess
file/key lock. Android documents that
[AtomicFile does not provide concurrency locking](https://developer.android.com/reference/android/util/AtomicFile)
and leaves mutual exclusion across threads/processes to the caller. A secondary
process reading or recovering a profile while the main UI replaces it can race
that mutation; concurrent first-key creation or deletion also lacks a shared
lock. Atomic-write recovery alone does not establish safe multiprocess profile
mutation.

The expected failure policy remains blocking of intercepted requests when a
profile cannot be decrypted or loaded. Such failures can still cause lost or
stale profile updates and require user recovery. This is an availability and
consistency risk identified by source review, not an on-device reproduction or
evidence of a direct-connection leak.

Before claiming same-app multiprocess mutation support, establish a single
profile owner or a tested interprocess serialization policy, and test concurrent
startup/import/replacement/deletion and key creation on Android. The current
contribution preserves the existing process-latched transport and does not add an
untested locking redesign. Separate processes and unhooked network paths also
remain outside any promise of complete app traffic coverage.

## Dependency inventory

Versions below are the existing transport graph retained from v2.1.1. Exact
module sums are in `native/wireguard/go.sum`; do not resolve floating branches or
replace them with newly downloaded binary payloads.

| Module | Pinned version | License / role |
| --- | --- | --- |
| `golang.zx2c4.com/wireguard` | `v0.0.0-20261024220952-b3bc704ef0ee` | MIT; WireGuard and userspace-stack adapter |
| `gvisor.dev/gvisor` | `v0.0.0-20250503011706-39ed1f5ac29c` | Apache-2.0; TCP/IP and DNS stack |
| `github.com/google/btree` | `v1.1.2` | Apache-2.0; stack dependency |
| `golang.org/x/crypto` | `v0.37.0` | BSD-3-Clause with Go patent grant; crypto dependency |
| `golang.org/x/net` | `v0.39.0` | BSD-3-Clause with Go patent grant; connection limiting and network helpers |
| `golang.org/x/sys` | `v0.32.0` | BSD-3-Clause with Go patent grant; OS support |
| `golang.org/x/time` | `v0.7.0` | BSD-3-Clause with Go patent grant; transitive timing support |
| `golang.zx2c4.com/wintun` | `v0.0.0-20230126152724-0fa3db229ce2` | MIT; Windows-only module graph entry, not Android runtime code |
| Go runtime/standard library | Pinned by native build toolchain | BSD-style Go license and patent grant; statically included portions |

Source inspection shows permissive native dependencies alongside the project's
GPLv3 and additional Section 7 notices. The Linux kernel WireGuard module's
GPLv2 license is not the license of the linked Go implementation. Preserve the
actual pinned module copyright/license texts rather than relying on project
names or this table alone. This inventory is not a third-party security audit or
a guarantee that the old module versions have no vulnerabilities.

The [recorded dependency advisory review](dependency-audit.md) found 38
module-level advisories, including one affected package imported by the Android
graph. Symbol scans reported no reachable findings, but C/JNI entrypoint and
static-analysis limits prevent treating that result as a security guarantee.
The review records the exact IDs, reported fixed versions, build configuration,
manual DNS-path triage and the remaining dependency maintenance decision. Module
pins remain unchanged to preserve the regression baseline; advisory disposition
and any compatibility-tested refresh remain a maintenance gate before broader
security claims.

## Distribution and authorship

Preserve root `LICENSE` and `NOTICE`, identify unofficial builds appropriately,
and keep original copyright headers. Contributions are submitted under the
upstream terms in [CONTRIBUTING.md](../../CONTRIBUTING.md). The author reports
creating the custom patch; final submission remains the author's responsibility
under upstream's code-origin rule.

The native build retains third-party notice texts separately from a vendored
source tree. Include the native engine's notices in both the `.mpp` and patched
APK and provide a route for users to read them. That includes applicable Go
runtime notices; a license file that exists only in a development cache is not
part of the shipped distribution. Preserve gVisor attribution material and any
module-specific patent/notice files. Confirm the actual linked Android module
graph when changing dependencies; the presence of a Windows module in `go.sum`
does not mean its code is linked into Android.

Do not include proprietary target APKs, live profiles, private keys, session/API
tokens, signing material, proxy authorization headers or unsanitized packet
captures in commits, artifacts or issue reports. Test fixture keys generated for
local peers are test data, not provider credentials.
