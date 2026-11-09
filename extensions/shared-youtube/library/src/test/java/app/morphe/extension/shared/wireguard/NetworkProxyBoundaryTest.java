// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Executes the real proxy integration with Android, Cronet and tunnel endpoints stubbed. */
public class NetworkProxyBoundaryTest {
    private static HostBoundaryFixture fixture;
    private static HostBoundaryFixture modernFixture;

    @BeforeClass
    public static void compile() throws Exception {
        Map<String, String> stubs = new LinkedHashMap<>();
        stubs.put("android.util.Base64", """
                package android.util;
                public class Base64 {
                    public static final int NO_WRAP = 2;
                    public static String encodeToString(byte[] bytes, int flags) {
                        return java.util.Base64.getEncoder().encodeToString(bytes);
                    }
                }
                """);
        stubs.put("android.util.Pair", """
                package android.util;
                public class Pair<A, B> {
                    public final A first; public final B second;
                    private Pair(A first, B second) { this.first = first; this.second = second; }
                    public static <A, B> Pair<A, B> create(A first, B second) {
                        return new Pair<>(first, second);
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.Logger", """
                package app.morphe.extension.shared;
                public class Logger {
                    public static final StringBuilder messages = new StringBuilder();
                    public static void printException(java.util.function.Supplier<String> value) {
                        messages.append(value.get());
                    }
                    public static void printException(java.util.function.Supplier<String> value, Throwable ex) {
                        messages.append(value.get());
                        if (ex != null) messages.append(ex.getMessage());
                    }
                    public static void printInfo(java.util.function.Supplier<String> value, Exception ex) {}
                }
                """);
        stubs.put("app.morphe.extension.shared.settings.SharedYouTubeSettings", """
                package app.morphe.extension.shared.settings;
                public class SharedYouTubeSettings {
                    public static class Value<T> {
                        public T value;
                        public Value(T value) { this.value = value; }
                        public T get() { return value; }
                    }
                    public static final Value<Boolean> PROXY_ENABLED = new Value<>(false);
                    public static final Value<String> PROXY_HOST = new Value<>("generic.invalid");
                    public static final Value<Integer> PROXY_PORT = new Value<>(8080);
                    public static final Value<Boolean> PROXY_HTTPS = new Value<>(true);
                    public static final Value<Boolean> PROXY_ALLOW_DIRECT_FALLBACK = new Value<>(true);
                    public static final Value<Boolean> PROXY_AUTH_ENABLED = new Value<>(false);
                    public static final Value<String> PROXY_AUTH_USERNAME = new Value<>("generic");
                    public static final Value<String> PROXY_AUTH_PASSWORD = new Value<>("generic-password");
                }
                """);
        stubs.put("org.chromium.net.Proxy", """
                package org.chromium.net;
                import java.util.List;
                import java.util.concurrent.Executor;
                public class Proxy {
                    public static final int SCHEME_HTTP = 0, SCHEME_HTTPS = 1;
                    public static int creates;
                    public static boolean fail;
                    public int scheme, port;
                    public String host;
                    public HttpConnectCallback callback;
                    public static Proxy createHttpProxy(int scheme, String host, int port,
                            Executor executor, HttpConnectCallback callback) {
                        creates++;
                        if (fail) throw new IllegalStateException("sensitive-test-error");
                        Proxy proxy = new Proxy();
                        proxy.scheme = scheme; proxy.host = host; proxy.port = port; proxy.callback = callback;
                        return proxy;
                    }
                    public abstract static class HttpConnectCallback {
                        public static final int RESPONSE_ACTION_PROCEED = 1;
                        public abstract void onBeforeRequest(Request request);
                        public abstract int onResponseReceived(List<?> headers, int status);
                        public abstract static class Request {
                            public abstract void proceed(List<?> headers);
                        }
                    }
                }
                """);
        stubs.put("org.chromium.net.ProxyOptions", """
                package org.chromium.net;
                public class ProxyOptions {
                    public java.util.List<Proxy> proxies;
                    public Integer fallback;
                    public static ProxyOptions fromProxyList(java.util.List<Proxy> proxies) {
                        ProxyOptions result = new ProxyOptions(); result.proxies = proxies; return result;
                    }
                    public static ProxyOptions fromProxyList(java.util.List<Proxy> proxies, int fallback) {
                        ProxyOptions result = fromProxyList(proxies); result.fallback = fallback; return result;
                    }
                }
                """);
        stubs.put("org.chromium.net.CronetEngine", """
                package org.chromium.net;
                public class CronetEngine {
                    public static class Builder {
                        public boolean quicDisabled, failQuic, failOptions;
                        public ProxyOptions options;
                        public Builder enableQuic(boolean enabled) {
                            if (failQuic) throw new IllegalStateException("sensitive-test-error");
                            quicDisabled = !enabled; return this;
                        }
                        public Builder setProxyOptions(ProxyOptions options) {
                            if (failOptions) throw new IllegalStateException("sensitive-test-error");
                            this.options = options; return this;
                        }
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.requests.Requester", """
                package app.morphe.extension.shared.requests;
                public class Requester {
                    public interface ConnectionProvider {
                        java.net.HttpURLConnection openConnection(java.net.URL url) throws java.io.IOException;
                    }
                    public static ConnectionProvider provider;
                    public static void setConnectionProvider(ConnectionProvider value) { provider = value; }
                }
                """);
        stubs.put("app.morphe.extension.shared.requests.CronetTransport", """
                package app.morphe.extension.shared.requests;
                public class CronetTransport {
                    public static boolean requireProxy, allowDirectFallback, registeredProxy;
                    public static int opens;
                    public static java.net.HttpURLConnection openConnection(java.net.URL url,
                            boolean proxy, boolean fallback) {
                        opens++; requireProxy = proxy; allowDirectFallback = fallback;
                        return new boundary.NetworkScenario.Connection(url);
                    }
                    public static void registerCronetEngine(org.chromium.net.CronetEngine engine, boolean proxy) {
                        registeredProxy = proxy;
                    }
                    public static void setMainCronetEngine(org.chromium.net.CronetEngine engine) {}
                }
                """);
        stubs.put("app.morphe.extension.shared.requests.proxy.PlaintextHttpURLConnection", """
                package app.morphe.extension.shared.requests.proxy;
                public class PlaintextHttpURLConnection extends boundary.NetworkScenario.Connection {
                    public PlaintextHttpURLConnection(java.net.HttpURLConnection connection) {
                        super(connection.getURL());
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.requests.proxy.ProxyFallbackHttpURLConnection", """
                package app.morphe.extension.shared.requests.proxy;
                public class ProxyFallbackHttpURLConnection extends boundary.NetworkScenario.Connection {
                    public static int constructions;
                    public ProxyFallbackHttpURLConnection(java.net.HttpURLConnection proxy,
                            java.net.HttpURLConnection direct, java.util.function.Consumer<java.io.IOException> callback) {
                        super(proxy.getURL()); constructions++;
                    }
                }
                """);
        stubs.put("app.morphe.extension.shared.wireguard.WireGuardProxy", """
                package app.morphe.extension.shared.wireguard;
                public class WireGuardProxy {
                    public static boolean enabled, fail;
                    public static int endpointCalls;
                    public static boolean isEnabled() { return enabled; }
                    public static Endpoint endpoint() {
                        endpointCalls++;
                        if (fail) throw new IllegalStateException("sensitive-test-error");
                        return new Endpoint();
                    }
                    public static class Endpoint {
                        public String host = "127.0.0.1";
                        public int port = 23456;
                        public String authorization = "Basic test-local-authorization";
                    }
                }
                """);
        stubs.put("boundary.NetworkScenario", SCENARIO);
        String name = "app.morphe.extension.shared.patches.NetworkProxyPatch";
        String source = HostBoundaryFixture.production(name);
        fixture = new HostBoundaryFixture(stubs, Map.of(name, source));
        // Emulate only the version-selected extension marker injected by Network proxy.
        String legacyMarker = "return false; // Modify during patching,";
        if (!source.contains(legacyMarker)) throw new AssertionError("Missing proxy API marker");
        modernFixture = new HostBoundaryFixture(stubs,
                Map.of(name, source.replace(legacyMarker, "return true; // Test: modern Cronet API")));
    }

    @AfterClass
    public static void cleanup() throws Exception {
        if (fixture != null) fixture.close();
        if (modernFixture != null) modernFixture.close();
    }

    @Test public void disabledDoesNotCreateProxyOrReadTunnelEndpoint() throws Throwable { run("disabled"); }
    @Test public void wireGuardOverridesGenericEndpointAndFallback() throws Throwable { run("wireguard"); }
    @Test public void plaintextUsesOnlyAuthenticatedLocalProxy() throws Throwable { run("plaintext"); }
    @Test public void endpointFailureBlocksCronetAndRequester() throws Throwable { run("endpointFailure"); }
    @Test public void cronetSetupFailuresAbortEngineCreationWithoutSensitiveLogs() throws Throwable { run("setupFailure"); }
    @Test public void registrationConsumesAppliedFlagAndClearsItOnFailure() throws Throwable { run("registration"); }
    @Test public void genericProxyRetainsLegacyFallbackAndQuicBehavior() throws Throwable { run("generic"); }
    @Test public void modernApiDisallowsWireGuardDirectFallback() throws Throwable {
        modernFixture.run("boundary.NetworkScenario", "wireguard");
    }
    @Test public void genericProxyRetainsModernFallbackBehavior() throws Throwable {
        modernFixture.run("boundary.NetworkScenario", "genericModern");
    }

    private void run(String scenario) throws Throwable { fixture.run("boundary.NetworkScenario", scenario); }

    private static final String SCENARIO = """
            package boundary;
            import java.net.*;
            import java.io.*;
            import java.util.*;
            import android.util.Pair;
            import org.chromium.net.*;
            import org.chromium.net.Proxy;
            import app.morphe.extension.shared.Logger;
            import app.morphe.extension.shared.patches.NetworkProxyPatch;
            import app.morphe.extension.shared.requests.*;
            import app.morphe.extension.shared.requests.proxy.*;
            import app.morphe.extension.shared.settings.SharedYouTubeSettings;
            import app.morphe.extension.shared.wireguard.WireGuardProxy;
            public class NetworkScenario {
                public static class Connection extends HttpURLConnection {
                    public Connection(URL url) { super(url); }
                    public void connect() { throw new AssertionError("No destination network permitted"); }
                    public void disconnect() {}
                    public boolean usingProxy() { return true; }
                }
                static void check(boolean value) { if (!value) throw new AssertionError(); }
                static void reject(Runnable action) {
                    try { action.run(); throw new AssertionError("Expected engine creation failure"); }
                    catch (IllegalStateException expected) {
                        check(!String.valueOf(expected.getMessage()).contains("sensitive-test-error"));
                    }
                }
                public static void run(String scenario) throws Exception {
                    CronetEngine.Builder builder = new CronetEngine.Builder();
                    if (scenario.equals("disabled")) {
                        NetworkProxyPatch.applyProxyOptions(builder);
                        check(builder.options == null && !builder.quicDisabled);
                        check(Proxy.creates == 0 && WireGuardProxy.endpointCalls == 0);
                        Requester.provider.openConnection(new URL("https://example.invalid"));
                        check(!CronetTransport.requireProxy && CronetTransport.allowDirectFallback);
                        return;
                    }
                    if (scenario.equals("generic") || scenario.equals("genericModern")) {
                        SharedYouTubeSettings.PROXY_ENABLED.value = true;
                        NetworkProxyPatch.applyProxyOptions(builder);
                        check(!builder.quicDisabled && WireGuardProxy.endpointCalls == 0);
                        if (scenario.equals("generic")) {
                            check(builder.options.proxies.size() == 2 && builder.options.proxies.get(1) == null);
                            check(builder.options.fallback == null);
                        } else {
                            check(builder.options.proxies.size() == 1 && builder.options.fallback == 1);
                        }
                        check(builder.options.proxies.get(0).scheme == Proxy.SCHEME_HTTPS);
                        return;
                    }
                    WireGuardProxy.enabled = true;
                    if (scenario.equals("endpointFailure")) {
                        WireGuardProxy.fail = true;
                        reject(() -> NetworkProxyPatch.applyProxyOptions(builder));
                        try {
                            Requester.provider.openConnection(new URL("https://example.invalid"));
                            throw new AssertionError("Expected Requester failure");
                        } catch (IllegalStateException expected) {}
                        check(builder.options == null && CronetTransport.opens == 0);
                        return;
                    }
                    if (scenario.equals("setupFailure")) {
                        for (int failure = 0; failure < 3; failure++) {
                            CronetEngine.Builder failed = new CronetEngine.Builder();
                            Proxy.fail = failure == 0; failed.failQuic = failure == 1; failed.failOptions = failure == 2;
                            reject(() -> NetworkProxyPatch.applyProxyOptions(failed));
                            check(failed.options == null);
                        }
                        check(!Logger.messages.toString().contains("sensitive-test-error"));
                        check(CronetTransport.opens == 0 && ProxyFallbackHttpURLConnection.constructions == 0);
                        return;
                    }
                    if (scenario.equals("plaintext")) {
                        final int[] proxyOpens = {0};
                        URL url = new URL(null, "http://example.invalid/resource", new URLStreamHandler() {
                            protected URLConnection openConnection(URL value) {
                                throw new AssertionError("Unexpected direct connection");
                            }
                            protected URLConnection openConnection(URL value, java.net.Proxy proxy) {
                                check(proxy.type() == java.net.Proxy.Type.HTTP);
                                InetSocketAddress address = (InetSocketAddress) proxy.address();
                                check(address.getHostString().equals("127.0.0.1") && address.getPort() == 23456);
                                proxyOpens[0]++; return new Connection(value);
                            }
                        });
                        NetworkProxyPatch.initialize();
                        HttpURLConnection connection = Requester.provider.openConnection(url);
                        check(connection.getRequestProperty("Proxy-Authorization").equals("Basic test-local-authorization"));
                        check(proxyOpens[0] == 1 && CronetTransport.opens == 0);
                        check(ProxyFallbackHttpURLConnection.constructions == 0);
                        return;
                    }
                    NetworkProxyPatch.applyProxyOptions(builder);
                    if (scenario.equals("registration")) {
                        NetworkProxyPatch.recordCronetEngine(new CronetEngine());
                        check(CronetTransport.registeredProxy);
                        NetworkProxyPatch.recordCronetEngine(new CronetEngine());
                        check(!CronetTransport.registeredProxy);
                        NetworkProxyPatch.applyProxyOptions(builder);
                        Proxy.fail = true;
                        reject(() -> NetworkProxyPatch.applyProxyOptions(new CronetEngine.Builder()));
                        NetworkProxyPatch.recordCronetEngine(new CronetEngine());
                        check(!CronetTransport.registeredProxy);
                        return;
                    }
                    check(scenario.equals("wireguard"));
                    check(builder.quicDisabled && builder.options.proxies.size() == 1);
                    check(builder.options.fallback == null || builder.options.fallback == 0);
                    Proxy proxy = builder.options.proxies.get(0);
                    check(proxy.scheme == Proxy.SCHEME_HTTP && proxy.host.equals("127.0.0.1") && proxy.port == 23456);
                    proxy.callback.onBeforeRequest(new Proxy.HttpConnectCallback.Request() {
                        public void proceed(List<?> headers) {
                            check(headers.size() == 1);
                            Pair<?, ?> header = (Pair<?, ?>) headers.get(0);
                            check(header.first.equals("Proxy-Authorization"));
                            check(header.second.equals("Basic test-local-authorization"));
                        }
                    });
                    Requester.provider.openConnection(new URL("https://example.invalid"));
                    check(CronetTransport.requireProxy && !CronetTransport.allowDirectFallback);
                    check(ProxyFallbackHttpURLConnection.constructions == 0);
                }
            }
            """;
}
