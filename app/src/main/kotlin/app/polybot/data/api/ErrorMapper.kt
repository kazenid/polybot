package app.polybot.data.api

import app.polybot.core.result.ApiError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Memetakan respons non-200 (Connect error, gateway, problem+json) ke [ApiError]. */
object ErrorMapper {
    private const val DEFAULT_RETRY_AFTER_MS = 1_000L
    private const val OUTCOME_UNKNOWN_SUFFIX = "_OUTCOME_UNKNOWN"
    private val AUTH_CODES = setOf(
        "UNAUTHENTICATED", "SIGNATURE_INVALID", "KEY_DISABLED", "IP_NOT_ALLOWED",
        "PERMISSION_DENIED", "FORBIDDEN",
    )

    fun map(status: Int, headers: (String) -> String?, body: String, json: Json): ApiError {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val code = root?.string("code").orEmpty()
        val message = root?.string("message") ?: root?.string("detail") ?: "HTTP $status"
        val upper = code.uppercase()
        val lower = code.lowercase()

        return when {
            upper == "TIMESTAMP_SKEW" -> ApiError.ClockSkew()
            isDuplicateId(upper, message.uppercase()) ->
                ApiError.DuplicateClientOrderId(existingOrderId(body))
            upper == "AUTH_TEMPORARILY_UNAVAILABLE" -> ApiError.Transient(message)
            upper.endsWith(OUTCOME_UNKNOWN_SUFFIX) -> ApiError.OutcomeUnknown(code, message)
            upper in AUTH_CODES || status == 401 || status == 403 -> ApiError.Auth(code.ifEmpty { "HTTP_$status" }, message)
            status == 429 || lower == "resource_exhausted" ->
                ApiError.RateLimited(retryAfterMs(root, headers), message)
            lower == "deadline_exceeded" || status == 504 -> ApiError.Timeout()
            lower == "not_found" || (lower.endsWith("_not_found") && lower != "route_not_found") ->
                ApiError.NotFound(code, message)
            status == 404 || status == 501 || lower == "unimplemented" || lower == "route_not_found" ->
                ApiError.NotAvailable(code.ifEmpty { "HTTP_$status" }, message)
            status in 500..599 || lower == "unavailable" || lower == "internal" || lower == "unknown" ->
                ApiError.Transient(message)
            else -> ApiError.Validation(code.ifEmpty { "HTTP_$status" }, "$message [${code.ifEmpty { "HTTP $status" }}]")
        }
    }

    private const val DUPLICATE_ID = "CONFLICT_DUPLICATE_CLIENT_ORDER_ID"

    /** Kode server bisa berbeda dari dokumentasi; pesan "Duplicate client order ID" juga dikenali. */
    private fun isDuplicateId(code: String, message: String): Boolean =
        DUPLICATE_ID in code || DUPLICATE_ID in message ||
            (code.contains("DUPLICATE") && code.contains("CLIENT_ORDER")) ||
            message.contains("DUPLICATE CLIENT ORDER")
    private val EXISTING_ORDER_ID = Regex("\"existing_?[oO]rder_?[iI]d\"\\s*:\\s*\"?(\\d+)")

    private fun existingOrderId(body: String): Long? =
        EXISTING_ORDER_ID.find(body)?.groupValues?.get(1)?.toBigIntegerOrNull()?.toLong()

    private fun retryAfterMs(root: JsonObject?, headers: (String) -> String?): Long {
        detailRetryAfterMs(root)?.let { return it }
        headers("Retry-After-Ms")?.trim()?.toLongOrNull()?.let { return it }
        headers("Grpc-Retry-Pushback-Ms")?.trim()?.toLongOrNull()?.let { return it }
        headers("Retry-After")?.trim()?.toLongOrNull()?.let { return it * 1000 }
        return DEFAULT_RETRY_AFTER_MS
    }

    /** `details[].debug.retryAfterMs` (atau snake_case) bila server menyertakan bentuk JSON-nya. */
    private fun detailRetryAfterMs(root: JsonObject?): Long? {
        val details = (root?.get("details") as? JsonElement)?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        for (detail in details) {
            val debug = runCatching { detail.jsonObject["debug"]?.jsonObject }.getOrNull() ?: continue
            for (key in listOf("retryAfterMs", "retry_after_ms")) {
                (debug[key] as? JsonPrimitive)?.let { p -> (p.longOrNull ?: p.contentOrNull?.toLongOrNull())?.let { return it } }
            }
        }
        return null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
