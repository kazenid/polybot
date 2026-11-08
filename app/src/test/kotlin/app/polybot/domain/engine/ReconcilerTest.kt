package app.polybot.domain.engine

import app.polybot.core.result.ApiError
import app.polybot.core.time.Clock
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.RemoteStatus
import app.polybot.domain.model.Side
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconcilerTest {
    private val store = InMemoryOrderStore()
    private val exchange = FakeExchange()
    private var now = 1_000_000L
    private val reconciler = Reconciler(exchange, store, Clock { now })

    private fun local(id: String, status: LocalStatus, updatedAt: Long = now) = LocalOrder(
        clientOrderId = id, orderId = null, symbolId = 1, side = Side.BUY, priceTicks = 77_000_000_000_000,
        qtyScaled = 10_000, filledScaled = 0, postOnly = true, tif = "GTC", status = status, reason = null,
        createdAtMs = updatedAt, updatedAtMs = updatedAt,
    )

    @Test
    fun `fills on an open order are picked up`() = runBlocking {
        store.save(local("pbab12-1", LocalStatus.OPEN))
        exchange.put(exchange.remote("pbab12-1", RemoteStatus.WORKING, filled = 4_000))

        val report = reconciler.run()

        assertEquals(1, report.updated)
        assertEquals(4_000L, store.get("pbab12-1")!!.filledScaled)
        assertEquals(LocalStatus.OPEN, store.get("pbab12-1")!!.status)
    }

    @Test
    fun `order that left the open list is resolved from its final state`() = runBlocking {
        store.save(local("pbab12-1", LocalStatus.OPEN))
        exchange.put(exchange.remote("pbab12-1", RemoteStatus.FILLED, filled = 10_000))

        reconciler.run()

        assertEquals(LocalStatus.FILLED, store.get("pbab12-1")!!.status)
    }

    @Test
    fun `pending order in flight is left alone, then rejected once proven absent`() = runBlocking {
        store.save(local("pbab12-1", LocalStatus.PENDING, updatedAt = now))

        val early = reconciler.run()
        assertEquals(0, early.updated)
        assertEquals(LocalStatus.PENDING, store.get("pbab12-1")!!.status)

        now += 31_000
        val late = reconciler.run()
        assertEquals(listOf("pbab12-1"), late.rejected)
        assertEquals(LocalStatus.REJECTED, store.get("pbab12-1")!!.status)
    }

    @Test
    fun `open order that vanished is flagged, not guessed`() = runBlocking {
        store.save(local("pbab12-1", LocalStatus.OPEN))

        val report = reconciler.run()

        assertEquals(listOf("pbab12-1"), report.unresolved)
        assertFalse(report.clean)
        assertEquals(LocalStatus.UNKNOWN, store.get("pbab12-1")!!.status)
    }

    @Test
    fun `orphans of this bot are adopted and foreign orders are never touched`() = runBlocking {
        exchange.put(exchange.remote("pbzz99-7"))
        exchange.put(exchange.remote("someone-else-1"))
        exchange.put(exchange.remote(""))

        val report = reconciler.run(OrphanPolicy.ADOPT)

        assertEquals(listOf("pbzz99-7"), report.adopted)
        assertEquals(2, report.foreignIgnored)
        assertTrue(store.get("pbzz99-7")!!.adopted)
        assertEquals(LocalStatus.OPEN, store.get("pbzz99-7")!!.status)
        assertTrue(exchange.cancelRequests.isEmpty())
    }

    @Test
    fun `cancel policy cancels only our orphans`() = runBlocking {
        exchange.put(exchange.remote("pbzz99-7"))
        exchange.put(exchange.remote("someone-else-1"))

        val report = reconciler.run(OrphanPolicy.CANCEL)

        assertEquals(listOf("pbzz99-7"), report.canceledOrphans)
        assertEquals(listOf("pbzz99-7"), exchange.cancelRequests)
        assertEquals(LocalStatus.CANCEL_REQUESTED, store.get("pbzz99-7")!!.status)
    }

    @Test
    fun `a failed open-orders fetch changes nothing`() {
        runBlocking { store.save(local("pbab12-1", LocalStatus.OPEN)) }
        exchange.listFailure = ApiError.Timeout()

        assertThrows(ApiError.Timeout::class.java) { runBlocking { reconciler.run() } }

        assertEquals(LocalStatus.OPEN, store.rows.getValue("pbab12-1").status)
    }

    @Test
    fun `lookup failure is reported and other orders still reconcile`() = runBlocking {
        store.save(local("pbab12-1", LocalStatus.OPEN))
        store.save(local("pbab12-2", LocalStatus.OPEN))
        exchange.put(exchange.remote("pbab12-2", RemoteStatus.WORKING, filled = 1))
        exchange.getFailure = ApiError.Timeout()

        val report = reconciler.run()

        assertEquals(listOf("pbab12-1"), report.unresolved)
        assertEquals(1L, store.get("pbab12-2")!!.filledScaled)
    }

    @Test
    fun `second run is a no-op`() = runBlocking {
        store.save(local("pbab12-1", LocalStatus.OPEN))
        exchange.put(exchange.remote("pbab12-1", RemoteStatus.WORKING, filled = 5))
        exchange.put(exchange.remote("pbzz99-7"))

        reconciler.run()
        val second = reconciler.run()

        assertEquals(0, second.updated)
        assertTrue(second.adopted.isEmpty())
    }

    @Test
    fun `cancel requested stays requested while the server still lists it working`() = runBlocking {
        store.save(local("pbab12-1", LocalStatus.CANCEL_REQUESTED))
        exchange.put(exchange.remote("pbab12-1", RemoteStatus.WORKING))

        reconciler.run()

        assertEquals(LocalStatus.CANCEL_REQUESTED, store.get("pbab12-1")!!.status)
    }
}
