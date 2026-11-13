// SPDX-License-Identifier: GPL-3.0-only
import app.morphe.patcher.patch.Patch;
import app.morphe.patcher.patch.PatchLoader;
import app.morphe.patcher.patch.Compatibility;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Loads the built archive through the real Morphe loader without a target APK. */
public final class WireGuardBundleSmoke {
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Pass one built .mpp file");
        File bundle = new File(args[0]);
        if (!bundle.isFile()) throw new IllegalArgumentException("Bundle does not exist");
        Set<Patch> patches = new PatchLoader.Jar(Set.of(bundle));
        Map<String, Patch> wireGuardByPackage = new HashMap<>();
        for (Patch patch : patches) {
            if ("WireGuard proxy (experimental)".equals(patch.getName())) {
                if (!patch.getDefault()) throw new AssertionError("WireGuard patch must be default-selected");
                if (patch.getCompatibility() == null || patch.getCompatibility().size() != 1) {
                    throw new AssertionError("Each WireGuard patch must target exactly one app");
                }
                String packageName = patch.getCompatibility().get(0).getPackageName();
                if (wireGuardByPackage.put(packageName, patch) != null) {
                    throw new AssertionError("Duplicate WireGuard patch for " + packageName);
                }
            }
        }
        Set<String> expectedPackages = Set.of("com.google.android.youtube",
                "com.google.android.apps.youtube.music");
        if (!wireGuardByPackage.keySet().equals(expectedPackages)) {
            throw new AssertionError("Wrong WireGuard targets: " + wireGuardByPackage.keySet());
        }
        for (Map.Entry<String, Patch> entry : wireGuardByPackage.entrySet()) {
            int networkDependencies = 0;
            for (Patch dependency : entry.getValue().getDependencies()) {
                if (!"Network proxy".equals(dependency.getName())) continue;
                networkDependencies++;
                if (dependency.getCompatibility() == null || dependency.getCompatibility().size() != 1
                        || !entry.getKey().equals(dependency.getCompatibility().get(0).getPackageName())) {
                    throw new AssertionError("Cross-app Network proxy dependency: " + entry.getKey());
                }
            }
            if (networkDependencies != 1) {
                throw new AssertionError("Expected one app-specific Network proxy dependency: " + entry.getKey());
            }
        }
        Compatibility music = wireGuardByPackage.get("com.google.android.apps.youtube.music")
                .getCompatibility().get(0);
        Set<String> musicVersions = new HashSet<>();
        music.getTargets().forEach(target -> musicVersions.add(target.getVersion()));
        if (!musicVersions.equals(Set.of("9.15.51", "9.20.53", "9.30.53", "9.43.51", "9.44.52", "9.45.51"))) {
            throw new AssertionError("Unexpected Music compatibility targets: " + musicVersions);
        }
        System.out.println("PASS real Morphe JAR loader: " + patches.size() + " patches discovered");
        System.out.println("PASS two default-selected WireGuard patches, one per app, with matching Network proxy dependencies");
        System.out.println("PASS Music declares its own six compatibility targets, not YouTube versions");
        System.out.println("APK application and Android DEX loading were NOT executed by this JVM check");
    }
}
