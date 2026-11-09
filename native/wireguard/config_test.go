// SPDX-License-Identifier: GPL-3.0-only

package wireguard

import (
	"encoding/base64"
	"errors"
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

func TestValidateStandardKeepaliveOff(t *testing.T) {
	for _, value := range []string{"off", "OFF", "OfF", "0"} {
		t.Run(value, func(t *testing.T) {
			text := strings.Replace(fixtureConfig(), "PersistentKeepalive = 25", "PersistentKeepalive = "+value, 1)
			c, err := parseConfig(text)
			if err != nil {
				t.Fatal(err)
			}
			defer c.clearKeys()
			if c.keepalive != 0 || !strings.Contains(c.uapi(), "persistent_keepalive_interval=0\n") {
				t.Fatal("off did not disable persistent keepalive")
			}
		})
	}
}

func TestValidateStandardBareAddresses(t *testing.T) {
	cases := []struct {
		name, address string
		want          []netip.Addr
	}{
		{"IPv4", "10.77.0.2", []netip.Addr{netip.MustParseAddr("10.77.0.2")}},
		{"mixed", "10.77.0.2, fd00:77::2", []netip.Addr{netip.MustParseAddr("10.77.0.2"), netip.MustParseAddr("fd00:77::2")}},
		{"mixed CIDR", "10.77.0.2/32, fd00:77::2", []netip.Addr{netip.MustParseAddr("10.77.0.2"), netip.MustParseAddr("fd00:77::2")}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			text := strings.Replace(fixtureConfig(), "Address = 10.77.0.2/32", "Address = "+tc.address, 1)
			c, err := parseConfig(text)
			if err != nil {
				t.Fatal(err)
			}
			defer c.clearKeys()
			if len(c.addresses) != len(tc.want) {
				t.Fatal("interface addresses changed")
			}
			for i, want := range tc.want {
				if c.addresses[i] != want {
					t.Fatal("interface address changed")
				}
			}
		})
	}
}

func TestBareAllowedIPsRemainHostRoutes(t *testing.T) {
	cases := []struct {
		name, local, server, adjacent, prefix string
	}{
		{"IPv4", "10.77.0.2", "10.77.0.1", "10.77.0.3", "10.77.0.1/32"},
		{"IPv6", "fd00:77::2", "fd00:77::1", "fd00:77::3", "fd00:77::1/128"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			text := strings.NewReplacer("Address = 10.77.0.2/32", "Address = "+tc.local,
				"DNS = 10.77.0.1", "DNS = "+tc.server,
				"AllowedIPs = 10.77.0.0/24", "AllowedIPs = "+tc.server).Replace(fixtureConfig())
			c, err := parseConfig(text)
			if err != nil {
				t.Fatal(err)
			}
			defer c.clearKeys()
			if len(c.routes) != 1 || c.routes[0].String() != tc.prefix ||
				!c.allows(netip.MustParseAddr(tc.server)) || c.allows(netip.MustParseAddr(tc.adjacent)) ||
				!strings.Contains(c.uapi(), "allowed_ip="+tc.prefix+"\n") {
				t.Fatal("bare AllowedIPs expanded beyond its single host")
			}
		})
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
		"invalid off value":  strings.Replace(fixtureConfig(), "= 25", "= offx", 1),
		"oversize keepalive": strings.Replace(fixtureConfig(), "= 25", "= 65536", 1),
		"bare address name":  strings.Replace(fixtureConfig(), "10.77.0.2/32", "client.example", 1),
		"address empty CIDR": strings.Replace(fixtureConfig(), "10.77.0.2/32", "10.77.0.2/", 1),
		"address empty item": strings.Replace(fixtureConfig(), "10.77.0.2/32", "10.77.0.2,", 1),
		"bare zoned address": strings.Replace(fixtureConfig(), "10.77.0.2/32", "fd00:77::2%test", 1),
		"route empty CIDR":   strings.Replace(fixtureConfig(), "10.77.0.0/24", "10.77.0.1/", 1),
		"bare route name":    strings.Replace(fixtureConfig(), "10.77.0.0/24", "server.example", 1),
		"bare mapped route":  strings.Replace(fixtureConfig(), "10.77.0.0/24", "::ffff:10.77.0.1", 1),
		"bare zoned route":   strings.Replace(fixtureConfig(), "10.77.0.0/24", "fd00:77::1%test", 1),
		"bare route DNS":     strings.Replace(fixtureConfig(), "10.77.0.0/24", "10.77.0.2", 1),
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

func TestValidationErrorsHaveStableLocalizationCodes(t *testing.T) {
	config := fixtureConfig()
	cases := map[string]string{
		"invalid_text":          config + "\x00",
		"invalid_interface":     "[Interface]\n" + config,
		"invalid_peer":          config + "[Peer]\n",
		"unsupported_section":   strings.Replace(config, "[Interface]", "[Other]", 1),
		"invalid_directive":     "[Interface]\nPrivateKey = \n",
		"duplicate_directive":   strings.Replace(config, "[Peer]", "MTU = 1420\nMTU = 1420\n[Peer]", 1),
		"invalid_address":       strings.Replace(config, "Address = 10.77.0.2/32", "Address = client.invalid", 1),
		"duplicate_address":     strings.Replace(config, "Address = 10.77.0.2/32", "Address = 10.77.0.2/32, 10.77.0.2/32", 1),
		"invalid_dns":           strings.Replace(config, "DNS = 10.77.0.1", "DNS = dns.invalid", 1),
		"invalid_mtu":           strings.Replace(config, "[Peer]", "MTU = 0\n[Peer]", 1),
		"invalid_endpoint":      strings.Replace(config, "127.0.0.1:51820", "vpn.example:51820", 1),
		"invalid_routes":        strings.Replace(config, "10.77.0.0/24", "10.77.0.0/33", 1),
		"unsupported_directive": strings.Replace(config, "[Peer]", "PostUp = secret-hook-marker\n[Peer]", 1),
		"invalid_value":         strings.Replace(config, "PrivateKey = ", "PrivateKey = BAD", 1),
		"line_too_long":         config + strings.Repeat("#", maxConfigLine+1),
		"missing_required":      strings.Replace(config, "PublicKey = ", "# PublicKey = ", 1),
		"ipv6_mtu": strings.NewReplacer("Address = 10.77.0.2/32", "Address = 10.77.0.2/32, fd00:77::2/128",
			"[Peer]", "MTU = 1200\n[Peer]").Replace(config),
		"dns_required":   strings.Replace(config, "DNS = 10.77.0.1\n", "", 1),
		"dns_not_routed": strings.Replace(config, "DNS = 10.77.0.1", "DNS = 1.1.1.1", 1),
	}
	for code, text := range cases {
		t.Run(code, func(t *testing.T) {
			err := Validate(text)
			var configErr *ConfigError
			if !errors.As(err, &configErr) || configErr.Code != code {
				t.Fatalf("expected localization code %q, got %v", code, err)
			}
			if configErr.Reason == "" || err.Error() != configErr.Reason || configErr.Reason == code {
				t.Fatal("non-UI diagnostic was lost")
			}
			for _, marker := range []string{"secret-hook-marker", "AQIDBA", "vpn.example", "client.invalid", "dns.invalid"} {
				if strings.Contains(configErr.Code, marker) || strings.Contains(configErr.Reason, marker) {
					t.Fatal("validation diagnostic contains profile data")
				}
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
