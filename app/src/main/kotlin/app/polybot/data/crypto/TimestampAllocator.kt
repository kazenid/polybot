package app.polybot.data.crypto

import app.polybot.core.time.Clock

/**
 * Timestamp ms yang unik per proses. Server menolak timestamp yang mendahului jam lokal lebih dari
 * ~5 detik, jadi alokasi berhenti di `now + maxFutureSkewMs`.
 */
class TimestampAllocator(
    private val clock: Clock = Clock.System,
    private val maxFutureSkewMs: Long = MAX_FUTURE_SKEW_MS,
) {
    private var last = 0L

    /** Mengembalikan timestamp berikutnya, atau null jika akan melewati batas `now + maxFutureSkewMs`. */
    @Synchronized
    fun tryNext(): Long? {
        val now = clock.nowMs()
        val candidate = if (now > last) now else last + 1
        if (candidate > now + maxFutureSkewMs) return null
        last = candidate
        return candidate
    }

    companion object {
        const val MAX_FUTURE_SKEW_MS = 5_000L
    }
}
