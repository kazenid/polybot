package app.polybot.data.api

import app.polybot.core.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {
    private val limits = mapOf(
        RateClass.READ to RateLimiter.Limit(perSecond = 10.0, burst = 3),
        RateClass.PLACE to RateLimiter.Limit(perSecond = 10.0, burst = 3),
        RateClass.CANCEL to RateLimiter.Limit(perSecond = 10.0, burst = 3),
    )

    @Test
    fun `burst passes immediately then requests are paced`() = runTest {
        val limiter = RateLimiter(Clock { currentTime }, limits)
        repeat(3) { limiter.acquire(RateClass.READ) }
        assertEquals(0, currentTime)

        limiter.acquire(RateClass.READ)
        assertTrue(currentTime >= 100, "4th request waits for one token at 10/s, was $currentTime")
    }

    @Test
    fun `classes are independent`() = runTest {
        val limiter = RateLimiter(Clock { currentTime }, limits)
        repeat(3) { limiter.acquire(RateClass.PLACE) }
        limiter.acquire(RateClass.CANCEL)
        assertEquals(0, currentTime)
    }

    @Test
    fun `penalize blocks the class until retry-after passes`() = runTest {
        val limiter = RateLimiter(Clock { currentTime }, limits)
        limiter.penalize(RateClass.READ, 1_000)
        limiter.acquire(RateClass.READ)
        assertTrue(currentTime >= 1_000, "was $currentTime")

        // kelas lain tidak terpengaruh
        val before = currentTime
        limiter.acquire(RateClass.CANCEL)
        assertEquals(before, currentTime)
    }
}
