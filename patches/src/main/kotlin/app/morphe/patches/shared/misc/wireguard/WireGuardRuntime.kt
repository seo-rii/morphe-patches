// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.patches.shared.misc.wireguard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.listApkEntries
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.util.inputStreamFromBundledResource

private object WireGuardIncludedFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/shared/wireguard/WireGuardProxy;",
    name = "isPatchIncluded",
    returnType = "Z",
    parameters = emptyList(),
)

/** Shared installation only; each app declares its own network hooks and compatibility. */
internal fun installWireGuardRuntime(preferenceScreen: BasePreferenceScreen.Screen) {
    // Resolve all inputs before changing the APK. Never produce a patch with a missing engine.
    val payloads = try {
        WireGuardNativePayload.selectAbis(listApkEntries("lib/")).associateWith { abi ->
            val bytes = inputStreamFromBundledResource("wireguard", "$abi/libmorphewireguard.so")
                ?.use { it.readBytes() }
                ?: throw PatchException(
                    "Missing WireGuard engine for $abi. Run scripts/build-wireguard-android.sh " +
                            "before building this patch bundle."
                )
            WireGuardNativePayload.validateElf(abi, bytes)
            bytes
        }
    } catch (exception: IllegalArgumentException) {
        throw PatchException(exception.message ?: "Invalid WireGuard native payload")
    }

    val licenseRoot = "wireguard/licenses"
    val licensePaths = inputStreamFromBundledResource(licenseRoot, "INDEX")
        ?.bufferedReader()?.use { it.readLines() }
        ?: throw PatchException("Missing WireGuard license index. Rebuild this patch bundle from source.")
    if (licensePaths.isEmpty() || licensePaths.any {
            it.isBlank() || it.startsWith('/') || it.split('/').any { part -> part == ".." }
        }) {
        throw PatchException("Invalid WireGuard license index")
    }
    val licenses = licensePaths.associateWith { path ->
        inputStreamFromBundledResource(licenseRoot, path)?.use { it.readBytes() }
            ?: throw PatchException("Missing WireGuard license notice: $path")
    }

    // The same bundle can patch different app APKs in one JVM. Do not reuse a previous match.
    WireGuardIncludedFingerprint.clearMatch()
    WireGuardIncludedFingerprint.method.returnEarly(true)

    payloads.forEach { (abi, bytes) ->
        resourceFile("lib/$abi/libmorphewireguard.so", copy = false).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    licenses.forEach { (path, bytes) ->
        resourceFile("assets/$licenseRoot/$path", copy = false).apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    resourceXml("AndroidManifest.xml").use { manifest ->
        val application = manifest.elements("application").first()
        // The payload is copied as an APK file, not through an Android Gradle jniLibs merge.
        // Extraction lets System.loadLibrary load it even if the APK writer compresses it.
        application.setAttribute("android:extractNativeLibs", "true")
        application.appendChild("activity") {
            setAttribute("android:name", "app.morphe.extension.shared.wireguard.WireGuardSettingsActivity")
            setAttribute("android:exported", "false")
            setAttribute("android:excludeFromRecents", "true")
            setAttribute("android:label", "@string/morphe_wireguard_settings_title")
        }
    }

    preferenceScreen.addPreferences(
        NonInteractivePreference(
            key = "morphe_wireguard_settings",
            tag = "app.morphe.extension.shared.wireguard.WireGuardPreference",
            selectable = true,
        )
    )
}
