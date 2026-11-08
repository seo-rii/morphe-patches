// SPDX-License-Identifier: GPL-3.0-only

package wireguard

import (
	"crypto/sha256"
	"crypto/subtle"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

func (e *Engine) serveHTTP(w http.ResponseWriter, r *http.Request) {
	// Error responses are bounded too; successful streaming responses refresh
	// the deadline on each write rather than imposing a maximum video length.
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(idleTimeout))
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil || host != "127.0.0.1" {
		http.Error(w, "Loopback clients only", http.StatusForbidden)
		return
	}
	// Comparing hashes is length-independent and keeps authentication checks
	// constant-time. Never log requests, authorization headers, or raw errors.
	provided := sha256.Sum256([]byte(r.Header.Get("Proxy-Authorization")))
	if subtle.ConstantTimeCompare(provided[:], e.authDigest[:]) != 1 {
		w.Header().Set("Proxy-Authenticate", `Basic realm="App WireGuard"`)
		w.Header().Set("Connection", "close")
		http.Error(w, "Proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	if e.ctx.Err() != nil {
		http.Error(w, "WireGuard proxy is stopped", http.StatusServiceUnavailable)
		return
	}
	if r.Method == http.MethodConnect {
		e.connect(w, r)
		return
	}
	e.forwardHTTP(w, r)
}

func (e *Engine) connect(w http.ResponseWriter, r *http.Request) {
	// CONNECT carries bytes end-to-end; no TLS interception, certificate changes,
	// SOCKS/UDP shim, or HTTP/3 traffic is provided by this implementation.
	if r.ContentLength > 0 || len(r.TransferEncoding) != 0 || r.URL.Path != "" || r.URL.RawQuery != "" || r.URL.User != nil {
		http.Error(w, "Invalid CONNECT request", http.StatusBadRequest)
		return
	}
	destination := r.Host
	if _, _, err := net.SplitHostPort(destination); err != nil {
		http.Error(w, "CONNECT requires host:port", http.StatusBadRequest)
		return
	}
	upstream, err := e.dialContext(r.Context(), "tcp", destination)
	if err != nil {
		http.Error(w, "WireGuard connection unavailable", http.StatusBadGateway)
		return
	}
	defer upstream.Close()
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "CONNECT is unavailable", http.StatusInternalServerError)
		return
	}
	client, buffered, err := hijacker.Hijack()
	if err != nil {
		return
	}
	defer func() {
		_ = client.Close()
		e.untrack(client)
	}()
	_ = client.SetDeadline(time.Now().Add(idleTimeout))
	if _, err := buffered.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	if err := buffered.Flush(); err != nil {
		return
	}
	clientIO := &idleConn{Conn: client, timeout: idleTimeout}
	// Drain only bytes already buffered by net/http, then use the wrapper for
	// socket reads so a stalled client cannot leave an unbounded blocked read.
	clientInput := io.MultiReader(io.LimitReader(buffered.Reader, int64(buffered.Reader.Buffered())), clientIO)
	var copies sync.WaitGroup
	copies.Add(2)
	go func() {
		defer copies.Done()
		if _, err := io.Copy(upstream, clientInput); err != nil {
			_ = upstream.Close()
			_ = client.Close()
			return
		}
		closeWrite(upstream)
	}()
	go func() {
		defer copies.Done()
		if _, err := io.Copy(clientIO, upstream); err != nil {
			_ = upstream.Close()
			_ = client.Close()
			return
		}
		closeWrite(client)
	}()
	copies.Wait()
}

func closeWrite(c net.Conn) {
	if half, ok := c.(interface{ CloseWrite() error }); ok {
		_ = half.CloseWrite()
	} else {
		_ = c.Close()
	}
}

// net/http no longer owns a CONNECT socket after Hijack. Both directions need
// deadlines: an upstream read deadline cannot interrupt a blocked client write.
type idleConn struct {
	net.Conn
	timeout time.Duration
}

func (c *idleConn) Read(p []byte) (int, error) {
	c.touch()
	n, err := c.Conn.Read(p)
	if n > 0 {
		c.touch()
	}
	return n, err
}

func (c *idleConn) Write(p []byte) (int, error) {
	c.touch()
	n, err := c.Conn.Write(p)
	if n > 0 {
		c.touch()
	}
	return n, err
}

func (c *idleConn) touch() {
	// Idle is connection-wide, not direction-wide. A long video download has
	// a blocked client read while its writes stay active; expiring that read
	// would incorrectly abort a healthy stream. net.Conn permits concurrent
	// deadline updates and applies them to operations already blocked in I/O.
	_ = c.Conn.SetDeadline(time.Now().Add(c.timeout))
}

type deadlineBody struct {
	io.ReadCloser
	controller *http.ResponseController
}

func (b *deadlineBody) Read(p []byte) (int, error) {
	_ = b.controller.SetReadDeadline(time.Now().Add(idleTimeout))
	return b.ReadCloser.Read(p)
}

type deadlineWriter struct {
	writer     io.Writer
	controller *http.ResponseController
}

func (w *deadlineWriter) Write(p []byte) (int, error) {
	_ = w.controller.SetWriteDeadline(time.Now().Add(idleTimeout))
	return w.writer.Write(p)
}

func (e *Engine) forwardHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Scheme != "http" || r.URL.Host == "" || r.URL.User != nil || r.Header.Get("Upgrade") != "" {
		http.Error(w, "Use an absolute HTTP URL or CONNECT", http.StatusBadRequest)
		return
	}
	outgoing := r.Clone(r.Context())
	outgoing.RequestURI = ""
	outgoing.Header = r.Header.Clone()
	controller := http.NewResponseController(w)
	if outgoing.Body != nil {
		outgoing.Body = &deadlineBody{ReadCloser: outgoing.Body, controller: controller}
	}
	stripHopHeaders(outgoing.Header)
	outgoing.Header.Del("Proxy-Authorization")
	outgoing.Header.Del("Proxy-Authenticate")
	response, err := e.transport.RoundTrip(outgoing)
	if err != nil {
		http.Error(w, "WireGuard request unavailable", http.StatusBadGateway)
		return
	}
	defer response.Body.Close()
	stripHopHeaders(response.Header)
	for name, values := range response.Header {
		for _, value := range values {
			w.Header().Add(name, value)
		}
	}
	w.WriteHeader(response.StatusCode)
	if _, err := io.Copy(&deadlineWriter{writer: w, controller: controller}, response.Body); err != nil {
		// Headers may already be sent. Abort instead of completing a truncated response.
		panic(http.ErrAbortHandler)
	}
}

func stripHopHeaders(h http.Header) {
	for _, value := range h.Values("Connection") {
		for _, field := range strings.Split(value, ",") {
			h.Del(strings.TrimSpace(field))
		}
	}
	for _, field := range []string{"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "TE", "Trailer", "Transfer-Encoding", "Upgrade"} {
		h.Del(field)
	}
}
