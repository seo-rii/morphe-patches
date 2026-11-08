// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded, strict document decoding shared by the picker and encrypted profile storage. */
final class ConfigText {
    static final int MAX_BYTES = 64 * 1024;
    private static final int READ_BUFFER_BYTES = 4096;

    private ConfigText() {
    }

    static String read(@Nullable InputStream input) throws IOException {
        byte[] bytes = readLimited(input, MAX_BYTES);
        try {
            return decode(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static byte[] readLimited(@Nullable InputStream input, int maxBytes) throws IOException {
        if (input == null) throw new IOException("Profile is unavailable");
        if (maxBytes < 1) throw new IllegalArgumentException("Invalid size limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(READ_BUFFER_BYTES, maxBytes + 1)];
        try {
            while (true) {
                final int count = input.read(buffer, 0, Math.min(buffer.length,
                        maxBytes - output.size() + 1));
                if (count < 0) break;
                if (count == 0) {
                    // InputStream implementations are allowed to return zero; make progress.
                    final int value = input.read();
                    if (value < 0) break;
                    if (output.size() == maxBytes) throw new IOException("Profile is too large");
                    output.write(value);
                } else {
                    if (count > maxBytes - output.size()) {
                        throw new IOException("Profile is too large");
                    }
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    static String decode(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Invalid profile size");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.charAt(0) == '\uFEFF') text = text.substring(1);
            if (text.trim().isEmpty() || text.indexOf('\0') >= 0) {
                throw new IOException("Invalid profile text");
            }
            return text;
        } catch (CharacterCodingException ex) {
            // Decoder exceptions do not include profile data. Do not echo document contents.
            throw new IOException("Profile must use UTF-8");
        }
    }
}
