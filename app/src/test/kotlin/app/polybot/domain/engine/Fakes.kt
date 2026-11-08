package app.polybot.domain.engine

import app.polybot.core.result.ApiError
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.Market
import app.polybot.domain.model.MarketStatus
import app.polybot.domain.model.OrderRequest
import app.polybot.domain.model.RemoteOrder
import app.polybot.domain.model.RemoteStatus
import app.polybot.domain.model.Side
import java.math.BigDecimal

val btcUsdt = Market(
    symbolId = 1, symbol = "BTC-USDT", baseAsset = "BTC", quoteAsset = "USDT",
    tickSize = BigDecimal("0.01"), stepSize = BigDecimal("0.00000001"),
    minNotional = BigDecimal("5"), minQty = BigDecimal("0.00001"),
    baseScale = 8, quoteScale = 6, status = MarketStatus.ENABLED,
    maxOrderNotional = null, maxMarketNotional = null, maxOpenOrders = 0,
)

class InMemoryOrderStore(private val run: String = "ab12") : OrderStore {
    var seq = 0L
        private set
    val rows = linkedMapOf<String, LocalOrder>()

    override suspend fun nextSeq() = ++seq
    override suspend fun runShort() = run
    override suspend fun save(order: LocalOrder) { rows[order.clientOrderId] = order }
    override suspend fun get(clientOrderId: String) = rows[clientOrderId]
    override suspend fun active() = rows.values.filter { !it.status.terminal }
    override suspend fun recent(limit: Int) = rows.values.toList().takeLast(limit).reversed()
}

sealed interface CreateBehavior {
    data object Ok : CreateBehavior

    /** Server menyimpan order lalu koneksi gagal / error dilempar (hasil ambigu atau duplikat). */
    data class StoreThen(val error: ApiError) : CreateBehavior

    /** Error dilempar tanpa menyimpan apa pun. */
    data class Fail(val error: ApiError) : CreateBehavior
}

class FakeExchange : ExchangeGateway {
    /** Kebenaran di sisi server. */
    val orders = linkedMapOf<String, RemoteOrder>()
    val createRequests = mutableListOf<OrderRequest>()
    val cancelRequests = mutableListOf<String>()
    val cancelAllRequests = mutableListOf<List<Long>>()
    var getCalls = 0
        private set
    val script = ArrayDeque<CreateBehavior>()
    var getFailure: ApiError? = null
    var listFailure: ApiError? = null
    var cancelFailure: ApiError? = null

    /** Bila true, cancel hanya mengubah order menjadi PENDING_CANCEL. */
    var cancelIsSlow = false
    var onCreate: (suspend () -> Unit)? = null
    private var nextOrderId = 1000L

    override suspend fun create(request: OrderRequest): Long {
        createRequests += request
        onCreate?.invoke()
        when (val behavior = script.removeFirstOrNull() ?: CreateBehavior.Ok) {
            CreateBehavior.Ok -> {
                orders[request.clientOrderId]?.let { throw ApiError.DuplicateClientOrderId(it.orderId) }
                return store(request).orderId
            }
            is CreateBehavior.StoreThen -> {
                if (request.clientOrderId !in orders) store(request)
                throw behavior.error
            }
            is CreateBehavior.Fail -> throw behavior.error
        }
    }

    override suspend fun cancel(clientOrderId: String, symbolId: Long) {
        cancelRequests += clientOrderId
        cancelFailure?.let { throw it }
        val order = orders[clientOrderId] ?: throw ApiError.NotFound("not_found", "order")
        if (order.status != RemoteStatus.WORKING && order.status != RemoteStatus.PENDING) {
            throw ApiError.NotFound("not_found", "order sudah tertutup")
        }
        orders[clientOrderId] = order.copy(status = if (cancelIsSlow) RemoteStatus.PENDING_CANCEL else RemoteStatus.CANCELED)
    }

    override suspend fun cancelAll(symbolIds: List<Long>) {
        cancelAllRequests += symbolIds
        orders.replaceAll { _, o ->
            if (o.symbolId in symbolIds && o.status == RemoteStatus.WORKING) o.copy(status = RemoteStatus.CANCELED) else o
        }
    }

    override suspend fun get(clientOrderId: String): RemoteOrder? {
        getCalls++
        getFailure?.let { throw it }
        return orders[clientOrderId]
    }

    override suspend fun listOpen(): List<RemoteOrder> {
        listFailure?.let { throw it }
        return orders.values.filter {
            it.status == RemoteStatus.WORKING || it.status == RemoteStatus.PENDING || it.status == RemoteStatus.PENDING_CANCEL
        }
    }

    fun put(order: RemoteOrder) { orders[order.clientOrderId] = order }

    fun remote(
        clientOrderId: String,
        status: RemoteStatus = RemoteStatus.WORKING,
        filled: Long = 0,
        orderId: Long = nextOrderId++,
    ) = RemoteOrder(
        orderId = orderId, clientOrderId = clientOrderId, symbolId = 1, side = Side.BUY, status = status,
        priceTicks = 77_000_000_000_000, qtyScaled = 10_000, filledScaled = filled, postOnly = true, tif = "GTC",
    )

    private fun store(request: OrderRequest): RemoteOrder =
        remote(request.clientOrderId).copy(
            symbolId = request.symbolId, side = request.side, priceTicks = request.priceTicks,
            qtyScaled = request.qtyScaled, postOnly = request.postOnly, tif = request.tif,
        ).also { orders[request.clientOrderId] = it }
}
