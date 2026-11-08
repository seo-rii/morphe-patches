# App-local WireGuard transport

This native component is a small HTTP proxy over the official `wireguard-go`
implementation and its gVisor-backed userspace network stack. It does not use
`VpnService`, an OS TUN device, root, system routing changes, `wg-quick`, a shell,
or a separately installed VPN application. It is not a Proton product.

## Public JNI contract

Java class: `app.morphe.extension.shared.wireguard.NativeTunnel`.

```java
public static native int start(String config, String username, String password, int listenPort);
public static native void stop();
public static native void validate(String config);
```

- `validate` throws a sanitized `IllegalArgumentException` on invalid input.
- `start` binds **only** `127.0.0.1`, returns its port, and does not wait for a
  handshake. Zero requests an ephemeral port. A positive result means *ready*,
  not *connected*. Bind/initialization failures throw `IllegalStateException`.
- `stop` is idempotent and closes existing HTTP/CONNECT streams. One native
  instance is allowed per process; multiple Android processes must not be
  mistaken for one shared singleton.
- Generate fresh random credentials for each process. Credentials are mandatory
  because loopback TCP sockets are accessible to other Android apps. Passwords
  must contain 16–256 printable non-space ASCII characters; usernames 1–128,
  excluding `:`. Neither keys nor credentials are logged.

The Java loader must load the packaged `libmorphewireguard.so`. Keep the JNI
class/method names from obfuscation. `cmd/jni` builds with `GOOS=android` (or
`-tags jni` for a host-JVM smoke test) and `-buildmode=c-shared`.

## Supported configuration subset (v1)

Exactly one `[Interface]` and one `[Peer]` are accepted. Core fields:

- Interface: `PrivateKey`, `Address`, `DNS`, optional `MTU`, `ListenPort`.
- Peer: `PublicKey`, `Endpoint`, `AllowedIPs`, optional `PresharedKey`,
  `PersistentKeepalive`.
- Endpoint **must be numeric IPv4 or bracketed IPv6**, with a nonzero UDP port.
  Hostnames are deliberately rejected so startup cannot block on DNS or leak an
  endpoint-name lookup. Proton's numeric-endpoint exports fit this subset.
- DNS must contain numeric IPs, be covered by `AllowedIPs`, and have a matching
  configured interface address family. DNS search domains are unsupported.
- `AllowedIPs` is honored, including restricted routes. An out-of-route resolved
  destination fails; it is never sent directly or silently granted a default
  route. Destination DNS is resolved inside the WireGuard network stack.
- `PreUp`, `PostUp`, `PreDown`, `PostDown`, `Table`, `FwMark`, `SaveConfig`,
  Amnezia extensions, and every unknown directive are rejected, never executed
  or silently ignored. A 64 KiB file and 4 KiB per-line limit are enforced.

IPv4 and IPv6 inside the tunnel are supported when configured. The proxy speaks
ordinary absolute-form HTTP and TCP `CONNECT`, not SOCKS, QUIC, CONNECT-UDP, or
MASQUE. YouTube QUIC must be disabled by the integration. HTTPS remains
end-to-end; the engine neither inspects TLS nor changes certificate validation.

## Failure and lifecycle policy

The engine has no direct dial fallback. Proxy authentication is checked before
request-triggered destination DNS or outbound application connections. Configured
WireGuard keepalives/handshakes may occur independently of a proxy request.
HTTP hop-by-hop/proxy headers are stripped from
forwarded HTTP requests. There are bounded header/connection counts, dial
deadlines, and idle stream timeouts. Active streams are tracked and closed on
stop, including streams hijacked by HTTP `CONNECT`.

This protects only requests routed into the proxy. It cannot block an unpatched
YouTube networking path, another process, or another application. Android
background execution and Wi-Fi/cellular transitions need device testing. An
existing system VPN may carry the outer WireGuard UDP connection (double
tunneling); this component does not bypass system VPN policies.

## Build and tests

```sh
go test ./...
go test -race ./...
# Separately labeled in-memory protocol tests (not a substitute for UDP tests):
go test -race -run '^(TestMemory|TestIdle|TestValidate|TestAllowedIPs|FuzzValidate)' ./...
go test -run '^$' -fuzz FuzzValidate -fuzztime=10s
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
  CC="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang" \
  go build -trimpath -buildmode=c-shared -o libmorphewireguard.so ./cmd/jni
```

The original end-to-end tests create **two actual WireGuard peers** on local UDP sockets,
with HTTP, verified TLS, and DNS servers inside a second in-memory network stack.
They test encrypted traffic, a large stream, authentication, routing restrictions,
no host-network fallback, pipelined CONNECT bytes, and repeated/concurrent
lifecycle operations. Test credentials are generated locally and are never
saved. Tests do not contact Proton, YouTube, or any public VPN endpoint.

`TestMemory*` exercises the same two encrypted WireGuard peers and proxy paths
using upstream `conn/bindtest.ChannelBind` for outer packets instead of UDP. The
proxy still uses a real loopback TCP listener, while DNS/HTTP/TLS run in the
userspace stack. This separate suite is useful in restricted build containers,
but does not verify Android UDP sockets, cellular/Wi-Fi behavior, or a provider.
Production always uses the official default UDP bind; no alternative transport
or permission fallback is selected at runtime.

Some Linux build containers forbid the default upstream transport's
`socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE)` route monitor. In this delivery
environment, `go run ./cmd/diagnose-linux` confirmed that ordinary upstream UDP
binding succeeds but that netlink operation is denied with `EPERM`. The full
UDP integration suite therefore fails here and has not been silently skipped.
Upstream Android builds set `StdNetSupportsStickySockets=false`, so they do not
open that Linux-only route-monitor socket. Actual Android operation still needs
a device test; successful in-memory tests and cross-compilation do not prove it.

The commands above document how to run the tests; consult the root verification
report for which commands were actually executed in the delivery environment.

## Dependencies and licenses

This new component is GPL-3.0-only, matching the surrounding patch project.
WireGuard cryptography is supplied by upstream, not reimplemented here:

- `golang.zx2c4.com/wireguard` is pinned in `go.mod` and `go.sum` (MIT).
- Its userspace TCP/IP stack is `gvisor.dev/gvisor` (Apache-2.0).
- Transitive Go dependencies and their exact checksums are in `go.sum`.

Distributions must preserve the surrounding repository's LICENSE/NOTICE and the
corresponding dependency notices. Building this library does not authorize
redistribution of proprietary YouTube APKs.
