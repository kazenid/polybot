package app.polybot.domain.config

import app.polybot.domain.engine.btcUsdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class StrategyAdviceTest {
    private fun d(v: String) = BigDecimal(v)
    private fun assertDec(expected: String, actual: BigDecimal?) = assertEquals(0, d(expected).compareTo(actual), "was $actual")

    private val grid = GridConfig(lower = d("76000"), upper = d("80000"), gridCount = 10, orderQuote = d("10"))

    // ---- Grid ----

    @Test
    fun `grid plan splits the range and counts levels around the price`() {
        val plan = GridAdvice.plan(grid, d("10"), d("78100"))!!
        assertEquals(11, plan.levels.size)
        assertDec("76000", plan.levels.first())
        assertDec("80000", plan.levels.last())
        assertDec("400", plan.step)
        // 76000, 76400, ..., 78000 di bawah 78100 (6 level); 78400 ... 80000 di atas (5 level)
        assertEquals(6, plan.buyLevels)
        assertEquals(5, plan.sellLevels)
        assertDec("60", plan.quoteNeeded)
        assertDec("50", plan.baseValueNeeded)
    }

    @Test
    fun `step in bps is relative to the price and net subtracts two fees`() {
        val plan = GridAdvice.plan(grid, d("10"), d("80000"))!!
        assertDec("50", plan.stepBps) // 400 / 80000
        assertDec("20", plan.feeBps)
        assertDec("30", plan.netBps)
        assertEquals(Verdict.GOOD, plan.verdict)
    }

    @Test
    fun `a dense grid does not cover its fees`() {
        val dense = grid.copy(gridCount = 200) // jarak 20 -> ~2.6 bps
        val plan = GridAdvice.plan(dense, d("10"), d("78000"))!!
        assertTrue(plan.netBps.signum() < 0)
        assertEquals(Verdict.LOSS, plan.verdict)
        assertTrue(GridAdvice.check(dense, RiskConfig(maxOpenOrders = 300), plan).containsKey(GridAdvice.Field.GRID_COUNT))
    }

    @Test
    fun `invalid ranges give no plan`() {
        assertNull(GridAdvice.plan(GridConfig(), d("10"), d("78000")))
        assertNull(GridAdvice.plan(grid.copy(upper = d("75000")), d("10"), d("78000")))
        assertNull(GridAdvice.plan(grid.copy(gridCount = 1), d("10"), d("78000")))
    }

    @Test
    fun `without a price the step uses the middle of the range`() {
        val plan = GridAdvice.plan(grid, d("10"), null)!!
        assertDec("51.3", plan.stepBps) // 400 / 78000
        assertEquals(0, plan.buyLevels + plan.sellLevels)
        assertEquals(RangePosition.UNKNOWN, plan.position)
    }

    @Test
    fun `range position follows the halt rule of the strategy`() {
        // jarak keluar rentang 100 bps: berhenti di bawah 76000 x 0.99 = 75240 atau di atas 80000 x 1.01 = 80800
        assertEquals(RangePosition.INSIDE, GridAdvice.position(grid, d("78000")))
        assertEquals(RangePosition.OUTSIDE_RUNNING, GridAdvice.position(grid, d("75500")))
        assertEquals(RangePosition.OUTSIDE_RUNNING, GridAdvice.position(grid, d("80500")))
        assertEquals(RangePosition.HALT, GridAdvice.position(grid, d("75000")))
        assertEquals(RangePosition.HALT, GridAdvice.position(grid, d("81000")))
        assertEquals(RangePosition.UNKNOWN, GridAdvice.position(grid, null))
    }

    @Test
    fun `range around the price rounds outward to ticks`() {
        val (lower, upper) = GridAdvice.rangeAround(d("77777.77"), d("0.05"), btcUsdt.tickSize)
        assertDec("73888.88", lower) // 77777.77 x 0.95 = 73888.8815 -> turun ke tick
        assertDec("81666.66", upper) // 77777.77 x 1.05 = 81666.6585 -> naik ke 81666.66
        assertTrue(lower < d("77777.77") && upper > d("77777.77"))
    }

    @Test
    fun `grid summary names the range step and halt distance`() {
        val plan = GridAdvice.plan(grid, d("10"), d("78000"))!!
        val text = GridAdvice.summary(grid, plan, "USDT") { it.setScale(0).toPlainString() }
        assertTrue(text.contains("76000 sampai 80000 menjadi 10 bagian"), text)
        assertTrue(text.contains("jarak 400"), text)
        assertTrue(text.contains("10 USDT per order"), text)
        assertTrue(text.contains("lebih dari 100 bps"), text)
    }

    // ---- DCA ----

    @Test
    fun `dca counts whole purchases and the time they take`() {
        val plan = DcaAdvice.plan(DcaConfig(intervalSec = 3600, orderQuote = d("10"), maxTotalQuote = d("100")), d("10"))!!
        assertEquals(10, plan.purchases)
        assertDec("100", plan.totalSpend)
        assertEquals(9 * 3600L, plan.durationSec)
        assertDec("0.1", plan.estimatedFee) // 100 x 10 bps
    }

    @Test
    fun `dca drops a partial last purchase and warns about the leftover`() {
        val dca = DcaConfig(intervalSec = 60, orderQuote = d("30"), maxTotalQuote = d("100"))
        val plan = DcaAdvice.plan(dca, d("10"))!!
        assertEquals(3, plan.purchases)
        assertDec("90", plan.totalSpend)
        assertTrue(DcaAdvice.check(dca, plan).getValue(DcaAdvice.Field.TOTAL).contains("sisa 10"))
    }

    @Test
    fun `dca without a valid budget has no plan`() {
        assertNull(DcaAdvice.plan(DcaConfig(orderQuote = d("10"), maxTotalQuote = d("5")), d("10")))
        assertNull(DcaAdvice.plan(DcaConfig(orderQuote = d("0")), d("10")))
    }

    @Test
    fun `a very tight interval is flagged`() {
        val dca = DcaConfig(intervalSec = 30)
        assertTrue(DcaAdvice.check(dca, DcaAdvice.plan(dca, d("10"))).containsKey(DcaAdvice.Field.INTERVAL))
        assertTrue(DcaAdvice.check(DcaConfig(), DcaAdvice.plan(DcaConfig(), d("10"))).isEmpty())
    }

    @Test
    fun `durations are written in plain words`() {
        assertEquals("45 detik", DcaAdvice.duration(45))
        assertEquals("15 menit", DcaAdvice.duration(900))
        assertEquals("1 jam", DcaAdvice.duration(3600))
        assertEquals("1 jam 30 menit", DcaAdvice.duration(5400))
        assertEquals("2 hari 3 jam", DcaAdvice.duration(2 * 86_400 + 3 * 3600 + 120))
        assertEquals("sekali", DcaAdvice.duration(0))
        assertEquals("1 menit 5 detik", DcaAdvice.duration(65))
    }

    @Test
    fun `dca summary says it never sells`() {
        val dca = DcaConfig()
        val text = DcaAdvice.summary(dca, DcaAdvice.plan(dca, d("10"))!!, "USDT")
        assertTrue(text.contains("10 USDT setiap 1 jam, 10 kali, total 100 USDT"), text)
        assertTrue(text.contains("tidak pernah menjual"), text)
    }

    // ---- Risiko ----

    @Test
    fun `order cap is the smaller of the absolute limit and the equity share`() {
        val risk = RiskConfig() // 10 USDT atau 2% ekuitas
        assertDec("10", RiskAdvice.orderCap(risk, d("6968")))
        assertDec("2", RiskAdvice.orderCap(risk, d("100")))
        assertDec("10", RiskAdvice.orderCap(risk, null))
    }

    @Test
    fun `exposure cap and daily loss are translated using the equity`() {
        val risk = RiskConfig()
        assertDec("1045.2", RiskAdvice.exposureCap(risk, d("6968")))
        assertDec("0.29", RiskAdvice.dailyLossPercent(risk, d("6968")))
        assertNull(RiskAdvice.exposureCap(risk, null))
        assertNull(RiskAdvice.dailyLossPercent(risk, d("0")))
    }

    @Test
    fun `default risk settings raise no warnings`() {
        assertTrue(RiskAdvice.check(RiskConfig(), d("6968")).isEmpty())
    }

    @Test
    fun `loose or twitchy risk settings are explained`() {
        val warnings = RiskAdvice.check(
            RiskConfig(
                maxOrderPctOfEquity = d("0.2"), maxExposurePct = d("0.8"), maxPriceDeviationPct = d("0.1"),
                maxOpenOrders = 1, maxConsecutiveErrors = 2, maxClockSkewMs = 100, maxDailyLossQuote = d("1000"),
            ),
            d("6968"),
        )
        assertEquals(RiskAdvice.Field.entries.toSet(), warnings.keys)
    }
}
