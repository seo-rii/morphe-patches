// SPDX-License-Identifier: GPL-3.0-only

// Package wireguard provides an app-local, fail-closed WireGuard HTTP proxy.
// It does not open an operating-system TUN device or change system routing.
package wireguard

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"strconv"
	"strings"
	"unicode/utf8"
)

const (
	MaxConfigBytes = 64 * 1024
	maxConfigLine  = 4096
	maxAddresses   = 8
	maxDNS         = 8
	maxRoutes      = 256
)

// ConfigError contains a stable localization code and a value-free diagnostic.
// JNI returns Code; Error keeps the English diagnostic for non-UI callers.
type ConfigError struct {
	Code   string
	Reason string
}

func (e *ConfigError) Error() string { return e.Reason }

func invalid(code, reason string) error { return &ConfigError{Code: code, Reason: reason} }

type config struct {
	privateKey [32]byte
	publicKey  [32]byte
	psk        [32]byte
	hasPSK     bool
	addresses  []netip.Addr
	dns        []netip.Addr
	routes     []netip.Prefix
	endpoint   netip.AddrPort
	listenPort uint16
	keepalive  uint16
	mtu        int
}

// Validate checks the supported, single-peer wg-quick .conf subset without
// starting a listener, resolving names, contacting a peer, or running commands.
func Validate(text string) error {
	c, err := parseConfig(text)
	if c != nil {
		c.clearKeys()
	}
	return err
}

func parseConfig(text string) (_ *config, resultErr error) {
	if len(text) == 0 || len(text) > MaxConfigBytes || !utf8.ValidString(text) || strings.ContainsRune(text, '\x00') {
		return nil, invalid("invalid_text", "Configuration must be valid UTF-8 text of at most 64 KiB.")
	}
	text = strings.TrimPrefix(text, "\ufeff")
	c := &config{mtu: 1420}
	defer func() {
		if resultErr != nil {
			c.clearKeys()
		}
	}()
	section := ""
	interfaceSeen, peerSeen := false, false
	seen := make(map[string]bool)
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 1024), maxConfigLine)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			switch strings.ToLower(line) {
			case "[interface]":
				if interfaceSeen || peerSeen {
					return nil, invalid("invalid_interface", "Exactly one [Interface], followed by one [Peer], is supported.")
				}
				interfaceSeen, section = true, "interface"
			case "[peer]":
				if !interfaceSeen || peerSeen {
					return nil, invalid("invalid_peer", "Exactly one [Peer] after [Interface] is supported.")
				}
				peerSeen, section = true, "peer"
			default:
				return nil, invalid("unsupported_section", "Configuration contains an unsupported section.")
			}
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		key, value = strings.ToLower(strings.TrimSpace(key)), strings.TrimSpace(value)
		if !ok || section == "" || value == "" {
			return nil, invalid("invalid_directive", "Configuration contains an invalid or empty directive.")
		}
		fullKey := section + "." + key
		multi := fullKey == "interface.address" || fullKey == "interface.dns" || fullKey == "peer.allowedips"
		if seen[fullKey] && !multi {
			return nil, invalid("duplicate_directive", "Duplicate singleton directives are not supported.")
		}
		seen[fullKey] = true
		var err error
		switch fullKey {
		case "interface.privatekey":
			c.privateKey, err = parseKey(value, false)
		case "interface.address":
			for _, item := range strings.Split(value, ",") {
				p, parseErr := parsePrefix(strings.TrimSpace(item))
				if parseErr != nil || !isUnicast(p.Addr()) || len(c.addresses) >= maxAddresses {
					return nil, invalid("invalid_address", "Address must contain at most 8 unicast IPv4/IPv6 addresses with optional CIDR masks.")
				}
				addr := p.Addr().Unmap()
				if containsAddr(c.addresses, addr) {
					return nil, invalid("duplicate_address", "Duplicate interface addresses are not supported.")
				}
				c.addresses = append(c.addresses, addr)
			}
		case "interface.dns":
			for _, item := range strings.Split(value, ",") {
				addr, parseErr := netip.ParseAddr(strings.TrimSpace(item))
				if parseErr != nil || !isUnicast(addr) || len(c.dns) >= maxDNS {
					return nil, invalid("invalid_dns", "DNS must contain at most 8 numeric unicast IP addresses; search domains are unsupported.")
				}
				addr = addr.Unmap()
				if !containsAddr(c.dns, addr) {
					c.dns = append(c.dns, addr)
				}
			}
		case "interface.listenport":
			c.listenPort, err = parseUint16(value)
		case "interface.mtu":
			c.mtu, err = strconv.Atoi(value)
			if err != nil || c.mtu < 576 || c.mtu > 9000 {
				return nil, invalid("invalid_mtu", "MTU must be between 576 and 9000 bytes.")
			}
		case "peer.publickey":
			c.publicKey, err = parseKey(value, false)
		case "peer.presharedkey":
			c.psk, err = parseKey(value, true)
			c.hasPSK = true
		case "peer.endpoint":
			c.endpoint, err = netip.ParseAddrPort(value)
			if err != nil || !isUnicast(c.endpoint.Addr()) || c.endpoint.Port() == 0 {
				return nil, invalid("invalid_endpoint", "Endpoint must be numeric IPv4:port or [IPv6]:port. Hostnames are unsupported in this version.")
			}
			c.endpoint = netip.AddrPortFrom(c.endpoint.Addr().Unmap(), c.endpoint.Port())
		case "peer.allowedips":
			for _, item := range strings.Split(value, ",") {
				p, parseErr := parsePrefix(strings.TrimSpace(item))
				if parseErr != nil || p.Addr().Zone() != "" || p.Addr().Is4In6() || len(c.routes) >= maxRoutes {
					return nil, invalid("invalid_routes", "AllowedIPs must contain at most 256 valid IPv4/IPv6 addresses or CIDR routes.")
				}
				c.routes = append(c.routes, p.Masked())
			}
		case "peer.persistentkeepalive":
			if strings.EqualFold(value, "off") {
				c.keepalive = 0
			} else {
				c.keepalive, err = parseUint16(value)
			}
		default:
			// In particular: do not execute PreUp/PostUp/PreDown/PostDown;
			// do not silently ignore Table, FwMark, SaveConfig or Amnezia keys.
			return nil, invalid("unsupported_directive", "Unsupported directive. Shell hooks, routing-table options, and non-WireGuard extensions are not accepted.")
		}
		if err != nil {
			return nil, invalid("invalid_value", "A key or numeric configuration value is invalid.")
		}
	}
	if scanner.Err() != nil {
		return nil, invalid("line_too_long", "Configuration contains a line longer than 4 KiB.")
	}
	if !interfaceSeen || !peerSeen || !seen["interface.privatekey"] || !seen["peer.publickey"] || !seen["peer.endpoint"] || len(c.addresses) == 0 || len(c.routes) == 0 {
		return nil, invalid("missing_required", "PrivateKey, Address, PublicKey, Endpoint and AllowedIPs are required.")
	}
	for _, addr := range c.addresses {
		if addr.Is6() && c.mtu < 1280 {
			return nil, invalid("ipv6_mtu", "IPv6 requires an MTU of at least 1280 bytes.")
		}
	}
	if len(c.dns) == 0 {
		return nil, invalid("dns_required", "At least one DNS server is required; system DNS fallback is disabled.")
	}
	for _, addr := range c.dns {
		if !c.hasFamily(addr) || !c.allows(addr) {
			return nil, invalid("dns_not_routed", "Every DNS server must have a configured address family and be covered by AllowedIPs.")
		}
	}
	return c, nil
}

// Like wg-quick, a bare numeric address denotes one host, never a subnet.
func parsePrefix(value string) (netip.Prefix, error) {
	if strings.Contains(value, "/") {
		return netip.ParsePrefix(value)
	}
	addr, err := netip.ParseAddr(value)
	if err != nil || addr.Zone() != "" {
		return netip.Prefix{}, errors.New("invalid address")
	}
	return netip.PrefixFrom(addr, addr.BitLen()), nil
}

func parseKey(value string, allowZero bool) ([32]byte, error) {
	var key [32]byte
	decoded, err := base64.StdEncoding.Strict().DecodeString(value)
	defer clear(decoded)
	if err != nil || len(decoded) != len(key) {
		return key, errors.New("invalid key")
	}
	copy(key[:], decoded)
	if !allowZero && key == ([32]byte{}) {
		return key, errors.New("zero key")
	}
	return key, nil
}

func parseUint16(value string) (uint16, error) {
	n, err := strconv.ParseUint(value, 10, 16)
	return uint16(n), err
}

func isUnicast(addr netip.Addr) bool {
	if !addr.IsValid() || addr.Zone() != "" {
		return false
	}
	addr = addr.Unmap()
	return (addr.IsGlobalUnicast() || addr.IsLoopback()) && !addr.IsLinkLocalUnicast()
}

func containsAddr(addrs []netip.Addr, target netip.Addr) bool {
	for _, addr := range addrs {
		if addr == target {
			return true
		}
	}
	return false
}

func (c *config) hasFamily(addr netip.Addr) bool {
	for _, local := range c.addresses {
		if addr.Is4() == local.Is4() {
			return true
		}
	}
	return false
}

func (c *config) allows(addr netip.Addr) bool {
	addr = addr.Unmap()
	for _, route := range c.routes {
		if route.Contains(addr) {
			return true
		}
	}
	return false
}

func (c *config) clearKeys() {
	clear(c.privateKey[:])
	clear(c.publicKey[:])
	clear(c.psk[:])
}

// uapi is internal only: its result includes the private key. Never expose,
// persist, or log it, and never expose Device.IpcGet() through the JNI API.
func (c *config) uapi() string {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\nlisten_port=%d\nreplace_peers=true\npublic_key=%s\nreplace_allowed_ips=true\nendpoint=%s\npersistent_keepalive_interval=%d\n",
		hex.EncodeToString(c.privateKey[:]), c.listenPort, hex.EncodeToString(c.publicKey[:]), c.endpoint.String(), c.keepalive)
	if c.hasPSK {
		fmt.Fprintf(&b, "preshared_key=%s\n", hex.EncodeToString(c.psk[:]))
	}
	for _, prefix := range c.routes {
		fmt.Fprintf(&b, "allowed_ip=%s\n", prefix.String())
	}
	return b.String()
}
