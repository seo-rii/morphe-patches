// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Host routing/lifecycle tests; Android storage and JNI are explicitly external stubs. */
public class WireGuardProxyBoundaryTest {
    private static HostBoundaryFixture included;
    private static HostBoundaryFixture omitted;

    @BeforeClass
    public static void compile() throws Exception {
        Map<String, String> stubs = new LinkedHashMap<>();
        stubs.put("android.util.Base64", """
                package android.util;
                public class Base64 {
                    public static final int NO_PADDING = 1, NO_WRAP = 2, URL_SAFE = 8;
                    public static String encodeToString(byte[] bytes, int flags) {
                        java.util.Base64.Encoder encoder = (flags & URL_SAFE) == 0
                                ? java.util.Base64.getEncoder() : java.util.Base64.getUrlEncoder();
                        if ((flags & NO_PADDING) != 0) encoder = encoder.withoutPadding();
                        return encoder.encodeToString(bytes);
                    }
                }
                """);
        stubs.put("androidx.annotation.Nullable", "package androidx.annotation; public @interface Nullable {}");
        stubs.put("app.morphe.extension.shared.Logger", """
                package app.morphe.extension.shared;
                public class Logger {
                    public static final StringBuilder messages = new StringBuilder();
                    public static void printException(java.util.function.Supplier<String> value) {
                        messages.append(value.get());
                    }
                    public static void printDebug(java.util.function.Supplier<String> value) {}
                }
                """);
        stubs.put("app.morphe.extension.shared.Utils", """
                package app.morphe.extension.shared;
                public class Utils { public static Object getContext() { return new Object(); } }
                """);
        stubs.put("app.morphe.extension.shared.settings.SharedYouTubeSettings", """
                package app.morphe.extension.shared.settings;
                public class SharedYouTubeSettings {
                    public static final Flag WIREGUARD_ENABLED = new Flag();
                    public static class Flag {
                        public boolean value;
                        public boolean get() { return value; }
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.Probe", """
                package app.morphe.extension.shared.wireguard;
                public class Probe {
                    public static int nativeLoads, starts, stops, profileLoads;
                    public static boolean missingProfile, failNative, invalidPort;
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.NativeTunnel", """
                package app.morphe.extension.shared.wireguard;
                public class NativeTunnel {
                    static { Probe.nativeLoads++; }
                    public static int start(String config, String username, String password, int port) {
                        Probe.starts++;
                        if (Probe.failNative) throw new UnsatisfiedLinkError("sensitive-test-error");
                        if (!config.equals("test-fixture-config") || !username.equals("wireguard")
                                || password.length() < 32 || port != 0) throw new AssertionError();
                        return Probe.invalidPort ? 0 : 23456;
                    }
                    public static void stop() { Probe.stops++; }
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.WireGuardConfigStore", """
                package app.morphe.extension.shared.wireguard;
                public class WireGuardConfigStore {
                    public static String load(Object context) throws java.io.IOException {
                        Probe.profileLoads++;
                        if (Probe.missingProfile) throw new java.io.IOException("sensitive-test-error");
                        return "test-fixture-config";
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.ProxyScenario", SCENARIO);
        String name = "app.morphe.extension.shared.wireguard.WireGuardProxy";
        String source = HostBoundaryFixture.production(name);
        omitted = new HostBoundaryFixture(stubs, Map.of(name, source));
        // This temp-source substitution emulates only installWireGuardRuntime's inclusion marker.
        // It does not modify release sources, binaries or patch-selection defaults.
        String marker = "public static boolean isPatchIncluded() {\n        return false;\n    }";
        if (!source.contains(marker)) throw new AssertionError("Missing WireGuard inclusion marker");
        included = new HostBoundaryFixture(stubs, Map.of(name, source.replace(marker,
                "public static boolean isPatchIncluded() { return true; }")));
    }

    @AfterClass
    public static void cleanup() throws Exception {
        if (included != null) included.close();
        if (omitted != null) omitted.close();
    }

    @Test public void omittedPatchIgnoresSavedEnabledFlag() throws Throwable {
        omitted.run("app.morphe.extension.shared.wireguard.ProxyScenario", "omitted");
    }
    @Test public void disabledNeverLoadsProfileOrNativeCode() throws Throwable { run("disabled"); }
    @Test public void enablingRequiresRestartAndDoesNotStartInCurrentProcess() throws Throwable { run("enableLater"); }
    @Test public void enabledStartsOnlyOnceWithConcurrentRequests() throws Throwable { run("concurrent"); }
    @Test public void disablingDoesNotChangeExistingProcessRouting() throws Throwable { run("disableLater"); }
    @Test public void missingProfileBlocksWithRealLoopback503WithoutNativeLoad() throws Throwable { run("missing"); }
    @Test public void jniLoadFailureBlocksAndKeepsEndpointStable() throws Throwable { run("nativeFailure"); }
    @Test public void invalidNativePortNeverBecomesDirectRouting() throws Throwable { run("invalidPort"); }

    private void run(String scenario) throws Throwable {
        included.run("app.morphe.extension.shared.wireguard.ProxyScenario", scenario);
    }

    private static final String SCENARIO = """
            package app.morphe.extension.shared.wireguard;
            import java.net.*;
            import java.nio.charset.StandardCharsets;
            import java.util.*;
            import java.util.concurrent.*;
            import app.morphe.extension.shared.Logger;
            import app.morphe.extension.shared.settings.SharedYouTubeSettings;
            public class ProxyScenario {
                static void check(boolean value) { if (!value) throw new AssertionError(); }
                public static void run(String scenario) throws Exception {
                    SharedYouTubeSettings.WIREGUARD_ENABLED.value = !scenario.equals("disabled")
                            && !scenario.equals("enableLater");
                    if (scenario.equals("omitted") || scenario.equals("disabled") || scenario.equals("enableLater")) {
                        check(!WireGuardProxy.isEnabled());
                        check(WireGuardProxy.state() == WireGuardProxy.State.DISABLED);
                        check(!WireGuardProxy.isRestartRequired());
                        if (scenario.equals("enableLater")) {
                            SharedYouTubeSettings.WIREGUARD_ENABLED.value = true;
                            check(!WireGuardProxy.isEnabled() && WireGuardProxy.isRestartRequired());
                        }
                        try { WireGuardProxy.endpoint(); throw new AssertionError("Expected disabled failure"); }
                        catch (IllegalStateException expected) {}
                        check(Probe.nativeLoads == 0 && Probe.profileLoads == 0 && Probe.starts == 0);
                        return;
                    }
                    check(WireGuardProxy.isEnabled() && Probe.nativeLoads == 0 && Probe.profileLoads == 0);
                    check(WireGuardProxy.state() == WireGuardProxy.State.NOT_STARTED);
                    if (scenario.equals("concurrent")) {
                        ExecutorService workers = Executors.newFixedThreadPool(8);
                        try {
                            List<Future<WireGuardProxy.Endpoint>> requests = new ArrayList<>();
                            for (int i = 0; i < 32; i++) requests.add(workers.submit(WireGuardProxy::endpoint));
                            WireGuardProxy.Endpoint first = requests.get(0).get(5, TimeUnit.SECONDS);
                            for (var request : requests) check(first == request.get(5, TimeUnit.SECONDS));
                            check(first.host.equals("127.0.0.1") && first.port == 23456);
                            String auth = new String(Base64.getDecoder().decode(first.authorization.substring(6)),
                                    StandardCharsets.US_ASCII);
                            check(auth.startsWith("wireguard:") && auth.length() > 40);
                            check(!first.toString().contains(auth));
                            check(Probe.starts == 1 && Probe.nativeLoads == 1 && Probe.profileLoads == 1);
                            check(WireGuardProxy.state() == WireGuardProxy.State.READY);
                        } finally { workers.shutdownNow(); }
                        return;
                    }
                    if (scenario.equals("disableLater")) {
                        WireGuardProxy.Endpoint first = WireGuardProxy.endpoint();
                        SharedYouTubeSettings.WIREGUARD_ENABLED.value = false;
                        check(WireGuardProxy.isEnabled() && WireGuardProxy.isRestartRequired());
                        check(first == WireGuardProxy.endpoint() && Probe.starts == 1);
                        return;
                    }
                    Probe.missingProfile = scenario.equals("missing");
                    Probe.failNative = scenario.equals("nativeFailure");
                    Probe.invalidPort = scenario.equals("invalidPort");
                    check(Probe.missingProfile || Probe.failNative || Probe.invalidPort);
                    WireGuardProxy.Endpoint endpoint = WireGuardProxy.endpoint();
                    try {
                        check(WireGuardProxy.state() == WireGuardProxy.State.BLOCKED);
                        check(endpoint == WireGuardProxy.endpoint() && endpoint.host.equals("127.0.0.1"));
                        if (Probe.missingProfile) check(Probe.nativeLoads == 0 && Probe.starts == 0);
                        check(Probe.profileLoads == 1 && Probe.starts <= 1);
                        check(Probe.stops == (Probe.invalidPort ? 1 : 0));
                        for (int i = 0; i < 3; i++) {
                            try (Socket client = new Socket(endpoint.host, endpoint.port)) {
                                client.setSoTimeout(3000);
                                String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                                check(response.startsWith("HTTP/1.1 503 ") && response.contains("Connection: close"));
                            }
                        }
                        check(!Logger.messages.toString().contains("sensitive-test-error"));
                    } finally {
                        // Production keeps this reservation until process death; the test loader
                        // is a simulated process, so release its socket explicitly.
                        var field = WireGuardProxy.class.getDeclaredField("failureListener");
                        field.setAccessible(true);
                        ((ServerSocket) field.get(null)).close();
                    }
                }
            }
            """;
}
