package app.polybot.domain.engine

import app.polybot.core.log.RingLogger
import app.polybot.core.result.ApiError
import app.polybot.core.time.Clock
import app.polybot.domain.config.BotConfig
import app.polybot.domain.config.GridConfig
import app.polybot.domain.config.RiskConfig
import app.polybot.domain.config.StrategyType
import app.polybot.domain.model.Catalog
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.Market
import app.polybot.domain.model.Side
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FakeData(var inputs: MarketInputs) : MarketDataSource {
    var failure: Throwable? = null
    var loadCalls = 0
        private set

    override suspend fun load(market: Market): MarketInputs {
        loadCalls++
        failure?.let { throw it }
        return inputs
    }
}

class BotEngineTest {
    private val t0 = 1_800_000_000_000L
    private var now = t0
    private val clock = Clock { now }

    /** Inventori di target (7,5% dari ekuitas 10.000): market maker memasang beli dan jual. */
    private fun normalInputs() = testSnapshot(quoteTotal = "9250", baseTotal = "0.00974026").inputs

    private val store = InMemoryOrderStore()
    private val exchange = FakeExchange()
    private val data = FakeData(normalInputs())
    private val log = RingLogger(clock = clock)
    private val live = EngineComponents(
        exchange, store, OrderManager(exchange, store, ClientOrderIds(store), clock, pause = {}), Reconciler(exchange, store, clock),
    )
    private val dryExchange = DryRunExchange()
    private val dryStore = VolatileOrderStore()
    private val dry = EngineComponents(
        dryExchange, dryStore, OrderManager(dryExchange, dryStore, ClientOrderIds(dryStore), clock, pause = {}),
        Reconciler(dryExchange, dryStore, clock),
    )
    private val catalog = Catalog(listOf(btcUsdt), emptyList(), maxOpenOrdersTotal = 0, serverTimeSec = 0)
    private val engine = BotEngine(live, { dry }, data, { catalog }, log, clock)

    private val liveConfig = BotConfig(dryRun = false)
    private val state get() = engine.status.value.state

    private fun logged(text: String) = log.entries.value.any { it.message.contains(text) }

    private suspend fun tickAfter(seconds: Long) {
        now += seconds * 1_000
        engine.tick()
    }

    @Test
    fun `invalid config never starts`() = runBlocking {
        assertFalse(engine.start(BotConfig(symbol = "DOGE-USDT")))
        assertInstanceOf(EngineState.Stopped::class.java, state)
        assertEquals(0, data.loadCalls)
    }

    @Test
    fun `dry run decides and logs but never touches the real exchange`() = runBlocking {
        assertTrue(engine.start(BotConfig(dryRun = true)))
        engine.tick()
        tickAfter(1)

        assertEquals(2, engine.status.value.openOrders)
        assertTrue(exchange.createRequests.isEmpty() && exchange.getCalls == 0 && exchange.cancelRequests.isEmpty())
        assertTrue(store.rows.isEmpty())
        assertTrue(logged("[DRY]"))
        assertTrue(logged("tidak ada order dikirim"))
    }

    @Test
    fun `live mode places post-only quotes once and leaves them alone while the market is quiet`() = runBlocking {
        assertTrue(engine.start(liveConfig))
        engine.tick()

        assertEquals(2, exchange.createRequests.size)
        assertEquals(setOf(Side.BUY, Side.SELL), exchange.createRequests.map { it.side }.toSet())
        assertTrue(exchange.createRequests.all { it.postOnly && it.tif == "GTC" })
        assertEquals(2, store.active().size)

        tickAfter(1)
        tickAfter(5)
        assertEquals(2, exchange.createRequests.size, "tidak ada order ganda saat pasar tenang")
        assertEquals(2, engine.status.value.openOrders)
        assertTrue(logged("[LIVE]"))
    }

    @Test
    fun `risk rejections are logged and nothing is sent`() = runBlocking {
        data.inputs = testSnapshot(quoteTotal = "100").inputs // batas 2% ekuitas = 2 USDT < order 10 USDT
        engine.start(liveConfig)
        engine.tick()

        assertTrue(exchange.createRequests.isEmpty())
        assertTrue(logged("Ditolak risiko"))
        assertEquals(EngineState.Running, state)
    }

    @Test
    fun `crossed book pauses and cancels, then resumes by itself`() = runBlocking {
        engine.start(liveConfig)
        engine.tick()
        assertEquals(2, exchange.createRequests.size)

        data.inputs = testSnapshot(bid = "77001.00", ask = "77000.00", quoteTotal = "9250", baseTotal = "0.00974026").inputs
        tickAfter(1)
        val paused = assertInstanceOf(EngineState.Paused::class.java, state)
        assertTrue(paused.auto)
        assertEquals(2, exchange.cancelRequests.size)
        assertTrue(store.active().isEmpty())

        data.inputs = normalInputs()
        tickAfter(10)
        assertEquals(EngineState.Running, state)
        assertEquals(4, exchange.createRequests.size, "kuotasi baru dipasang setelah pulih")
    }

    @Test
    fun `clock skew pauses`() = runBlocking {
        engine.start(liveConfig)
        data.inputs = testSnapshot(quoteTotal = "9250", baseTotal = "0.00974026", skew = 5_000).inputs
        engine.tick()
        assertInstanceOf(EngineState.Paused::class.java, state)
        assertTrue(exchange.createRequests.isEmpty())
    }

    @Test
    fun `daily loss stops the bot and cancels everything`() = runBlocking {
        engine.start(liveConfig)
        engine.tick()
        assertEquals(2, store.active().size)

        data.inputs = testSnapshot(quoteTotal = "9220", baseTotal = "0.00974026").inputs // ekuitas turun 30 (batas 20)
        tickAfter(1)

        val stopped = assertInstanceOf(EngineState.Stopped::class.java, state)
        assertTrue(stopped.reason.contains("Rugi harian"))
        assertTrue(store.active().isEmpty())
    }

    @Test
    fun `an auth or permission error is fatal and ends the loop`() = runBlocking {
        engine.start(liveConfig)
        data.failure = ApiError.Auth("permission_denied", "ledger read not granted")
        engine.tick()

        assertInstanceOf(EngineState.Fatal::class.java, state)
        val calls = data.loadCalls
        engine.tick()
        assertEquals(calls, data.loadCalls, "tidak ada panggilan lagi setelah fatal")
    }

    @Test
    fun `five failing cycles pause until the user resumes`() = runBlocking {
        engine.start(liveConfig)
        data.failure = ApiError.Timeout()
        repeat(4) { tickAfter(1) }
        assertEquals(EngineState.Running, state)
        tickAfter(1)

        val paused = assertInstanceOf(EngineState.Paused::class.java, state)
        assertFalse(paused.auto)
        val calls = data.loadCalls
        tickAfter(1)
        assertEquals(calls, data.loadCalls, "jeda manual tidak berjalan sendiri")

        data.failure = null
        engine.resume()
        assertEquals(EngineState.Running, state)
        assertEquals(0, engine.status.value.consecutiveErrors)
        tickAfter(10)
        assertEquals(2, exchange.createRequests.size)
    }

    @Test
    fun `repeated server rejections count toward the error limit`() = runBlocking {
        repeat(6) { exchange.script += CreateBehavior.Fail(ApiError.Validation("invalid_argument", "bad")) }
        engine.start(liveConfig)
        repeat(3) { tickAfter(10) }

        val paused = assertInstanceOf(EngineState.Paused::class.java, state)
        assertFalse(paused.auto)
        assertTrue(engine.status.value.consecutiveErrors >= 5)
    }

    @Test
    fun `grid halts and cancels when the price leaves the range`() = runBlocking {
        val grid = BotConfig(
            dryRun = false, strategy = StrategyType.GRID,
            grid = GridConfig(BigDecimal("70000"), BigDecimal("80000"), 10, BigDecimal("10")),
            risk = RiskConfig(maxOpenOrders = 12),
        )
        data.inputs = testSnapshot(quoteTotal = "10000").inputs
        engine.start(grid)
        engine.tick()
        assertEquals(1, exchange.createRequests.size, "hanya level yang dalam batas deviasi 2% yang lolos risiko")
        assertTrue(logged("menyimpang"))

        data.inputs = testSnapshot(bid = "90000.00", ask = "90000.02", quoteTotal = "10000").inputs
        tickAfter(1)

        val stopped = assertInstanceOf(EngineState.Stopped::class.java, state)
        assertTrue(stopped.reason.startsWith("Strategi:"))
        assertTrue(store.active().isEmpty())
    }

    @Test
    fun `stop cancels the bot's orders and the engine can start again`() = runBlocking {
        engine.start(liveConfig)
        engine.tick()
        tickAfter(1)

        engine.stop()

        assertEquals(2, exchange.cancelRequests.size)
        assertInstanceOf(EngineState.Stopped::class.java, state)
        assertTrue(engine.start(liveConfig))
    }

    @Test
    fun `cancelOnStop false leaves orders in place`() = runBlocking {
        engine.start(liveConfig.copy(cancelOnStop = false))
        engine.tick()
        engine.stop()
        assertTrue(exchange.cancelRequests.isEmpty())
        assertEquals(2, store.active().size)
    }

    @Test
    fun `kill switch cancels own orders and only uses cancel-all when allowed`() = runBlocking {
        engine.start(liveConfig)
        engine.tick()
        engine.killSwitch()
        assertEquals(2, exchange.cancelRequests.size)
        assertTrue(exchange.cancelAllRequests.isEmpty(), "order manual tidak disentuh secara default")
        assertEquals(EngineState.Stopped("Kill switch"), state)

        val again = FakeExchange()
        val store2 = InMemoryOrderStore()
        val live2 = EngineComponents(again, store2, OrderManager(again, store2, ClientOrderIds(store2), clock, pause = {}), Reconciler(again, store2, clock))
        val engine2 = BotEngine(live2, { dry }, data, { catalog }, log, clock)
        engine2.start(liveConfig.copy(killSwitchCancelsAllOnSymbol = true))
        engine2.tick()
        engine2.killSwitch()
        assertEquals(listOf(listOf(1L)), again.cancelAllRequests)
    }

    @Test
    fun `starting live adopts orphan orders left by an earlier run`() = runBlocking {
        exchange.put(exchange.remote("pbzz99-7"))
        exchange.put(exchange.remote("manual-order-1"))

        engine.start(liveConfig)

        assertTrue(store.get("pbzz99-7")!!.adopted)
        assertEquals(null, store.get("manual-order-1"), "order manual tidak diadopsi")
    }

    @Test
    fun `manual pause cancels quotes and resume continues`() = runBlocking {
        engine.start(liveConfig)
        engine.tick()
        engine.pause()

        assertEquals(EngineState.Paused("Dijeda pengguna", auto = false), state)
        assertTrue(store.active().isEmpty())
        val calls = data.loadCalls
        tickAfter(10)
        assertEquals(calls, data.loadCalls)

        engine.resume()
        assertEquals(EngineState.Running, state)
    }

    @Test
    fun `cannot start twice and poll delay follows activity`() = runBlocking {
        engine.start(liveConfig)
        assertFalse(engine.start(liveConfig))
        assertEquals(20L, engine.pollDelaySec())
        engine.tick()
        tickAfter(1)
        assertEquals(3L, engine.pollDelaySec())
    }

    @Test
    fun `unresolved orders stay out of the way until reconciled`() = runBlocking {
        exchange.script += CreateBehavior.Fail(ApiError.Timeout())
        exchange.getFailure = ApiError.Timeout()
        engine.start(liveConfig)
        engine.tick()

        assertTrue(store.rows.values.any { it.status == LocalStatus.UNKNOWN })
        assertTrue(logged("belum pasti"))
    }
}
