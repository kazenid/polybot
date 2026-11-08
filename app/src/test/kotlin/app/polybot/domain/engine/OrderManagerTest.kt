package app.polybot.domain.engine

import app.polybot.core.result.ApiError
import app.polybot.core.time.Clock
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.MarketStatus
import app.polybot.domain.model.RemoteStatus
import app.polybot.domain.model.Side
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderManagerTest {
    private val store = InMemoryOrderStore()
    private val exchange = FakeExchange()
    private val pauses = mutableListOf<Long>()
    private var now = 1_000_000L
    private val manager = OrderManager(exchange, store, ClientOrderIds(store), Clock { now }, pause = { pauses += it })

    private val price = BigDecimal("77000.00")
    private val qty = BigDecimal("0.0001")

    private suspend fun submit() = manager.submit(btcUsdt, Side.BUY, price, qty)

    @Test
    fun `order is persisted as PENDING before the first byte is sent`() = runBlocking {
        var statusAtSend: List<LocalStatus>? = null
        exchange.onCreate = { statusAtSend = store.active().map { it.status } }

        val outcome = submit()

        assertEquals(listOf(LocalStatus.PENDING), statusAtSend)
        val accepted = assertInstanceOf(SubmitOutcome.Accepted::class.java, outcome).order
        assertEquals(LocalStatus.OPEN, accepted.status)
        assertEquals(1000L, accepted.orderId)
        assertEquals(LocalStatus.OPEN, store.get(accepted.clientOrderId)!!.status)
        val sent = exchange.createRequests.single()
        assertEquals(77_000_000_000_000L, sent.priceTicks)
        assertEquals(10_000L, sent.qtyScaled)
        assertTrue(sent.postOnly)
    }

    @Test
    fun `local validation failure sends nothing and burns no id`() = runBlocking {
        val off = manager.submit(btcUsdt, Side.BUY, BigDecimal("77000.005"), qty)
        val tiny = manager.submit(btcUsdt, Side.BUY, price, BigDecimal("0.00001"))
        val halted = manager.submit(btcUsdt.copy(status = MarketStatus.HALTED), Side.BUY, price, qty)

        listOf(off, tiny, halted).forEach { assertInstanceOf(SubmitOutcome.Rejected::class.java, it) }
        assertTrue(exchange.createRequests.isEmpty())
        assertTrue(store.rows.isEmpty())
        assertEquals(0L, store.seq)
    }

    @Test
    fun `timeout after the server accepted is adopted without a second create`() = runBlocking {
        exchange.script += CreateBehavior.StoreThen(ApiError.Timeout())

        val order = assertInstanceOf(SubmitOutcome.Accepted::class.java, submit()).order

        assertEquals(1, exchange.createRequests.size)
        assertEquals(LocalStatus.OPEN, order.status)
        assertEquals(1, exchange.getCalls)
    }

    @Test
    fun `timeout before the server saw it is resent with the same id`() = runBlocking {
        exchange.script += CreateBehavior.Fail(ApiError.Timeout())

        val order = assertInstanceOf(SubmitOutcome.Accepted::class.java, submit()).order

        assertEquals(2, exchange.createRequests.size)
        assertEquals(exchange.createRequests[0].clientOrderId, exchange.createRequests[1].clientOrderId)
        assertEquals(LocalStatus.OPEN, order.status)
        assertEquals(1, exchange.orders.size)
    }

    @Test
    fun `ambiguous failure that cannot be read back is left unresolved and never resent`() = runBlocking {
        exchange.script += CreateBehavior.Fail(ApiError.Network(java.io.IOException("down")))
        exchange.getFailure = ApiError.Timeout()

        val order = assertInstanceOf(SubmitOutcome.Unresolved::class.java, submit()).order

        assertEquals(1, exchange.createRequests.size)
        assertEquals(LocalStatus.UNKNOWN, order.status)
        assertEquals(LocalStatus.UNKNOWN, store.get(order.clientOrderId)!!.status)
    }

    @Test
    fun `outcome unknown from the server is read back, not retried blindly`() = runBlocking {
        exchange.script += CreateBehavior.StoreThen(ApiError.OutcomeUnknown("ERROR_CODE_ORDER_CREATE_OUTCOME_UNKNOWN", "x"))
        assertInstanceOf(SubmitOutcome.Accepted::class.java, submit())
        assertEquals(1, exchange.createRequests.size)
    }

    @Test
    fun `duplicate id adopts the existing order`() = runBlocking {
        exchange.script += CreateBehavior.StoreThen(ApiError.DuplicateClientOrderId(null))

        val order = assertInstanceOf(SubmitOutcome.Accepted::class.java, submit()).order

        assertEquals(LocalStatus.OPEN, order.status)
        assertEquals(1000L, order.orderId)
    }

    @Test
    fun `duplicate id with only the existing order id still adopts`() = runBlocking {
        exchange.script += CreateBehavior.Fail(ApiError.DuplicateClientOrderId(777))
        val order = assertInstanceOf(SubmitOutcome.Accepted::class.java, submit()).order
        assertEquals(777L, order.orderId)
    }

    @Test
    fun `auth and validation rejections are final`() = runBlocking {
        exchange.script += CreateBehavior.Fail(ApiError.Auth("SIGNATURE_INVALID", "bad key"))

        val rejected = assertInstanceOf(SubmitOutcome.Rejected::class.java, submit())

        assertEquals(1, exchange.createRequests.size)
        assertEquals(0, exchange.getCalls)
        assertEquals(LocalStatus.REJECTED, rejected.order!!.status)
        assertInstanceOf(ApiError.Auth::class.java, rejected.error)
    }

    @Test
    fun `rate limit waits then resends with the same id`() = runBlocking {
        exchange.script += CreateBehavior.Fail(ApiError.RateLimited(1_500))

        assertInstanceOf(SubmitOutcome.Accepted::class.java, submit())

        assertEquals(listOf(1_500L), pauses)
        assertEquals(2, exchange.createRequests.size)
        assertEquals(exchange.createRequests[0].clientOrderId, exchange.createRequests[1].clientOrderId)
    }

    @Test
    fun `persistent rate limit gives up as rejected`() = runBlocking {
        repeat(5) { exchange.script += CreateBehavior.Fail(ApiError.RateLimited(100)) }
        val rejected = assertInstanceOf(SubmitOutcome.Rejected::class.java, submit())
        assertEquals(3, exchange.createRequests.size)
        assertEquals(LocalStatus.REJECTED, rejected.order!!.status)
    }

    @Test
    fun `each submit gets a new id`() = runBlocking {
        submit(); submit()
        val ids = exchange.createRequests.map { it.clientOrderId }
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `cancel is confirmed by reading the order back`() = runBlocking {
        val id = (submit() as SubmitOutcome.Accepted).order.clientOrderId

        val closed = assertInstanceOf(CancelOutcome.Closed::class.java, manager.cancel(id)).order

        assertEquals(LocalStatus.CANCELED, closed.status)
        assertEquals(listOf(id), exchange.cancelRequests)
        assertEquals(LocalStatus.CANCELED, store.get(id)!!.status)
    }

    @Test
    fun `slow cancel stays requested until the server closes it`() = runBlocking {
        exchange.cancelIsSlow = true
        val id = (submit() as SubmitOutcome.Accepted).order.clientOrderId

        val requested = assertInstanceOf(CancelOutcome.Requested::class.java, manager.cancel(id)).order

        assertEquals(LocalStatus.CANCEL_REQUESTED, requested.status)
        assertEquals(LocalStatus.CANCEL_REQUESTED, store.get(id)!!.status)
    }

    @Test
    fun `cancel of an order that already filled reports the fill`() = runBlocking {
        val id = (submit() as SubmitOutcome.Accepted).order.clientOrderId
        exchange.put(exchange.orders.getValue(id).copy(status = RemoteStatus.FILLED, filledScaled = 10_000))

        val closed = assertInstanceOf(CancelOutcome.Closed::class.java, manager.cancel(id)).order

        assertEquals(LocalStatus.FILLED, closed.status)
        assertEquals(10_000L, closed.filledScaled)
    }

    @Test
    fun `cancel edge cases`() = runBlocking {
        assertEquals(CancelOutcome.NotTracked, manager.cancel("pbab12-nope"))

        val id = (submit() as SubmitOutcome.Accepted).order.clientOrderId
        manager.cancel(id)
        exchange.cancelRequests.clear()
        assertInstanceOf(CancelOutcome.Closed::class.java, manager.cancel(id))
        assertTrue(exchange.cancelRequests.isEmpty(), "order terminal tidak dibatalkan lagi")
    }

    @Test
    fun `failed cancel keeps the order open`() = runBlocking {
        val id = (submit() as SubmitOutcome.Accepted).order.clientOrderId
        exchange.cancelFailure = ApiError.Auth("PERMISSION_DENIED", "no")

        assertInstanceOf(CancelOutcome.Failed::class.java, manager.cancel(id))

        assertEquals(LocalStatus.OPEN, store.get(id)!!.status)
    }
}
