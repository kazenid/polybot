package app.polybot.domain.engine

import app.polybot.core.log.RingLogger
import app.polybot.core.time.Clock
import app.polybot.domain.config.BotConfig
import app.polybot.domain.model.Catalog
import app.polybot.domain.strategy.Intent
import app.polybot.domain.strategy.Strategy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CostBasisTest {
    private val t0 = 1_800_000_000_000L
    private val clock = Clock { t0 }
    private val seen = mutableListOf<BigDecimal?>()
    private val recorder = object : Strategy {
        override val name = "rekam"
        override fun decide(snapshot: Snapshot, config: BotConfig): List<Intent> {
            seen += snapshot.lotCost
            return emptyList()
        }
    }

    private val exchange = FakeExchange()
    private val store = InMemoryOrderStore()
    private val dryExchange = DryRunExchange()
    private val dryStore = VolatileOrderStore()
    private val dry = EngineComponents(
        dryExchange, dryStore, OrderManager(dryExchange, dryStore, ClientOrderIds(dryStore), clock, pause = {}), Reconciler(dryExchange, dryStore, clock),
    )
    private val catalog = Catalog(listOf(btcUsdt), emptyList(), maxOpenOrdersTotal = 0, serverTimeSec = 0)
    private val data = FakeData(testSnapshot(quoteTotal = "9250", baseTotal = "0.00974026").inputs)

    private fun engine(costBasis: CostBasisSource) = BotEngine(
        EngineComponents(exchange, store, OrderManager(exchange, store, ClientOrderIds(store), clock, pause = {}), Reconciler(exchange, store, clock), costBasis),
        { dry }, data, { catalog }, RingLogger(clock = clock), clock, { recorder },
    )

    @Test
    fun `live ticks give the strategy the cost basis`() = runBlocking {
        val engine = engine { BigDecimal("78000") }
        assertTrue(engine.start(BotConfig(dryRun = false)))
        engine.tick()
        assertEquals(listOf(BigDecimal("78000")), seen)
    }

    @Test
    fun `dry run has no cost basis`() = runBlocking {
        val engine = engine { BigDecimal("78000") }
        assertTrue(engine.start(BotConfig(dryRun = true)))
        engine.tick()
        assertEquals(listOf<BigDecimal?>(null), seen)
    }

    @Test
    fun `a failing cost source does not fail the cycle`() = runBlocking {
        val engine = engine { error("503") }
        assertTrue(engine.start(BotConfig(dryRun = false)))
        engine.tick()
        assertEquals(listOf<BigDecimal?>(null), seen)
        assertEquals(0, engine.status.value.consecutiveErrors)
    }
}
