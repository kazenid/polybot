package app.polybot.data.orders

import app.polybot.core.result.ApiError
import app.polybot.data.api.dto.CancelAllOrdersRequest
import app.polybot.data.api.dto.CancelOrderRequest
import app.polybot.data.api.dto.CreateOrderRequest
import app.polybot.data.api.dto.LimitGtcDto
import app.polybot.data.api.dto.LimitGtdDto
import app.polybot.data.api.dto.LimitPriceDto
import app.polybot.data.api.dto.OrderDto
import app.polybot.data.api.dto.OrderIntentDto
import app.polybot.data.api.services.OrdersApi
import app.polybot.domain.engine.ExchangeGateway
import app.polybot.domain.model.OrderRequest
import app.polybot.domain.model.RemoteOrder
import app.polybot.domain.model.RemoteStatus
import app.polybot.domain.model.Side
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectExchangeGateway @Inject constructor(private val api: OrdersApi) : ExchangeGateway {
    override suspend fun create(request: OrderRequest): Long =
        api.create(CreateOrderRequest(toIntent(request))).orderId

    override suspend fun cancel(clientOrderId: String, symbolId: Long) {
        api.cancel(CancelOrderRequest(clientOrderId, symbolId))
    }

    override suspend fun cancelAll(symbolIds: List<Long>) {
        // request_id berbeda tiap pemanggilan: kill switch yang diulang pengguna tidak boleh dianggap duplikat.
        api.cancelAll(CancelAllOrdersRequest(symbolIds, "pbkill-${java.util.UUID.randomUUID().toString().take(18)}"))
    }

    override suspend fun get(clientOrderId: String): RemoteOrder? = try {
        api.get(clientOrderId).order?.takeIf { it.orderId != 0L }?.let(::toRemote)
    } catch (e: ApiError.NotFound) {
        null
    }

    override suspend fun listOpen(): List<RemoteOrder> {
        val result = mutableListOf<RemoteOrder>()
        var token = ""
        do {
            val page = api.openOrders(pageToken = token)
            page.orders.mapTo(result, ::toRemote)
            token = page.nextPageToken
        } while (token.isNotEmpty() && result.size < MAX_OPEN_ORDERS)
        return result
    }

    private companion object {
        const val MAX_OPEN_ORDERS = 1_000

        /** Selama server belum terbukti mendukung mode lain, taker yang akan menabrak order sendiri yang dibatalkan. */
        const val SELF_TRADE_MODE = "EXPIRE_TAKER"

        fun toIntent(r: OrderRequest): OrderIntentDto {
            val base = OrderIntentDto(
                symbolId = r.symbolId,
                side = r.side.name,
                baseQtyScaled = r.qtyScaled,
                clientOrderId = r.clientOrderId,
                selfTradePreventionMode = SELF_TRADE_MODE,
            )
            return when (r.tif) {
                "GTC" -> base.copy(limitGtc = LimitGtcDto(r.priceTicks, r.postOnly))
                "GTD" -> base.copy(
                    limitGtd = LimitGtdDto(
                        r.priceTicks, r.postOnly,
                        Instant.ofEpochMilli(requireNotNull(r.expireAtMs) { "GTD butuh expireAt" }).toString(),
                    ),
                )
                "IOC" -> base.copy(limitIoc = LimitPriceDto(r.priceTicks))
                "FOK" -> base.copy(limitFok = LimitPriceDto(r.priceTicks))
                else -> throw IllegalArgumentException("TIF tidak didukung: ${r.tif}")
            }
        }

        fun toRemote(o: OrderDto) = RemoteOrder(
            orderId = o.orderId,
            clientOrderId = o.clientOrderId,
            symbolId = o.symbolId,
            side = when (o.side) {
                "BUY" -> Side.BUY
                "SELL" -> Side.SELL
                else -> null
            },
            status = when (o.status.removePrefix("ORDER_STATUS_")) {
                "PENDING" -> RemoteStatus.PENDING
                "WORKING" -> RemoteStatus.WORKING
                "PENDING_CANCEL" -> RemoteStatus.PENDING_CANCEL
                "FILLED" -> RemoteStatus.FILLED
                "CANCELED" -> RemoteStatus.CANCELED
                "REJECTED" -> RemoteStatus.REJECTED
                else -> RemoteStatus.UNKNOWN
            },
            priceTicks = o.priceTicks,
            qtyScaled = o.origQtyScaled,
            filledScaled = o.cumQtyScaled,
            postOnly = o.postOnly,
            tif = o.timeInForce.removePrefix("TIME_IN_FORCE_"),
        )
    }
}
