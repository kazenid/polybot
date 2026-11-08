package app.polybot.domain.risk

import app.polybot.domain.config.RiskConfig
import app.polybot.domain.engine.MarketInputs
import app.polybot.domain.engine.Snapshot
import app.polybot.domain.engine.btcUsdt
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.Market
import app.polybot.domain.model.MarketStatus
import app.polybot.domain.model.Side
import app.polybot.domain.strategy.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RiskManagerTest {
    private fun d(s: String) = BigDecimal(s)
    private val risk = RiskManager(RiskConfig())
    private val state get() = RiskState()
    private var now = 1_800_000_000_000L

    private fun snap(
        bid: String? = "77000.00",
        ask: String? = "77000.02",
        quoteTotal: String = "10000",
        baseTotal: String = "0",
        quoteAvailable: String = quoteTotal,
        baseAvailable: String = baseTotal,
        skew: Long? = null,
        open: List<LocalOrder> = emptyList(),
        market: Market = btcUsdt,
        serverMax: Int = 0,
    ) = Snapshot(
        nowMs = now, market = market,
        inputs = MarketInputs(
            bestBid = bid?.let(::d), bestAsk = ask?.let(::d), lastPrice = null,
            baseAvailable = d(baseAvailable), quoteAvailable = d(quoteAvailable),
            baseTotal = d(baseTotal), quoteTotal = d(quoteTotal), clockSkewMs = skew,
        ),
        openOrders = open, serverMaxOpenOrders = serverMax,
    )

    private fun order(side: Side, price: String, qty: String = "0.0001") = Intent.Place(side, d(price), d(qty))

    private fun open(side: Side, price: String) = LocalOrder(
        clientOrderId = "pbab12-${price.hashCode()}", orderId = 1, symbolId = 1, side = side,
        priceTicks = d(price).movePointRight(9).longValueExact(), qtyScaled = 10_000, filledScaled = 0,
        postOnly = true, tif = "GTC", status = LocalStatus.OPEN, reason = null, createdAtMs = now, updatedAtMs = now,
    )

    private fun approve(intent: Intent.Place, s: Snapshot = snap(), st: RiskState = RiskState(), placed: List<Intent.Place> = emptyList()) =
        risk.approve(intent, s, st, placed)

    private fun rejectedReason(a: Approval) = (assertInstanceOf(Approval.Rejected::class.java, a)).reason

    // ---- preTrade ----

    @Test
    fun `healthy market passes`() {
        assertEquals(RiskVerdict.Ok, risk.preTrade(snap(), RiskState()))
    }

    @Test
    fun `symbol outside the allowlist stops the bot`() {
        val other = btcUsdt.copy(symbol = "DOGE-USDT")
        assertInstanceOf(RiskVerdict.Stop::class.java, risk.preTrade(snap(market = other), RiskState()))
    }

    @Test
    fun `empty or crossed book pauses and recovers on its own`() {
        for (s in listOf(snap(bid = null), snap(ask = null), snap(bid = "77001", ask = "77000"), snap(bid = "77000", ask = "77000"))) {
            val verdict = assertInstanceOf(RiskVerdict.Pause::class.java, risk.preTrade(s, RiskState()))
            assertTrue(verdict.auto)
        }
    }

    @Test
    fun `clock skew beyond the limit pauses`() {
        assertEquals(RiskVerdict.Ok, risk.preTrade(snap(skew = 3_000), RiskState()))
        assertEquals(RiskVerdict.Ok, risk.preTrade(snap(skew = -3_000), RiskState()))
        val pause = assertInstanceOf(RiskVerdict.Pause::class.java, risk.preTrade(snap(skew = -3_001), RiskState()))
        assertTrue(pause.auto)
    }

    @Test
    fun `five consecutive errors pause until the user resumes`() {
        val st = RiskState()
        repeat(4) { st.recordError() }
        assertEquals(RiskVerdict.Ok, risk.preTrade(snap(), st))
        st.recordError()
        val pause = assertInstanceOf(RiskVerdict.Pause::class.java, risk.preTrade(snap(), st))
        assertEquals(false, pause.auto)
        st.recordSuccess()
        assertEquals(RiskVerdict.Ok, risk.preTrade(snap(), st))
    }

    @Test
    fun `halted pair pauses`() {
        val halted = btcUsdt.copy(status = MarketStatus.HALTED)
        assertInstanceOf(RiskVerdict.Pause::class.java, risk.preTrade(snap(market = halted), RiskState()))
    }

    @Test
    fun `daily loss stops the bot and a new day resets the baseline`() {
        val st = RiskState()
        assertEquals(RiskVerdict.Ok, risk.preTrade(snap(quoteTotal = "10000"), st)) // ekuitas awal hari
        assertEquals(RiskVerdict.Ok, risk.preTrade(snap(quoteTotal = "9981"), st)) // rugi 19 < 20
        assertInstanceOf(RiskVerdict.Stop::class.java, risk.preTrade(snap(quoteTotal = "9980"), st)) // rugi 20

        now += 86_400_000L
        assertEquals(RiskVerdict.Ok, risk.preTrade(snap(quoteTotal = "9980"), st), "hari baru, acuan baru")
    }

    @Test
    fun `unrealized loss on inventory counts toward the daily limit`() {
        val st = RiskState()
        risk.preTrade(snap(quoteTotal = "9000", baseTotal = "0.0129870129"), st) // ~1000 dalam BTC
        val after = snap(bid = "75000.00", ask = "75000.02", quoteTotal = "9000", baseTotal = "0.0129870129") // ~ -2.6% dari 1000
        assertInstanceOf(RiskVerdict.Stop::class.java, risk.preTrade(after, st))
    }

    // ---- approve ----

    @Test
    fun `a normal order is approved`() {
        assertEquals(Approval.Approved, approve(order(Side.BUY, "76900.00")))
    }

    @Test
    fun `order value is capped by the absolute limit and by percent of equity`() {
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00", "0.00015"))).contains("melebihi batas")) // 11.5 > 10
        val small = snap(quoteTotal = "100") // 2% dari 100 = 2
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), small)).contains("melebihi batas"))
    }

    @Test
    fun `open order limit uses the strictest of config, pair and server`() {
        val six = (1..6).map { open(Side.SELL, "7700$it.00") }
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), snap(open = six))).contains("Order terbuka"))
        val two = six.take(2)
        assertEquals(Approval.Approved, approve(order(Side.BUY, "76900.00"), snap(open = two)))
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), snap(open = two, serverMax = 2))).contains("Order terbuka"))
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), snap(open = two, market = btcUsdt.copy(maxOpenOrders = 2)))).contains("Order terbuka"))
    }

    @Test
    fun `orders placed earlier in the same tick count against the limits`() {
        val placed = (1..6).map { order(Side.BUY, "7690$it.00") }
        assertTrue(rejectedReason(approve(order(Side.BUY, "76800.00"), placed = placed)).contains("Order terbuka"))
    }

    @Test
    fun `per-minute order budget`() {
        val st = RiskState()
        repeat(20) { st.recordOrder(now - 1_000) }
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), st = st)).contains("semenit"))
        now += 61_000
        assertEquals(Approval.Approved, approve(order(Side.BUY, "76900.00"), snap(), st))
    }

    @Test
    fun `price too far from mid is rejected`() {
        assertEquals(Approval.Approved, approve(order(Side.BUY, "75500.00"))) // ~1.9%
        assertTrue(rejectedReason(approve(order(Side.BUY, "75400.00"))).contains("menyimpang"))
    }

    @Test
    fun `buy needs quote balance beyond the reserve`() {
        // total 100, cadangan 5% = 5, tersedia 12 -> 7 dapat dipakai; order 7.69 ditolak
        val tight = snap(quoteTotal = "400", quoteAvailable = "12")
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), tight)).contains("tidak cukup"))
        val enough = snap(quoteTotal = "400", quoteAvailable = "40")
        assertEquals(Approval.Approved, approve(order(Side.BUY, "76900.00"), enough))
    }

    @Test
    fun `buys placed in the same tick consume the available balance`() {
        val s = snap(quoteTotal = "10000", quoteAvailable = "20") // cadangan 500 -> tidak ada yang bisa dipakai
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), s)).contains("tidak cukup"))
        val s2 = snap(quoteTotal = "100", quoteAvailable = "100") // cadangan 5, usable 95; 2% ekuitas = 2 -> cap
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), s2)).isNotEmpty())
    }

    @Test
    fun `exposure limit blocks buys but not sells`() {
        // 0.0194 BTC ~ 1494 USDT dari ekuitas ~ 10000 -> 14.9%; satu beli 7.7 lagi melewati 15%
        val heavy = snap(quoteTotal = "8500", baseTotal = "0.01948")
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), heavy)).contains("Eksposur"))
        val sell = snap(quoteTotal = "8500", baseTotal = "0.01948", baseAvailable = "0.01948")
        assertEquals(Approval.Approved, approve(order(Side.SELL, "77100.00"), sell))
    }

    @Test
    fun `open buy orders count toward exposure because they can fill at any time`() {
        // inventori 1495 (14.95%); tidak ada tambahan eksposur dari order baru 7.7 (-> 15.02%) bila ada beli terbuka senilai 0
        val base = snap(quoteTotal = "8505", baseTotal = "0.019415584")
        assertEquals(Approval.Approved, approve(order(Side.BUY, "76900.00", "0.00005"), base)) // 3.8 -> 14.99%
        val withOpenBuy = snap(quoteTotal = "8505", baseTotal = "0.019415584", open = listOf(open(Side.BUY, "76000.00")))
        // open() bernilai 10_000 scaled = 0.0001 BTC ~ 7.6 USDT, jadi total melewati 15%
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00", "0.00005"), withOpenBuy)).contains("Eksposur"))
    }

    @Test
    fun `sell needs base balance`() {
        assertTrue(rejectedReason(approve(order(Side.SELL, "77100.00"))).contains("aset dasar"))
        val withBase = snap(baseTotal = "0.001", baseAvailable = "0.0001")
        assertEquals(Approval.Approved, approve(order(Side.SELL, "77100.00"), withBase))
        val twice = approve(order(Side.SELL, "77100.00"), withBase, placed = listOf(order(Side.SELL, "77200.00")))
        assertTrue(rejectedReason(twice).contains("aset dasar"))
    }

    @Test
    fun `post-only orders that would cross the book are rejected`() {
        assertTrue(rejectedReason(approve(order(Side.BUY, "77000.02"))).contains("menyilang"))
        val s = snap(baseTotal = "0.001")
        assertTrue(rejectedReason(approve(order(Side.SELL, "77000.00"), s)).contains("menyilang"))
    }

    @Test
    fun `never trade against our own resting order`() {
        val ownSell = snap(baseTotal = "0.001", open = listOf(open(Side.SELL, "76990.00")))
        assertTrue(rejectedReason(approve(order(Side.BUY, "76995.00"), ownSell)).contains("milik sendiri"))
        val ownBuy = snap(baseTotal = "0.001", open = listOf(open(Side.BUY, "77010.00")))
        assertTrue(rejectedReason(approve(order(Side.SELL, "77005.00"), ownBuy)).contains("milik sendiri"))
        val placedBuy = approve(order(Side.SELL, "77001.00"), snap(baseTotal = "0.001"), placed = listOf(order(Side.BUY, "77010.00")))
        assertTrue(rejectedReason(placedBuy).contains("milik sendiri"))
    }

    @Test
    fun `no reference price means no orders`() {
        assertTrue(rejectedReason(approve(order(Side.BUY, "76900.00"), snap(bid = null, ask = null))).contains("acuan"))
    }
}
