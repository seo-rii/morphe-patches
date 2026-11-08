// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Production import workflow with document, JNI and encrypted storage as external boundaries. */
public class ProfileOperationsBoundaryTest {
    private static HostBoundaryFixture fixture;

    @BeforeClass
    public static void compile() throws Exception {
        Map<String, String> stubs = new LinkedHashMap<>();
        stubs.put("androidx.annotation.Nullable", "package androidx.annotation; public @interface Nullable {}");
        stubs.put("android.content.Context", "package android.content; public class Context {}");
        stubs.put("app.morphe.extension.shared.wireguard.ImportProbe", """
                package app.morphe.extension.shared.wireguard;
                import java.util.*;
                public class ImportProbe {
                    public static final String ORIGINAL = "previous-public-test-profile";
                    public static final String PRIVATE_MARKER = "synthetic-private-value-not-a-real-key";
                    public static final String REASON = "Exactly one [Peer] after [Interface] is supported.";
                    public static final List<String> events = new ArrayList<>();
                    public static String saved = ORIGINAL, expected;
                    public static int nativeLoads, validations, saves, loads;
                    public static boolean failNativeLoad, interruptValidation, expectClosed, closed;
                    public static RuntimeException validationFailure;
                    public static Exception saveFailure, loadFailure;
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.NativeTunnel", """
                package app.morphe.extension.shared.wireguard;
                public class NativeTunnel {
                    static {
                        ImportProbe.nativeLoads++;
                        if (ImportProbe.failNativeLoad) {
                            throw new UnsatisfiedLinkError(ImportProbe.PRIVATE_MARKER);
                        }
                    }
                    public static void validate(String config) {
                        ImportProbe.validations++;
                        ImportProbe.events.add("validate");
                        if (!config.equals(ImportProbe.expected)) throw new AssertionError("Changed profile");
                        if (ImportProbe.expectClosed && !ImportProbe.closed) {
                            throw new AssertionError("Document must close before validation");
                        }
                        if (ImportProbe.validationFailure != null) throw ImportProbe.validationFailure;
                        if (ImportProbe.interruptValidation) Thread.currentThread().interrupt();
                    }
                    public static int start(String config, String user, String password, int port) {
                        throw new AssertionError("Import must not start a tunnel");
                    }
                    public static void stop() { throw new AssertionError("Import must not stop a tunnel"); }
                    public static final class InvalidProfileException extends IllegalArgumentException {
                        public InvalidProfileException(String message) { super(message); }
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.WireGuardConfigStore", """
                package app.morphe.extension.shared.wireguard;
                import android.content.Context;
                import java.io.IOException;
                import java.security.GeneralSecurityException;
                public class WireGuardConfigStore {
                    public static void save(Context context, String config)
                            throws IOException, GeneralSecurityException {
                        ImportProbe.saves++;
                        ImportProbe.events.add("save");
                        if (ImportProbe.validations != 1) throw new AssertionError("Save before validation");
                        fail(ImportProbe.saveFailure);
                        ImportProbe.saved = config;
                    }
                    public static String load(Context context) throws IOException, GeneralSecurityException {
                        ImportProbe.loads++;
                        ImportProbe.events.add("load");
                        fail(ImportProbe.loadFailure);
                        return ImportProbe.saved;
                    }
                    private static void fail(Exception error) throws IOException, GeneralSecurityException {
                        if (error instanceof IOException ex) throw ex;
                        if (error instanceof GeneralSecurityException ex) throw ex;
                        if (error instanceof RuntimeException ex) throw ex;
                        if (error != null) throw new AssertionError("Unsupported test exception");
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.ImportScenario", SCENARIO);
        Map<String, String> production = new LinkedHashMap<>();
        for (String simpleName : new String[]{"ConfigText", "ProfileOperations"}) {
            String name = "app.morphe.extension.shared.wireguard." + simpleName;
            production.put(name, HostBoundaryFixture.production(name));
        }
        fixture = new HostBoundaryFixture(stubs, production);
    }

    @AfterClass
    public static void cleanup() throws Exception {
        if (fixture != null) fixture.close();
    }

    @Test public void successfulImportClosesValidatesAndSavesWithoutStartingTunnel() throws Throwable { run("success"); }
    @Test public void bomIsRemovedAndUnicodeTextIsPreserved() throws Throwable { run("bom"); }
    @Test public void inaccessibleDocumentFailsBeforeNativeLoad() throws Throwable { run("openIo"); }
    @Test public void nullProviderStreamFailsBeforeNativeLoad() throws Throwable { run("nullStream"); }
    @Test public void documentPermissionFailureIsPrivate() throws Throwable { run("permission"); }
    @Test public void providerRuntimeFailureIsPrivate() throws Throwable { run("providerRuntime"); }
    @Test public void streamReadFailureIsPrivate() throws Throwable { run("readIo"); }
    @Test public void streamCloseFailurePreventsValidationAndSave() throws Throwable { run("closeIo"); }
    @Test public void invalidUtf8HasEncodingDiagnosticBeforeNativeLoad() throws Throwable { run("encoding"); }
    @Test public void byteLimitHasSizeDiagnosticBeforeNativeLoad() throws Throwable { run("oversize"); }
    @Test public void emptyDocumentHasContentDiagnosticBeforeNativeLoad() throws Throwable { run("empty"); }
    @Test public void blankDocumentHasContentDiagnosticBeforeNativeLoad() throws Throwable { run("blank"); }
    @Test public void embeddedNulHasContentDiagnosticBeforeNativeLoad() throws Throwable { run("nul"); }
    @Test public void typedNativeReasonIsShownWithoutRetainingCause() throws Throwable { run("typedValidation"); }
    @Test public void untrustedNativeArgumentMessageIsNotShownOrRetained() throws Throwable { run("argumentValidation"); }
    @Test public void missingNativeLibraryAndPoisonedClassHaveSameNativeDiagnostic() throws Throwable { run("nativeMissing"); }
    @Test public void storageIoFailureKeepsPreviousProfileAndHasStorageDiagnostic() throws Throwable { run("storeIo"); }
    @Test public void keyStoreFailureKeepsPreviousProfileAndHasStorageDiagnostic() throws Throwable { run("storeSecurity"); }
    @Test public void storageRuntimeFailureIsPrivate() throws Throwable { run("storeRuntime"); }
    @Test public void storedIoFailureHasLoadDiagnosticBeforeNativeLoad() throws Throwable { run("loadIo"); }
    @Test public void storedDecryptionFailureHasLoadDiagnosticBeforeNativeLoad() throws Throwable { run("loadSecurity"); }
    @Test public void storedRuntimeFailureHasLoadDiagnosticBeforeNativeLoad() throws Throwable { run("loadRuntime"); }
    @Test public void enablingValidationOnlyLoadsAndValidatesStoredProfile() throws Throwable { run("loadSuccess"); }
    @Test public void interruptedValidationDoesNotOverwriteStoredProfile() throws Throwable { run("interrupted"); }

    private void run(String scenario) throws Throwable {
        fixture.run("app.morphe.extension.shared.wireguard.ImportScenario", scenario);
    }

    private static final String SCENARIO = """
            package app.morphe.extension.shared.wireguard;
            import android.content.Context;
            import java.io.*;
            import java.nio.charset.StandardCharsets;
            import java.security.GeneralSecurityException;
            import java.util.*;
            public class ImportScenario {
                // Public text fixture. Native syntax validation is separately exercised by JNI tests.
                private static final String PROFILE = "# public fixture: 한글 🦊\\r\\n"
                        + ImportProbe.PRIVATE_MARKER + "\\r\\n";
                private static final Context CONTEXT = new Context();
                private static void check(boolean ok, String label) {
                    if (!ok) throw new AssertionError(label);
                }
                private interface Action { void run() throws ProfileOperations.Failure; }
                private static void reject(String key, String reason, Action action) throws Exception {
                    try {
                        action.run();
                        throw new AssertionError("Expected profile operation failure");
                    } catch (ProfileOperations.Failure error) {
                        check(error.resourceKey.equals("morphe_wireguard_" + key), "Wrong diagnostic stage");
                        check(Objects.equals(error.validationReason, reason), "Wrong validation detail");
                        check(error.getCause() == null, "Failure retained private cause");
                        check(error.getSuppressed().length == 0, "Failure retained suppressed cause");
                        StringWriter output = new StringWriter();
                        error.printStackTrace(new PrintWriter(output));
                        check(!output.toString().contains(ImportProbe.PRIVATE_MARKER), "Diagnostic leaked profile data");
                        check(!String.valueOf(error.validationReason).contains(ImportProbe.PRIVATE_MARKER),
                                "Validation detail leaked profile data");
                    }
                }
                private static TrackingStream stream(byte[] bytes) {
                    return new TrackingStream(bytes);
                }
                private static void importText(String text) throws ProfileOperations.Failure {
                    importBytes(text.getBytes(StandardCharsets.UTF_8));
                }
                private static void importBytes(byte[] bytes) throws ProfileOperations.Failure {
                    ProfileOperations.importProfile(CONTEXT, () -> {
                        ImportProbe.events.add("open");
                        return stream(bytes);
                    });
                }
                private static void untouched() {
                    check(ImportProbe.nativeLoads == 0 && ImportProbe.validations == 0, "Read failure touched native code");
                    check(ImportProbe.saves == 0, "Failed read reached storage");
                    check(ImportProbe.saved.equals(ImportProbe.ORIGINAL), "Previous profile changed");
                }
                public static void run(String scenario) throws Exception {
                    ImportProbe.expected = PROFILE;
                    ImportProbe.expectClosed = true;
                    switch (scenario) {
                        case "success", "bom" -> {
                            importText(scenario.equals("bom") ? "\\ufeff" + PROFILE : PROFILE);
                            check(ImportProbe.saved.equals(PROFILE), "Imported text changed");
                            check(ImportProbe.events.equals(List.of("open", "close", "validate", "save")), "Wrong import order");
                            check(ImportProbe.validations == 1 && ImportProbe.saves == 1 && ImportProbe.loads == 0,
                                    "Unexpected operation count");
                            return;
                        }
                        case "openIo", "permission", "providerRuntime" -> {
                            reject("import_read_failed", null, () -> ProfileOperations.importProfile(CONTEXT, () -> {
                                if (scenario.equals("permission")) throw new SecurityException(ImportProbe.PRIVATE_MARKER);
                                if (scenario.equals("providerRuntime")) throw new IllegalArgumentException(ImportProbe.PRIVATE_MARKER);
                                throw new IOException(ImportProbe.PRIVATE_MARKER);
                            }));
                            untouched();
                            return;
                        }
                        case "nullStream" -> {
                            reject("import_read_failed", null, () -> ProfileOperations.importProfile(CONTEXT, () -> null));
                            untouched();
                            return;
                        }
                        case "readIo" -> {
                            reject("import_read_failed", null, () -> ProfileOperations.importProfile(CONTEXT, () -> new InputStream() {
                                public int read() throws IOException { throw new IOException(ImportProbe.PRIVATE_MARKER); }
                                public void close() { ImportProbe.closed = true; }
                            }));
                            check(ImportProbe.closed, "Failed read did not close stream");
                            untouched();
                            return;
                        }
                        case "closeIo" -> {
                            reject("import_read_failed", null, () -> ProfileOperations.importProfile(CONTEXT,
                                    () -> new ByteArrayInputStream(PROFILE.getBytes(StandardCharsets.UTF_8)) {
                                        public void close() throws IOException { throw new IOException(ImportProbe.PRIVATE_MARKER); }
                                    }));
                            untouched();
                            return;
                        }
                        case "encoding" -> reject("import_encoding_failed", null,
                                () -> importBytes(new byte[]{(byte) 0xc3, 0x28}));
                        case "oversize" -> reject("import_too_large", null, () -> importText("#".repeat(65537)));
                        case "empty" -> reject("import_invalid_text", null, () -> importText(""));
                        case "blank" -> reject("import_invalid_text", null, () -> importText(" \\n\\t"));
                        case "nul" -> reject("import_invalid_text", null, () -> importText(PROFILE + "\\0"));
                        default -> { }
                    }
                    if (List.of("encoding", "oversize", "empty", "blank", "nul").contains(scenario)) {
                        check(ImportProbe.closed, "Invalid document stream not closed");
                        untouched();
                        return;
                    }
                    if (scenario.equals("typedValidation") || scenario.equals("argumentValidation")) {
                        String reason = scenario.equals("typedValidation") ? ImportProbe.REASON : null;
                        ImportProbe.validationFailure = reason == null
                                ? new IllegalArgumentException(ImportProbe.PRIVATE_MARKER)
                                : new NativeTunnel.InvalidProfileException(reason);
                        reject("validation_failed", reason, () -> importText(PROFILE));
                        check(ImportProbe.validations == 1 && ImportProbe.saves == 0, "Invalid profile reached storage");
                        check(ImportProbe.saved.equals(ImportProbe.ORIGINAL), "Previous profile changed");
                        return;
                    }
                    if (scenario.equals("nativeMissing")) {
                        ImportProbe.failNativeLoad = true;
                        reject("native_failed", null, () -> importText(PROFILE));
                        // A real failed initializer poisons the class until process restart.
                        reject("native_failed", null, () -> importText(PROFILE));
                        check(ImportProbe.nativeLoads == 1 && ImportProbe.validations == 0 && ImportProbe.saves == 0,
                                "Unexpected native loading or storage after class-init failure");
                        check(ImportProbe.saved.equals(ImportProbe.ORIGINAL), "Previous profile changed");
                        return;
                    }
                    if (scenario.startsWith("store")) {
                        ImportProbe.saveFailure = switch (scenario) {
                            case "storeIo" -> new IOException(ImportProbe.PRIVATE_MARKER);
                            case "storeSecurity" -> new GeneralSecurityException(ImportProbe.PRIVATE_MARKER);
                            default -> new IllegalStateException(ImportProbe.PRIVATE_MARKER);
                        };
                        reject("store_failed", null, () -> importText(PROFILE));
                        check(ImportProbe.validations == 1 && ImportProbe.saves == 1, "Storage failed before validation");
                        check(ImportProbe.saved.equals(ImportProbe.ORIGINAL), "Failed storage replaced previous profile");
                        return;
                    }
                    if (scenario.startsWith("load")) {
                        ImportProbe.expectClosed = false;
                        if (scenario.equals("loadSuccess")) {
                            ImportProbe.expected = ImportProbe.ORIGINAL;
                            ProfileOperations.validateStoredProfile(CONTEXT);
                            check(ImportProbe.events.equals(List.of("load", "validate")), "Wrong stored-validation order");
                            check(ImportProbe.saves == 0 && ImportProbe.loads == 1 && ImportProbe.validations == 1,
                                    "Stored validation changed profile");
                            return;
                        }
                        ImportProbe.loadFailure = switch (scenario) {
                            case "loadIo" -> new IOException(ImportProbe.PRIVATE_MARKER);
                            case "loadSecurity" -> new GeneralSecurityException(ImportProbe.PRIVATE_MARKER);
                            default -> new IllegalStateException(ImportProbe.PRIVATE_MARKER);
                        };
                        reject("load_failed", null, () -> ProfileOperations.validateStoredProfile(CONTEXT));
                        check(ImportProbe.loads == 1 && ImportProbe.saves == 0 && ImportProbe.nativeLoads == 0,
                                "Failed stored load touched validation or save");
                        return;
                    }
                    if (scenario.equals("interrupted")) {
                        ImportProbe.interruptValidation = true;
                        try {
                            importText(PROFILE);
                            check(Thread.currentThread().isInterrupted(), "Lost interruption");
                            check(ImportProbe.validations == 1 && ImportProbe.saves == 0, "Interrupted import reached save");
                            check(ImportProbe.saved.equals(ImportProbe.ORIGINAL), "Interrupted import changed stored profile");
                        } finally {
                            Thread.interrupted();
                        }
                        return;
                    }
                    throw new AssertionError("Unknown import test scenario");
                }
                private static final class TrackingStream extends ByteArrayInputStream {
                    TrackingStream(byte[] bytes) { super(bytes); }
                    public void close() {
                        ImportProbe.closed = true;
                        ImportProbe.events.add("close");
                    }
                }
            }
            """;
}
