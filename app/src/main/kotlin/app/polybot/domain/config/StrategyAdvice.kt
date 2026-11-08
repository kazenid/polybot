package app.polybot.domain.config

import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import java.math.BigDecimal
import java.math.RoundingMode

private val BPS = BigDecimal(10_000)
private val HUNDRED = BigDecimal(100)
private val TWO = BigDecimal(2)

private fun plain(value: BigDecimal): String = MarketMakerAdvice.plain(value)

/** Posisi harga terhadap rentang grid. Sama dengan aturan di `Grid.decide`. */
enum class RangePosition {
    /** Harga di antara batas bawah dan atas. */
    INSIDE,

    /** Di luar rentang tetapi masih dalam jarak keluar rentang: bot jalan, hanya satu sisi yang punya level. */
    OUTSIDE_RUNNING,

    /** Lewat jarak keluar rentang: bot berhenti dan membatalkan order. */
    HALT,

    /** Harga pasar belum diketahui. */
    UNKNOWN,
}

data class GridPlan(
    /** Level harga dari batas bawah ke atas (jumlah grid + 1). */
    val levels: List<BigDecimal>,
    val step: BigDecimal,
    /** Jarak antar level terhadap harga acuan (harga pasar, atau tengah rentang bila belum diketahui). */
    val stepBps: BigDecimal,
    val feeBps: BigDecimal,
    /** Hasil bersih teoritis satu siklus beli di satu level lalu jual di level berikutnya. */
    val netBps: BigDecimal,
    val buyLevels: Int,
    val sellLevels: Int,
    val quoteNeeded: BigDecimal,
    val baseValueNeeded: BigDecimal,
    val position: RangePosition,
) {
    val verdict: Verdict
        get() = when {
            netBps.signum() <= 0 -> Verdict.LOSS
            netBps < RoundTrip.THIN_BPS -> Verdict.THIN
            else -> Verdict.GOOD
        }
}

object GridAdvice {
    /** Rencana grid, atau null bila rentang belum valid (batas bawah/atas belum diisi atau terbalik, grid kurang dari 2). */
    fun plan(grid: GridConfig, makerFeeBps: BigDecimal, price: BigDecimal?): GridPlan? {
        if (grid.lower.signum() <= 0 || grid.upper <= grid.lower || grid.gridCount < 2) return null
        val step = grid.upper.subtract(grid.lower).divide(BigDecimal(grid.gridCount), 10, RoundingMode.HALF_EVEN)
        val levels = (0..grid.gridCount).map { grid.lower.add(step.multiply(BigDecimal(it))) }
        val reference = price?.takeIf { it.signum() > 0 } ?: grid.lower.add(grid.upper).divide(TWO)
        val stepBps = step.multiply(BPS).divide(reference, 1, RoundingMode.HALF_UP)
        val fee = makerFeeBps.multiply(TWO)
        val buy = if (price != null) levels.count { it < price } else 0
        val sell = if (price != null) levels.count { it > price } else 0
        return GridPlan(
            levels = levels,
            step = step,
            stepBps = stepBps,
            feeBps = fee,
            netBps = stepBps.subtract(fee),
            buyLevels = buy,
            sellLevels = sell,
            quoteNeeded = grid.orderQuote.multiply(BigDecimal(buy)),
            baseValueNeeded = grid.orderQuote.multiply(BigDecimal(sell)),
            position = position(grid, price),
        )
    }

    fun position(grid: GridConfig, price: BigDecimal?): RangePosition {
        if (price == null || grid.lower.signum() <= 0 || grid.upper <= grid.lower) return RangePosition.UNKNOWN
        val breakFraction = grid.rangeBreakBps.divide(BPS, 10, RoundingMode.HALF_EVEN)
        val breakLow = grid.lower.multiply(BigDecimal.ONE.subtract(breakFraction))
        val breakHigh = grid.upper.multiply(BigDecimal.ONE.add(breakFraction))
        return when {
            price < breakLow || price > breakHigh -> RangePosition.HALT
            price < grid.lower || price > grid.upper -> RangePosition.OUTSIDE_RUNNING
            else -> RangePosition.INSIDE
        }
    }

    /** Rentang simetris [pct] (mis. 0.05 = 5%) di sekitar [price], dibulatkan keluar ke kelipatan tick. */
    fun rangeAround(price: BigDecimal, pct: BigDecimal, tickSize: BigDecimal): Pair<BigDecimal, BigDecimal> {
        val lower = Numeric.roundPrice(price.multiply(BigDecimal.ONE.subtract(pct)), tickSize, Side.BUY)
        val upper = Numeric.roundPrice(price.multiply(BigDecimal.ONE.add(pct)), tickSize, Side.SELL)
        return lower to upper
    }

    enum class Field { RANGE, GRID_COUNT, BREAK }

    fun check(grid: GridConfig, risk: RiskConfig, plan: GridPlan?): Map<Field, String> = buildMap {
        if (grid.lower.signum() > 0 && grid.upper <= grid.lower) put(Field.RANGE, "Batas atas harus lebih besar dari batas bawah")
        if (grid.gridCount > risk.maxOpenOrders) put(Field.GRID_COUNT, "Lebih banyak dari order terbuka maksimum di Risiko (${risk.maxOpenOrders})")
        if (plan != null && plan.netBps.signum() <= 0) put(Field.GRID_COUNT, "Jarak antar level tidak menutup fee dua kali. Kurangi jumlah grid atau lebarkan rentang")
        if (grid.rangeBreakBps.signum() <= 0) put(Field.BREAK, "Nol berarti bot berhenti begitu harga menyentuh batas")
    }

    fun summary(grid: GridConfig, plan: GridPlan, quote: String, formatPrice: (BigDecimal) -> String): String =
        "Bot membagi ${formatPrice(grid.lower)} sampai ${formatPrice(grid.upper)} menjadi ${grid.gridCount} bagian " +
            "(jarak ${formatPrice(plan.step)}, sekitar ${plain(plan.stepBps)} bps). Beli di level di bawah harga dan jual di level di atasnya, " +
            "${plain(grid.orderQuote)} $quote per order. Bot berhenti dan membatalkan order bila harga keluar rentang lebih dari ${plain(grid.rangeBreakBps)} bps."
}

data class DcaPlan(
    val purchases: Int,
    val totalSpend: BigDecimal,
    /** Dari pembelian pertama sampai terakhir (pembelian pertama langsung). */
    val durationSec: Long,
    val estimatedFee: BigDecimal,
)

object DcaAdvice {
    fun plan(dca: DcaConfig, makerFeeBps: BigDecimal): DcaPlan? {
        if (dca.orderQuote.signum() <= 0 || dca.maxTotalQuote < dca.orderQuote || dca.intervalSec < 1) return null
        val purchases = dca.maxTotalQuote.divide(dca.orderQuote, 0, RoundingMode.DOWN).toInt()
        val total = dca.orderQuote.multiply(BigDecimal(purchases))
        return DcaPlan(
            purchases = purchases,
            totalSpend = total,
            durationSec = (purchases - 1).toLong() * dca.intervalSec,
            estimatedFee = total.multiply(makerFeeBps).divide(BPS, 6, RoundingMode.HALF_UP),
        )
    }

    enum class Field { INTERVAL, ORDER, TOTAL }

    fun check(dca: DcaConfig, plan: DcaPlan?): Map<Field, String> = buildMap {
        if (dca.intervalSec in 10..59) put(Field.INTERVAL, "Sangat rapat untuk DCA: pembelian jadi hampir terus-menerus")
        if (plan != null && plan.purchases > 500) put(Field.TOTAL, "${plan.purchases} pembelian: banyak sekali. Perbesar nilai per pembelian")
        if (dca.orderQuote.signum() > 0 && dca.maxTotalQuote >= dca.orderQuote && dca.maxTotalQuote.remainder(dca.orderQuote).signum() != 0) {
            put(Field.TOTAL, "Bukan kelipatan nilai per pembelian: sisa ${plain(dca.maxTotalQuote.remainder(dca.orderQuote))} tidak dibelanjakan")
        }
    }

    /** Durasi dalam bahasa biasa: "45 detik", "15 menit", "1 jam 30 menit", "2 hari 3 jam". */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "sekali"
        val day = seconds / 86_400
        val hour = seconds % 86_400 / 3_600
        val minute = seconds % 3_600 / 60
        val second = seconds % 60
        val parts = buildList {
            if (day > 0) add("$day hari")
            if (hour > 0) add("$hour jam")
            if (minute > 0) add("$minute menit")
            if (second > 0 && day == 0L && hour == 0L) add("$second detik")
        }
        return parts.take(2).joinToString(" ")
    }

    fun summary(dca: DcaConfig, plan: DcaPlan, quote: String): String =
        "Bot membeli ${plain(dca.orderQuote)} $quote setiap ${duration(dca.intervalSec.toLong())}, ${plan.purchases} kali, " +
            "total ${plain(plan.totalSpend)} $quote. Pembelian pertama langsung, selesai sekitar ${duration(plan.durationSec)} kemudian. " +
            "DCA hanya membeli dan tidak pernah menjual."
}

object RiskAdvice {
    /** Nilai order maksimum yang berlaku: yang lebih kecil antara batas mutlak dan persen ekuitas. */
    fun orderCap(risk: RiskConfig, equity: BigDecimal?): BigDecimal =
        if (equity == null) risk.maxOrderQuote else risk.maxOrderQuote.min(risk.maxOrderPctOfEquity.multiply(equity))

    /** Nilai aset dasar maksimum yang boleh ditahan (dalam quote). */
    fun exposureCap(risk: RiskConfig, equity: BigDecimal?): BigDecimal? = equity?.multiply(risk.maxExposurePct)

    /** Batas rugi harian sebagai persen ekuitas, satu desimal; null tanpa ekuitas. */
    fun dailyLossPercent(risk: RiskConfig, equity: BigDecimal?): BigDecimal? =
        equity?.takeIf { it.signum() > 0 }?.let { risk.maxDailyLossQuote.multiply(HUNDRED).divide(it, 2, RoundingMode.HALF_UP) }

    enum class Field { ORDER_PCT, EXPOSURE, DEVIATION, OPEN_ORDERS, ERRORS, CLOCK, DAILY_LOSS }

    fun check(risk: RiskConfig, equity: BigDecimal?): Map<Field, String> = buildMap {
        if (risk.maxOrderPctOfEquity > BigDecimal("0.1")) put(Field.ORDER_PCT, "Lebih dari 10% ekuitas dalam satu order: sangat besar")
        if (risk.maxExposurePct > BigDecimal("0.5")) put(Field.EXPOSURE, "Lebih dari separuh ekuitas bisa tertahan di aset dasar")
        if (risk.maxPriceDeviationPct > BigDecimal("0.05")) put(Field.DEVIATION, "Lebih dari 5%: hampir tidak menyaring harga yang janggal")
        if (risk.maxOpenOrders < 2) put(Field.OPEN_ORDERS, "Market Maker butuh minimal 2 (beli dan jual)")
        if (risk.maxConsecutiveErrors < 3) put(Field.ERRORS, "Terlalu sensitif: gangguan server sesaat (HTTP 503) akan menjeda bot")
        if (risk.maxClockSkewMs < 500) put(Field.CLOCK, "Terlalu ketat: bot sering dijeda karena selisih jam kecil")
        val pct = dailyLossPercent(risk, equity)
        if (pct != null && pct > BigDecimal(5)) put(Field.DAILY_LOSS, "Setara ${plain(pct)}% ekuitas: batas yang longgar")
    }
}
