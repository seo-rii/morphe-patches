package app.morphe.patches.shared.misc.wireguard

import app.morphe.patches.music.misc.proxy.networkProxyPatch as musicNetworkProxyPatch
import app.morphe.patches.music.misc.wireguard.wireGuardProxyPatch as musicWireGuardPatch
import app.morphe.patches.youtube.misc.proxy.networkProxyPatch as youtubeNetworkProxyPatch
import app.morphe.patches.youtube.misc.wireguard.wireGuardProxyPatch as youtubeWireGuardPatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class WireGuardPatchMetadataTest {
    @Test
    fun `both apps include distinct default-selected patches with app-local dependency graphs`() {
        assertTrue(youtubeWireGuardPatch.default)
        assertTrue(musicWireGuardPatch.default)
        assertEquals("WireGuard proxy (experimental)", youtubeWireGuardPatch.name)
        assertEquals(youtubeWireGuardPatch.name, musicWireGuardPatch.name)
        assertNotEquals(youtubeWireGuardPatch, musicWireGuardPatch)
        assertEquals(setOf(youtubeNetworkProxyPatch), youtubeWireGuardPatch.dependencies)
        assertEquals(setOf(musicNetworkProxyPatch), musicWireGuardPatch.dependencies)
    }

    @Test
    fun `music does not inherit YouTube package or app targets`() {
        assertEquals("com.google.android.youtube",
            youtubeWireGuardPatch.compatibility!!.single().packageName)
        val music = musicWireGuardPatch.compatibility!!.single()
        assertEquals("com.google.android.apps.youtube.music", music.packageName)
        assertEquals(setOf("9.15.51", "9.20.53", "9.30.53"),
            music.targets.filterNot { it.isExperimental }.map { it.version }.toSet())
        assertEquals(setOf("9.42.52", "9.43.51", "9.44.52"),
            music.targets.filter { it.isExperimental }.map { it.version }.toSet())
        assertEquals(setOf(26), music.targets.map { it.minSdk }.toSet())
    }
}
