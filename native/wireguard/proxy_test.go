// SPDX-License-Identifier: GPL-3.0-only

package wireguard

import (
	"io"
	"net"
	"testing"
	"time"
)

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
