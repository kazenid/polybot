package app.polybot.data.api

import app.polybot.core.result.ApiError
import app.polybot.core.time.Clock
import app.polybot.core.time.ClockMonitor
import app.polybot.data.api.dto.ApiJson
import app.polybot.data.crypto.RequestSigner
import app.polybot.data.crypto.SigningBackpressureException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Connect unary JSON. Body diserialisasi ke byte sekali; byte yang sama dihash, ditandatangani, dan dikirim.
 * Retry hanya untuk request baca (`retryable = true`). Mutasi tidak pernah diulang di sini: pemanggil
 * wajib membaca balik status order dulu (plan bagian 9).
 */
class ConnectClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val signer: RequestSigner?,
    private val rateLimiter: RateLimiter,
    private val clock: Clock = Clock.System,
    private val clockMonitor: ClockMonitor = ClockMonitor(),
    private val json: Json = ApiJson,
    private val maxRetries: Int = 3,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun <Req, Res> call(
        procedure: String,
        requestSerializer: KSerializer<Req>,
        request: Req,
        responseSerializer: KSerializer<Res>,
        auth: Boolean = false,
        rateClass: RateClass = RateClass.READ,
        retryable: Boolean = true,
    ): Res {
        if (auth && signer == null) throw ApiError.Auth("NO_CREDENTIALS", "Kredensial API belum diisi")
        val body = json.encodeToString(requestSerializer, request).toByteArray(Charsets.UTF_8)
        val url = "$baseUrl/$procedure"

        var attempt = 0
        while (true) {
            try {
                rateLimiter.acquire(rateClass)
                val text = send(url, body, auth)
                return try {
                    json.decodeFromString(responseSerializer, text)
                } catch (e: SerializationException) {
                    // Hanya baris pertama: kotlinx menaruh potongan JSON di baris berikutnya.
                    val reason = e.message?.lineSequence()?.firstOrNull()?.take(160).orEmpty()
                    throw ApiError.BadResponse("Respons $procedure tidak bisa dibaca ($reason)", e)
                }
            } catch (e: ApiError) {
                if (e is ApiError.RateLimited) rateLimiter.penalize(rateClass, e.retryAfterMs)
                val canRetry = retryable && attempt < maxRetries &&
                    (e is ApiError.Retryable || e is ApiError.Timeout)
                if (!canRetry) throw e
                attempt++
                pause(backoffMs(e, attempt))
            }
        }
    }

    private suspend fun send(url: String, body: ByteArray, auth: Boolean): String {
        val builder = Request.Builder()
            .url(url)
            .header("Connect-Protocol-Version", "1")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        if (auth) {
            // Tanda tangan baru tiap percobaan: timestamp harus unik dan segar.
            val headers = try {
                signer!!.sign("POST", url, body)
            } catch (e: SigningBackpressureException) {
                throw ApiError.ClockSkew("Alokasi timestamp macet; periksa jam perangkat")
            }
            headers.forEach { (k, v) -> builder.header(k, v) }
        }
        val response = try {
            http.newCall(builder.build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            throw ApiError.Timeout(e)
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
        response.use {
            response.headers.getDate("Date")?.let { date -> clockMonitor.record(date.time, clock.nowMs()) }
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return text
            throw ErrorMapper.map(response.code, { name -> response.header(name) }, text, json)
        }
    }

    private fun backoffMs(error: ApiError, attempt: Int): Long = when (error) {
        is ApiError.RateLimited -> error.retryAfterMs
        is ApiError.ClockSkew -> 0L
        else -> {
            val base = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (attempt - 1))
            base / 2 + Random.nextLong(base / 2 + 1)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val USER_AGENT = "PolyBot-Android/0.1"
        const val BASE_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
