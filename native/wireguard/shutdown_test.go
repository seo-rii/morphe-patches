// SPDX-License-Identifier: GPL-3.0-only

package wireguard

import (
	"context"
	"net/netip"
	"strconv"
	"sync"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// gatedShutdownTUN holds a real peer receiver inside Write. Its own Close waits
// for writes so a failing ordering assertion cannot trigger the upstream race.
type gatedShutdownTUN struct {
	tun.Device
	mu            sync.RWMutex
	writeOnce     sync.Once
	releaseOnce   sync.Once
	closeOnce     sync.Once
	writeStarted  chan struct{}
	writeReleased chan struct{}
	writeFinished chan struct{}
	closeStarted  chan struct{}
	closeErr      error
}

func (g *gatedShutdownTUN) Write(buffers [][]byte, offset int) (int, error) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	first := false
	g.writeOnce.Do(func() {
		first = true
		close(g.writeStarted)
		<-g.writeReleased
	})
	n, err := g.Device.Write(buffers, offset)
	if first {
		close(g.writeFinished)
	}
	return n, err
}

func (g *gatedShutdownTUN) releaseWrite() {
	g.releaseOnce.Do(func() { close(g.writeReleased) })
}

func (g *gatedShutdownTUN) Close() error {
	g.closeOnce.Do(func() {
		close(g.closeStarted)
		g.mu.Lock()
		defer g.mu.Unlock()
		g.closeErr = g.Device.Close()
	})
	return g.closeErr
}

func TestShutdownWaitsForPeerReceiverBeforeClosingTUN(t *testing.T) {
	peer := newMemoryTestPeer(t)
	config, err := parseConfig(peer.config)
	if err != nil {
		t.Fatal("could not parse generated test profile")
	}
	defer config.clearKeys()
	base, stack, err := netstack.CreateNetTUN(config.addresses, config.dns, config.mtu)
	if err != nil {
		t.Fatal(err)
	}
	gated := &gatedShutdownTUN{
		Device: base, writeStarted: make(chan struct{}), writeReleased: make(chan struct{}),
		writeFinished: make(chan struct{}), closeStarted: make(chan struct{}),
	}
	dev := device.NewDevice(gated, peer.clientBind, device.NewLogger(device.LogLevelSilent, ""))
	t.Cleanup(func() {
		gated.releaseWrite()
		closeWireGuardDevice(dev)
	})
	if err := dev.IpcSet(config.uapi()); err != nil {
		t.Fatal("could not configure generated test peer")
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	port, err := strconv.ParseUint(peer.httpPort, 10, 16)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	dialDone := make(chan struct{})
	go func() {
		defer close(dialDone)
		connection, _ := stack.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(netip.MustParseAddr("10.77.0.1"), uint16(port)))
		if connection != nil {
			_ = connection.Close()
		}
	}()
	awaitShutdownSignal(t, "peer receiver entering TUN.Write", gated.writeStarted)
	// Cancel the pending virtual connection before shutdown. Releasing the peer's
	// reply then exercises the same reset-generation path seen in the CI race.
	cancel()
	awaitShutdownSignal(t, "pending dial cancellation", dialDone)
	shutdownStarted, shutdownDone := make(chan struct{}), make(chan struct{})
	go func() {
		close(shutdownStarted)
		closeWireGuardDevice(dev)
		close(shutdownDone)
	}()
	awaitShutdownSignal(t, "shutdown invocation", shutdownStarted)
	closedEarly := false
	select {
	case <-gated.closeStarted:
		closedEarly = true
	case <-time.After(100 * time.Millisecond):
	}
	gated.releaseWrite()
	awaitShutdownSignal(t, "peer receiver finishing TUN.Write", gated.writeFinished)
	awaitShutdownSignal(t, "device shutdown", shutdownDone)
	if closedEarly {
		t.Fatal("TUN.Close started while a peer receiver was still inside TUN.Write")
	}
}

func awaitShutdownSignal(t *testing.T, operation string, signal <-chan struct{}) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for %s", operation)
	}
}
