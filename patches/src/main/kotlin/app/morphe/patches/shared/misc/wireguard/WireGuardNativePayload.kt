// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.patches.shared.misc.wireguard

/** Native payload validation before a modified APK is produced. */
internal object WireGuardNativePayload {
    val supportedAbis = setOf("arm64-v8a", "x86_64")

    fun selectAbis(apkEntries: Iterable<String>): Set<String> {
        val abis = apkEntries.mapNotNull { path ->
            val parts = path.split('/')
            if (parts.size == 3 && parts[0] == "lib" && parts[2].endsWith(".so")) parts[1] else null
        }.toSet()
        require(abis.isNotEmpty()) {
            "Cannot determine the APK architecture. Use a complete arm64-v8a or x86_64 APK for the selected app."
        }
        val unsupported = abis - supportedAbis
        require(unsupported.isEmpty()) {
            "WireGuard preview supports only 64-bit APKs. Unsupported architectures: " +
                    unsupported.sorted().joinToString() + ". Select an arm64-v8a or x86_64 APK."
        }
        return abis
    }

    fun validateElf(abi: String, bytes: ByteArray) {
        require(abi in supportedAbis) { "Unsupported native architecture: $abi" }
        require(bytes.size >= 64 && bytes[0] == 0x7f.toByte() &&
                bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()) { "WireGuard payload is not an ELF library" }
        require(bytes[4] == 2.toByte() && bytes[5] == 1.toByte()) {
            "WireGuard payload must be little-endian ELF64"
        }
        fun word(offset: Int) = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)
        require(word(16) == 3) { "WireGuard payload must be a shared library" }
        val expectedMachine = if (abi == "arm64-v8a") 183 else 62
        require(word(18) == expectedMachine) { "WireGuard payload architecture does not match $abi" }
    }
}
