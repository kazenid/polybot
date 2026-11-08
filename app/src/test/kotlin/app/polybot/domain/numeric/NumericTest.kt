package app.polybot.domain.numeric

import app.polybot.domain.model.Market
import app.polybot.domain.model.MarketStatus
import app.polybot.domain.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class NumericTest {
    private fun d(s: String) = BigDecimal(s)

    private val btcUsdt = Market(
        symbolId = 1, symbol = "BTC-USDT", baseAsset = "BTC", quoteAsset = "USDT",
        tickSize = d("0.01"), stepSize = d("0.00000001"), minNotional = d("5"), minQty = d("0.00001"),
        baseScale = 8, quoteScale = 6, status = MarketStatus.ENABLED,
        maxOrderNotional = null, maxMarketNotional = null, maxOpenOrders = 0,
    )

    @Test
    fun `ticks match real testnet order book values`() {
        // BTC-USDT: priceTicks 77512010000000 -> 77512.01, qtyScaled 173683226 (skala 8) -> 1.73683226
        assertEquals(0, d("77512.01").compareTo(Numeric.ticksToPrice(77_512_010_000_000)))
        assertEquals(0, d("1.73683226").compareTo(Numeric.scaledToQty(173_683_226, 8)))
    }

    @Test
    fun `price round trip`() {
        val price = d("77512.01")
        assertEquals(price.compareTo(Numeric.ticksToPrice(Numeric.priceToTicks(price))), 0)
        assertEquals(1L, Numeric.priceToTicks(d("0.000000001")))
        assertEquals(0L, Numeric.priceToTicks(d("0")))
    }

    @Test
    fun `price finer than protocol unit or overflowing is rejected`() {
        assertThrows(ArithmeticException::class.java) { Numeric.priceToTicks(d("0.0000000001")) }
        assertThrows(ArithmeticException::class.java) { Numeric.priceToTicks(d("9223372037")) }
    }

    @Test
    fun `quantity round trip and scale enforcement`() {
        assertEquals(173_683_226L, Numeric.qtyToScaled(d("1.73683226"), 8))
        assertThrows(ArithmeticException::class.java) { Numeric.qtyToScaled(d("0.123456789"), 8) }
        assertEquals(1L, Numeric.qtyToScaled(d("0.000001"), 6))
    }

    @Test
    fun `bid rounds down and ask rounds up to tick`() {
        assertEquals(0, d("100.12").compareTo(Numeric.roundPrice(d("100.129"), d("0.01"), Side.BUY)))
        assertEquals(0, d("100.13").compareTo(Numeric.roundPrice(d("100.121"), d("0.01"), Side.SELL)))
        assertEquals(0, d("100.12").compareTo(Numeric.roundPrice(d("100.12"), d("0.01"), Side.SELL)))
    }

    @Test
    fun `quantity floors to step`() {
        assertEquals(0, d("0.00012345").compareTo(Numeric.floorQty(d("0.000123459"), d("0.00000001"))))
        assertEquals(0, d("10").compareTo(Numeric.floorQty(d("19.9"), d("10"))))
    }

    @Test
    fun `u128 combines hi and lo at 18 decimals`() {
        assertEquals(0, d("1.5").compareTo(Numeric.u128ToDecimal("0", "1500000000000000000")))
        // hi = 1 -> 2^64 = 18446744073709551616 -> 18.446744073709551616
        assertEquals(0, d("18.446744073709551616").compareTo(Numeric.u128ToDecimal("1", "0")))
        assertEquals(0, d("0").compareTo(Numeric.u128ToDecimal("", "")))
        assertThrows(IllegalArgumentException::class.java) { Numeric.u128ToDecimal("0", "18446744073709551616") }
    }

    @Test
    fun `validate catches tick step min qty and min notional`() {
        assertNull(Numeric.validate(btcUsdt, d("77512.01"), d("0.0001")))
        assertEquals(Numeric.Violation.PRICE_OFF_TICK, Numeric.validate(btcUsdt, d("77512.005"), d("0.0001")))
        assertEquals(Numeric.Violation.QTY_OFF_STEP, Numeric.validate(btcUsdt, d("77512.01"), d("0.000100001")))
        assertEquals(Numeric.Violation.BELOW_MIN_QTY, Numeric.validate(btcUsdt, d("77512.01"), d("0.000001")))
        assertEquals(Numeric.Violation.BELOW_MIN_NOTIONAL, Numeric.validate(btcUsdt, d("77512.01"), d("0.00005")))
        assertEquals(Numeric.Violation.NON_POSITIVE, Numeric.validate(btcUsdt, d("0"), d("1")))
    }

    @Test
    fun `max order notional is enforced when present`() {
        val capped = btcUsdt.copy(maxOrderNotional = d("100"))
        assertEquals(Numeric.Violation.ABOVE_MAX_NOTIONAL, Numeric.validate(capped, d("77512.01"), d("0.002")))
    }
}
