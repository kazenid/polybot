package app.polybot.data.crypto

/**
 * Format yang diterima server dan SDK resmi: tepat 64 karakter hex (32 byte), tanpa `0x`, base64, atau PEM.
 * Pesan tidak pernah memuat isi input, hanya panjangnya.
 */
object PrivateKeyFormat {
    private val HEX = Regex("^[0-9a-fA-F]+$")
    const val LENGTH = 64

    /** Null bila format valid; selain itu penjelasan masalahnya dalam bahasa pengguna. */
    fun check(input: String): String? {
        val value = input.trim()
        return when {
            value.isEmpty() -> "Kosong"
            value.startsWith("0x", ignoreCase = true) -> "Hapus awalan 0x; yang dipakai hanya 64 karakter hex."
            value.startsWith("-----") -> "Ini format PEM. Yang dibutuhkan 64 karakter hex mentah."
            !HEX.matches(value) -> "Mengandung karakter selain 0-9 dan a-f (spasi, baris baru, atau base64?)."
            value.length == 128 ->
                "128 karakter bukan format yang diterima. Mungkin seed 32 byte + public key; " +
                    "coba 64 karakter pertama dan cocokkan public key turunannya."
            value.length != LENGTH -> "Panjang ${value.length} karakter; harus tepat $LENGTH."
            else -> null
        }
    }
}
