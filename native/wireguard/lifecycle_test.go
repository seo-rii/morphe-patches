// SPDX-License-Identifier: GPL-3.0-only

package wireguard

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/conn/bindtest"
)

// Only test outer packets are dropped. Production keeps the upstream UDP bind.
type packetDropBind struct {
	conn.Bind
	drop atomic.Bool
	sent chan struct{}
}

func (b *packetDropBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	select {
	case b.sent <- struct{}{}:
	default:
	}
	if b.drop.Load() {
		return nil
	}
	return b.Bind.Send(packets, endpoint)
}

func TestMemoryRecoversAfterOuterPacketLoss(t *testing.T) {
	binds := bindtest.NewChannelBinds()
	clientBind := &packetDropBind{Bind: binds[1]}
	peer := newTestPeerWithBind(t, binds[0], clientBind, false)
	engine := peer.start(t)
	client := proxyClient(t, engine, nil)
	client.Transport.(*http.Transport).DisableKeepAlives = true
	address := "http://media.test:" + peer.httpPort + "/"
	readResponse(t, client, address)
	requestsBefore := peer.requests.Load()
	clientBind.drop.Store(true)
	ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.Do(request)
	if response != nil {
		_, _ = io.Copy(io.Discard, response.Body)
		response.Body.Close()
	}
	if err == nil || peer.requests.Load() != requestsBefore {
		t.Fatal("request did not stop when outer WireGuard packets were dropped")
	}
	clientBind.drop.Store(false)
	if got := readResponse(t, client, address); got != "hello over encrypted WireGuard" {
		t.Fatal("encrypted traffic did not recover after packet delivery resumed")
	}
}

func TestMemoryStopCancelsPendingDialAndReleasesListener(t *testing.T) {
	binds := bindtest.NewChannelBinds()
	clientBind := &packetDropBind{Bind: binds[0], sent: make(chan struct{}, 1)}
	clientBind.drop.Store(true)
	engine, err := startWithBind(fixtureConfig(), testUser, testPassword, 0, clientBind)
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	dialDone := make(chan error, 1)
	go func() {
		connection, err := engine.dialContext(context.Background(), "tcp", "10.77.0.1:80")
		if connection != nil {
			connection.Close()
		}
		dialDone <- err
	}()
	select {
	case <-clientBind.sent:
	case <-time.After(3 * time.Second):
		t.Fatal("pending connection never attempted a WireGuard packet")
	}
	closed := make(chan struct{})
	go func() {
		engine.Close()
		close(closed)
	}()
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("stop did not finish with an unreachable peer")
	}
	select {
	case err := <-dialDone:
		if err == nil {
			t.Fatal("pending connection succeeded after stop")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("stop did not cancel the pending userspace connection")
	}
	if _, err := engine.dialContext(context.Background(), "tcp", "10.77.0.1:80"); !errors.Is(err, errClosed) {
		t.Fatal("stopped engine accepted a new connection")
	}
	listener, err := net.Listen("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(engine.Port())))
	if err != nil {
		t.Fatal("stop did not release the local listener")
	}
	listener.Close()
}
