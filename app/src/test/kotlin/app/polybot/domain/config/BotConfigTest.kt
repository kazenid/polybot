package app.polybot.domain.config

import app.polybot.data.prefs.BotConfigStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BotConfigTest {
    @Test
    fun `defaults are safe and valid`() {
        val config = BotConfig()
        assertTrue(config.dryRun, "dry run harus aktif secara default")
        assertTrue(config.cancelOnStop)
        assertFalse(config.killSwitchCancelsAllOnSymbol, "kill switch tidak menyentuh order manual secara default")
        assertEquals(emptyList<String>(), config.validate())
    }

    @Test
    fun `json round trip keeps decimals exactly`() {
        val original = BotConfig(
            strategy = StrategyType.GRID,
            marketMaker = MarketMakerConfig(halfSpreadBps = BigDecimal("12.5")),
            grid = GridConfig(lower = BigDecimal("70000.01"), upper = BigDecimal("80000"), gridCount = 8),
        )
        val back = BotConfigStore.decode(BotConfigStore.encode(original))
        assertEquals(original.strategy, back.strategy)
        assertEquals(0, BigDecimal("12.5").compareTo(back.marketMaker.halfSpreadBps))
        assertEquals("70000.01", back.grid.lower.toPlainString())
    }

    @Test
    fun `corrupt or missing json falls back to defaults`() {
        assertEquals(BotConfig(), BotConfigStore.decode(null))
        assertEquals(BotConfig(), BotConfigStore.decode("{not json"))
        assertEquals("ETH-USDT", BotConfigStore.decode("""{"symbol":"ETH-USDT","unknownFuture":1}""").symbol, "kunci tak dikenal diabaikan, nilai lain dipertahankan")
    }

    @Test
    fun `validation catches unsafe or inconsistent settings`() {
        fun errors(c: BotConfig) = c.validate().joinToString()
        assertTrue(errors(BotConfig(symbol = "DOGE-USDT")).contains("tidak ada di daftar"))
        assertTrue(errors(BotConfig(risk = RiskConfig(maxOrderQuote = BigDecimal.ZERO))).contains("per order"))
        assertTrue(errors(BotConfig(marketMaker = MarketMakerConfig(orderQuote = BigDecimal("50")))).contains("melebihi batas risiko"))
        assertTrue(errors(BotConfig(strategy = StrategyType.GRID)).contains("Rentang grid"))
        assertTrue(
            errors(
                BotConfig(
                    strategy = StrategyType.GRID,
                    grid = GridConfig(BigDecimal("70000"), BigDecimal("80000"), gridCount = 10),
                    risk = RiskConfig(maxOpenOrders = 4),
                ),
            ).contains("maksimum order terbuka"),
        )
        assertTrue(errors(BotConfig(strategy = StrategyType.DCA, dca = DcaConfig(intervalSec = 1))).contains("Interval DCA"))
        assertTrue(errors(BotConfig(pollActiveSec = 10, pollIdleSec = 5)).contains("Interval polling"))
    }

    @Test
    fun `break-even sell covers both fees and the margin`() {
        val cfg = MarketMakerConfig() // fee 10 bps, margin 10 bps
        // 100 x (1 + 0.001 + 0.001) / (1 - 0.001), sekitar 30 bps di atas biaya
        assertEquals(BigDecimal("100.3003"), cfg.breakEvenSell(BigDecimal("100")).setScale(4, java.math.RoundingMode.HALF_UP))
        // Hasil bersih di titik impas: jual x (1-f) - biaya x (1+f) = margin x biaya
        val sell = cfg.breakEvenSell(BigDecimal("100"))
        val net = sell.multiply(BigDecimal("0.999")).subtract(BigDecimal("100").multiply(BigDecimal("1.001")))
        assertEquals(0, BigDecimal("0.1").compareTo(net.setScale(6, java.math.RoundingMode.HALF_UP)))
    }

    @Test
    fun `break-even floor is on by default and survives a json round trip`() {
        assertTrue(MarketMakerConfig().sellAboveCost)
        val off = BotConfig(marketMaker = MarketMakerConfig(sellAboveCost = false))
        assertFalse(BotConfigStore.decode(BotConfigStore.encode(off)).marketMaker.sellAboveCost)
    }
}
