package app.polybot.domain.market

import app.polybot.domain.model.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/** Timeframe candle yang dipakai UI; [wire] adalah nama enum server (terverifikasi di testnet). */
enum class Timeframe(val wire: String, val label: String) {
    MIN_1("MIN_1", "1 mnt"),
    MIN_5("MIN_5", "5 mnt"),
    MIN_15("MIN_15", "15 mnt"),
    HOUR_1("HOUR_1", "1 jam"),
    HOUR_4("HOUR_4", "4 jam"),
    DAY_1("DAY_1", "1 hari"),
}

/** Perubahan harga dari [reference] ke [last] selama [spanHours] jam data yang tersedia. */
data class PriceChange(val last: BigDecimal, val reference: BigDecimal, val spanHours: Int) {
    val amount: BigDecimal get() = last.subtract(reference)

    /** Persen perubahan, dua desimal; null bila harga acuan nol. */
    val percent: BigDecimal? get() =
        if (reference.signum() > 0) amount.multiply(HUNDRED).divide(reference, 2, RoundingMode.HALF_UP) else null

    val direction: Direction get() = when (amount.signum()) {
        1 -> Direction.UP
        -1 -> Direction.DOWN
        else -> Direction.FLAT
    }

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal(100)
    }
}

enum class Direction { UP, DOWN, FLAT }

object MarketStats {
    /**
     * Perubahan harga sepanjang [candles] (urut lama ke baru). Acuan = open candle tertua, jadi dengan 25 candle
     * 1 jam hasilnya adalah perubahan 24 jam. [lastPrice] (mis. harga tengah order book) menggantikan close terakhir
     * bila ada, supaya angka ikut harga terkini.
     */
    fun change(candles: List<Candle>, lastPrice: BigDecimal?): PriceChange? {
        val first = candles.firstOrNull() ?: return null
        val last = lastPrice?.takeIf { it.signum() > 0 } ?: candles.last().close
        val span = ((candles.last().tsSec - first.tsSec) / 3600).toInt() + 1
        return PriceChange(last, first.open, span)
    }

    fun high(candles: List<Candle>): BigDecimal? = candles.maxOfOrNull { it.high }

    fun low(candles: List<Candle>): BigDecimal? = candles.minOfOrNull { it.low }

    fun volume(candles: List<Candle>): BigDecimal = candles.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.volume) }
}
