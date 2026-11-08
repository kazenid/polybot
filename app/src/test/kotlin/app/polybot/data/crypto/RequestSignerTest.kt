package app.polybot.data.crypto

import app.polybot.core.time.Clock
import app.polybot.core.util.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer as BcVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class RequestSignerTest {
    private val seed = ByteArray(32) { it.toByte() }

    private fun signer(clock: Clock = Clock.System, maxBackpressureMs: Long = 10_000L) =
        RequestSigner("key-1", Ed25519Signer(seed), TimestampAllocator(clock), maxBackpressureMs)

    @Test
    fun `canonical string matches official test vector`() {
        val canonical = RequestSigner.canonicalString(
            timestamp = "123",
            method = "post",
            url = "https://api.example.test/foo/bar?b=2&a=1",
            body = "{}".toByteArray(),
        )
        assertEquals(
            "123\nPOST\n/foo/bar\na=1&b=2\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            canonical,
        )
    }

    @Test
    fun `empty body and empty path`() {
        val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(
            "1700000000000\nPOST\n/orders.v1.OrdersService/CreateOrder\n\n$empty",
            RequestSigner.canonicalString(
                "1700000000000", "POST", "https://api.testnet.polyester.com/orders.v1.OrdersService/CreateOrder",
                ByteArray(0),
            ),
        )
        assertEquals(
            "1\nGET\n/\n\n$empty",
            RequestSigner.canonicalString("1", "get", "https://api.example.test", ByteArray(0)),
        )
    }

    @Test
    fun `headers carry a verifiable 128 char signature`() {
        val url = "https://api.example.test/foo/bar?b=2&a=1"
        val body = "{}".toByteArray()
        val headers = signer().signAt("123", "post", url, body)

        assertEquals("key-1", headers["X-API-KEY-ID"])
        assertEquals("123", headers["X-API-TIMESTAMP"])
        val signature = headers.getValue("X-API-SIGNATURE")
        assertEquals(128, signature.length)

        val publicKey = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey()
        val canonical = RequestSigner.canonicalString("123", "post", url, body).toByteArray()
        val verifier = BcVerifier().apply {
            init(false, publicKey)
            update(canonical, 0, canonical.size)
        }
        assertTrue(verifier.verifySignature(Hex.decode(signature)))
    }

    @Test
    fun `sequential timestamps are unique even when the clock stands still`() = runBlocking {
        val signer = signer(clock = { 1_000L })
        val timestamps = (1..3).map { signer.sign("POST", "https://x.test/a", ByteArray(0)).getValue("X-API-TIMESTAMP") }
        assertEquals(listOf("1000", "1001", "1002"), timestamps)
    }

    @Test
    fun `allocator refuses to run past the future skew limit`() {
        val allocator = TimestampAllocator(clock = { 0L + 1_000L }, maxFutureSkewMs = 2)
        assertEquals(1_000L, allocator.tryNext())
        assertEquals(1_001L, allocator.tryNext())
        assertEquals(1_002L, allocator.tryNext())
        assertNull(allocator.tryNext())
    }

    @Test
    fun `signing fails with backpressure when the clock never advances`() {
        val signer = RequestSigner("k", Ed25519Signer(seed), TimestampAllocator({ 1_000L }, maxFutureSkewMs = 1), 20L)
        assertThrows(SigningBackpressureException::class.java) {
            runBlocking {
                repeat(5) { signer.sign("POST", "https://x.test/a", ByteArray(0)) }
            }
        }
    }

    @Test
    fun `10000 parallel signatures never share a timestamp`() = runBlocking {
        val signer = signer()
        val timestamps = (1..10_000).map {
            async(Dispatchers.Default) {
                signer.sign("POST", "https://x.test/a", ByteArray(0)).getValue("X-API-TIMESTAMP")
            }
        }.awaitAll()
        assertEquals(10_000, timestamps.toSet().size)
    }

    @Test
    fun `timestamps stay within the future skew of the clock`() = runBlocking {
        val now = AtomicLong(50_000L)
        val signer = signer(clock = { now.get() })
        val issued = (1..100).map {
            signer.sign("POST", "https://x.test/a", ByteArray(0)).getValue("X-API-TIMESTAMP").toLong()
        }
        assertTrue(issued.all { it <= now.get() + TimestampAllocator.MAX_FUTURE_SKEW_MS })
    }
}
