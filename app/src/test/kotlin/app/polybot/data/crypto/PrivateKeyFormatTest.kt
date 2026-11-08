package app.polybot.data.crypto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivateKeyFormatTest {
    private val valid = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"

    @Test
    fun `accepts exactly 64 hex characters in either case, ignoring outer whitespace`() {
        assertNull(PrivateKeyFormat.check(valid))
        assertNull(PrivateKeyFormat.check(valid.uppercase()))
        assertNull(PrivateKeyFormat.check("  $valid\n"))
    }

    @Test
    fun `explains each common mistake`() {
        assertTrue(PrivateKeyFormat.check("0x$valid")!!.contains("0x"))
        assertTrue(PrivateKeyFormat.check("-----BEGIN PRIVATE KEY-----")!!.contains("PEM"))
        assertTrue(PrivateKeyFormat.check(valid.take(32) + " " + valid.drop(32))!!.contains("karakter selain"))
        assertTrue(PrivateKeyFormat.check(valid + valid)!!.contains("128"))
        assertTrue(PrivateKeyFormat.check(valid.dropLast(2))!!.contains("62"))
        assertNotNull(PrivateKeyFormat.check(""))
    }

    @Test
    fun `messages never echo the input`() {
        val secret = "zz" + valid.drop(2)
        assertFalse(PrivateKeyFormat.check(secret)!!.contains(secret))
        assertFalse(PrivateKeyFormat.check(secret)!!.contains(valid.drop(2)))
    }
}
