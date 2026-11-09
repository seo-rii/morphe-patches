// SPDX-License-Identifier: GPL-3.0-only

package wireguard

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/conn/bindtest"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

const testUser = "test-app"
const testPassword = "test-only-random-looking-password-32"

type testPeer struct {
	config       string
	stack        *netstack.Net
	dev          *device.Device
	dnsQueries   atomic.Int64
	requests     atomic.Int64
	authLeaked   atomic.Bool
	httpPort     string
	httpsPort    string
	certificates *x509.CertPool
	clientBind   conn.Bind // nil selects production Start and real UDP.
}

// newTestPeer creates an actual WireGuard server in a second userspace network
// stack. Only outer UDP packets use host networking, bound to an ephemeral port.
// HTTP and DNS servers live at private, virtual addresses. No root, /dev/net/tun,
// external VPN accounts, outbound Internet traffic, or persisted keys are used.
func newTestPeer(t *testing.T) *testPeer {
	return newTestPeerWithBind(t, conn.NewDefaultBind(), nil, false)
}

// newMemoryTestPeer tests the same encryption/netstack/proxy with WireGuard's
// upstream ChannelBind. It performs no UDP or host route-monitoring operations
// and is explicitly separate from the real-UDP integration tests above.
func newMemoryTestPeer(t *testing.T) *testPeer {
	binds := bindtest.NewChannelBinds()
	return newTestPeerWithBind(t, binds[0], binds[1], false)
}

func (p *testPeer) start(t *testing.T) *Engine {
	t.Helper()
	var engine *Engine
	var err error
	if p.clientBind == nil {
		engine, err = Start(p.config, testUser, testPassword, 0)
	} else {
		engine, err = startWithBind(p.config, testUser, testPassword, 0, p.clientBind)
	}
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(engine.Close)
	return engine
}

func newTestPeerWithBind(t *testing.T, serverBind, clientBind conn.Bind, ipv6 bool) *testPeer {
	t.Helper()
	serverKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	clientKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serverIP := netip.MustParseAddr("10.77.0.1")
	clientAddress, allowedIPs := "10.77.0.2/32", "10.77.0.0/24"
	if ipv6 {
		serverIP = netip.MustParseAddr("fd77::1")
		clientAddress, allowedIPs = "fd77::2/128", "fd77::/64"
	}
	tun, stack, err := netstack.CreateNetTUN([]netip.Addr{serverIP}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tun, serverBind, device.NewLogger(device.LogLevelSilent, ""))
	t.Cleanup(func() { closeWireGuardDevice(dev) })
	uapi := fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\nallowed_ip=%s\n", hex.EncodeToString(serverKey.Bytes()), hex.EncodeToString(clientKey.PublicKey().Bytes()), clientAddress)
	if err := dev.IpcSet(uapi); err != nil {
		t.Fatal("test peer configuration failed")
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	state, err := dev.IpcGet()
	if err != nil {
		t.Fatal("test peer state unavailable")
	}
	var port string
	for _, line := range strings.Split(state, "\n") {
		if strings.HasPrefix(line, "listen_port=") {
			port = strings.TrimPrefix(line, "listen_port=")
		}
	}
	if port == "" || port == "0" {
		t.Fatal("test peer has no UDP port")
	}
	p := &testPeer{stack: stack, dev: dev, clientBind: clientBind}
	p.config = fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = %s\nDNS = %s\n\n[Peer]\nPublicKey = %s\nAllowedIPs = %s\nEndpoint = 127.0.0.1:%s\n", base64.StdEncoding.EncodeToString(clientKey.Bytes()), clientAddress, serverIP.String(), base64.StdEncoding.EncodeToString(serverKey.PublicKey().Bytes()), allowedIPs, port)
	dns, err := stack.ListenUDPAddrPort(netip.AddrPortFrom(serverIP, 53))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = dns.Close() })
	go func() {
		packet := make([]byte, 4096)
		for {
			n, client, err := dns.ReadFrom(packet)
			if err != nil {
				return
			}
			var request dnsmessage.Message
			if request.Unpack(packet[:n]) != nil {
				continue
			}
			p.dnsQueries.Add(1)
			response := dnsmessage.Message{Header: dnsmessage.Header{ID: request.ID, Response: true, Authoritative: true, RecursionDesired: request.RecursionDesired, RecursionAvailable: true}, Questions: request.Questions}
			for _, question := range request.Questions {
				if question.Type == dnsmessage.TypeA && serverIP.Is4() {
					ip := serverIP.As4()
					if question.Name.String() == "blocked.test." {
						ip = [4]byte{203, 0, 113, 50}
					}
					response.Answers = append(response.Answers, dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 1}, Body: &dnsmessage.AResource{A: ip}})
				}
				if question.Type == dnsmessage.TypeAAAA && serverIP.Is6() {
					ip := serverIP.As16()
					if question.Name.String() == "blocked.test." {
						ip = netip.MustParseAddr("2001:db8::50").As16()
					}
					response.Answers = append(response.Answers, dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: question.Name, Type: dnsmessage.TypeAAAA, Class: dnsmessage.ClassINET, TTL: 1}, Body: &dnsmessage.AAAAResource{AAAA: ip}})
				}
			}
			encoded, err := response.Pack()
			if err == nil {
				_, _ = dns.WriteTo(encoded, client)
			}
		}
	}()
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p.requests.Add(1)
		if r.Header.Get("Proxy-Authorization") != "" || r.Header.Get("Proxy-Connection") != "" || r.Header.Get("X-Hop-Only") != "" {
			p.authLeaked.Store(true)
		}
		if r.URL.Path == "/stream" {
			_, _ = w.Write(bytes.Repeat([]byte("wireguard-stream\n"), 65536))
			return
		}
		if r.Method == http.MethodPost {
			_, _ = io.Copy(w, r.Body)
			return
		}
		_, _ = io.WriteString(w, "hello over encrypted WireGuard")
	})
	httpListener, err := stack.ListenTCPAddrPort(netip.AddrPortFrom(serverIP, 0))
	if err != nil {
		t.Fatal(err)
	}
	_, p.httpPort, _ = net.SplitHostPort(httpListener.Addr().String())
	httpServer := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second, ErrorLog: log.New(io.Discard, "", 0)}
	t.Cleanup(func() { _ = httpServer.Close() })
	go func() { _ = httpServer.Serve(httpListener) }()
	tlsCert, roots := makeTestCertificate(t)
	p.certificates = roots
	tlsListener, err := stack.ListenTCPAddrPort(netip.AddrPortFrom(serverIP, 0))
	if err != nil {
		t.Fatal(err)
	}
	_, p.httpsPort, _ = net.SplitHostPort(tlsListener.Addr().String())
	httpsServer := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second, ErrorLog: log.New(io.Discard, "", 0)}
	t.Cleanup(func() { _ = httpsServer.Close() })
	go func() {
		_ = httpsServer.Serve(tls.NewListener(tlsListener, &tls.Config{Certificates: []tls.Certificate{tlsCert}, MinVersion: tls.VersionTLS12}))
	}()
	return p
}

func makeTestCertificate(t *testing.T) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "media.test"}, DNSNames: []string{"media.test"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	certDER, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})
	cert, err := tls.X509KeyPair(certPEM, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}))
	if err != nil {
		t.Fatal(err)
	}
	roots := x509.NewCertPool()
	roots.AppendCertsFromPEM(certPEM)
	return cert, roots
}

func proxyClient(t *testing.T, e *Engine, roots *x509.CertPool) *http.Client {
	t.Helper()
	proxyURL := &url.URL{Scheme: "http", Host: net.JoinHostPort("127.0.0.1", strconv.Itoa(e.Port())), User: url.UserPassword(testUser, testPassword)}
	transport := &http.Transport{Proxy: http.ProxyURL(proxyURL), TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}}
	t.Cleanup(transport.CloseIdleConnections)
	return &http.Client{Transport: transport, Timeout: 10 * time.Second}
}

func readResponse(t *testing.T, client *http.Client, address string) string {
	t.Helper()
	response, err := client.Get(address)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status %d: %s", response.StatusCode, body)
	}
	return string(body)
}

func TestWireGuardEndToEndHTTPDNSAndTLS(t *testing.T) {
	testHTTPDNSAndTLS(t, newTestPeer(t))
}

func TestMemoryWireGuardHTTPDNSAndTLS(t *testing.T) {
	testHTTPDNSAndTLS(t, newMemoryTestPeer(t))
}

func TestMemoryIPv6HTTPDNSAndTLS(t *testing.T) {
	binds := bindtest.NewChannelBinds()
	testHTTPDNSAndTLS(t, newTestPeerWithBind(t, binds[0], binds[1], true))
}

func testHTTPDNSAndTLS(t *testing.T, peer *testPeer) {
	engine := peer.start(t)
	client := proxyClient(t, engine, peer.certificates)
	if got := readResponse(t, client, "http://media.test:"+peer.httpPort+"/"); got != "hello over encrypted WireGuard" {
		t.Fatalf("unexpected HTTP body: %q", got)
	}
	upload := "request body through the userspace tunnel"
	request, err := http.NewRequest(http.MethodPost, "http://media.test:"+peer.httpPort+"/", strings.NewReader(upload))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Connection", "X-Hop-Only")
	request.Header.Set("X-Hop-Only", "must-not-reach-upstream")
	request.Header.Set("Proxy-Connection", "keep-alive")
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	returned, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || response.StatusCode != http.StatusOK || string(returned) != upload {
		t.Fatal("HTTP request body forwarding failed")
	}
	if got := readResponse(t, client, "https://media.test:"+peer.httpsPort+"/"); got != "hello over encrypted WireGuard" {
		t.Fatalf("unexpected CONNECT/TLS body: %q", got)
	}
	stream := readResponse(t, client, "https://media.test:"+peer.httpsPort+"/stream")
	if stream != strings.Repeat("wireguard-stream\n", 65536) {
		t.Fatal("stream was truncated or corrupted")
	}
	if peer.dnsQueries.Load() == 0 {
		t.Fatal("test did not exercise DNS inside the WireGuard tunnel")
	}
	if peer.authLeaked.Load() {
		t.Fatal("local proxy authentication or hop-by-hop headers reached the remote server")
	}
	state, err := engine.device.IpcGet()
	if err != nil || !strings.Contains(state, "last_handshake_time_sec=") || strings.Contains(state, "last_handshake_time_sec=0\n") {
		t.Fatal("a real WireGuard handshake was not observed")
	}
}

func TestProxyAuthenticationBeforeRemoteDial(t *testing.T) {
	testAuthenticationBeforeRemoteDial(t, newTestPeer(t))
}

func TestMemoryAuthenticationBeforeRemoteDial(t *testing.T) {
	testAuthenticationBeforeRemoteDial(t, newMemoryTestPeer(t))
}

func testAuthenticationBeforeRemoteDial(t *testing.T, peer *testPeer) {
	engine := peer.start(t)
	proxyURL := &url.URL{Scheme: "http", Host: net.JoinHostPort("127.0.0.1", strconv.Itoa(engine.Port()))}
	transport := &http.Transport{Proxy: http.ProxyURL(proxyURL)}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 3 * time.Second}
	response, err := client.Get("http://media.test:" + peer.httpPort)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusProxyAuthRequired || peer.dnsQueries.Load() != 0 || peer.requests.Load() != 0 {
		t.Fatal("unauthorized request was not rejected before remote activity")
	}
}

func TestProxyHonorsRoutesAfterDNS(t *testing.T) {
	testHonorsRoutesAfterDNS(t, newTestPeer(t))
}

func TestMemoryHonorsRoutesAfterDNS(t *testing.T) {
	testHonorsRoutesAfterDNS(t, newMemoryTestPeer(t))
}

func testHonorsRoutesAfterDNS(t *testing.T, peer *testPeer) {
	engine := peer.start(t)
	client := proxyClient(t, engine, nil)
	response, err := client.Get("http://blocked.test:80/")
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusBadGateway || peer.requests.Load() != 0 {
		t.Fatal("out-of-route destination did not fail closed")
	}
}

func TestNoSystemNetworkFallback(t *testing.T) {
	testNoSystemNetworkFallback(t, nil)
}

func TestMemoryNoSystemNetworkFallback(t *testing.T) {
	binds := bindtest.NewChannelBinds()
	testNoSystemNetworkFallback(t, binds[0])
}

func testNoSystemNetworkFallback(t *testing.T, bind conn.Bind) {
	var hostHits atomic.Int64
	host := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hostHits.Add(1) }))
	defer host.Close()
	config := strings.ReplaceAll(fixtureConfig(), "10.77.0.0/24", "0.0.0.0/0")
	var engine *Engine
	var err error
	if bind == nil {
		engine, err = Start(config, testUser, testPassword, 0)
	} else {
		engine, err = startWithBind(config, testUser, testPassword, 0, bind)
	}
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	client := proxyClient(t, engine, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, host.URL, nil)
	response, _ := client.Do(request)
	if response != nil {
		response.Body.Close()
		if response.StatusCode == http.StatusOK {
			t.Fatal("request unexpectedly reached an ordinary host socket")
		}
	}
	if hostHits.Load() != 0 {
		t.Fatal("traffic leaked to the host network")
	}
}

func TestConnectPreservesBufferedBytesAndStopClosesStream(t *testing.T) {
	testConnectPreservesBufferedBytesAndStopClosesStream(t, newTestPeer(t))
}

func TestMemoryConnectPreservesBufferedBytesAndStopClosesStream(t *testing.T) {
	testConnectPreservesBufferedBytesAndStopClosesStream(t, newMemoryTestPeer(t))
}

func testConnectPreservesBufferedBytesAndStopClosesStream(t *testing.T, peer *testPeer) {
	engine := peer.start(t)
	socket, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(engine.Port())), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	_ = socket.SetDeadline(time.Now().Add(10 * time.Second))
	credential := base64.StdEncoding.EncodeToString([]byte(testUser + ":" + testPassword))
	_, _ = fmt.Fprintf(socket, "CONNECT media.test:%s HTTP/1.1\r\nHost: media.test:%s\r\nProxy-Authorization: Basic %s\r\n\r\nGET / HTTP/1.1\r\nHost: media.test\r\n\r\n", peer.httpPort, peer.httpPort, credential)
	reader := bufio.NewReader(socket)
	response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil || response.StatusCode != http.StatusOK {
		t.Fatal("CONNECT failed")
	}
	response, err = http.ReadResponse(reader, &http.Request{Method: http.MethodGet})
	if err != nil {
		t.Fatal("pipelined bytes after CONNECT were lost")
	}
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || string(body) != "hello over encrypted WireGuard" {
		t.Fatal("pipelined request failed")
	}
	engine.Close()
	_ = socket.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := reader.ReadByte(); err == nil {
		t.Fatal("CONNECT stream stayed open after engine close")
	}
	engine.Close() // Idempotent.
}

func TestConcurrentTrafficAndLifecycle(t *testing.T) {
	testConcurrentTrafficAndLifecycle(t, newTestPeer)
}

func TestMemoryConcurrentTrafficAndLifecycle(t *testing.T) {
	testConcurrentTrafficAndLifecycle(t, newMemoryTestPeer)
}

func testConcurrentTrafficAndLifecycle(t *testing.T, create func(*testing.T) *testPeer) {
	for round := 0; round < 3; round++ {
		peer := create(t)
		engine := peer.start(t)
		client := proxyClient(t, engine, peer.certificates)
		var workers sync.WaitGroup
		for i := 0; i < 8; i++ {
			workers.Add(1)
			go func() {
				defer workers.Done()
				response, err := client.Get("https://media.test:" + peer.httpsPort + "/")
				if err != nil {
					t.Errorf("concurrent request failed: %v", err)
					return
				}
				_, _ = io.Copy(io.Discard, response.Body)
				response.Body.Close()
			}()
		}
		workers.Wait()
		workers.Add(2)
		go func() { defer workers.Done(); engine.Close() }()
		go func() { defer workers.Done(); engine.Close() }()
		workers.Wait()
	}
}
