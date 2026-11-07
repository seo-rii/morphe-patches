// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/** Process-latched app-only WireGuard proxy. Restart is required for all profile/mode changes. */
public final class WireGuardProxy {
    public enum State {
        DISABLED, NOT_STARTED, READY, BLOCKED
    }

    // A saved setting must not change routing underneath already constructed Cronet engines.
    private static final boolean ENABLED_THIS_PROCESS =
            isPatchIncluded() && SharedYouTubeSettings.WIREGUARD_ENABLED.get();
    private static final String USERNAME = "wireguard";
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final int TOKEN_BYTES = 32;
    private static final int FAILURE_LISTEN_BACKLOG = 16;
    private static final byte[] FAILURE_RESPONSE = ("HTTP/1.1 503 Service Unavailable\r\n"
            + "Connection: close\r\nContent-Length: 0\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);

    private static volatile State state = ENABLED_THIS_PROCESS
            ? State.NOT_STARTED : State.DISABLED;
    private static volatile boolean restartRequired;
    @Nullable
    private static Endpoint endpoint;
    @Nullable
    private static ServerSocket failureListener;

    private WireGuardProxy() {
    }

    /** Injection point. Removing the optional patch must not reactivate a saved setting. */
    public static boolean isPatchIncluded() {
        return false;
    }

    public static boolean isEnabled() {
        return ENABLED_THIS_PROCESS;
    }

    public static State state() {
        return state;
    }

    public static boolean isRestartRequired() {
        return restartRequired
                || (isPatchIncluded()
                && ENABLED_THIS_PROCESS != SharedYouTubeSettings.WIREGUARD_ENABLED.get());
    }

    static void markRestartRequired() {
        restartRequired = true;
    }

    /**
     * The port and authentication token stay fixed until process death. READY is only local
     * listener readiness; it is not a claim about a remote handshake or Internet connectivity.
     */
    public static synchronized Endpoint endpoint() {
        if (!ENABLED_THIS_PROCESS) throw new IllegalStateException("WireGuard is disabled");
        if (endpoint != null) return endpoint;

        byte[] randomBytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        String password = Base64.encodeToString(randomBytes,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        Arrays.fill(randomBytes, (byte) 0);
        String authorization = "Basic " + Base64.encodeToString(
                (USERNAME + ":" + password).getBytes(StandardCharsets.US_ASCII), Base64.NO_WRAP);
        try {
            String config = WireGuardConfigStore.load(Utils.getContext());
            final int port = NativeTunnel.start(config, USERNAME, password, 0);
            if (port < 1 || port > 65535) {
                NativeTunnel.stop();
                throw new IllegalStateException("Invalid local proxy port");
            }
            endpoint = new Endpoint(port, authorization);
            state = State.READY;
        } catch (Exception | LinkageError ex) {
            // Do not log exception messages: a provider/JNI implementation might include secrets.
            Logger.printException(() -> "WireGuard startup failed; intercepted requests are blocked");
            state = State.BLOCKED;
            endpoint = new Endpoint(startFailureListener(), authorization);
        }
        return endpoint;
    }

    private static int startFailureListener() {
        ServerSocket listener;
        try {
            listener = new ServerSocket();
        } catch (IOException ex) {
            throw new IllegalStateException("WireGuard cannot safely establish a local proxy");
        }
        boolean started = false;
        try {
            listener.bind(new InetSocketAddress(
                    InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), FAILURE_LISTEN_BACKLOG);
            Thread thread = new Thread(() -> {
                while (!listener.isClosed()) {
                    Socket accepted;
                    try {
                        accepted = listener.accept();
                    } catch (IOException ex) {
                        if (!listener.isClosed()) {
                            Logger.printException(() -> "WireGuard blocking listener stopped");
                        }
                        // Keep the port reserved on failure; never let another local process
                        // replace this proxy while existing Cronet engines still reference it.
                        break;
                    }
                    try (Socket connection = accepted) {
                        connection.getOutputStream().write(FAILURE_RESPONSE);
                    } catch (IOException ex) {
                        // A client may close before reading the rejection. Continue serving
                        // other requests instead of letting one reset stop the blocking proxy.
                        Logger.printDebug(() -> "WireGuard blocked-proxy response was not delivered");
                    }
                }
            }, "morphe-wireguard-blocked");
            thread.setDaemon(true);
            thread.start();
            started = true;
            failureListener = listener;
            return listener.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("WireGuard cannot safely establish a local proxy");
        } finally {
            // Failed bind or thread startup must not leak the socket. After successful startup
            // the port remains reserved until process death, including a later accept failure.
            if (!started) {
                try {
                    listener.close();
                } catch (IOException ex) {
                    Logger.printException(() -> "WireGuard blocking listener cleanup failed");
                }
            }
        }
    }

    public static final class Endpoint {
        public final String host = LOOPBACK_HOST;
        public final int port;
        public final String authorization;

        private Endpoint(int port, String authorization) {
            this.port = port;
            this.authorization = authorization;
        }

        // Intentionally no toString(): it must never expose authentication credentials.
    }
}
