// SPDX-License-Identifier: GPL-3.0-only
import app.morphe.extension.shared.wireguard.NativeTunnel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Host JNI checks. These do not substitute for Android/ART or real-server testing. */
public final class WireGuardJniSmoke {
    private static int checks;
    private static final String USER = "jni-test";
    private static final String PASSWORD = "test-only-proxy-password-not-a-vpn-secret";

    private static String key(int offset) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (offset + i);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String config() {
        // Deterministic test-only keys; the peer does not exist and no successful handshake is expected.
        return "# UTF-8 comment: 한글 🦊\n[Interface]\nPrivateKey = " + key(1)
                + "\nAddress = 10.77.0.2/32\nDNS = 10.77.0.1\n[Peer]\nPublicKey = " + key(33)
                + "\nAllowedIPs = 10.77.0.0/24\nEndpoint = 127.0.0.1:51820\n";
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            check(type.isInstance(error), "Wrong exception type: " + error.getClass().getName());
            check(error.getMessage() == null || (!error.getMessage().contains(key(1))
                    && !error.getMessage().contains("secret-hook-marker")), "Error leaked profile values");
            return;
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private static String request(int port, String auth) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(3000);
            OutputStream output = socket.getOutputStream();
            String headers = "CONNECT 203.0.113.7:443 HTTP/1.1\r\nHost: 203.0.113.7:443\r\n"
                    + (auth == null ? "" : "Proxy-Authorization: Basic " + auth + "\r\n")
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
        }
    }

    public static void main(String[] args) throws Exception {
        NativeTunnel.validate(config());
        checks++;
        NativeTunnel.validate("\ufeff" + config());
        checks++;
        rejects(IllegalArgumentException.class, () -> NativeTunnel.validate(null));
        rejects(IllegalArgumentException.class, () -> NativeTunnel.validate(""));
        rejects(IllegalArgumentException.class, () -> NativeTunnel.validate("x".repeat(65537)));
        rejects(IllegalArgumentException.class, () -> NativeTunnel.validate(config() + "\u0000"));
        rejects(IllegalArgumentException.class, () -> NativeTunnel.validate(config() + "# \ud800\n"));
        rejects(IllegalArgumentException.class, () -> NativeTunnel.validate(
                config().replace("[Peer]", "PostUp = secret-hook-marker\n[Peer]")));
        rejects(IllegalArgumentException.class, () -> NativeTunnel.validate(
                config().replace("127.0.0.1:51820", "example.test:51820")));
        rejects(IllegalArgumentException.class, () -> NativeTunnel.start(config(), USER, PASSWORD, -1));
        NativeTunnel.stop();
        NativeTunnel.stop();
        checks++;
        System.out.println("JNI validation and idle-stop checks passed: " + checks);
        if (args.length == 1 && args[0].equals("--validate-only")) {
            System.out.println("Network/lifecycle checks NOT EXECUTED (--validate-only).");
            return;
        }

        int port = NativeTunnel.start(config(), USER, PASSWORD, 0);
        try {
            check(port > 0 && port <= 65535, "Invalid bound port");
            rejects(IllegalStateException.class, () -> NativeTunnel.start(config(), USER, PASSWORD, 0));
            check(request(port, null).startsWith("HTTP/1.1 407"), "Unauthenticated client was not rejected");
            String wrong = Base64.getEncoder().encodeToString((USER + ":wrong-password")
                    .getBytes(StandardCharsets.US_ASCII));
            check(request(port, wrong).startsWith("HTTP/1.1 407"), "Wrong authentication was not rejected");
            String auth = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD)
                    .getBytes(StandardCharsets.US_ASCII));
            // Outside AllowedIPs: failure must be immediate, with no system dial fallback.
            check(request(port, auth).startsWith("HTTP/1.1 502"), "Out-of-route connection was not blocked");
        } finally {
            NativeTunnel.stop();
        }
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                throw new AssertionError("Proxy still listening after stop");
            } catch (java.io.IOException expected) {
                checks++;
            }
        }
        for (int i = 0; i < 5; i++) {
            check(NativeTunnel.start(config(), USER, PASSWORD, 0) > 0, "Restart failed");
            NativeTunnel.stop();
        }
        System.out.println("All host JNI checks passed: " + checks);
    }
}
