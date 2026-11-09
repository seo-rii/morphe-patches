// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Real profile envelope and AES-GCM with host filesystem and a test-only key-store provider. */
public class WireGuardConfigStoreBoundaryTest {
    private static HostBoundaryFixture fixture;

    @BeforeClass
    public static void compile() throws Exception {
        Map<String, String> stubs = new LinkedHashMap<>();
        stubs.put("androidx.annotation.Nullable", "package androidx.annotation; public @interface Nullable {}");
        stubs.put("android.content.Context", """
                package android.content;
                public class Context {
                    private final java.io.File directory;
                    public Context(java.io.File directory) { this.directory = directory; }
                    public java.io.File getNoBackupFilesDir() { return directory; }
                }
                """);
        stubs.put("android.util.AtomicFile", """
                package android.util;
                import java.io.*;
                import java.nio.file.*;
                public class AtomicFile {
                    private final File base;
                    public AtomicFile(File base) { this.base = base; }
                    public File getBaseFile() { return base; }
                    public FileInputStream openRead() throws FileNotFoundException {
                        // Model Android API 26 AtomicFile's public recovery contract.
                        File backup = new File(base.getPath() + ".bak");
                        if (backup.isFile()) {
                            try { Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                            catch (IOException ex) { throw new FileNotFoundException("Recovery failed"); }
                        }
                        return new FileInputStream(base);
                    }
                    public FileOutputStream startWrite() throws IOException { return new FileOutputStream(base); }
                    public void finishWrite(FileOutputStream stream) throws IOException { stream.close(); }
                    public void failWrite(FileOutputStream stream) throws IOException { stream.close(); }
                    public void delete() { base.delete(); new File(base.getPath() + ".bak").delete(); }
                }
                """);
        stubs.put("android.security.keystore.KeyProperties", """
                package android.security.keystore;
                public class KeyProperties {
                    public static final int PURPOSE_ENCRYPT = 1, PURPOSE_DECRYPT = 2;
                    public static final String KEY_ALGORITHM_AES = "AES", BLOCK_MODE_GCM = "GCM",
                            ENCRYPTION_PADDING_NONE = "NoPadding";
                }
                """);
        stubs.put("android.security.keystore.KeyGenParameterSpec", """
                package android.security.keystore;
                public class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {
                    public final String alias;
                    public KeyGenParameterSpec(String alias) { this.alias = alias; }
                    public static class Builder {
                        private final String alias;
                        public Builder(String alias, int purposes) { this.alias = alias; }
                        public Builder setKeySize(int size) { return this; }
                        public Builder setBlockModes(String... modes) { return this; }
                        public Builder setEncryptionPaddings(String... padding) { return this; }
                        public Builder setRandomizedEncryptionRequired(boolean value) { return this; }
                        public KeyGenParameterSpec build() { return new KeyGenParameterSpec(alias); }
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.Logger", """
                package app.morphe.extension.shared;
                public class Logger {
                    public static void printException(java.util.function.Supplier<String> value) {}
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.TestKeyStoreProvider", PROVIDER);
        stubs.put("app.morphe.extension.shared.wireguard.StoreScenario", SCENARIO);
        Map<String, String> production = new LinkedHashMap<>();
        for (String simpleName : new String[]{"ConfigText", "WireGuardConfigStore"}) {
            String name = "app.morphe.extension.shared.wireguard." + simpleName;
            production.put(name, HostBoundaryFixture.production(name));
        }
        fixture = new HostBoundaryFixture(stubs, production);
    }

    @AfterClass
    public static void cleanup() throws Exception { if (fixture != null) fixture.close(); }

    @Test public void encryptedRoundTripDoesNotStorePlaintext() throws Throwable { run("roundTrip"); }
    @Test public void repeatedWritesUseDifferentIvAndCiphertext() throws Throwable { run("freshIv"); }
    @Test public void ciphertextTamperingIsRejected() throws Throwable { run("tamper"); }
    @Test public void missingKeystoreKeyDoesNotRegenerateOrDecrypt() throws Throwable { run("missingKey"); }
    @Test public void removalDeletesProfileAndKey() throws Throwable { run("remove"); }
    @Test public void interruptedAtomicWriteKeepsProfileAvailable() throws Throwable { run("recover"); }
    @Test public void missingProfileDoesNotCreateKey() throws Throwable { run("missing"); }
    @Test public void invalidEnvelopeIsRejected() throws Throwable { run("invalidEnvelope"); }
    @Test public void invalidPlaintextDoesNotWriteProfile() throws Throwable { run("invalidPlaintext"); }

    private void run(String scenario) throws Throwable {
        fixture.run("app.morphe.extension.shared.wireguard.StoreScenario", scenario);
    }

    private static final String PROVIDER = """
            package app.morphe.extension.shared.wireguard;
            import java.io.*;
            import java.security.*;
            import java.security.cert.Certificate;
            import java.security.spec.AlgorithmParameterSpec;
            import java.util.*;
            import javax.crypto.*;
            import javax.crypto.spec.SecretKeySpec;
            import android.security.keystore.KeyGenParameterSpec;
            public class TestKeyStoreProvider extends Provider {
                public static final Map<String, Key> keys = new HashMap<>();
                public TestKeyStoreProvider() {
                    super("AndroidKeyStore", "1.0", "Host test-only provider; no Android security claim");
                    put("KeyStore.AndroidKeyStore", Store.class.getName());
                    put("KeyGenerator.AES", Generator.class.getName());
                }
                public static class Store extends KeyStoreSpi {
                    public Key engineGetKey(String alias, char[] password) { return keys.get(alias); }
                    public Certificate[] engineGetCertificateChain(String alias) { return null; }
                    public Certificate engineGetCertificate(String alias) { return null; }
                    public Date engineGetCreationDate(String alias) { return null; }
                    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
                        keys.put(alias, key);
                    }
                    public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) { throw new UnsupportedOperationException(); }
                    public void engineSetCertificateEntry(String alias, Certificate cert) { throw new UnsupportedOperationException(); }
                    public void engineDeleteEntry(String alias) { keys.remove(alias); }
                    public Enumeration<String> engineAliases() { return Collections.enumeration(keys.keySet()); }
                    public boolean engineContainsAlias(String alias) { return keys.containsKey(alias); }
                    public int engineSize() { return keys.size(); }
                    public boolean engineIsKeyEntry(String alias) { return keys.containsKey(alias); }
                    public boolean engineIsCertificateEntry(String alias) { return false; }
                    public String engineGetCertificateAlias(Certificate cert) { return null; }
                    public void engineStore(OutputStream stream, char[] password) { throw new UnsupportedOperationException(); }
                    public void engineLoad(InputStream stream, char[] password) {}
                }
                public static class Generator extends KeyGeneratorSpi {
                    private String alias;
                    private SecureRandom random;
                    protected void engineInit(SecureRandom random) { this.random = random; }
                    protected void engineInit(int size, SecureRandom random) { this.random = random; }
                    protected void engineInit(AlgorithmParameterSpec spec, SecureRandom random) {
                        this.alias = ((KeyGenParameterSpec) spec).alias; this.random = random;
                    }
                    protected SecretKey engineGenerateKey() {
                        byte[] bytes = new byte[32]; random.nextBytes(bytes);
                        SecretKey key = new SecretKeySpec(bytes, "AES");
                        Arrays.fill(bytes, (byte) 0); keys.put(alias, key); return key;
                    }
                }
            }
            """;

    private static final String SCENARIO = """
            package app.morphe.extension.shared.wireguard;
            import java.nio.file.*;
            import java.nio.charset.StandardCharsets;
            import java.security.*;
            import java.util.*;
            import android.content.Context;
            public class StoreScenario {
                static void check(boolean value) { if (!value) throw new AssertionError(); }
                public static void run(String scenario) throws Exception {
                    if (Security.getProvider("AndroidKeyStore") != null) throw new AssertionError("Test provider collision");
                    Security.addProvider(new TestKeyStoreProvider());
                    Path directory = Files.createTempDirectory("wireguard-test-store-");
                    try {
                        Context context = new Context(directory.toFile());
                        Path file = directory.resolve("morphe-wireguard-v1.bin");
                        String config = "[Interface]\\n# synthetic-profile-marker \\uD83D\\uDD12\\n";
                        if (scenario.equals("missing")) {
                            check(!WireGuardConfigStore.hasProfile(context) && TestKeyStoreProvider.keys.isEmpty());
                            return;
                        }
                        if (scenario.equals("invalidPlaintext")) {
                            try { WireGuardConfigStore.save(context, "a".repeat(ConfigText.MAX_BYTES + 1));
                                throw new AssertionError("Expected oversized profile rejection");
                            } catch (java.io.IOException expected) {}
                            check(!Files.exists(file) && TestKeyStoreProvider.keys.isEmpty()); return;
                        }
                        WireGuardConfigStore.save(context, config);
                        check(WireGuardConfigStore.hasProfile(context));
                        byte[] original = Files.readAllBytes(file);
                        if (scenario.equals("roundTrip")) {
                            check(WireGuardConfigStore.load(context).equals(config));
                            check(!new String(original, StandardCharsets.ISO_8859_1).contains("synthetic-profile-marker"));
                            check(TestKeyStoreProvider.keys.size() == 1); return;
                        }
                        if (scenario.equals("freshIv")) {
                            WireGuardConfigStore.save(context, config);
                            check(!Arrays.equals(original, Files.readAllBytes(file)));
                            check(WireGuardConfigStore.load(context).equals(config)); return;
                        }
                        if (scenario.equals("recover")) {
                            Files.move(file, directory.resolve("morphe-wireguard-v1.bin.bak"));
                            check(!Files.exists(file));
                            check(WireGuardConfigStore.hasProfile(context));
                            check(WireGuardConfigStore.load(context).equals(config)); return;
                        }
                        if (scenario.equals("remove")) {
                            WireGuardConfigStore.delete(context);
                            check(!WireGuardConfigStore.hasProfile(context) && TestKeyStoreProvider.keys.isEmpty()); return;
                        }
                        if (scenario.equals("tamper")) {
                            original[original.length - 1] ^= 1; Files.write(file, original);
                        } else if (scenario.equals("invalidEnvelope")) {
                            original[0] ^= 1; Files.write(file, original);
                        } else if (scenario.equals("missingKey")) {
                            TestKeyStoreProvider.keys.clear();
                        } else { throw new AssertionError("Unknown scenario"); }
                        try { WireGuardConfigStore.load(context); throw new AssertionError("Expected authenticated load rejection"); }
                        catch (GeneralSecurityException expected) {}
                        if (scenario.equals("missingKey")) check(TestKeyStoreProvider.keys.isEmpty());
                    } finally {
                        Security.removeProvider("AndroidKeyStore");
                        try (var paths = Files.walk(directory)) {
                            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                        }
                    }
                }
            }
            """;
}
