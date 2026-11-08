package app.polybot.core.result

/** Kategori error API (plan bagian 9). Pesan tidak boleh memuat nilai kunci. */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Boleh dicoba ulang untuk request baca. */
    sealed class Retryable(message: String, cause: Throwable? = null) : ApiError(message, cause)

    class RateLimited(val retryAfterMs: Long, message: String = "Rate limited") : Retryable(message)
    class Transient(message: String, cause: Throwable? = null) : Retryable(message, cause)
    class ClockSkew(message: String = "Timestamp ditolak server") : Retryable(message)

    /** Hasil mutasi tidak diketahui: baca balik sebelum kirim ulang. */
    class OutcomeUnknown(val code: String, message: String) : ApiError(message)

    /** Timeout: request baca boleh diulang, mutasi harus dianggap OutcomeUnknown. */
    class Timeout(cause: Throwable? = null) : ApiError("Request timeout", cause)

    /** Kunci salah, dinonaktifkan, IP ditolak, atau izin ditolak. Hentikan bot. */
    class Auth(val code: String, message: String) : ApiError(message)

    class Validation(val code: String, message: String) : ApiError(message)

    /** Objek yang diminta tidak ada (`not_found`), berbeda dari route yang tidak ada. */
    class NotFound(val code: String, message: String) : ApiError(message)

    /** `client_order_id` sudah dipakai; [existingOrderId] menunjuk order yang memegangnya bila server menyebutkannya. */
    class DuplicateClientOrderId(val existingOrderId: Long?) : ApiError("client_order_id sudah dipakai")

    /** 404/501/unimplemented: fitur tidak ada di host ini. */
    class NotAvailable(val code: String, message: String) : ApiError(message)

    /** Tidak bisa terhubung (offline, DNS, TLS). */
    class Network(cause: Throwable) : ApiError("Jaringan tidak tersedia", cause)

    /** Respons 200 yang tidak bisa dibaca. */
    class BadResponse(message: String, cause: Throwable? = null) : ApiError(message, cause)
}
