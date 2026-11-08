package app.polybot.data.market

import app.polybot.data.api.dto.ApiJson
import app.polybot.data.api.dto.CandlesResponse
import app.polybot.data.api.dto.OrderBookResponse
import app.polybot.data.prefs.WatchlistStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MarketRepositoryTest {
    @Test
    fun `candles are sorted oldest first and scaled`() {
        // Bentuk respons asli testnet: terbaru dulu, harga ticks 1e9, isClosed hilang saat false.
        val body = """{"symbolId":1,"timeframe":"HOUR_1","candles":[{"tsSec":"1793527200","open":"78141160000000","high":"78540160000000","low":"78017150000000","close":"78017150000000","volume":"1121556951","quoteVolume":"877136.46"},{"tsSec":"1793523600","open":"78244680000000","high":"78461690000000","low":"77900910000000","close":"78141160000000","volume":"3719311795","isClosed":true,"quoteVolume":"2907542.39"}],"nextPageToken":"x"}"""
        val candles = MarketRepository.toCandles(ApiJson.decodeFromString(CandlesResponse.serializer(), body), volumeScale = 8)

        assertEquals(listOf(1793523600L, 1793527200L), candles.map { it.tsSec })
        assertEquals(0, BigDecimal("78244.68").compareTo(candles[0].open))
        assertEquals(0, BigDecimal("78141.16").compareTo(candles[0].close))
        assertEquals(0, BigDecimal("11.21556951").compareTo(candles[1].volume))
        assertEquals(true, candles[0].isClosed)
        assertFalse(candles[1].isClosed)
    }

    @Test
    fun `order book keeps best price first and mid is the average`() {
        val body = """{"symbolId":1,"bookSeq":"1","bids":[{"priceTicks":"100000000000","qtyScaled":"150000000"},{"priceTicks":"99000000000","qtyScaled":"1"}],"asks":[{"priceTicks":"101000000000","qtyScaled":"200000000"}],"ts":"2026-11-01T00:00:00Z"}"""
        val book = MarketRepository.toOrderBook(ApiJson.decodeFromString(OrderBookResponse.serializer(), body), qtyScale = 8)

        assertEquals(0, BigDecimal("100").compareTo(book.bids[0].price))
        assertEquals(0, BigDecimal("1.5").compareTo(book.bids[0].quantity))
        assertEquals(0, BigDecimal("100.5").compareTo(MarketRepository.mid(book)))
    }

    @Test
    fun `mid is null when a side is empty`() {
        val body = """{"symbolId":1,"bids":[{"priceTicks":"100000000000","qtyScaled":"1"}],"asks":[]}"""
        assertNull(MarketRepository.mid(MarketRepository.toOrderBook(ApiJson.decodeFromString(OrderBookResponse.serializer(), body), 8)))
    }
}

class WatchlistStoreTest {
    @Test
    fun `encode and decode keep order and drop duplicates`() {
        val raw = WatchlistStore.encode(listOf("ETH-USDT", "BTC-USDT", "ETH-USDT"))
        assertEquals("ETH-USDT,BTC-USDT", raw)
        assertEquals(listOf("ETH-USDT", "BTC-USDT"), WatchlistStore.decode(raw))
    }

    @Test
    fun `unset and empty are different`() {
        assertNull(WatchlistStore.decode(null))
        assertEquals(emptyList<String>(), WatchlistStore.decode(""))
    }

    @Test
    fun `defaults prefer BTC and ETH then fill with other active pairs`() {
        val active = listOf("AVAX-USDT", "BNB-USDT", "BTC-USDT", "DOGE-USDT", "ETH-USDT", "XRP-USDT")
        assertEquals(listOf("BTC-USDT", "ETH-USDT", "AVAX-USDT", "BNB-USDT", "DOGE-USDT"), WatchlistStore.defaults(active))
    }

    @Test
    fun `defaults work when the preferred pairs are not active`() {
        assertEquals(listOf("AVAX-USDT", "BNB-USDT"), WatchlistStore.defaults(listOf("AVAX-USDT", "BNB-USDT")))
        assertEquals(emptyList<String>(), WatchlistStore.defaults(emptyList()))
    }
}
