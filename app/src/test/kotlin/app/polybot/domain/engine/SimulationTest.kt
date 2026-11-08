package app.polybot.domain.engine

import app.polybot.core.log.RingLogger
import app.polybot.core.time.Clock
import app.polybot.domain.config.BotConfig
import app.polybot.domain.config.DcaConfig
import app.polybot.domain.config.GridConfig
import app.polybot.domain.config.MarketMakerConfig
import app.polybot.domain.config.RiskConfig
import app.polybot.domain.config.StrategyType
import app.polybot.domain.model.Catalog
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Menjalankan engine nyata terhadap bursa simulasi dengan harga acak dan memeriksa invarian keselamatan
 * di setiap siklus, bukan hanya hasil akhir.
 */
class SimulationTest {
    private class Outcome(val sim: SimMarket, val engine: BotEngine, val log: RingLogger, val endState: EngineState)

    private fun simulate(
        config: BotConfig,
        seed: Long,
        ticks: Int,
        maxStepTicks: Int,
        quote: String = "9250",
        base: String = "0.00974026",
    ): Outcome = runBlocking {
        var now = 1_800_000_000_000L
        val clock = Clock { now }
        val sim = SimMarket(btcUsdt, startMidTicks = 7_700_001, quote = BigDecimal(quote), base = BigDecimal(base), seed = seed, maxStepTicks = maxStepTicks)
        val store = InMemoryOrderStore()
        val live = EngineComponents(sim, store, OrderManager(sim, store, ClientOrderIds(store), clock, pause = {}), Reconciler(sim, store, clock))
        val log = RingLogger(capacity = 5_000, clock = clock)
        val catalog = Catalog(listOf(btcUsdt), emptyList(), 0, 0)
        val engine = BotEngine(live, { error("dry run tidak dipakai") }, sim, { catalog }, log, clock)

        assertTrue(engine.start(config), "start gagal: ${engine.status.value.state}")
        repeat(ticks) {
            sim.step()
            now += 5_000
            engine.tick()

            // ---- invarian di setiap siklus ----
            val open = sim.openOrders
            assertTrue(open.size <= config.risk.maxOpenOrders, "order terbuka ${open.size} > batas, siklus $it")
            val bids = open.filter { o -> o.side == Side.BUY }.map { o -> Numeric.ticksToPrice(o.priceTicks) }
            val asks = open.filter { o -> o.side == Side.SELL }.map { o -> Numeric.ticksToPrice(o.priceTicks) }
            if (bids.isNotEmpty() && asks.isNotEmpty()) assertTrue(bids.max() < asks.min(), "order sendiri saling menyilang, siklus $it")
            assertTrue(sim.quoteTotal.signum() >= 0 && sim.baseTotal.signum() >= 0, "saldo negatif, siklus $it")
        }
        val endState = engine.status.value.state
        if (endState.inSession) engine.stop()
        Outcome(sim, engine, log, endState)
    }

    private fun assertSafety(o: Outcome, config: BotConfig) {
        assertEquals(emptyList<String>(), o.sim.serverRejections, "tidak boleh ada penolakan dari server")
        assertTrue(o.sim.maxNotional <= config.risk.maxOrderQuote, "nilai order ${o.sim.maxNotional} melebihi batas")
        assertTrue(o.sim.worstExposureAtPlacement <= config.risk.maxExposurePct, "eksposur ${o.sim.worstExposureAtPlacement} melebihi batas")
        assertEquals(o.sim.placedIds.size, o.sim.placedIds.toSet().size, "client_order_id harus unik")
        assertTrue(o.sim.placedIds.all { id -> ClientOrderIds.isOurs(id) })
        assertTrue(o.sim.openOrders.isEmpty(), "setelah stop tidak boleh ada order tersisa")
    }

    private val looseLoss = RiskConfig(maxDailyLossQuote = BigDecimal("1000000"))

    @Test
    fun `market maker keeps every invariant across many random walks`() {
        val config = BotConfig(dryRun = false, risk = looseLoss)
        var totalFills = 0
        for (seed in 1L..6L) {
            val o = simulate(config, seed, ticks = 2_500, maxStepTicks = 15_000)
            assertSafety(o, config)
            assertTrue(o.sim.placedIds.isNotEmpty(), "seed $seed: bot harus memasang order")
            totalFills += o.sim.fills
        }
        assertTrue(totalFills > 0, "simulasi harus menghasilkan fill agar logika inventori teruji")
    }

    @Test
    fun `two level market maker keeps every invariant`() {
        val config = BotConfig(dryRun = false, risk = looseLoss.copy(maxOpenOrders = 6), marketMaker = MarketMakerConfig(levels = 2))
        for (seed in 10L..13L) assertSafety(simulate(config, seed, ticks = 1_500, maxStepTicks = 10_000), config)
    }

    @Test
    fun `grid keeps every invariant and halts cleanly if the price escapes`() {
        val config = BotConfig(
            dryRun = false, strategy = StrategyType.GRID,
            grid = GridConfig(BigDecimal("70000"), BigDecimal("84000"), 14, BigDecimal("10")),
            risk = looseLoss.copy(maxOpenOrders = 14, maxNewOrdersPerMinute = 100),
        )
        var placed = 0
        var fills = 0
        for (seed in 20L..25L) {
            val o = simulate(config, seed, ticks = 1_500, maxStepTicks = 2_000, quote = "10000", base = "0.05")
            assertSafety(o, config)
            placed += o.sim.placedIds.size
            fills += o.sim.fills
        }
        assertTrue(placed > 20, "grid harus benar-benar memasang order, hanya $placed")
        assertTrue(fills > 0, "grid harus mengalami fill")
    }

    @Test
    fun `dca never exceeds its budget and keeps every invariant`() {
        val config = BotConfig(
            dryRun = false, strategy = StrategyType.DCA,
            dca = DcaConfig(intervalSec = 60, orderQuote = BigDecimal("10"), maxTotalQuote = BigDecimal("100")),
            risk = looseLoss,
        )
        for (seed in 30L..33L) {
            val o = simulate(config, seed, ticks = 1_000, maxStepTicks = 3_000, quote = "10000", base = "0")
            assertSafety(o, config)
            assertTrue(o.sim.placedIds.isNotEmpty(), "seed $seed: DCA harus membeli")
            assertTrue(o.sim.placedIds.size <= 10, "DCA memasang ${o.sim.placedIds.size} order, anggaran hanya cukup 10")
        }
    }

    @Test
    fun `with the default loss limit a hostile market ends in a clean stop, never in orphan orders`() {
        val config = BotConfig(dryRun = false)
        var stoppedByLoss = 0
        for (seed in 40L..45L) {
            val o = simulate(config, seed, ticks = 2_000, maxStepTicks = 25_000)
            assertSafety(o, config)
            val end = o.endState
            if (end is EngineState.Stopped && end.reason.contains("Rugi harian")) stoppedByLoss++
        }
        assertTrue(stoppedByLoss > 0, "pasar yang ganas harus memicu batas rugi harian setidaknya sekali")
    }
}
