package app.polybot.data.crypto

import app.polybot.core.util.Hex
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

class Ed25519Signer(seed: ByteArray) : Signer {
    private val key: Ed25519PrivateKeyParameters

    init {
        require(seed.size == SEED_BYTES) { "Ed25519 seed must be $SEED_BYTES bytes" }
        key = Ed25519PrivateKeyParameters(seed, 0)
    }

    override fun sign(message: ByteArray): ByteArray {
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Public key (32 byte, hex). Bukan rahasia; aman ditampilkan untuk dicocokkan dengan yang tercatat di server. */
    val publicKeyHex: String get() = Hex.encode(key.generatePublicKey().encoded)

    override fun toString(): String = "Ed25519Signer(***)"

    companion object {
        const val SEED_BYTES = 32

        fun fromHex(seedHex: String): Ed25519Signer = Ed25519Signer(Hex.decode(seedHex.trim()))

        /** Public key turunan dari seed hex, atau null bila seed tidak valid. */
        fun publicKeyHexOrNull(seedHex: String): String? =
            if (PrivateKeyFormat.check(seedHex) == null) fromHex(seedHex).publicKeyHex else null
    }
}
