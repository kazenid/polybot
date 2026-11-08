package app.polybot.data.orders

import app.polybot.data.db.CounterDao
import app.polybot.data.db.OrderDao
import app.polybot.data.db.OrderEntity
import app.polybot.domain.engine.OrderStore
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.Side
import kotlin.random.Random

class RoomOrderStore(private val orders: OrderDao, private val counters: CounterDao) : OrderStore {
    override suspend fun nextSeq(): Long = counters.increment(SEQ)

    override suspend fun runShort(): String {
        val value = counters.getOrCreate(RUN_SHORT, Random.nextLong(RUN_SHORT_SPACE))
        return value.toString(36).padStart(RUN_SHORT_LENGTH, '0')
    }

    override suspend fun save(order: LocalOrder) = orders.upsert(order.toEntity())

    override suspend fun get(clientOrderId: String): LocalOrder? = orders.get(clientOrderId)?.toDomain()

    override suspend fun active(): List<LocalOrder> = orders.active().map { it.toDomain() }

    override suspend fun recent(limit: Int): List<LocalOrder> = orders.recent(limit).map { it.toDomain() }

    private companion object {
        const val SEQ = "order_seq"
        const val RUN_SHORT = "run_short"
        const val RUN_SHORT_LENGTH = 4
        const val RUN_SHORT_SPACE = 1_679_616L // 36^4

        fun LocalOrder.toEntity() = OrderEntity(
            clientOrderId, orderId, symbolId, side.name, priceTicks, qtyScaled, filledScaled, postOnly, tif,
            status.name, reason, createdAtMs, updatedAtMs, adopted,
        )

        fun OrderEntity.toDomain() = LocalOrder(
            clientOrderId = clientOrderId, orderId = orderId, symbolId = symbolId, side = Side.valueOf(side),
            priceTicks = priceTicks, qtyScaled = qtyScaled, filledScaled = filledScaled, postOnly = postOnly,
            tif = tif, status = LocalStatus.valueOf(status), reason = reason, createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs, adopted = adopted,
        )
    }
}
