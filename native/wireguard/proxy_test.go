// SPDX-License-Identifier: GPL-3.0-only

package wireguard

import (
	"bufio"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestProxyResponseBodyCompletion(t *testing.T) {
	for _, scheme := range []string{"http", "https"} {
		for _, tc := range []struct {
			name     string
			body     string
			complete bool
		}{
			{"complete-small", "hello", true},
			{"truncated-small", "hello", false},
			{"complete-stream", strings.Repeat("x", 8192), true},
			{"truncated-stream", strings.Repeat("x", 8192), false},
		} {
			t.Run(scheme+"/"+tc.name, func(t *testing.T) {
				peer := newMemoryTestPeer(t)
				listener, err := peer.stack.ListenTCPAddrPort(netip.MustParseAddrPort("10.77.0.1:0"))
				if err != nil {
					t.Fatal(err)
				}
				defer listener.Close()
				cert, roots := makeTestCertificate(t)
				var origin net.Listener = listener
				if scheme == "https" {
					origin = tls.NewListener(listener, &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12})
				}
				originDone := make(chan error, 1)
				go func() {
					connection, err := origin.Accept()
					if err != nil {
						originDone <- err
						return
					}
					defer connection.Close()
					_ = connection.SetDeadline(time.Now().Add(5 * time.Second))
					request, err := http.ReadRequest(bufio.NewReader(connection))
					if err != nil {
						originDone <- err
						return
					}
					_ = request.Body.Close()
					ending := ""
					if tc.complete {
						ending = "0\r\n\r\n"
					}
					// Closing without the final chunk simulates an interrupted origin.
					_, err = fmt.Fprintf(connection, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n%x\r\n%s\r\n%s", len(tc.body), tc.body, ending)
					originDone <- err
				}()
				engine := peer.start(t)
				client := proxyClient(t, engine, roots)
				_, port, err := net.SplitHostPort(listener.Addr().String())
				if err != nil {
					t.Fatal(err)
				}
				response, requestErr := client.Get(scheme + "://media.test:" + port + "/")
				select {
				case err := <-originDone:
					if err != nil {
						t.Fatalf("origin failed to send test response: %v", err)
					}
				case <-time.After(5 * time.Second):
					t.Fatal("origin did not finish sending test response")
				}
				if requestErr != nil {
					// An abort before a small HTTP response is flushed closes the
					// connection before the client can read any response headers.
					if scheme == "http" && !tc.complete && tc.name == "truncated-small" && errors.Is(requestErr, io.EOF) {
						return
					}
					t.Fatal(requestErr)
				}
				defer response.Body.Close()
				body, readErr := io.ReadAll(response.Body)
				if response.StatusCode != http.StatusOK || !strings.HasPrefix(tc.body, string(body)) {
					t.Fatalf("unexpected response: status=%d bytes=%d", response.StatusCode, len(body))
				}
				if tc.complete && (readErr != nil || string(body) != tc.body) {
					t.Fatalf("complete response failed: bytes=%d error=%v", len(body), readErr)
				}
				if !tc.complete && (len(body) == 0 || !errors.Is(readErr, io.ErrUnexpectedEOF)) {
					t.Fatalf("truncated response must fail the client read: bytes=%d error=%v", len(body), readErr)
				}
			})
		}
	}
}

func TestIdleClientReadAndWriteAreBounded(t *testing.T) {
	for _, operation := range []string{"read", "write"} {
		t.Run(operation, func(t *testing.T) {
			client, other := net.Pipe()
			defer client.Close()
			defer other.Close()
			wrapped := &idleConn{Conn: client, timeout: 25 * time.Millisecond}
			start := time.Now()
			var err error
			if operation == "read" {
				_, err = wrapped.Read(make([]byte, 1))
			} else {
				// The peer deliberately never reads, just like an authenticated
				// local client that abandons a CONNECT response body.
				_, err = wrapped.Write([]byte("unread CONNECT data"))
			}
			if timeout, ok := err.(net.Error); !ok || !timeout.Timeout() {
				t.Fatalf("expected bounded idle timeout, got %v", err)
			}
			if time.Since(start) > time.Second {
				t.Fatal("idle operation was not interrupted promptly")
			}
		})
	}
}

func TestIdleDeadlineTracksActivityInEitherDirection(t *testing.T) {
	client, other := net.Pipe()
	defer client.Close()
	defer other.Close()
	// A one-second margin keeps this real-time regression reliable while race
	// instrumentation and parallel native builds compete for CPU time.
	wrapped := &idleConn{Conn: client, timeout: time.Second}
	readDone := make(chan error, 1)
	go func() {
		_, err := wrapped.Read(make([]byte, 1))
		readDone <- err
	}()
	go func() { _, _ = io.Copy(io.Discard, other) }()
	// The client never sends additional request data, while response writes
	// keep flowing for longer than one timeout interval.
	for i := 0; i < 20; i++ {
		time.Sleep(60 * time.Millisecond)
		if _, err := wrapped.Write([]byte{1}); err != nil {
			t.Fatal(err)
		}
		select {
		case err := <-readDone:
			t.Fatalf("active one-way stream expired: %v", err)
		default:
		}
	}
	select {
	case err := <-readDone:
		if timeout, ok := err.(net.Error); !ok || !timeout.Timeout() {
			t.Fatalf("fully idle stream did not time out: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("fully idle connection stayed open")
	}
}

func TestIdleClientDeadlineSlides(t *testing.T) {
	client, other := net.Pipe()
	defer client.Close()
	defer other.Close()
	wrapped := &idleConn{Conn: client, timeout: time.Second}
	go func() {
		for i := 0; i < 20; i++ {
			time.Sleep(60 * time.Millisecond)
			if _, err := other.Write([]byte{1}); err != nil {
				return
			}
		}
	}()
	for i := 0; i < 20; i++ {
		if _, err := wrapped.Read(make([]byte, 1)); err != nil {
			t.Fatalf("active stream should outlive one idle interval: %v", err)
		}
	}
}
