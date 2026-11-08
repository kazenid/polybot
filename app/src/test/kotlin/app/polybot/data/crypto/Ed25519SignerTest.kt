package app.polybot.data.crypto

import app.polybot.core.util.Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Ed25519SignerTest {
    // RFC 8032 section 7.1, TEST 1 (pesan kosong).
    private val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    private val signature =
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

    @Test
    fun `matches RFC 8032 test vector`() {
        val actual = Ed25519Signer.fromHex(seed).sign(ByteArray(0))
        assertEquals(signature, Hex.encode(actual))
    }

    @Test
    fun `derives the RFC 8032 public keys from seeds`() {
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            Ed25519Signer.fromHex(seed).publicKeyHex,
        )
        assertEquals(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            Ed25519Signer.publicKeyHexOrNull("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"),
        )
    }

    @Test
    fun `no public key for an invalid seed`() {
        assertNull(Ed25519Signer.publicKeyHexOrNull("abcd"))
        assertNull(Ed25519Signer.publicKeyHexOrNull("0x$seed"))
    }

    @Test
    fun `rejects wrong seed length`() {
        assertThrows(IllegalArgumentException::class.java) { Ed25519Signer.fromHex("abcd") }
    }

    @Test
    fun `invalid hex error does not leak input`() {
        val secret = "zz" + "0".repeat(62)
        val error = assertThrows(IllegalArgumentException::class.java) { Ed25519Signer.fromHex(secret) }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun `toString never exposes key material`() {
        assertFalse(Ed25519Signer.fromHex(seed).toString().contains(seed))
        assertFalse(
            ApiCredentials("id", seed, "acc").toString().contains(seed),
        )
    }
}
