package app.polybot.domain.strategy

import app.polybot.domain.config.BotConfig
import app.polybot.domain.config.DcaConfig
import app.polybot.domain.config.GridConfig
import app.polybot.domain.config.MarketMakerConfig
import app.polybot.domain.config.RiskConfig
import app.polybot.domain.config.StrategyType
import app.polybot.domain.engine.btcUsdt
import app.polybot.domain.engine.ourOrder
import app.polybot.domain.engine.testSnapshot
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class StrategiesTest {
    private fun d(s: String) = BigDecimal(s)
    private val t0 = 1_800_000_000_000L
    private val mmConfig = BotConfig()

    private fun places(intents: List<Intent>) = intents.filterIsInstance<Intent.Place>()

    private fun assertValid(place: Intent.Place) =
        assertEquals(null, Numeric.validate(btcUsdt, place.price, place.qty), "order harus lolos validasi pair")

    // ---- Market maker ----

    @Test
    fun `with no inventory the market maker only bids, below the market`() {
        val buy = places(MarketMaker().decide(testSnapshot(), mmConfig)).single()

        assertEquals(Side.BUY, buy.side)
        assertTrue(buy.postOnly)
        assertTrue(buy.price < d("77000.00"), "bid harus di bawah best bid, was ${buy.price}")
        assertEquals(0, buy.price.remainder(d("0.01")).signum(), "kelipatan tick")
        assertTrue(buy.notional >= d("9.9") && buy.notional <= d("10.0"), "nilai ~10 USDT, was ${buy.notional}")
        assertValid(buy)
    }

    @Test
    fun `at target inventory it quotes both sides around the mid`() {
        // 750 dari 10000 = 7.5% = separuh batas eksposur 15%
        val snapshot = testSnapshot(quoteTotal = "9250", baseTotal = "0.00974026")
        val intents = places(MarketMaker().decide(snapshot, mmConfig))

        val buy = intents.single { it.side == Side.BUY }
        val sell = intents.single { it.side == Side.SELL }
        val mid = snapshot.mid!!
        assertTrue(buy.price < d("77000.00") && sell.price > d("77000.02"))
        val below = mid.subtract(buy.price)
        val above = sell.price.subtract(mid)
        assertTrue(below.subtract(above).abs() < d("2"), "asimetri $below vs $above")
    }

    @Test
    fun `heavy inventory stops buying`() {
        val snapshot = testSnapshot(quoteTotal = "8700", baseTotal = "0.0168831") // ~13% > 12% (80% dari 15%)
        val intents = places(MarketMaker().decide(snapshot, mmConfig))
        assertEquals(1, intents.size)
        assertTrue(intents.all { it.side == Side.SELL }, "sisi beli ditahan")
    }

    private val base75 = "0.00974026" // ~7.5% dari 10000 pada harga 77000

    @Test
    fun `quotes stay symmetric whatever the starting inventory is`() {
        // 10% inventori pada awal sesi: skew netral, bukan menuju target tetap 7.5%.
        val snapshot = testSnapshot(quoteTotal = "9000", baseTotal = "0.01298701")
        val intents = places(MarketMaker().decide(snapshot, mmConfig))
        val buy = intents.single { it.side == Side.BUY }
        val sell = intents.single { it.side == Side.SELL }
        val mid = snapshot.mid!!
        assertTrue(mid.subtract(buy.price).subtract(sell.price.subtract(mid)).abs() < d("2"))
    }

    @Test
    fun `growing inventory shifts quotes lower than at the start of the session`() {
        val mm = MarketMaker()
        val start = places(mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)).single { it.side == Side.BUY }
        val more = places(mm.decide(testSnapshot(quoteTotal = "9000", baseTotal = "0.01298701", now = t0 + 10_000), mmConfig)).single { it.side == Side.BUY }
        assertTrue(more.price < start.price, "inventori naik harus menurunkan bid: ${start.price} -> ${more.price}")
    }

    @Test
    fun `half spread never goes below fee plus the minimum edge`() {
        val cfg = mmConfig.copy(marketMaker = MarketMakerConfig(halfSpreadBps = d("5")))
        val snapshot = testSnapshot(quoteTotal = "9250", baseTotal = base75)
        val buy = places(MarketMaker().decide(snapshot, cfg)).single { it.side == Side.BUY }
        val belowBps = snapshot.mid!!.subtract(buy.price).divide(snapshot.mid, 8, java.math.RoundingMode.HALF_UP).multiply(d("10000"))
        assertTrue(belowBps >= d("19.9"), "fee 10 + margin 10 = 20 bps, was $belowBps")
    }

    @Test
    fun `multiple levels step away from the mid`() {
        val cfg = mmConfig.copy(marketMaker = MarketMakerConfig(levels = 2), risk = RiskConfig(maxOpenOrders = 6))
        val intents = places(MarketMaker().decide(testSnapshot(quoteTotal = "9250", baseTotal = base75), cfg))

        val buys = intents.filter { it.side == Side.BUY }.sortedByDescending { it.price }
        val sells = intents.filter { it.side == Side.SELL }.sortedBy { it.price }
        assertEquals(2, buys.size)
        assertEquals(2, sells.size)
        assertTrue(buys[0].price > buys[1].price && sells[0].price < sells[1].price)
    }

    // Kuotasi ideal pada mid 77000.01: half spread efektif 25 bps -> bid ~76807.51, ask ~77192.51
    private val idealBid = "76807.50"
    private val idealAsk = "77192.50"

    @Test
    fun `quiet market keeps resting orders`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        val open = listOf(ourOrder(Side.BUY, idealBid, createdAtMs = t0), ourOrder(Side.SELL, idealAsk, createdAtMs = t0))
        val quiet = testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0 + 10_000, open = open)
        assertEquals(emptyList<Intent>(), mm.decide(quiet, mmConfig))
    }

    @Test
    fun `an order that became too aggressive is cancelled but one that is merely far is kept`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        // Harga turun sekitar 20 bps: bid lama jadi terlalu dekat ke pasar (berbahaya), ask lama jadi lebih jauh (aman).
        val open = listOf(ourOrder(Side.BUY, idealBid, createdAtMs = t0), ourOrder(Side.SELL, idealAsk, createdAtMs = t0))
        val dropped = testSnapshot(
            bid = "76850.00", ask = "76850.02", quoteTotal = "9250", baseTotal = base75, now = t0 + 10_000, open = open,
        )
        val cancels = mm.decide(dropped, mmConfig)
        assertEquals(1, cancels.size)
        val cancel = cancels.single() as Intent.Cancel
        assertEquals(open[0].clientOrderId, cancel.clientOrderId)
        assertTrue(cancel.reason.contains("mid bergeser"))
    }

    @Test
    fun `an order that drifted far from the ideal quote is replaced`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        val open = listOf(ourOrder(Side.BUY, idealBid, createdAtMs = t0), ourOrder(Side.SELL, idealAsk, createdAtMs = t0))
        val rallied = testSnapshot(
            bid = "77300.00", ask = "77300.02", quoteTotal = "9250", baseTotal = base75, now = t0 + 10_000, open = open,
        )
        val cancels = mm.decide(rallied, mmConfig)
        assertTrue(cancels.all { it is Intent.Cancel })
        assertTrue(cancels.isNotEmpty())
    }

    @Test
    fun `a side without an order is quoted without waiting for the other side`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        val onlySell = listOf(ourOrder(Side.SELL, idealAsk, createdAtMs = t0 + 20_000))
        val intents = mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0 + 30_000, open = onlySell), mmConfig)
        val places = places(intents)
        assertEquals(1, places.size)
        assertEquals(Side.BUY, places.single().side)
    }

    @Test
    fun `buying pauses when inventory grew and the price is falling`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        // Setelah terisi inventori naik ke ~9%, dan mid turun ~40 bps dari puncak jendela.
        val falling = testSnapshot(
            bid = "76690.00", ask = "76690.02", quoteTotal = "9100", baseTotal = "0.01173300", now = t0 + 60_000,
        )
        val intents = places(mm.decide(falling, mmConfig))
        assertTrue(intents.none { it.side == Side.BUY }, "beli ditahan saat tren turun")
        assertTrue(intents.any { it.side == Side.SELL })
    }

    @Test
    fun `falling price alone does not stop buying when no inventory was added`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        val falling = testSnapshot(bid = "76690.00", ask = "76690.02", quoteTotal = "9250", baseTotal = base75, now = t0 + 60_000)
        assertTrue(places(mm.decide(falling, mmConfig)).any { it.side == Side.BUY })
    }

    @Test
    fun `orders older than the maximum age are cancelled`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        val open = listOf(ourOrder(Side.BUY, idealBid, createdAtMs = t0))
        val old = testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0 + 61_000, open = open)
        val cancel = mm.decide(old, mmConfig).single() as Intent.Cancel
        assertTrue(cancel.reason.contains("berumur"))
    }

    @Test
    fun `cooldown prevents requoting right after a cancel`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        val open = listOf(ourOrder(Side.BUY, idealBid, createdAtMs = t0))
        mm.decide(testSnapshot(now = t0 + 61_000, open = open, quoteTotal = "9250", baseTotal = base75), mmConfig)

        val justAfter = testSnapshot(now = t0 + 62_000, quoteTotal = "9250", baseTotal = base75)
        assertEquals(emptyList<Intent>(), mm.decide(justAfter, mmConfig), "dalam cooldown 5 detik")
        assertTrue(mm.decide(justAfter.copy(nowMs = t0 + 67_000), mmConfig).isNotEmpty())
    }

    // ---- Lantai jual impas ----

    @Test
    fun `sell is lifted to break-even when the position cost is above the ideal ask`() {
        // biaya 78000: impas = 78000 x 1.002 / 0.999 = 78234.24, jauh di atas ask ideal ~77192
        val snapshot = testSnapshot(quoteTotal = "9250", baseTotal = base75, lotCost = "78000")
        val sell = places(MarketMaker().decide(snapshot, mmConfig)).single { it.side == Side.SELL }

        assertEquals(0, d("78234.24").compareTo(sell.price))
        assertTrue(sell.note.contains("impas"))
        assertValid(sell)
    }

    @Test
    fun `break-even floor does not move the ask when the cost is low`() {
        val withCost = places(MarketMaker().decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, lotCost = "70000"), mmConfig))
        val without = places(MarketMaker().decide(testSnapshot(quoteTotal = "9250", baseTotal = base75), mmConfig))

        assertEquals(without.single { it.side == Side.SELL }.price, withCost.single { it.side == Side.SELL }.price)
        assertTrue(withCost.none { it.note.contains("impas") })
    }

    @Test
    fun `break-even floor can be switched off`() {
        val cfg = mmConfig.copy(marketMaker = MarketMakerConfig(sellAboveCost = false))
        val sell = places(MarketMaker().decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, lotCost = "78000"), cfg)).single { it.side == Side.SELL }
        assertTrue(sell.price < d("78000"), "tanpa lantai, jual di sekitar ask ideal")
    }

    @Test
    fun `the floor never affects buys`() {
        val low = places(MarketMaker().decide(testSnapshot(quoteTotal = "9250", baseTotal = base75), mmConfig)).single { it.side == Side.BUY }
        val high = places(MarketMaker().decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, lotCost = "90000"), mmConfig)).single { it.side == Side.BUY }
        assertEquals(low.price, high.price)
    }

    @Test
    fun `a resting sell below break-even is cancelled once the cost is known`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        val open = listOf(ourOrder(Side.SELL, idealAsk, createdAtMs = t0))
        val snapshot = testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0 + 10_000, open = open, lotCost = "78000")

        val cancel = mm.decide(snapshot, mmConfig).single() as Intent.Cancel
        assertEquals(open[0].clientOrderId, cancel.clientOrderId)
    }

    @Test
    fun `a sell already at the floor is left alone`() {
        val mm = MarketMaker()
        mm.decide(testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0), mmConfig)
        val open = listOf(ourOrder(Side.SELL, "78234.24", createdAtMs = t0), ourOrder(Side.BUY, idealBid, createdAtMs = t0))
        val snapshot = testSnapshot(quoteTotal = "9250", baseTotal = base75, now = t0 + 10_000, open = open, lotCost = "78000")
        assertEquals(emptyList<Intent>(), mm.decide(snapshot, mmConfig))
    }

    @Test
    fun `no reference price means no quotes`() {
        assertEquals(emptyList<Intent>(), MarketMaker().decide(testSnapshot(bid = null, ask = null), mmConfig))
    }

    // ---- Grid ----

    private val gridConfig = BotConfig(
        strategy = StrategyType.GRID,
        grid = GridConfig(lower = d("70000"), upper = d("80000"), gridCount = 10, orderQuote = d("10")),
        risk = RiskConfig(maxOpenOrders = 12),
    )

    @Test
    fun `grid places buys below the market nearest first and caps new orders per tick`() {
        val intents = places(Grid().decide(testSnapshot(), gridConfig))

        assertEquals(4, intents.size)
        assertTrue(intents.all { it.side == Side.BUY })
        assertEquals(listOf("76000", "75000", "74000", "73000"), intents.map { it.price.toBigInteger().toString() })
        intents.forEach(::assertValid)
    }

    @Test
    fun `grid skips levels that already have an order`() {
        val open = listOf(ourOrder(Side.BUY, "76000.00"), ourOrder(Side.BUY, "75000.00"))
        val intents = places(Grid().decide(testSnapshot(open = open), gridConfig))
        assertEquals(listOf("74000", "73000", "72000", "71000"), intents.map { it.price.toBigInteger().toString() })
    }

    @Test
    fun `grid sells above the market only as far as the base balance goes`() {
        // saldo cukup untuk satu order jual (10 USDT / 78000 = 0.000128 BTC)
        val snapshot = testSnapshot(baseTotal = "0.00015", baseAvailable = "0.00015")
        val sells = places(Grid().decide(snapshot, gridConfig)).filter { it.side == Side.SELL }
        assertEquals(1, sells.size)
        assertEquals(0, d("78000").compareTo(sells.single().price))
    }

    @Test
    fun `grid halts when the price leaves the range by more than the break distance`() {
        assertTrue(Grid().decide(testSnapshot(bid = "80500.00", ask = "80500.02"), gridConfig).none { it is Intent.Halt })
        assertInstanceOf(Intent.Halt::class.java, Grid().decide(testSnapshot(bid = "81000.00", ask = "81000.02"), gridConfig).single())
        assertInstanceOf(Intent.Halt::class.java, Grid().decide(testSnapshot(bid = "68000.00", ask = "68000.02"), gridConfig).single())
    }

    // ---- DCA ----

    private val dcaConfig = BotConfig(
        strategy = StrategyType.DCA,
        dca = DcaConfig(intervalSec = 60, orderQuote = d("10"), maxTotalQuote = d("25")),
    )

    @Test
    fun `dca buys one tick below the best bid on schedule and halts at the budget`() {
        val dca = Dca()
        val first = dca.decide(testSnapshot(now = t0), dcaConfig).single() as Intent.Place
        assertEquals(Side.BUY, first.side)
        assertEquals(0, d("76999.99").compareTo(first.price))

        assertEquals(emptyList<Intent>(), dca.decide(testSnapshot(now = t0 + 30_000), dcaConfig), "belum waktunya")
        assertTrue(dca.decide(testSnapshot(now = t0 + 60_000), dcaConfig).single() is Intent.Place)
        assertInstanceOf(Intent.Halt::class.java, dca.decide(testSnapshot(now = t0 + 120_000), dcaConfig).single())
    }

    @Test
    fun `dca without a bid does nothing and does not spend budget`() {
        val dca = Dca()
        assertEquals(emptyList<Intent>(), dca.decide(testSnapshot(bid = null, ask = null), dcaConfig))
        assertTrue(dca.decide(testSnapshot(), dcaConfig).single() is Intent.Place)
    }
}
