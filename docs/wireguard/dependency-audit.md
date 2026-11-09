# Native dependency advisory review

## Recorded scope

This review was run on 2026-11-07 against the native module retained from
v2.1.1. It did not change dependency versions or native transport code.
The tool was `govulncheck v1.9.0`, built and run with `go1.26.10`, using
`https://vuln.go.dev`. The database reported its last update as
`2026-11-05T23:49:57Z`. A later database or different build configuration can
produce different findings.

The inspected module inputs were:

| File | SHA-256 |
| --- | --- |
| [`native/wireguard/go.mod`](../../native/wireguard/go.mod) | `56aad0ed6e3a073025900cc8f0270e3164c7b4e8be1618ff99f2394d14818556` |
| [`native/wireguard/go.sum`](../../native/wireguard/go.sum) | `328d3e164fd9e57ea74aa31ebe319c0017f4ecadba8e0915c86a272072aa73fe` |

## Executed scans

| Configuration | Scan | Exit | Observed result |
| --- | --- | --- | --- |
| Linux/amd64, `./...` | Source, symbols | 0 | No reported symbol-level call paths; 1 imported-package and 37 other module advisories |
| Android/arm64, API 26 NDK compiler, `./cmd/jni` | Source, symbols | 0 | No reported symbol-level call paths; 1 imported-package and 37 other module advisories |
| Android/arm64, same JNI root | Source, packages | 3 | 1 imported-package advisory and 37 module-only advisories |
| Module requirements, no package pattern | Modules | 3 | 38 advisories across 3 required modules |

Exit 3 in the package/module scans indicates findings, not a successful empty
report. Exit 0 from the symbol scans is only the scanner's observation at that
analysis level. It does not establish that the dependency graph is free of
vulnerabilities. No advisory was suppressed.

`cmd/jni/main()` is empty. Android reaches Go through C/JNI calls to the
`//export` entrypoints `morphe_wg_start`, `morphe_wg_validate` and
`morphe_wg_stop`. A source call graph may not represent all native entry edges.
The zero symbol-level result therefore cannot independently demonstrate that
those externally callable paths avoid vulnerable code. The Linux scan also
excludes Android-only JNI files. These are static host analyses, not Android
execution or malformed-input exploit tests. See the official
[govulncheck documentation](https://pkg.go.dev/golang.org/x/vuln@v1.9.0/cmd/govulncheck)
for build-configuration and static-analysis limits.

## Android package graph and DNS triage

`go list -deps` for `GOOS=android GOARCH=arm64 CGO_ENABLED=1 ./cmd/jni`
returned 260 package paths. The scanner listed the application module, 7
external modules and the Go standard library. The Windows-only `wintun`
requirement did not appear in that Android package graph.

Intersecting the affected package paths from the 38 official OSV records with
that actual Android graph found only
`golang.org/x/net/dns/dnsmessage`, for
[GO-2026-5942 / CVE-2026-46600](https://vuln.go.dev/ID/GO-2026-5942.json).
The other 37 records concern affected package paths absent from this graph,
including SSH, OpenPGP, HTML, HTTP/2, IDNA and Windows helpers. A required module
can contain advisory-bearing packages without those packages being imported by
this Android entrypoint. This conclusion applies to the recorded package graph;
changing imports, build tags or dependencies requires another review.

The DNS advisory describes a panic when decoding malformed SVCB/HTTPS resource
record parameters, and its published version range includes the pinned
`golang.org/x/net v0.39.0` (reported fix: `v0.56.0`). The listed affected symbols
include `Message.Unpack`, generic answer/authority/additional parsing methods,
`Parser.SVCBResource`, `Parser.HTTPSResource` and `unpackSVCBResource`.

Manual source inspection followed the production entry path:

```text
NativeTunnel.start -> JNI C shim -> morphe_wg_start -> wireguard.Start
  -> Engine.dialContext -> netstack.Net.LookupContextHost
```

The pinned WireGuard adapter requests A and AAAA records and reads results with
`AnswerHeader`, `AResource`, `AAAAResource` and `SkipAnswer`. Its production DNS
path does not call the advisory's listed generic resource decoders. The pinned
`x/net v0.39.0` source does not contain SVCB/HTTPS resource decoder
implementations; unknown record bodies use the unknown-resource/skip path.
`morphe_wg_validate` parses configuration, and `morphe_wg_stop` closes the engine;
neither introduces another DNS record decoder.

This supports the inference that the specific SVCB/HTTPS panic is not reached
by the retained production resolver. It is not a proof covering every native
entrypoint or malformed DNS response, and no new exploit reproduction or Android
DNS acceptance test was run for this advisory. Keep the advisory visible during
future dependency changes rather than relying on a zero symbol count or silently
removing the record from consideration.

## Exact module advisory inventory

All rows below were returned by the module-level scan. The version in the
"Reported fix" column is the boundary published in that record, not a
recommendation to upgrade individual modules without testing the complete
WireGuard/gVisor graph. The links point directly to the official OSV records.

- `x/net` means `golang.org/x/net v0.39.0`: 17 records.
- `x/crypto` means `golang.org/x/crypto v0.37.0`: 20 records.
- `x/sys` means `golang.org/x/sys v0.32.0`: 1 record.

Only the DNS row has an affected package imported by the recorded Android graph.

| Module | Advisory | Reported fix | Android graph classification |
| --- | --- | --- | --- |
| `x/net` | [GO-2026-6866](https://vuln.go.dev/ID/GO-2026-6866.json) | `v0.62.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-6860](https://vuln.go.dev/ID/GO-2026-6860.json) | `v0.62.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-6617](https://vuln.go.dev/ID/GO-2026-6617.json) | `v0.60.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-6612](https://vuln.go.dev/ID/GO-2026-6612.json) | `v0.60.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-6611](https://vuln.go.dev/ID/GO-2026-6611.json) | `v0.60.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-6610](https://vuln.go.dev/ID/GO-2026-6610.json) | `v0.60.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-6603](https://vuln.go.dev/ID/GO-2026-6603.json) | `v0.60.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-6355](https://vuln.go.dev/ID/GO-2026-6355.json) | `v0.56.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-6354](https://vuln.go.dev/ID/GO-2026-6354.json) | `v0.56.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-6303](https://vuln.go.dev/ID/GO-2026-6303.json) | `v0.55.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-5942](https://vuln.go.dev/ID/GO-2026-5942.json) | `v0.56.0` | Imported DNS package; manually triaged above |
| `x/crypto` | [GO-2026-5932](https://vuln.go.dev/ID/GO-2026-5932.json) | `v0.58.1-0.20261009181718-2f1f834330b2` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5033](https://vuln.go.dev/ID/GO-2026-5033.json) | `v0.52.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-5030](https://vuln.go.dev/ID/GO-2026-5030.json) | `v0.55.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-5029](https://vuln.go.dev/ID/GO-2026-5029.json) | `v0.55.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-5028](https://vuln.go.dev/ID/GO-2026-5028.json) | `v0.55.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-5027](https://vuln.go.dev/ID/GO-2026-5027.json) | `v0.55.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-5026](https://vuln.go.dev/ID/GO-2026-5026.json) | `v0.55.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-5025](https://vuln.go.dev/ID/GO-2026-5025.json) | `v0.55.0` | Module only; affected packages absent |
| `x/sys` | [GO-2026-5024](https://vuln.go.dev/ID/GO-2026-5024.json) | `v0.44.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5023](https://vuln.go.dev/ID/GO-2026-5023.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5021](https://vuln.go.dev/ID/GO-2026-5021.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5020](https://vuln.go.dev/ID/GO-2026-5020.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5019](https://vuln.go.dev/ID/GO-2026-5019.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5018](https://vuln.go.dev/ID/GO-2026-5018.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5017](https://vuln.go.dev/ID/GO-2026-5017.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5016](https://vuln.go.dev/ID/GO-2026-5016.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5015](https://vuln.go.dev/ID/GO-2026-5015.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5014](https://vuln.go.dev/ID/GO-2026-5014.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5013](https://vuln.go.dev/ID/GO-2026-5013.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5006](https://vuln.go.dev/ID/GO-2026-5006.json) | `v0.52.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2026-5005](https://vuln.go.dev/ID/GO-2026-5005.json) | `v0.52.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-4918](https://vuln.go.dev/ID/GO-2026-4918.json) | `v0.53.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-4441](https://vuln.go.dev/ID/GO-2026-4441.json) | `v0.45.0` | Module only; affected packages absent |
| `x/net` | [GO-2026-4440](https://vuln.go.dev/ID/GO-2026-4440.json) | `v0.45.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2025-4135](https://vuln.go.dev/ID/GO-2025-4135.json) | `v0.45.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2025-4134](https://vuln.go.dev/ID/GO-2025-4134.json) | `v0.45.0` | Module only; affected packages absent |
| `x/crypto` | [GO-2025-4116](https://vuln.go.dev/ID/GO-2025-4116.json) | `v0.43.0` | Module only; affected packages absent |

## Reproduction

The following Linux commands use the pinned Go and NDK toolchains from the
[source-build procedure](build.md). Set the paths to installed tools; no provider
profiles, target APKs or GitHub credentials are needed. Install the audit tool in
a new tools directory outside the source checkout. `go install ...@v1.9.0` does
not add it to this project's module requirements.

```bash
export WIREGUARD_GO_BIN=/path/to/go1.26.10/bin/go
export ANDROID_NDK_HOME=/path/to/android-ndk-r28c
wireguard_audit_tools=/absolute/path/to/new-audit-tools
mkdir -p "$wireguard_audit_tools"
GOBIN="$wireguard_audit_tools" GOWORK=off GOTOOLCHAIN=local \
  "$WIREGUARD_GO_BIN" install golang.org/x/vuln/cmd/govulncheck@v1.9.0

cd native/wireguard
wireguard_govulncheck() {
    env PATH="$(dirname "$WIREGUARD_GO_BIN"):$PATH" \
      GOWORK=off GOTOOLCHAIN=local GOFLAGS=-mod=readonly \
      "$wireguard_audit_tools/govulncheck" "$@"
}

wireguard_govulncheck -show traces,version ./...
wireguard_govulncheck -scan module -show version,verbose

export GOOS=android GOARCH=arm64 CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
wireguard_govulncheck -show traces,version ./cmd/jni
wireguard_govulncheck -scan package -show verbose,version ./cmd/jni

# Match the native build's intentional exclusion of VCS stamping in a worktree.
GOWORK=off GOTOOLCHAIN=local GOFLAGS='-mod=readonly -buildvcs=false' \
  "$WIREGUARD_GO_BIN" list -deps -f '{{.ImportPath}}' ./cmd/jni
```

Run the commands individually or record expected finding exit codes explicitly
in automation; a runner using `set -e` stops at the first exit 3. Source analysis
used arm64; this does not claim an independent x86_64 vulnerability scan or an
Android runtime test. The package graph command disables VCS stamping, as does
the native build: in this linked worktree an ordinary stamped `go list` tried to
read Git status from the non-repository workspace parent. This does not change
package selection or module versions.

## Maintenance decision

The retained pins preserve the v2.1.1 regression baseline. They are not endorsed
as an indefinitely maintained dependency set. Before broader security or
multiprocess support claims, review this inventory with maintainers, refresh or
explicitly disposition advisories, and validate any chosen dependency changes
against both Android ABIs, JNI, proxy authentication, DNS, routing, failure and
lifecycle tests. Update copied dependency licenses with the graph. Do not bulk
upgrade the stack solely to make a module-level scan print no findings.

Profile mutation within several processes of one app has a separate storage
concurrency limit described in [the security review](security.md#multiple-processes).
That limit is not resolved by a dependency scan. Re-run this review when module
pins, Go/NDK versions, imports, build tags or reachable native entrypoints change.
