package app.polybot.data.crypto

interface Signer {
    fun sign(message: ByteArray): ByteArray
}
