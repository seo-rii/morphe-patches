package app.morphe.patches.youtube.misc.wireguard

import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.wireguard.installWireGuardRuntime
import app.morphe.patches.youtube.misc.playservice.is_20_47_or_greater
import app.morphe.patches.youtube.misc.proxy.networkProxyPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import java.util.logging.Logger

/** An app-local transport; never registers an Android VpnService or an OS TUN interface. */
@Suppress("unused")
val wireGuardProxyPatch = patch(
    name = "WireGuard proxy (experimental)",
    description = "Imports a WireGuard configuration and routes supported app requests through an " +
            "authenticated app-local proxy, without Android VPN permission. " +
            "Requires a 64-bit YouTube 20.47.62 or newer APK. Root mounting is not supported. " +
            "This is an unofficial, unverified-on-device prototype, not a system-wide VPN or kill switch.",
    // Install by default; WireGuard traffic itself remains explicitly opt-in.
    default = true,
) {
    dependsOn(networkProxyPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        if (!is_20_47_or_greater) {
            Logger.getLogger(this::class.java.name).warning(
                "WireGuard proxy requires YouTube 20.47.62 or newer."
            )
            return@execute
        }

        installWireGuardRuntime(PreferenceScreen.MISC)
    }
}
