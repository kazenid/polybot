package app.polybot.data.api

import app.polybot.core.result.ApiError
import app.polybot.core.time.ClockMonitor
import app.polybot.core.util.Hex
import app.polybot.data.api.dto.EmptyRequest
import app.polybot.data.api.dto.MeResponse
import app.polybot.data.api.dto.OrderBookRequest
import app.polybot.data.api.dto.OrderBookResponse
import app.polybot.data.crypto.Ed25519Signer
import app.polybot.data.crypto.RequestSigner
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer as BcVerifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ConnectClientTest {
    private lateinit var server: MockWebServer
    private val seed = ByteArray(32) { (it + 1).toByte() }
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(2, TimeUnit.SECONDS).build()
    private val pauses = mutableListOf<Long>()
    private val clockMonitor = ClockMonitor()

    @BeforeEach
    fun start() {
        server = MockWebServer().also { it.start() }
        pauses.clear()
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun client(withSigner: Boolean = true, maxRetries: Int = 3) = ConnectClient(
        baseUrl = server.url("").toString().trimEnd('/'),
        http = http,
        signer = if (withSigner) RequestSigner("key-abc", Ed25519Signer(seed)) else null,
        rateLimiter = RateLimiter(),
        clockMonitor = clockMonitor,
        maxRetries = maxRetries,
        pause = { pauses += it },
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private suspend fun ConnectClient.me(auth: Boolean = true, retryable: Boolean = true) = call(
        "auth.v1.AuthService/Me", EmptyRequest.serializer(), EmptyRequest(), MeResponse.serializer(),
        auth = auth, retryable = retryable,
    )

    @Test
    fun `public call sends connect json without auth headers and parses int64 strings`() = runBlocking {
        server.enqueue(json("""{"symbolId":1,"bookSeq":"10975826","bids":[{"priceTicks":"77512010000000","qtyScaled":"173683226"}],"asks":[]}"""))

        val book = client(withSigner = false).call(
            "orderbook.v1.OrderbookService/GetOrderBook",
            OrderBookRequest.serializer(), OrderBookRequest(1, "DEPTH_5"), OrderBookResponse.serializer(),
        )

        assertEquals(77_512_010_000_000, book.bids.single().priceTicks)
        assertEquals(10_975_826, book.bookSeq)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/orderbook.v1.OrderbookService/GetOrderBook", req.path)
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertEquals("1", req.getHeader("Connect-Protocol-Version"))
        assertEquals("""{"symbolId":"1","depth":"DEPTH_5"}""", req.body.readUtf8())
        assertNull(req.getHeader("X-API-KEY-ID"))
    }

    @Test
    fun `authenticated call signs the exact bytes that were sent`() = runBlocking {
        server.enqueue(json("""{"accountId":"42","apiKeyId":"key-abc"}"""))

        val me = client().me()

        assertEquals("42", me.accountId)
        val req = server.takeRequest()
        assertEquals("key-abc", req.getHeader("X-API-KEY-ID"))
        val timestamp = req.getHeader("X-API-TIMESTAMP")!!
        val signature = Hex.decode(req.getHeader("X-API-SIGNATURE")!!)
        val sentBody = req.body.readByteArray()
        val canonical = RequestSigner.canonicalString(timestamp, "POST", server.url(req.path!!).toString(), sentBody)
        val verifier = BcVerifier().apply {
            init(false, Ed25519PrivateKeyParameters(seed, 0).generatePublicKey())
            update(canonical.toByteArray(), 0, canonical.length)
        }
        assertTrue(verifier.verifySignature(signature))
    }

    @Test
    fun `auth call without credentials fails before any request`() {
        val error = assertThrows(ApiError.Auth::class.java) { runBlocking { client(withSigner = false).me() } }
        assertEquals("NO_CREDENTIALS", error.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rate limited reads are retried after the server delay`() = runBlocking {
        server.enqueue(json("""{"code":"resource_exhausted"}""", 429).setHeader("Retry-After-Ms", "750"))
        server.enqueue(json("""{"accountId":"7"}"""))

        assertEquals("7", client().me().accountId)
        assertEquals(listOf(750L), pauses)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `clock skew is retried with a freshly signed timestamp`() = runBlocking {
        server.enqueue(json("""{"code":"TIMESTAMP_SKEW"}""", 401))
        server.enqueue(json("""{"accountId":"7"}"""))

        client().me()

        val first: RecordedRequest = server.takeRequest()
        val second: RecordedRequest = server.takeRequest()
        assertNotEquals(first.getHeader("X-API-TIMESTAMP"), second.getHeader("X-API-TIMESTAMP"))
        assertNotEquals(first.getHeader("X-API-SIGNATURE"), second.getHeader("X-API-SIGNATURE"))
    }

    @Test
    fun `auth rejection is never retried`() {
        server.enqueue(json("""{"code":"SIGNATURE_INVALID"}""", 401))
        assertThrows(ApiError.Auth::class.java) { runBlocking { client().me() } }
        assertEquals(1, server.requestCount)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun `server errors stop after max retries`() {
        repeat(4) { server.enqueue(json("{}", 503)) }
        assertThrows(ApiError.Transient::class.java) { runBlocking { client(maxRetries = 3).me() } }
        assertEquals(4, server.requestCount)
        assertEquals(3, pauses.size)
    }

    @Test
    fun `mutations are not retried`() {
        server.enqueue(json("{}", 503))
        server.enqueue(json("""{"accountId":"7"}"""))
        assertThrows(ApiError.Transient::class.java) { runBlocking { client().me(retryable = false) } }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `unreadable success body becomes BadResponse`() {
        server.enqueue(json("""{"accountId":{"nested":true}}"""))
        assertThrows(ApiError.BadResponse::class.java) { runBlocking { client().me() } }
    }

    @Test
    fun `server Date header feeds the clock monitor`() = runBlocking {
        server.enqueue(json("""{"accountId":"7"}""").setHeader("Date", "Sat, 31 Oct 2026 15:00:00 GMT"))
        client().me()
        assertNotNull(clockMonitor.skewMs())
        assertTrue(clockMonitor.isSkewed(), "jam test jauh dari 2026-10-31 15:00 GMT")
    }

    @Test
    fun `dead server maps to network error`() {
        val port = server.port
        server.shutdown()
        val c = ConnectClient("http://127.0.0.1:$port", http, null, RateLimiter(), pause = {}, maxRetries = 0)
        val error = assertThrows(ApiError::class.java) {
            runBlocking {
                c.call("x.v1.S/M", EmptyRequest.serializer(), EmptyRequest(), MeResponse.serializer())
            }
        }
        assertInstanceOf(ApiError.Network::class.java, error)
    }
}
