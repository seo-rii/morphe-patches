package app.morphe.extension.shared.wireguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ConfigTextTest {
    @Test
    public void acceptsUtf8AndOptionalBom() throws IOException {
        String config = "[Interface]\r\n# 프로필\r\n";
        assertEquals(config, ConfigText.read(bytes(config)));
        assertEquals(config, ConfigText.read(bytes("\uFEFF" + config)));
    }

    @Test
    public void acceptsSupplementaryUnicodeComments() throws IOException {
        String config = "[Interface]\n# Profile \uD83D\uDD12\n";
        assertEquals(config, ConfigText.read(bytes(config)));
        assertEquals(config, ConfigText.read(bytes("\uFEFF" + config)));
    }

    @Test
    public void rejectsEmptyBlankNulAndInvalidUtf8() {
        reject(new byte[0]);
        reject(" \n\t".getBytes(StandardCharsets.UTF_8));
        reject("\uFEFF".getBytes(StandardCharsets.UTF_8));
        reject("[Interface]\0".getBytes(StandardCharsets.UTF_8));
        reject(new byte[]{(byte) 0xc3, 0x28});
        reject(new byte[]{(byte) 0xff, (byte) 0xfe});
    }

    @Test
    public void enforcesByteLimitWithNoUnboundedRead() throws IOException {
        byte[] exact = new byte[ConfigText.MAX_BYTES];
        Arrays.fill(exact, (byte) 'a');
        assertEquals(ConfigText.MAX_BYTES, ConfigText.read(new ByteArrayInputStream(exact)).length());
        reject(Arrays.copyOf(exact, exact.length + 1));
        CountingStream endless = new CountingStream();
        try {
            ConfigText.read(endless);
            fail("Expected size rejection");
        } catch (IOException expected) {
            assertEquals(ConfigText.MAX_BYTES + 1, endless.count);
        }
    }

    @Test
    public void countsUtf8BytesRatherThanJavaCharacters() throws IOException {
        String exact = "\uD55C".repeat(ConfigText.MAX_BYTES / 3) + "a";
        assertEquals(ConfigText.MAX_BYTES, exact.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(exact, ConfigText.read(bytes(exact)));
        reject((exact + "\uD55C").getBytes(StandardCharsets.UTF_8));
        // The optional BOM still counts toward the document's byte limit.
        reject(("\uFEFF" + exact).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void makesProgressWhenBulkReadReturnsZero() throws IOException {
        InputStream unusual = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return available() > 0 ? 0 : -1;
            }
        };
        assertEquals("abc", ConfigText.read(unusual));
    }

    @Test
    public void enforcesByteLimitWhenBulkReadReturnsZero() {
        int[] count = {0};
        InputStream endless = new InputStream() {
            @Override
            public int read(byte[] buffer, int offset, int length) {
                return 0;
            }

            @Override
            public int read() {
                count[0]++;
                return 'a';
            }
        };
        try {
            ConfigText.read(endless);
            fail("Expected size rejection");
        } catch (IOException expected) {
            assertEquals(ConfigText.MAX_BYTES + 1, count[0]);
        }
    }

    @Test
    public void rejectsNullStreamsWithoutDocumentContent() {
        try {
            ConfigText.read(null);
            fail("Expected unavailable profile");
        } catch (IOException expected) {
            assertEquals("Profile is unavailable", expected.getMessage());
        }
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(byte[] bytes) {
        try {
            ConfigText.read(new ByteArrayInputStream(bytes));
            fail("Expected invalid profile rejection");
        } catch (IOException expected) {
            // Rejection is the assertion; no profile content is included in failure messages.
        }
    }

    private static final class CountingStream extends InputStream {
        private int count;

        @Override
        public int read() {
            count++;
            return 'a';
        }
    }
}
