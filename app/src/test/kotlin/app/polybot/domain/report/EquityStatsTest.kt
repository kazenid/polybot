package app.polybot.domain.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EquityStatsTest {
    private fun p(min: Long, equity: String, symbol: String = "BTC-USDT", dry: Boolean = false) =
        EquityPoint(min * 60_000, BigDecimal(equity), dry, symbol)

    private fun assertDec(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual), "was $actual")

    @Test
    fun `quote asset comes from the symbol`() {
        assertEquals("USDT", quoteOf("BTC-USDT"))
        assertEquals("BTC", quoteOf("ETH-BTC"))
        assertEquals("", quoteOf(""))
    }

    @Test
    fun `points in another quote are dropped so the chart keeps one unit`() {
        val mixed = listOf(p(0, "6965", "BTC-USDT"), p(1, "0.008", "ETH-BTC"), p(2, "6966", "BTC-USDT"))
        assertEquals(listOf(0L, 2L), EquityStats.sameQuoteAsLatest(mixed).map { it.tsMs / 60_000 })
        // titik terbaru menentukan satuan
        assertEquals(listOf(1L), EquityStats.sameQuoteAsLatest(listOf(p(0, "6965"), p(1, "0.008", "ETH-BTC"))).map { it.tsMs / 60_000 })
    }

    @Test
    fun `session change sums each contiguous session and ignores the gaps`() {
        val points = listOf(
            p(0, "100"), p(1, "101"), p(2, "102"), // +2
            p(60, "90"), p(61, "91"), // bot mati 58 menit (turun 12 tidak dihitung), sesi kedua +1
        )
        assertDec("3", EquityStats.sessionChange(points))
    }

    @Test
    fun `dry run points and other quotes do not count`() {
        val points = listOf(p(0, "100"), p(1, "110", dry = true), p(2, "0.5", "ETH-BTC"), p(3, "103"))
        assertDec("3", EquityStats.sessionChange(points))
    }

    @Test
    fun `too few points give zero`() {
        assertDec("0", EquityStats.sessionChange(emptyList()))
        assertDec("0", EquityStats.sessionChange(listOf(p(0, "100"))))
    }
}
