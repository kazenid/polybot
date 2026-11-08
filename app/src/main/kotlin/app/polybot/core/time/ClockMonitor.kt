package app.polybot.core.time

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Selisih jam HP terhadap server, dari header `Date` respons (resolusi 1 detik).
 * Positif = jam HP lebih maju dari server.
 */
class ClockMonitor(private val maxSkewMs: Long = MAX_SKEW_MS) {
    private val last = AtomicReference<Long?>(null)

    fun record(serverDateMs: Long, localNowMs: Long) {
        last.set(localNowMs - serverDateMs)
    }

    /** Selisih terakhir dalam ms, atau null bila belum ada respons. */
    fun skewMs(): Long? = last.get()

    /** True hanya jika selisih terukur dan melewati ambang. Resolusi header 1 detik, jadi ambang memberi ruang. */
    fun isSkewed(): Boolean = last.get()?.let { abs(it) > maxSkewMs } ?: false

    companion object {
        const val MAX_SKEW_MS = 3_000L
    }
}
