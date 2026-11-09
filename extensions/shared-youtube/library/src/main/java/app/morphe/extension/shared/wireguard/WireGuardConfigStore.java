// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import app.morphe.extension.shared.Logger;

/**
 * Profiles contain private keys. They never enter Morphe preferences, logs, exports or backups.
 * Encrypted bytes live in noBackupFilesDir and their AES key lives in AndroidKeyStore.
 */
final class WireGuardConfigStore {
    private static final String FILE_NAME = "morphe-wireguard-v1.bin";
    private static final String KEY_ALIAS = "morphe.wireguard.profile.v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final byte[] MAGIC = {'M', 'W', 'G', 'C', 1};
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_STORED_BYTES = ConfigText.MAX_BYTES + MAGIC.length
            + IV_BYTES + TAG_BITS / 8;

    private WireGuardConfigStore() {
    }

    static synchronized boolean hasProfile(Context context) {
        // openRead restores AtomicFile's previous committed copy after an interrupted write.
        // Looking only at the base path would hide that recoverable profile on older Android.
        try (InputStream ignored = storage(context).openRead()) {
            return true;
        } catch (FileNotFoundException ex) {
            return false;
        } catch (IOException ex) {
            Logger.printException(
                    () -> "WireGuard profile availability check failed");
            return false;
        }
    }

    static synchronized void save(Context context, String config)
            throws GeneralSecurityException, IOException {
        byte[] plaintext = config.getBytes(StandardCharsets.UTF_8);
        try {
            ConfigText.decode(plaintext);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true));
            cipher.updateAAD(MAGIC);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                throw new GeneralSecurityException("Unsupported profile encryption IV");
            }
            byte[] ciphertext = cipher.doFinal(plaintext);
            AtomicFile file = storage(context);
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(MAGIC);
                stream.write(iv);
                stream.write(ciphertext);
                file.finishWrite(stream);
            } catch (IOException ex) {
                if (stream != null) file.failWrite(stream);
                throw new IOException("Could not store profile");
            }
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static synchronized String load(Context context)
            throws GeneralSecurityException, IOException {
        byte[] encrypted;
        try (InputStream input = storage(context).openRead()) {
            encrypted = ConfigText.readLimited(input, MAX_STORED_BYTES);
        }
        if (encrypted.length < MAGIC.length + IV_BYTES + TAG_BITS / 8
                || !Arrays.equals(MAGIC, Arrays.copyOfRange(encrypted, 0, MAGIC.length))) {
            throw new GeneralSecurityException("Invalid encrypted profile");
        }
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false),
                new GCMParameterSpec(TAG_BITS, encrypted, MAGIC.length, IV_BYTES));
        cipher.updateAAD(MAGIC);
        final int ciphertextOffset = MAGIC.length + IV_BYTES;
        byte[] plaintext = cipher.doFinal(encrypted, ciphertextOffset,
                encrypted.length - ciphertextOffset);
        try {
            return ConfigText.decode(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static synchronized void delete(Context context)
            throws GeneralSecurityException, IOException {
        AtomicFile file = storage(context);
        file.delete();
        KeyStore keyStore = openKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        if (file.getBaseFile().exists()) throw new IOException("Could not remove profile");
    }

    private static AtomicFile storage(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), FILE_NAME));
    }

    private static KeyStore openKeyStore() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }

    private static SecretKey encryptionKey(boolean create)
            throws GeneralSecurityException, IOException {
        KeyStore keyStore = openKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        if (!create) throw new GeneralSecurityException("Profile key is unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
