package app.polybot.domain.numeric

import app.polybot.domain.model.Market
import app.polybot.domain.model.Side
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Semua konversi harga/kuantitas lewat sini. Tanpa Double/Float. */
object Numeric {
    /** `priceTicks` = harga * 1e9 (terkonfirmasi dari order book testnet). */
    const val PRICE_SCALE = 9

    /** Nilai ledger (saldo, fee `*_e18`) memakai 18 desimal. */
    const val LEDGER_SCALE = 18

    private val UINT64_RANGE = BigInteger.ONE.shiftLeft(64)

    fun ticksToPrice(ticks: Long): BigDecimal = BigDecimal.valueOf(ticks, PRICE_SCALE)

    /** Tepat 1e-9; menolak harga yang lebih halus atau di luar rentang Long. */
    fun priceToTicks(price: BigDecimal): Long =
        price.movePointRight(PRICE_SCALE).longValueExact()

    fun scaledToQty(scaled: Long, scale: Int): BigDecimal = BigDecimal.valueOf(scaled, scale)

    /** Tepat sesuai skala; menolak kuantitas yang lebih halus dari skala simbol. */
    fun qtyToScaled(qty: BigDecimal, scale: Int): Long =
        qty.movePointRight(scale).longValueExact()

    /** U128 `hi`/`lo` (uint64 teks desimal) menjadi nilai desimal berskala [scale]. */
    fun u128ToDecimal(hi: String, lo: String, scale: Int = LEDGER_SCALE): BigDecimal {
        val high = BigInteger(hi.ifEmpty { "0" })
        val low = BigInteger(lo.ifEmpty { "0" })
        require(high.signum() >= 0 && high < UINT64_RANGE && low.signum() >= 0 && low < UINT64_RANGE) {
            "U128 part out of uint64 range"
        }
        return BigDecimal(high.shiftLeft(64).or(low), scale)
    }

    /** Pembulatan konservatif ke kelipatan tick: bid ke bawah, ask ke atas. */
    fun roundPrice(price: BigDecimal, tickSize: BigDecimal, side: Side): BigDecimal {
        require(tickSize.signum() > 0) { "tickSize must be positive" }
        val mode = if (side == Side.BUY) RoundingMode.FLOOR else RoundingMode.CEILING
        return price.divide(tickSize, 0, mode).multiply(tickSize)
    }

    /** Kuantitas dibulatkan ke bawah ke kelipatan step. */
    fun floorQty(qty: BigDecimal, stepSize: BigDecimal): BigDecimal {
        require(stepSize.signum() > 0) { "stepSize must be positive" }
        return qty.divide(stepSize, 0, RoundingMode.FLOOR).multiply(stepSize)
    }

    enum class Violation { NON_POSITIVE, PRICE_OFF_TICK, QTY_OFF_STEP, BELOW_MIN_QTY, BELOW_MIN_NOTIONAL, ABOVE_MAX_NOTIONAL }

    /** Cek lokal sebelum order dikirim; server tetap memvalidasi ulang. Null = lolos. */
    fun validate(market: Market, price: BigDecimal, qty: BigDecimal): Violation? {
        if (price.signum() <= 0 || qty.signum() <= 0) return Violation.NON_POSITIVE
        if (price.remainder(market.tickSize).signum() != 0) return Violation.PRICE_OFF_TICK
        if (qty.remainder(market.stepSize).signum() != 0) return Violation.QTY_OFF_STEP
        if (qty < market.minQty) return Violation.BELOW_MIN_QTY
        val notional = price.multiply(qty)
        if (notional < market.minNotional) return Violation.BELOW_MIN_NOTIONAL
        val max = market.maxOrderNotional
        if (max != null && max.signum() > 0 && notional > max) return Violation.ABOVE_MAX_NOTIONAL
        return null
    }
}
