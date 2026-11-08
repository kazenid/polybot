package app.polybot.data.crypto

import app.polybot.core.util.Hex
import kotlinx.coroutines.delay
import java.net.URI

class SigningBackpressureException(val waitedMs: Long) :
    RuntimeException("Timestamp allocation blocked for ${waitedMs}ms; check device clock")

/**
 * Menandatangani request: header `X-API-KEY-ID`, `X-API-TIMESTAMP`, `X-API-SIGNATURE`.
 * String yang ditandatangani = timestamp, METHOD, path, query kanonik, sha256(body) dipisah `\n`.
 */
class RequestSigner(
    private val keyId: String,
    private val signer: Signer,
    private val timestamps: TimestampAllocator = TimestampAllocator(),
    private val maxBackpressureMs: Long = 10_000L,
) {
    suspend fun sign(method: String, url: String, body: ByteArray): Map<String, String> {
        val timestamp = nextTimestamp()
        return signAt(timestamp.toString(), method, url, body)
    }

    internal fun signAt(timestamp: String, method: String, url: String, body: ByteArray): Map<String, String> {
        val canonical = canonicalString(timestamp, method, url, body)
        val signature = signer.sign(canonical.toByteArray(Charsets.UTF_8))
        return mapOf(
            HEADER_KEY_ID to keyId,
            HEADER_TIMESTAMP to timestamp,
            HEADER_SIGNATURE to Hex.encode(signature),
        )
    }

    private suspend fun nextTimestamp(): Long {
        var waited = 0L
        while (true) {
            timestamps.tryNext()?.let { return it }
            if (waited >= maxBackpressureMs) throw SigningBackpressureException(waited)
            delay(1)
            waited++
        }
    }

    override fun toString(): String = "RequestSigner(keyId=$keyId)"

    companion object {
        const val HEADER_KEY_ID = "X-API-KEY-ID"
        const val HEADER_TIMESTAMP = "X-API-TIMESTAMP"
        const val HEADER_SIGNATURE = "X-API-SIGNATURE"

        fun canonicalString(timestamp: String, method: String, url: String, body: ByteArray): String {
            val uri = URI(url)
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            return listOf(
                timestamp,
                method.uppercase(),
                path,
                CanonicalQuery.canonicalize(uri.rawQuery),
                Hex.sha256(body),
            ).joinToString("\n")
        }
    }
}
