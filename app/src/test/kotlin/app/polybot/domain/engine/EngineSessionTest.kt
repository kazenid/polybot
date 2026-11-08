package app.polybot.domain.engine

import app.polybot.core.log.RingLogger
import app.polybot.core.time.Clock
import app.polybot.domain.config.BotConfig
import app.polybot.domain.model.Catalog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineSessionTest {
    private var now = 1_800_000_000_000L
    private val clock = Clock { now }
    private val store = InMemoryOrderStore()
    private val exchange = FakeExchange()
    private val data = FakeData(testSnapshot(quoteTotal = "9250", baseTotal = "0.00974026").inputs)
    private val log = RingLogger(clock = clock)
    private val live = EngineComponents(
        exchange, store, OrderManager(exchange, store, ClientOrderIds(store), clock, pause = {}), Reconciler(exchange, store, clock),
    )
    private val catalog = Catalog(listOf(btcUsdt), emptyList(), 0, 0)
    private val engine = BotEngine(live, { error("tidak dipakai") }, data, { catalog }, log, clock)
    private val config = BotConfig(dryRun = false)
    private val state get() = engine.status.value.state

    @Test
    fun `every start attempt gets a new session id, even a rejected one`() = runBlocking {
        val before = engine.status.value.sessionId
        assertFalse(engine.start(BotConfig(symbol = "DOGE-USDT")))
        val rejected = engine.status.value.sessionId
        assertEquals(before + 1, rejected)
        assertTrue(engine.start(config))
        assertEquals(rejected + 1, engine.status.value.sessionId)
        assertNotNull(engine.status.value.startedAtMs)
        assertFalse(engine.start(config), "start ganda tidak membuat sesi baru")
        assertEquals(rejected + 1, engine.status.value.sessionId)
    }

    @Test
    fun `losing the network pauses the bot and regaining it resumes it`() = runBlocking {
        engine.start(config)
        engine.tick()
        assertEquals(2, exchange.createRequests.size)

        engine.networkChanged(false)
        val paused = state as EngineState.Paused
        assertTrue(paused.auto)
        assertEquals(BotEngine.NETWORK_REASON, paused.reason)
        assertTrue(store.active().size == 2, "order tidak dibatalkan saat offline (tidak mungkin tanpa jaringan)")

        engine.networkChanged(true)
        assertEquals(EngineState.Running, state)
        now += 10_000
        engine.tick()
        assertEquals(2, exchange.createRequests.size, "tidak ada order ganda setelah jaringan kembali")
    }

    @Test
    fun `regaining the network never un-pauses a different pause`() = runBlocking {
        engine.start(config)
        engine.pause("Dijeda pengguna")
        engine.networkChanged(false)
        engine.networkChanged(true)
        assertEquals(EngineState.Paused("Dijeda pengguna", auto = false), state)
    }

    @Test
    fun `network events outside a session do nothing`() = runBlocking {
        engine.networkChanged(false)
        engine.networkChanged(true)
        assertEquals(EngineState.Idle, state)
    }

    @Test
    fun `after a restart the engine reconciles before it places anything new`() = runBlocking {
        // Sesi sebelumnya sempat memasang order, lalu proses mati: server masih memegangnya, catatan lokal hilang.
        exchange.put(exchange.remote("pbzz99-1"))
        exchange.put(exchange.remote("pbzz99-2", filled = 3_000))
        exchange.put(exchange.remote("manual-1"))

        engine.start(config)

        assertEquals(setOf("pbzz99-1", "pbzz99-2"), store.rows.keys, "order bot lama diadopsi, order manual tidak")
        engine.tick()
        assertEquals(0, exchange.createRequests.size, "order yatim yang diadopsi dihitung; tidak ada kuotasi tambahan")
        assertTrue(store.rows.getValue("pbzz99-2").filledScaled == 3_000L)
    }
}
