package app.polybot.domain.report

import app.polybot.data.api.dto.U128Dto
import app.polybot.data.api.dto.UserTradeDto
import app.polybot.data.report.FillMapper
import app.polybot.domain.engine.btcUsdt
import app.polybot.domain.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReportTest {
    private fun d(s: String) = BigDecimal(s)
    private var seq = 0L

    private fun fill(side: Side, price: String, qty: String, ts: Long, symbol: String = "BTC-USDT", fee: String = "0") =
        Fill(++seq, seq, symbol, side, true, d(price), d(qty), d(fee), ts)

    private fun realized(fills: List<Fill>) = PnlCalculator.compute(fills).fills.map { it.realized }
    private fun assertDec(expected: String, actual: BigDecimal) = assertEquals(0, d(expected).compareTo(actual), "expected $expected but was $actual")

    // ---- PnL ----

    @Test
    fun `a round trip realizes the price difference`() {
        val r = realized(listOf(fill(Side.BUY, "100", "1", 1), fill(Side.SELL, "110", "1", 2)))
        assertDec("0", r[0])
        assertDec("10", r[1])
        assertDec("-10", realized(listOf(fill(Side.BUY, "100", "1", 1), fill(Side.SELL, "90", "1", 2)))[1])
    }

    @Test
    fun `a sell matches the newest lot and the older lot stays open`() {
        val result = PnlCalculator.compute(
            listOf(fill(Side.BUY, "100", "1", 1), fill(Side.BUY, "120", "1", 2), fill(Side.SELL, "130", "1", 3)),
        )
        assertDec("10", result.fills[2].realized) // (130 - 120) x 1: lot terbaru dulu
        val position = result.positions.single()
        assertDec("1", position.qty)
        assertDec("100", position.avgCost)
        assertDec("100", position.newestLotCost!!)
    }

    @Test
    fun `an old expensive lot does not drag new round trips into a loss`() {
        // Lot lama 80000, lalu dua putaran di sekitar 78000: laba putaran baru tetap terlihat.
        val result = PnlCalculator.compute(
            listOf(
                fill(Side.BUY, "80000", "1", 1),
                fill(Side.BUY, "78000", "1", 2), fill(Side.SELL, "78200", "1", 3),
                fill(Side.BUY, "77900", "1", 4), fill(Side.SELL, "78100", "1", 5),
            ),
        )
        assertDec("200", result.fills[2].realized)
        assertDec("200", result.fills[4].realized)
        assertDec("80000", result.positions.single().avgCost)
    }

    @Test
    fun `a sell larger than the newest lot continues into older lots`() {
        val result = PnlCalculator.compute(
            listOf(fill(Side.BUY, "100", "1", 1), fill(Side.BUY, "110", "1", 2), fill(Side.SELL, "120", "1.5", 3)),
        )
        assertDec("20", result.fills[2].realized) // 1 x (120-110) + 0.5 x (120-100)
        val position = result.positions.single()
        assertDec("0.5", position.qty)
        assertDec("100", position.newestLotCost!!)
        assertEquals(1, position.lots.size)
    }

    @Test
    fun `partial sell leaves the average cost unchanged`() {
        val result = PnlCalculator.compute(listOf(fill(Side.BUY, "100", "2", 1), fill(Side.SELL, "110", "1", 2)))
        assertDec("10", result.fills[1].realized)
        assertDec("100", result.positions.single().avgCost)
        assertDec("1", result.positions.single().qty)
    }

    @Test
    fun `selling more than the known position only realizes the known part`() {
        assertDec("10", realized(listOf(fill(Side.BUY, "100", "1", 1), fill(Side.SELL, "110", "2", 2)))[1])
        assertDec("0", realized(listOf(fill(Side.SELL, "100", "1", 1)))[0])
        assertDec("0", PnlCalculator.compute(listOf(fill(Side.SELL, "100", "1", 1))).positions.single().qty)
    }

    @Test
    fun `symbols are independent and input order does not matter`() {
        val shuffled = listOf(
            fill(Side.SELL, "3100", "1", 4, "ETH-USDT"), fill(Side.BUY, "100", "1", 1), fill(Side.BUY, "3000", "1", 3, "ETH-USDT"),
            fill(Side.SELL, "105", "1", 2),
        )
        val results = PnlCalculator.compute(shuffled).fills.associate { it.fill.matchId to it.realized }
        assertDec("100", results.getValue(shuffled[0].matchId))
        assertDec("5", results.getValue(shuffled[3].matchId))
    }

    @Test
    fun `exact decimal arithmetic without rounding drift`() {
        val r = realized(listOf(fill(Side.BUY, "10", "3", 1), fill(Side.BUY, "11", "3", 2), fill(Side.SELL, "12", "6", 3)))
        assertDec("9", r[2]) // 3 x (12-11) + 3 x (12-10)
    }

    @Test
    fun `summary separates realized pnl from fees and counts rebates as income`() {
        val fills = listOf(
            fill(Side.BUY, "100", "1", 1, fee = "0.10"),
            fill(Side.SELL, "110", "1", 2, fee = "-0.05"), // rebate
        )
        val summary = PnlCalculator.summarize(PnlCalculator.compute(fills).fills)
        assertEquals(2, summary.trades)
        assertDec("210", summary.volumeQuote)
        assertDec("10", summary.realized)
        assertDec("0.05", summary.fees)
        assertDec("9.95", summary.net)
    }

    // ---- FillMapper ----

    private fun dto(
        side: String = "BUY", fee: String = "50000000000000000", feeAsset: String = "QUOTE", rebate: Boolean = false,
        price: Long = 77_000_000_000_000, qty: Long = 10_000, match: Long = 5, ts: Long = 1_793_461_450_428_374_869,
    ) = UserTradeDto(
        symbolId = 1, matchId = match, orderId = 9, side = side, isMaker = true, priceTicks = price, qtyScaled = qty,
        feeAmountE18 = U128Dto("0", fee), feeAsset = feeAsset, feeIsRebate = rebate, tsNs = ts,
    )

    @Test
    fun `mapper converts units and time`() {
        val fill = FillMapper.map(dto(), btcUsdt)!!
        assertEquals(Side.BUY, fill.side)
        assertDec("77000", fill.price)
        assertDec("0.0001", fill.qty)
        assertDec("0.05", fill.feeQuote)
        assertEquals(1_793_461_450_428L, fill.tsMs)
        assertEquals("BTC-USDT", fill.symbol)
    }

    @Test
    fun `fees in the base asset are converted to quote at the fill price and rebates are negative`() {
        val baseFee = FillMapper.map(dto(fee = "100000000000000", feeAsset = "BASE"), btcUsdt)!! // 0.0001 BTC
        assertDec("7.7", baseFee.feeQuote)
        assertDec("-0.05", FillMapper.map(dto(rebate = true), btcUsdt)!!.feeQuote)
    }

    @Test
    fun `mapper rejects unusable rows instead of inventing numbers`() {
        assertNull(FillMapper.map(dto(side = ""), btcUsdt))
        assertNull(FillMapper.map(dto(qty = 0), btcUsdt))
        assertNull(FillMapper.map(dto(price = 0), btcUsdt))
        assertNull(FillMapper.map(dto(match = 0), btcUsdt))
    }

    // ---- CSV ----

    @Test
    fun `csv has a header, one line per fill, and plain decimals`() {
        val results = PnlCalculator.compute(listOf(fill(Side.BUY, "100.5", "0.001", 1_793_461_450_000, fee = "0.01"), fill(Side.SELL, "101", "0.001", 1_793_461_460_000)))
            .fills
        val lines = CsvExporter.fills(results).trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("waktu_utc,simbol,sisi,peran"))
        assertTrue(lines[1].startsWith("2026-10-31T") && lines[1].contains(",BTC-USDT,BUY,maker,100.5,0.001,0.1005,0.01,0,"))
        assertTrue(lines[2].contains(",SELL,") && lines[2].contains("0.0005"), lines[2])
    }

    @Test
    fun `csv escapes separators and neutralizes spreadsheet formulas`() {
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""))
        assertEquals("'=1+1", CsvExporter.escape("=1+1"))
        assertEquals("-0.5", CsvExporter.escape("-0.5"), "angka negatif tidak diubah")
    }

    // ---- downsample ----

    @Test
    fun `downsample keeps both ends, order, and the size limit`() {
        val items = (1..1000).toList()
        val small = downsample(items, 50)
        assertEquals(50, small.size)
        assertEquals(1, small.first())
        assertEquals(1000, small.last())
        assertEquals(small.sorted(), small)
        assertEquals(items.take(10), downsample(items.take(10), 50))
    }
}
