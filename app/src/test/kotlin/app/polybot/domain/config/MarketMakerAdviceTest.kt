package app.polybot.domain.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MarketMakerAdviceTest {
    private fun d(v: String) = BigDecimal(v)
    private fun assertDec(expected: String, actual: BigDecimal?) = assertEquals(0, d(expected).compareTo(actual), "was $actual")

    // ---- profil ----

    @Test
    fun `balanced profile equals the default configuration`() {
        val applied = MarketMakerProfiles.apply(MmProfile.BALANCED, MarketMakerConfig())
        assertEquals(MmProfile.BALANCED, MarketMakerProfiles.detect(applied))
        assertEquals(MmProfile.BALANCED, MarketMakerProfiles.detect(MarketMakerConfig()))
    }

    @Test
    fun `profiles set the spread above the safe floor by a fixed margin`() {
        val base = MarketMakerConfig() // fee 10 + margin 10 = batas aman 20
        assertDec("40", MarketMakerProfiles.apply(MmProfile.CAUTIOUS, base).halfSpreadBps)
        assertDec("25", MarketMakerProfiles.apply(MmProfile.BALANCED, base).halfSpreadBps)
        assertDec("21", MarketMakerProfiles.apply(MmProfile.AGGRESSIVE, base).halfSpreadBps)
    }

    @Test
    fun `profile spread follows the fee and margin`() {
        val base = MarketMakerConfig(makerFeeBps = d("5"), minNetEdgeBps = d("5"))
        assertDec("30", MarketMakerProfiles.apply(MmProfile.CAUTIOUS, base).halfSpreadBps) // 10 + 20
        assertEquals(MmProfile.CAUTIOUS, MarketMakerProfiles.detect(MarketMakerProfiles.apply(MmProfile.CAUTIOUS, base)))
    }

    @Test
    fun `applying a profile keeps the users own order size fee and switches`() {
        val mine = MarketMakerConfig(orderQuote = d("25"), levels = 2, makerFeeBps = d("8"), minNetEdgeBps = d("12"), sellAboveCost = false, trendWindowSec = 600)
        val applied = MarketMakerProfiles.apply(MmProfile.AGGRESSIVE, mine)
        assertDec("25", applied.orderQuote)
        assertEquals(2, applied.levels)
        assertDec("8", applied.makerFeeBps)
        assertDec("12", applied.minNetEdgeBps)
        assertEquals(false, applied.sellAboveCost)
        assertEquals(600, applied.trendWindowSec)
    }

    @Test
    fun `editing any controlled value turns the profile into custom`() {
        val applied = MarketMakerProfiles.apply(MmProfile.CAUTIOUS, MarketMakerConfig())
        assertNull(MarketMakerProfiles.detect(applied.copy(cooldownSec = 9)))
        assertNull(MarketMakerProfiles.detect(applied.copy(halfSpreadBps = d("41"))))
    }

    @Test
    fun `old stored spread of 15 is custom not a profile`() {
        assertNull(MarketMakerProfiles.detect(MarketMakerConfig(halfSpreadBps = d("15"))))
    }

    // ---- putaran ----

    @Test
    fun `round trip is gross minus two fees`() {
        val rt = MarketMakerAdvice.roundTrip(MarketMakerConfig(), d("78000"))
        assertDec("25", rt.halfSpreadBps)
        assertDec("50", rt.grossBps)
        assertDec("20", rt.feeBps)
        assertDec("30", rt.netBps)
        assertDec("0.03", rt.netQuote) // 10 USDT x 30 bps
        assertDec("77805", rt.buyPrice)
        assertDec("78195", rt.sellPrice)
        assertEquals(Verdict.GOOD, rt.verdict)
    }

    @Test
    fun `a spread below the floor is lifted before computing the round trip`() {
        val rt = MarketMakerAdvice.roundTrip(MarketMakerConfig(halfSpreadBps = d("5")), null)
        assertDec("20", rt.halfSpreadBps)
        assertDec("20", rt.netBps)
        assertNull(rt.buyPrice)
    }

    @Test
    fun `thin and losing margins are flagged`() {
        // fee 10, margin 5, jarak 15 -> bersih 10 bps
        assertEquals(Verdict.THIN, MarketMakerAdvice.roundTrip(MarketMakerConfig(minNetEdgeBps = d("5"), halfSpreadBps = d("15")), null).verdict)
        // tanpa margin, jarak sama dengan fee -> bersih 0
        assertEquals(Verdict.LOSS, MarketMakerAdvice.roundTrip(MarketMakerConfig(minNetEdgeBps = d("0"), halfSpreadBps = d("10")), null).verdict)
    }

    // ---- peringatan ----

    @Test
    fun `defaults produce no warnings`() {
        assertTrue(MarketMakerAdvice.check(MarketMakerConfig()).isEmpty())
    }

    @Test
    fun `risky values are explained per field`() {
        val warnings = MarketMakerAdvice.check(
            MarketMakerConfig(
                halfSpreadBps = d("15"), makerFeeBps = d("0"), minNetEdgeBps = d("0"), requoteThresholdBps = d("30"),
                maxOrderAgeSec = 5, cooldownSec = 1, trendHoldBps = d("2"),
            ),
        )
        assertEquals(MarketMakerAdvice.Field.entries.toSet() - MarketMakerAdvice.Field.HALF_SPREAD, warnings.keys - MarketMakerAdvice.Field.HALF_SPREAD)
        assertTrue(MarketMakerAdvice.check(MarketMakerConfig(halfSpreadBps = d("15"))).getValue(MarketMakerAdvice.Field.HALF_SPREAD).contains("dipakai 20"))
    }

    // ---- ringkasan ----

    @Test
    fun `summary names prices when the market is known`() {
        val text = MarketMakerAdvice.summary(BotConfig(), "77805", "78195", "USDT")
        assertTrue(text.contains("beli 10 USDT di 77805 dan jual di 78195"), text)
        assertTrue(text.contains("bergeser 8 bps"), text)
        assertTrue(text.contains("15% ekuitas"), text)
        assertTrue(text.contains("rugi 20 USDT"), text)
    }

    @Test
    fun `summary falls back to distance when there is no price`() {
        val text = MarketMakerAdvice.summary(BotConfig(), null, null, "USDT")
        assertTrue(text.contains("25 bps (0,25%) dari harga tengah"), text)
    }

    @Test
    fun `percent uses a decimal comma`() {
        assertEquals("0,25", MarketMakerAdvice.percent(d("25")))
        assertEquals("1", MarketMakerAdvice.percent(d("100")))
        assertEquals("0,2", MarketMakerAdvice.percent(d("20")))
    }
}
