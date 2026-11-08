package app.polybot.data.api

import app.polybot.data.api.dto.ApiJson
import app.polybot.data.api.dto.UserTradesResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class UserTradeDtoTest {
    @Test
    fun `uint64 ids above int64 range still parse and stay distinct`() {
        val body = """{"trades":[{"matchId":"18446744073709551615","orderId":"18446744073709551614","priceTicks":"1","qtyScaled":"1"},{"matchId":"18446744073709551613","orderId":"5"}]}"""
        val trades = ApiJson.decodeFromString(UserTradesResponse.serializer(), body).trades

        assertEquals(-1L, trades[0].matchId)
        assertNotEquals(trades[0].matchId, trades[1].matchId)
        assertEquals(5L, trades[1].orderId)
    }

    @Test
    fun `timestamp accepts rfc3339 or nanoseconds`() {
        val body = """{"trades":[{"matchId":"1","tsNs":"2026-10-31T15:44:10Z"},{"matchId":"2","tsNs":"1793461380000000000"},{"matchId":"3","tsNs":"2026-10-31T15:44:10.929219936Z"}]}"""
        val trades = ApiJson.decodeFromString(UserTradesResponse.serializer(), body).trades

        assertEquals(1793461450L * 1_000_000_000L, trades[0].tsNs)
        assertEquals(1793461380000000000L, trades[1].tsNs)
        assertEquals(1793461450929219936L, trades[2].tsNs)
    }
}

class OrderDtoLenientTest {
    @Test
    fun `order history tolerates uint64 id and rfc3339 times`() {
        val body = """{"orders":[{"orderId":"18446744073709551615","symbolId":"1","createdTsNs":"2026-10-31T15:44:10Z","terminalTsNs":"2026-10-31T15:45:10.5Z"}]}"""
        val o = ApiJson.decodeFromString(app.polybot.data.api.dto.OpenOrdersResponse.serializer(), body).orders[0]

        assertEquals(-1L, o.orderId)
        assertEquals(1793461450L * 1_000_000_000L, o.createdTsNs)
        assertEquals(1793461510500000000L, o.terminalTsNs)
    }
}

class CreateOrderResponseTest {
    @Test
    fun `create response with 20 digit order id parses`() {
        val r = ApiJson.decodeFromString(app.polybot.data.api.dto.CreateOrderResponse.serializer(), """{"orderId":"10446744073709551615","clientOrderId":"pb1-1"}""")
        assertNotEquals(0L, r.orderId)
        assertEquals("pb1-1", r.clientOrderId)
    }
}
