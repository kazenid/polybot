package app.polybot.core.util

import java.security.MessageDigest

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f])
        }
        return out.toString()
    }

    /** Pesan error sengaja tidak memuat isi input, karena input bisa berupa private key. */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex length must be even" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun sha256(bytes: ByteArray): String =
        encode(MessageDigest.getInstance("SHA-256").digest(bytes))
}
