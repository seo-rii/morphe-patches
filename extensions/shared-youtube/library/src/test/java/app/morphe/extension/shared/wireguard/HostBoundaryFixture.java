// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles production Java against only the external boundaries supplied by a test. */
final class HostBoundaryFixture implements AutoCloseable {
    private final Path directory;

    HostBoundaryFixture(Map<String, String> stubs, Map<String, String> production) throws IOException {
        directory = Files.createTempDirectory("wireguard-host-boundary-");
        Path sources = directory.resolve("src");
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes);
        Path compiler = Path.of(System.getProperty("java.home"), "bin", "javac");
        if (!Files.isExecutable(compiler)) throw new AssertionError("Boundary tests require a JDK");
        List<String> arguments = new ArrayList<>(List.of(
                compiler.toString(), "--release", "17", "-encoding", "UTF-8", "-d", classes.toString()));
        Map<String, String> inputs = new LinkedHashMap<>(stubs);
        inputs.putAll(production);
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            Path file = sources.resolve(input.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.write(file, input.getValue().getBytes(StandardCharsets.UTF_8));
            arguments.add(file.toString());
        }
        int exitCode;
        try {
            // Android's unit-test compile bootclasspath excludes javax.tools. The test itself
            // runs on a host JDK, so invoke that JDK's compiler directly without a shell.
            exitCode = new ProcessBuilder(arguments).inheritIO().start().waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("WireGuard boundary compilation was interrupted", ex);
        }
        if (exitCode != 0) {
            close();
            throw new AssertionError("Could not compile WireGuard boundary fixture");
        }
    }

    static String production(String className) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("extensions/shared-youtube/library/src"))) {
            root = root.getParent();
        }
        if (root == null) throw new AssertionError("Cannot locate Morphe source root");
        return new String(Files.readAllBytes(root.resolve("extensions/shared-youtube/library/src/main/java")
                .resolve(className.replace('.', '/') + ".java")), StandardCharsets.UTF_8);
    }

    void run(String className, String scenario) throws Throwable {
        // A fresh loader represents a fresh app process and resets production static state.
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{directory.resolve("classes").toUri().toURL()}, null)) {
            try {
                loader.loadClass(className).getMethod("run", String.class).invoke(null, scenario);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }

    @Override
    public void close() throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
