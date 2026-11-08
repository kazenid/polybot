package app.polybot.data.catalog

import app.polybot.core.result.ApiError
import app.polybot.data.api.dto.ApiJson
import app.polybot.data.api.dto.SpotConfigResponse
import app.polybot.domain.model.MarketStatus
import app.polybot.domain.numeric.Numeric
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CatalogRepositoryTest {
    /** Respons asli GetSpotConfig dari api.testnet.polyester.com (31 Okt 2026). */
    private val catalog = CatalogRepository.toCatalog(
        ApiJson.decodeFromString(
            SpotConfigResponse.serializer(),
            javaClass.getResource("/spot_config_testnet.json")!!.readText(),
        ),
    )

    @Test
    fun `real testnet catalog parses`() {
        assertEquals(19, catalog.markets.size)
        assertTrue(catalog.assets.size >= 15)

        val btc = catalog.market("BTC-USDT")
        assertNotNull(btc)
        btc!!
        assertEquals(1, btc.symbolId)
        assertEquals(0, BigDecimal("0.01").compareTo(btc.tickSize))
        assertEquals(0, BigDecimal("0.00000001").compareTo(btc.stepSize))
        assertEquals(0, BigDecimal("5").compareTo(btc.minNotional))
        assertEquals(8, btc.baseScale)
        assertEquals(6, btc.quoteScale)
        assertEquals(MarketStatus.ENABLED, btc.status)
    }

    @Test
    fun `lookup by id and asset ledger id`() {
        assertEquals("ETH-USDT", catalog.market(2)?.symbol)
        assertEquals("USDT", catalog.assetByLedgerId(1)?.symbol)
        assertNull(catalog.market("NOPE-USDT"))
    }

    @Test
    fun `disabled pairs do not accept new quotes`() {
        val disabled = catalog.market("BTC-USDC")!!
        assertEquals(MarketStatus.DISABLED, disabled.status)
        assertEquals(false, disabled.status.acceptsNewQuotes)
        assertEquals(18, catalog.markets.count { it.status.acceptsNewQuotes })
    }

    @Test
    fun `absent limit fields mean no cap`() {
        val btc = catalog.market("BTC-USDT")!!
        assertNull(btc.maxOrderNotional)
        assertEquals(0, catalog.maxOpenOrdersTotal)
    }

    @Test
    fun `local validation uses catalog rules`() {
        val btc = catalog.market("BTC-USDT")!!
        assertNull(Numeric.validate(btc, BigDecimal("77512.01"), BigDecimal("0.0001")))
        assertEquals(
            Numeric.Violation.BELOW_MIN_NOTIONAL,
            Numeric.validate(btc, BigDecimal("77512.01"), BigDecimal("0.00001")),
        )
    }

    @Test
    fun `empty catalog is rejected`() {
        assertThrows(ApiError.BadResponse::class.java) {
            CatalogRepository.toCatalog(SpotConfigResponse())
        }
    }

    @Test
    fun `requireCatalog fails closed before hydration`() {
        val repo = CatalogRepository(api = mockk())
        assertThrows(CatalogNotReadyException::class.java) { repo.requireCatalog() }
    }
}
