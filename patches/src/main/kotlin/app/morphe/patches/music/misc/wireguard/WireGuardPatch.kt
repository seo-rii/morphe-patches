// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.patches.music.misc.wireguard

import app.morphe.patcher.patch.patch
import app.morphe.patches.music.misc.proxy.networkProxyPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.wireguard.installWireGuardRuntime

/** Reuses the shared app-local transport through Music's own network and settings hooks. */
@Suppress("unused")
val wireGuardProxyPatch = patch(
    name = "WireGuard proxy (experimental)",
    description = "Imports a WireGuard configuration and routes supported app requests through an " +
            "authenticated app-local proxy, without Android VPN permission. " +
            "Targets the YouTube Music versions listed in this bundle and requires a 64-bit APK. " +
            "Root mounting is not supported. This is an unofficial, unverified-on-device prototype, " +
            "not a system-wide VPN or kill switch.",
    // Include the UI and transport hooks in the normal patch selection.
    // The runtime connection remains disabled until the user imports a profile
    // and explicitly enables WireGuard in the patched app's settings.
    default = true,
) {
    dependsOn(networkProxyPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        installWireGuardRuntime(PreferenceScreen.MISC)
    }
}
