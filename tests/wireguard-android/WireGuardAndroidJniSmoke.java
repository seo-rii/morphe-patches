// SPDX-License-Identifier: GPL-3.0-only
import app.morphe.extension.shared.wireguard.NativeTunnel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Android/ART JNI smoke only. Does not patch or test YouTube or Music. */
public final class WireGuardAndroidJniSmoke {
    private static final String USER = "android-jni-test";
    private static final String PASSWORD = "public-synthetic-proxy-test-password";
    private static int checks;

    private static String key(int offset) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (offset + i);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String config() {
        // Public synthetic keys; no server account or successful handshake.
        return "# UTF-8: 한글 🦊\n[Interface]\nPrivateKey = " + key(1)
                + "\nAddress = 10.77.0.2/32\nDNS = 10.77.0.1\n[Peer]\nPublicKey = " + key(33)
                + "\nAllowedIPs = 10.77.0.0/24\nEndpoint = 127.0.0.1:51820\n";
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void rejects(String input) {
        try {
            NativeTunnel.validate(input);
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() == null || !expected.getMessage().contains(key(1)),
                    "Error disclosed synthetic key text");
            return;
        }
        throw new AssertionError("Invalid config was accepted");
    }

    private static String request(int port, String password) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            String auth = password == null ? "" : "Proxy-Authorization: Basic "
                    + Base64.getEncoder().encodeToString((USER + ":" + password)
                    .getBytes(StandardCharsets.US_ASCII)) + "\r\n";
            String request = "CONNECT 203.0.113.7:443 HTTP/1.1\r\nHost: 203.0.113.7:443\r\n"
                    + auth + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
        }
    }

    public static void main(String[] args) throws Exception {
        NativeTunnel.validate(config());
        checks++;
        NativeTunnel.validate("\ufeff" + config());
        checks++;
        rejects(null);
        rejects("");
        rejects(config() + "\u0000");
        rejects(config() + "# \ud800\n");
        rejects(config().replace("127.0.0.1:51820", "example.test:51820"));
        rejects(config().replace("[Peer]", "PostUp = unsupported-test-hook\n[Peer]"));
        char[] oversized = new char[65537];
        java.util.Arrays.fill(oversized, 'x');
        rejects(new String(oversized));
        NativeTunnel.stop();
        NativeTunnel.stop();
        checks++;

        final int port = NativeTunnel.start(config(), USER, PASSWORD, 0);
        try {
            check(port > 0 && port <= 65535, "Invalid proxy port");
            try {
                NativeTunnel.start(config(), USER, PASSWORD, 0);
                throw new AssertionError("Duplicate start was accepted");
            } catch (IllegalStateException expected) {
                checks++;
            }
            check(request(port, null).startsWith("HTTP/1.1 407"), "Missing auth was accepted");
            check(request(port, "wrong-password").startsWith("HTTP/1.1 407"), "Wrong auth was accepted");
            check(request(port, PASSWORD).startsWith("HTTP/1.1 502"), "Out-of-route dial was accepted");
        } finally {
            NativeTunnel.stop();
        }
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                throw new AssertionError("Listener survived stop");
            } catch (java.io.IOException expected) {
                checks++;
            }
        }
        for (int i = 0; i < 3; i++) {
            try {
                check(NativeTunnel.start(config(), USER, PASSWORD, 0) > 0, "Restart failed");
            } finally {
                NativeTunnel.stop();
            }
        }
        System.out.println("Android/ART JNI smoke passed: " + checks + " checks");
        System.out.println("No YouTube/Music patching, app UI, remote handshake, DNS or playback acceptance was run.");
    }
}
