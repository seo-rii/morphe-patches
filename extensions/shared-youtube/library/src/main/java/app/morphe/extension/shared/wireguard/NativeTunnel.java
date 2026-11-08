// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

/**
 * The native library uses a userspace TCP/IP stack, not Android VpnService or a system TUN.
 * This class must not be initialized while the WireGuard setting is disabled.
 */
public final class NativeTunnel {
    static {
        System.loadLibrary("morphewireguard");
    }

    private NativeTunnel() {
    }

    /** Returns the bound loopback port. It does not prove a remote handshake succeeded. */
    public static native int start(String config, String username, String password, int port);

    public static native void stop();

    /** Checks a profile without making network connections or executing configuration hooks. */
    public static native void validate(String config);

    /** JNI validation supplies fixed diagnostic text, never profile values or parser causes. */
    public static final class InvalidProfileException extends IllegalArgumentException {
        public InvalidProfileException(String message) {
            super(message);
        }
    }
}
