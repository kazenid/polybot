package app.polybot.data.api

import app.polybot.data.api.dto.ApiJson
import app.polybot.data.api.dto.BalancesResponse
import app.polybot.data.api.dto.CandlesResponse
import app.polybot.data.api.dto.MeResponse
import app.polybot.data.api.dto.OpenOrdersResponse
import app.polybot.data.api.dto.OrderBookResponse
import app.polybot.data.api.dto.TradesResponse
import app.polybot.domain.model.Candle
import app.polybot.domain.numeric.Numeric
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Respons asli dari api.testnet.polyester.com (31 Okt 2026), kecuali bagian bertanda "stub". */
class MarketDataDtoTest {
    @Test
    fun `order book`() {
        val body = """{"symbolId":1,"bookSeq":"10975826","bids":[{"priceTicks":"77512010000000","qtyScaled":"173683226"},{"priceTicks":"77512000000000","qtyScaled":"5033501"}],"asks":[{"priceTicks":"77515040000000","qtyScaled":"1481228"}],"ts":"2026-10-31T15:44:10.929219936Z"}"""
        val book = ApiJson.decodeFromString(OrderBookResponse.serializer(), body)

        assertEquals(2, book.bids.size)
        assertEquals(0, BigDecimal("77512.01").compareTo(Numeric.ticksToPrice(book.bids[0].priceTicks)))
        assertEquals(0, BigDecimal("77515.04").compareTo(Numeric.ticksToPrice(book.asks[0].priceTicks)))
        assertTrue(book.bids[0].priceTicks > 0 && book.bids[0].priceTicks < book.asks[0].priceTicks)
    }

    @Test
    fun `candles are newest first and convert to ordered decimals`() {
        val body = """{"symbolId":1, "timeframe":"MIN_1", "candles":[{"tsSec":"1793461380", "open":"77615140000000", "high":"77649940000000", "low":"77395500000000", "close":"77512640000000", "volume":"155788813", "quoteVolume":"120788.5655522772"}, {"tsSec":"1793461320", "open":"77529650000000", "high":"77684360000000", "low":"77452800000000", "close":"77615140000000", "volume":"160381103", "quoteVolume":"124414.6294897013"}], "nextPageToken":"abc"}"""
        val response = ApiJson.decodeFromString(CandlesResponse.serializer(), body)

        val candles = response.candles.map {
            Candle(
                it.tsSec, Numeric.ticksToPrice(it.open), Numeric.ticksToPrice(it.high), Numeric.ticksToPrice(it.low),
                Numeric.ticksToPrice(it.close), Numeric.scaledToQty(it.volume, 8), it.isClosed,
            )
        }.sortedBy { it.tsSec }

        assertEquals(listOf(1793461320L, 1793461380L), candles.map { it.tsSec })
        assertEquals(0, BigDecimal("77615.14").compareTo(candles[1].open))
        assertEquals(0, BigDecimal("77615.14").compareTo(candles[0].close)) // close menit lama = open menit baru
        assertFalse(candles[0].isClosed, "isClosed dihilangkan server saat false")
    }

    @Test
    fun `trades omit isBuy for sells`() {
        val body = """{"trades":[{"symbolId":1, "matchId":"281474979214634", "isBuy":true, "priceTicks":"77515040000000", "qtyScaled":"230000", "tsNs":"1793461450428374869"}, {"symbolId":1, "matchId":"281474979214633", "priceTicks":"77512010000000", "qtyScaled":"266097", "tsNs":"1793461449373458365"}], "nextPageToken":"cGc"}"""
        val trades = ApiJson.decodeFromString(TradesResponse.serializer(), body).trades

        assertTrue(trades[0].isBuy)
        assertFalse(trades[1].isBuy)
        assertEquals(281_474_979_214_634, trades[0].matchId)
        assertEquals(1_793_461_450_428_374_869, trades[0].tsNs)
    }

    @Test
    fun `stub - balances with U128 as hi lo strings and empty response`() {
        val body = """{"balances":[{"assetId":"1","trading":{"hi":"0","lo":"1500000000000000000"},"funding":{"lo":"2000000000000000000"},"available":{"lo":"1000000000000000000"}}]}"""
        val b = ApiJson.decodeFromString(BalancesResponse.serializer(), body).balances.single()

        assertEquals(0, BigDecimal("1.5").compareTo(Numeric.u128ToDecimal(b.trading.hi, b.trading.lo)))
        assertEquals(0, BigDecimal("2").compareTo(Numeric.u128ToDecimal(b.funding.hi, b.funding.lo)))
        assertEquals(0, BigDecimal.ZERO.compareTo(Numeric.u128ToDecimal(b.reserved.hi, b.reserved.lo)))
        assertEquals(0, ApiJson.decodeFromString(BalancesResponse.serializer(), "{}").balances.size)
    }

    @Test
    fun `stub - open orders tolerate unknown fields and omitted zeros`() {
        val body = """{"orders":[{"orderId":"9","symbolId":"1","clientOrderId":"pbabc-1","side":"BUY","status":"WORKING","orderType":"LIMIT","timeInForce":"GTC","postOnly":true,"origQtyScaled":"100000","leavesQtyScaled":"100000","priceTicks":"77000000000000","createdTsNs":"1793461450428374869","somethingNew":{"x":1}}]}"""
        val o = ApiJson.decodeFromString(OpenOrdersResponse.serializer(), body).orders.single()

        assertEquals(9, o.orderId)
        assertEquals("WORKING", o.status)
        assertEquals(0, o.cumQtyScaled)
        assertTrue(o.postOnly)
    }

    @Test
    fun `stub - Me accepts a string account id, a numeric one, and unknown fields`() {
        val text = ApiJson.decodeFromString(
            MeResponse.serializer(),
            """{"accountId":"RLxqJGUDg92","apiKeyId":"ak_abc","username":"alice","session":{"x":1},"extra":[1,2]}""",
        )
        assertEquals("RLxqJGUDg92", text.accountId)
        assertEquals("ak_abc", text.apiKeyId)

        assertEquals("42", ApiJson.decodeFromString(MeResponse.serializer(), """{"accountId":"42"}""").accountId)
        assertEquals("42", ApiJson.decodeFromString(MeResponse.serializer(), """{"accountId":42}""").accountId)
        assertEquals("", ApiJson.decodeFromString(MeResponse.serializer(), "{}").accountId)
    }
}
