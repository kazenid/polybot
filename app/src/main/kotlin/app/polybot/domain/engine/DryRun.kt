package app.polybot.domain.engine

import app.polybot.core.result.ApiError
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.OrderRequest
import app.polybot.domain.model.RemoteOrder
import app.polybot.domain.model.RemoteStatus

/** Penyimpanan di memori untuk dry run: hilang saat proses berakhir dan tidak menyentuh Room. */
class VolatileOrderStore(private val run: String = "dry0") : OrderStore {
    private var seq = 0L
    private val rows = linkedMapOf<String, LocalOrder>()

    override suspend fun nextSeq(): Long = ++seq
    override suspend fun runShort(): String = run
    override suspend fun save(order: LocalOrder) { rows[order.clientOrderId] = order }
    override suspend fun get(clientOrderId: String): LocalOrder? = rows[clientOrderId]
    override suspend fun active(): List<LocalOrder> = rows.values.filter { !it.status.terminal }
    override suspend fun recent(limit: Int): List<LocalOrder> = rows.values.toList().takeLast(limit).reversed()
}

/**
 * Bursa palsu untuk dry run: menerima semua order, tidak pernah mengisinya, dan tidak melakukan panggilan jaringan.
 * Dipakai supaya jalur kode engine sama persis dengan mode live.
 */
class DryRunExchange : ExchangeGateway {
    private val orders = linkedMapOf<String, RemoteOrder>()
    private var nextOrderId = 1L

    override suspend fun create(request: OrderRequest): Long {
        orders[request.clientOrderId]?.let { throw ApiError.DuplicateClientOrderId(it.orderId) }
        val id = nextOrderId++
        orders[request.clientOrderId] = RemoteOrder(
            orderId = id, clientOrderId = request.clientOrderId, symbolId = request.symbolId, side = request.side,
            status = RemoteStatus.WORKING, priceTicks = request.priceTicks, qtyScaled = request.qtyScaled,
            filledScaled = 0, postOnly = request.postOnly, tif = request.tif,
        )
        return id
    }

    override suspend fun cancel(clientOrderId: String, symbolId: Long) {
        val order = orders[clientOrderId]
        if (order == null || order.status != RemoteStatus.WORKING) throw ApiError.NotFound("not_found", "order")
        orders[clientOrderId] = order.copy(status = RemoteStatus.CANCELED)
    }

    override suspend fun cancelAll(symbolIds: List<Long>) {
        orders.replaceAll { _, o ->
            if (o.symbolId in symbolIds && o.status == RemoteStatus.WORKING) o.copy(status = RemoteStatus.CANCELED) else o
        }
    }

    override suspend fun get(clientOrderId: String): RemoteOrder? = orders[clientOrderId]

    override suspend fun listOpen(): List<RemoteOrder> = orders.values.filter { it.status == RemoteStatus.WORKING }
}
