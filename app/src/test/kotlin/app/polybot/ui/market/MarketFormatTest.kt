package app.polybot.ui.market

import app.polybot.domain.market.PriceChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MarketFormatTest {
    private fun d(v: String) = BigDecimal(v)

    @Test
    fun `price uses the tick size decimals`() {
        assertEquals("78017.15", d("78017.1500").priceText(d("0.01")))
        assertEquals("78017.10", d("78017.1").priceText(d("0.01")))
        assertEquals("0.07312", d("0.073124").priceText(d("0.00001")))
        assertEquals("3200", d("3200.4").priceText(d("1")))
        assertEquals("3200", d("3200.4").priceText(d("1.0")))
    }

    @Test
    fun `quantity drops trailing zeros and caps decimals`() {
        assertEquals("1.5", d("1.50000000").qtyText())
        assertEquals("0.0012", d("0.00123456").qtyText())
        assertEquals("0", d("0.00001").qtyText())
        assertEquals("1234.57", d("1234.5678").qtyText(2))
    }

    @Test
    fun `percent text has no sign because the arrow shows direction`() {
        assertEquals("1.23%", PriceChange(d("101.23"), d("100"), 24).percentText())
        assertEquals("2.50%", PriceChange(d("97.5"), d("100"), 24).percentText())
        assertEquals("-", PriceChange(d("1"), d("0"), 24).percentText())
    }
}
