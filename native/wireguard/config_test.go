// SPDX-License-Identifier: GPL-3.0-only

package wireguard

import (
	"encoding/base64"
	"net/netip"
	"strings"
	"testing"
)

func fixtureConfig() string {
	// Deliberately public synthetic test keys, not real account credentials.
	private, public := [32]byte{1, 2, 3, 4}, [32]byte{5, 6, 7, 8}
	return "[Interface]\nPrivateKey = " + base64.StdEncoding.EncodeToString(private[:]) + "\nAddress = 10.77.0.2/32\nDNS = 10.77.0.1\n\n[Peer]\nPublicKey = " + base64.StdEncoding.EncodeToString(public[:]) + "\nEndpoint = 127.0.0.1:51820\nAllowedIPs = 10.77.0.0/24\nPersistentKeepalive = 25\n"
}

func TestValidateSupportedConfig(t *testing.T) {
	for _, text := range []string{
		fixtureConfig(),
		"# 한글 주석 및 😀 emoji are UTF-8\n" + fixtureConfig(),
		"\ufeff" + strings.ReplaceAll(fixtureConfig(), "\n", "\r\n"),
		strings.Replace(fixtureConfig(), "Address = 10.77.0.2/32", "Address = 10.77.0.2/32, fd00:77::2/128\nMTU = 1280", 1),
		strings.Replace(fixtureConfig(), "Endpoint = 127.0.0.1:51820", "Endpoint = [::1]:51820 # test peer", 1),
	} {
		if err := Validate(text); err != nil {
			t.Fatal(err)
		}
	}
}

func TestValidateRejectsUnsupportedOrUnsafeConfig(t *testing.T) {
	cases := map[string]string{
		"shell hook":         strings.Replace(fixtureConfig(), "[Peer]", "PostUp = touch /tmp/never-executed\n[Peer]", 1),
		"routing table":      strings.Replace(fixtureConfig(), "[Peer]", "Table = auto\n[Peer]", 1),
		"save config":        strings.Replace(fixtureConfig(), "[Peer]", "SaveConfig = true\n[Peer]", 1),
		"hostname endpoint":  strings.Replace(fixtureConfig(), "127.0.0.1:51820", "vpn.example:51820", 1),
		"endpoint no port":   strings.Replace(fixtureConfig(), "127.0.0.1:51820", "127.0.0.1", 1),
		"zero endpoint port": strings.Replace(fixtureConfig(), "127.0.0.1:51820", "127.0.0.1:0", 1),
		"endpoint multicast": strings.Replace(fixtureConfig(), "127.0.0.1:51820", "224.0.0.1:51820", 1),
		"endpoint broadcast": strings.Replace(fixtureConfig(), "127.0.0.1:51820", "255.255.255.255:51820", 1),
		"mapped unspecified": strings.Replace(fixtureConfig(), "127.0.0.1:51820", "[::ffff:0.0.0.0]:51820", 1),
		"second peer":        fixtureConfig() + "[Peer]\n",
		"duplicate key":      strings.Replace(fixtureConfig(), "[Peer]", "MTU = 1420\nMTU = 1420\n[Peer]", 1),
		"out of route DNS":   strings.Replace(fixtureConfig(), "DNS = 10.77.0.1", "DNS = 1.1.1.1", 1),
		"DNS search domain":  strings.Replace(fixtureConfig(), "DNS = 10.77.0.1", "DNS = private.example", 1),
		"no DNS":             strings.Replace(fixtureConfig(), "DNS = 10.77.0.1\n", "", 1),
		"unknown directive":  fixtureConfig() + "Other = 1\n",
		"invalid range":      strings.Replace(fixtureConfig(), "10.77.0.0/24", "10.77.0.0/33", 1),
		"missing interface":  strings.Replace(fixtureConfig(), "[Interface]\n", "", 1),
		"short key":          strings.Replace(fixtureConfig(), "PrivateKey = ", "PrivateKey = BAD", 1),
		"negative keepalive": strings.Replace(fixtureConfig(), "= 25", "= -1", 1),
		"oversized":          strings.Repeat("#", MaxConfigBytes+1),
		"long line":          strings.Repeat("#", maxConfigLine+1),
		"embedded NUL":       fixtureConfig() + "\x00",
		"invalid UTF8":       fixtureConfig() + "\xff",
	}
	for name, text := range cases {
		t.Run(name, func(t *testing.T) {
			err := Validate(text)
			if err == nil {
				t.Fatal("expected rejection")
			}
			if strings.Contains(err.Error(), "touch") || strings.Contains(err.Error(), "AQIDBA") || strings.Contains(err.Error(), "vpn.example") {
				t.Fatal("validation error leaked a config value")
			}
		})
	}
}

func TestAllowedIPsAreNotExpanded(t *testing.T) {
	c, err := parseConfig(fixtureConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer c.clearKeys()
	if !c.allows(netip.MustParseAddr("10.77.0.1")) || c.allows(netip.MustParseAddr("8.8.8.8")) {
		t.Fatal("AllowedIPs policy was not preserved")
	}
	if strings.Contains(c.uapi(), "allowed_ip=0.0.0.0/0") {
		t.Fatal("configuration silently gained a default route")
	}
}

func FuzzValidate(f *testing.F) {
	f.Add(fixtureConfig())
	f.Add("[Interface]\nPostUp=should-not-run\n")
	f.Add("")
	f.Fuzz(func(t *testing.T, text string) {
		_ = Validate(text)
	})
}
