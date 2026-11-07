package app.morphe.patches.shared.misc.wireguard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WireGuardNativePayloadTest {
    @Test
    fun `detects original APK native ABIs without adding unsupported architectures`() {
        assertEquals(setOf("arm64-v8a"), WireGuardNativePayload.selectAbis(listOf(
            "lib/arm64-v8a/libcronet.so", "lib/arm64-v8a/libother.so", "assets/ignore.so",
        )))
        assertEquals(setOf("arm64-v8a", "x86_64"), WireGuardNativePayload.selectAbis(listOf(
            "lib/arm64-v8a/libcronet.so", "lib/x86_64/libcronet.so",
        )))
    }

    @Test
    fun `rejects incomplete and unsupported inputs`() {
        assertFailsWith<IllegalArgumentException> { WireGuardNativePayload.selectAbis(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            WireGuardNativePayload.selectAbis(listOf("lib/armeabi-v7a/libcronet.so"))
        }
        assertFailsWith<IllegalArgumentException> {
            WireGuardNativePayload.selectAbis(listOf("lib/arm64-v8a/a.so", "lib/x86/b.so"))
        }
    }

    @Test
    fun `accepts only shared ELF64 libraries for the selected ABI`() {
        for ((abi, machine) in listOf("arm64-v8a" to 183, "x86_64" to 62)) {
            val bytes = elf(machine)
            WireGuardNativePayload.validateElf(abi, bytes)
            assertFailsWith<IllegalArgumentException> {
                WireGuardNativePayload.validateElf(abi, bytes.copyOf(16))
            }
            assertFailsWith<IllegalArgumentException> {
                WireGuardNativePayload.validateElf(abi, bytes.clone().apply { this[4] = 1 })
            }
            assertFailsWith<IllegalArgumentException> {
                WireGuardNativePayload.validateElf(abi, bytes.clone().apply { this[16] = 2 })
            }
        }
        assertFailsWith<IllegalArgumentException> {
            WireGuardNativePayload.validateElf("arm64-v8a", elf(62))
        }
    }

    private fun elf(machine: Int) = ByteArray(64).apply {
        this[0] = 0x7f
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2
        this[5] = 1
        this[16] = 3
        this[18] = machine.toByte()
        this[19] = (machine shr 8).toByte()
    }
}
