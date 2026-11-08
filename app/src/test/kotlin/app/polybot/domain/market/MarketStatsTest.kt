package app.polybot.domain.market

import app.polybot.domain.model.Candle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MarketStatsTest {
    private fun d(v: String) = BigDecimal(v)

    private fun candle(tsSec: Long, open: String, high: String, low: String, close: String, volume: String = "1") =
        Candle(tsSec, d(open), d(high), d(low), d(close), d(volume), isClosed = true)

    private val hour = 3600L

    @Test
    fun `price rise gives positive percent and UP direction`() {
        val candles = listOf(candle(0, "100", "110", "95", "105"), candle(hour, "105", "125", "104", "120"))
        val change = MarketStats.change(candles, lastPrice = null)!!
        assertEquals(Direction.UP, change.direction)
        assertEquals(0, d("20.00").compareTo(change.percent))
        assertEquals(0, d("20").compareTo(change.amount))
    }

    @Test
    fun `price fall gives negative percent and DOWN direction`() {
        val candles = listOf(candle(0, "200", "210", "180", "190"), candle(hour, "190", "191", "150", "150"))
        val change = MarketStats.change(candles, lastPrice = null)!!
        assertEquals(Direction.DOWN, change.direction)
        assertEquals(0, d("-25.00").compareTo(change.percent))
    }

    @Test
    fun `live price replaces the last close`() {
        val candles = listOf(candle(0, "100", "101", "99", "100"), candle(hour, "100", "101", "99", "100"))
        val change = MarketStats.change(candles, lastPrice = d("90"))!!
        assertEquals(Direction.DOWN, change.direction)
        assertEquals(0, d("-10.00").compareTo(change.percent))
    }

    @Test
    fun `zero live price falls back to the last close`() {
        val candles = listOf(candle(0, "100", "101", "99", "100"), candle(hour, "100", "111", "99", "110"))
        assertEquals(0, d("10.00").compareTo(MarketStats.change(candles, lastPrice = BigDecimal.ZERO)!!.percent))
    }

    @Test
    fun `unchanged price is FLAT`() {
        val candles = listOf(candle(0, "100", "100", "100", "100"))
        assertEquals(Direction.FLAT, MarketStats.change(candles, null)!!.direction)
    }

    @Test
    fun `no candles gives no change and zero reference gives no percent`() {
        assertNull(MarketStats.change(emptyList(), d("1")))
        assertNull(MarketStats.change(listOf(candle(0, "0", "1", "0", "1")), null)!!.percent)
    }

    @Test
    fun `span counts the hours covered by the candles`() {
        val day = (0..24).map { candle(it * hour, "100", "101", "99", "100") }
        assertEquals(25, MarketStats.change(day, null)!!.spanHours)
    }

    @Test
    fun `high low and volume across candles`() {
        val candles = listOf(candle(0, "100", "130", "90", "110", "2.5"), candle(hour, "110", "120", "80", "100", "1.5"))
        assertEquals(0, d("130").compareTo(MarketStats.high(candles)))
        assertEquals(0, d("80").compareTo(MarketStats.low(candles)))
        assertEquals(0, d("4.0").compareTo(MarketStats.volume(candles)))
        assertNull(MarketStats.high(emptyList()))
    }
}
