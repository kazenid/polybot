package app.polybot.data.api

import app.polybot.core.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RateClass { READ, PLACE, CANCEL }

/**
 * Token bucket sisi klien per kelas. Bobot per request di server belum diketahui, jadi batas
 * default sengaja jauh di bawah kuota tier 0 (PLACE 250/s, CANCEL 500/s).
 */
class RateLimiter(
    private val clock: Clock = Clock.System,
    limits: Map<RateClass, Limit> = DEFAULT_LIMITS,
) {
    data class Limit(val perSecond: Double, val burst: Int)

    private class Bucket(val limit: Limit, var tokens: Double, var updatedMs: Long, var blockedUntilMs: Long = 0)

    private val mutex = Mutex()
    private val buckets = limits.mapValues { (_, l) -> Bucket(l, l.burst.toDouble(), clock.nowMs()) }

    suspend fun acquire(rateClass: RateClass) {
        while (true) {
            val waitMs = mutex.withLock { tryTake(buckets.getValue(rateClass)) }
            if (waitMs <= 0) return
            delay(waitMs)
        }
    }

    /** Tahan semua request kelas ini sampai `retryAfterMs` berlalu (dari respons 429). */
    suspend fun penalize(rateClass: RateClass, retryAfterMs: Long) {
        mutex.withLock {
            val bucket = buckets.getValue(rateClass)
            bucket.blockedUntilMs = maxOf(bucket.blockedUntilMs, clock.nowMs() + retryAfterMs)
            bucket.tokens = 0.0
        }
    }

    private fun tryTake(bucket: Bucket): Long {
        val now = clock.nowMs()
        if (now < bucket.blockedUntilMs) return bucket.blockedUntilMs - now
        val elapsed = (now - bucket.updatedMs).coerceAtLeast(0)
        bucket.tokens = minOf(bucket.limit.burst.toDouble(), bucket.tokens + elapsed * bucket.limit.perSecond / 1000.0)
        bucket.updatedMs = now
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return 0
        }
        return Math.ceil((1.0 - bucket.tokens) * 1000.0 / bucket.limit.perSecond).toLong().coerceAtLeast(1)
    }

    companion object {
        val DEFAULT_LIMITS = mapOf(
            RateClass.READ to Limit(perSecond = 10.0, burst = 20),
            RateClass.PLACE to Limit(perSecond = 5.0, burst = 10),
            RateClass.CANCEL to Limit(perSecond = 10.0, burst = 20),
        )
    }
}
