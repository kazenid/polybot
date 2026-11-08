package app.polybot.domain.report

import app.polybot.domain.model.Side
import java.math.BigDecimal
import java.math.MathContext

/** Satu fill beserta laba rugi terealisasinya (hanya jual yang merealisasi). */
data class FillResult(val fill: Fill, val realized: BigDecimal)

/** Sisa pembelian yang belum terjual: [qty] pada harga [cost] (tanpa fee). */
data class Lot(val qty: BigDecimal, val cost: BigDecimal)

/** Posisi terbuka per simbol; [lots] urut dari yang tertua ke terbaru. */
data class SymbolPosition(val symbol: String, val qty: BigDecimal, val avgCost: BigDecimal, val lots: List<Lot> = emptyList()) {
    /** Biaya pembelian terbaru yang masih terbuka; dasar lantai jual impas. */
    val newestLotCost: BigDecimal? get() = lots.lastOrNull()?.cost
}

data class PnlResult(val fills: List<FillResult>, val positions: List<SymbolPosition>)

data class PnlSummary(
    val trades: Int,
    val volumeQuote: BigDecimal,
    /** Laba rugi terealisasi dari jual, sebelum fee. */
    val realized: BigDecimal,
    /** Fee bersih (rebate mengurangi). */
    val fees: BigDecimal,
) {
    val net: BigDecimal get() = realized.subtract(fees)

    /** Fee sebagai bps dari volume; null tanpa volume. */
    val feeBps: BigDecimal? get() =
        if (volumeQuote.signum() > 0) fees.multiply(BPS).divide(volumeQuote, 1, java.math.RoundingMode.HALF_UP) else null

    /** Fee sebagai persen laba kotor terealisasi; null bila belum ada laba kotor. */
    val feeShareOfGross: BigDecimal? get() =
        if (realized.signum() > 0) fees.multiply(HUNDRED).divide(realized, 0, java.math.RoundingMode.HALF_UP) else null

    /** Laba kotor cukup untuk menutup fee. */
    val coversFees: Boolean get() = realized > fees

    private companion object {
        val BPS: BigDecimal = BigDecimal(10_000)
        val HUNDRED: BigDecimal = BigDecimal(100)
    }
}

/**
 * Pencocokan per lot, terbaru dulu (LIFO), per simbol (spot, tanpa short). Jual mencocokkan diri dengan pembelian
 * terbaru yang masih terbuka, sesuai cara market maker bekerja (beli lalu jual kembali), sehingga lot lama yang dibeli
 * di harga jauh berbeda tidak menyeret putaran baru; lot lama tetap terbuka sampai benar-benar terjual.
 * Jual melebihi posisi yang dikenal (riwayat sebelum data lokal) dihitung impas untuk kelebihannya, bukan rugi atau
 * laba fiktif. Fee dilaporkan terpisah.
 */
object PnlCalculator {
    private val MC = MathContext.DECIMAL128

    fun compute(fills: List<Fill>): PnlResult {
        val results = ArrayList<FillResult>(fills.size)
        val positions = mutableListOf<SymbolPosition>()
        for ((symbol, group) in fills.groupBy { it.symbol }) {
            val lots = ArrayDeque<Lot>()
            for (fill in group.sortedWith(compareBy({ it.tsMs }, { it.matchId }))) {
                var realized = BigDecimal.ZERO
                when (fill.side) {
                    Side.BUY -> lots.addLast(Lot(fill.qty, fill.price))
                    Side.SELL -> {
                        var remaining = fill.qty
                        while (remaining.signum() > 0 && lots.isNotEmpty()) {
                            val top = lots.removeLast()
                            val matched = remaining.min(top.qty)
                            realized = realized.add(fill.price.subtract(top.cost).multiply(matched))
                            remaining = remaining.subtract(matched)
                            if (top.qty > matched) lots.addLast(Lot(top.qty.subtract(matched), top.cost))
                        }
                    }
                }
                results += FillResult(fill, realized)
            }
            val qty = lots.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.qty) }
            val cost = lots.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.qty.multiply(l.cost)) }
            positions += SymbolPosition(symbol, qty, if (qty.signum() > 0) cost.divide(qty, MC) else BigDecimal.ZERO, lots.toList())
        }
        return PnlResult(results.sortedWith(compareBy({ it.fill.tsMs }, { it.fill.matchId })), positions.sortedBy { it.symbol })
    }

    /** Ringkasan untuk subset fill (mis. rentang waktu), memakai hasil yang dihitung dari seluruh riwayat. */
    fun summarize(results: List<FillResult>): PnlSummary = PnlSummary(
        trades = results.size,
        volumeQuote = results.sumOf { it.fill.notional },
        realized = results.sumOf { it.realized },
        fees = results.sumOf { it.fill.feeQuote },
    )
}
