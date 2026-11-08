package app.polybot.domain.engine

import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.Market
import app.polybot.domain.model.Side
import java.math.BigDecimal
import java.math.RoundingMode

/** Data pasar dan saldo untuk satu siklus; diambil dari sumber data (live) atau simulasi. */
data class MarketInputs(
    val bestBid: BigDecimal?,
    val bestAsk: BigDecimal?,
    val lastPrice: BigDecimal?,
    val baseAvailable: BigDecimal,
    val quoteAvailable: BigDecimal,
    val baseTotal: BigDecimal,
    val quoteTotal: BigDecimal,
    /** Selisih jam HP terhadap server (ms), null bila belum terukur. */
    val clockSkewMs: Long?,
)

interface MarketDataSource {
    suspend fun load(market: Market): MarketInputs
}

data class Snapshot(
    val nowMs: Long,
    val market: Market,
    val inputs: MarketInputs,
    /** Order milik bot yang belum terminal (dari catatan lokal yang sudah direkonsiliasi). */
    val openOrders: List<LocalOrder>,
    /** Batas order terbuka total dari server; 0 = tidak ada batas yang diumumkan. */
    val serverMaxOpenOrders: Int = 0,
    /** Biaya pembelian terbaru yang belum terjual (tanpa fee) dari riwayat fill; null bila tidak diketahui. */
    val lotCost: BigDecimal? = null,
) {
    val bestBid: BigDecimal? get() = inputs.bestBid
    val bestAsk: BigDecimal? get() = inputs.bestAsk

    val mid: BigDecimal?
        get() {
            val bid = bestBid
            val ask = bestAsk
            return if (bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0) {
                bid.add(ask).divide(TWO)
            } else {
                inputs.lastPrice?.takeIf { it.signum() > 0 }
            }
        }

    /** Null bila buku normal; selain itu alasan buku dianggap anomali. */
    val bookAnomaly: String?
        get() {
            val bid = bestBid
            val ask = bestAsk
            return when {
                bid == null || ask == null || bid.signum() <= 0 || ask.signum() <= 0 -> "Order book kosong"
                bid >= ask -> "Order book crossed (bid >= ask)"
                else -> null
            }
        }

    val baseValueQuote: BigDecimal get() = inputs.baseTotal.multiply(mid ?: BigDecimal.ZERO)

    val equityQuote: BigDecimal get() = inputs.quoteTotal.add(baseValueQuote)

    /** Nilai aset dasar sebagai fraksi ekuitas (0 bila ekuitas nol). */
    val inventoryFraction: BigDecimal
        get() = equityQuote.takeIf { it.signum() > 0 }?.let { baseValueQuote.divide(it, 10, RoundingMode.HALF_EVEN) } ?: BigDecimal.ZERO

    fun openOrders(side: Side): List<LocalOrder> = openOrders.filter { it.side == side }

    companion object {
        val TWO: BigDecimal = BigDecimal(2)
    }
}
