package app.polybot.domain.report

import java.math.BigDecimal

/** Satu catatan ekuitas (dalam aset quote pair [symbol]) yang direkam bot tiap menit. */
data class EquityPoint(val tsMs: Long, val equity: BigDecimal, val dryRun: Boolean, val symbol: String = "")

/** Aset quote dari simbol seperti `BTC-USDT` -> `USDT`; kosong bila format tidak dikenal. */
fun quoteOf(symbol: String): String = symbol.substringAfter('-', "")

object EquityStats {
    /** Sesi bot dianggap putus bila jarak dua catatan melebihi ini. */
    const val SESSION_GAP_MS = 5 * 60_000L

    /**
     * Hanya catatan yang satuannya sama dengan catatan terbaru. Ekuitas pair berkuotasi BTC (mis. ETH-BTC) bernilai
     * ~0,008 dan merusak grafik bila dicampur dengan ekuitas dalam USDT.
     */
    fun sameQuoteAsLatest(points: List<EquityPoint>): List<EquityPoint> {
        val quote = quoteOf(points.lastOrNull()?.symbol.orEmpty())
        return if (quote.isEmpty()) points else points.filter { quoteOf(it.symbol) == quote }
    }

    /**
     * Perubahan ekuitas selama bot berjalan: jumlah (akhir - awal) tiap sesi yang bersambung, hanya sesi live dan
     * satu satuan quote. Selang waktu bot mati tidak dihitung. Termasuk naik turunnya harga aset yang dipegang.
     */
    fun sessionChange(points: List<EquityPoint>, gapMs: Long = SESSION_GAP_MS): BigDecimal {
        val live = sameQuoteAsLatest(points).filter { !it.dryRun }.sortedBy { it.tsMs }
        if (live.size < 2) return BigDecimal.ZERO
        var total = BigDecimal.ZERO
        var start = live.first()
        var prev = start
        for (p in live.drop(1)) {
            if (p.tsMs - prev.tsMs > gapMs || p.symbol != prev.symbol) {
                total = total.add(prev.equity.subtract(start.equity))
                start = p
            }
            prev = p
        }
        return total.add(prev.equity.subtract(start.equity))
    }
}
