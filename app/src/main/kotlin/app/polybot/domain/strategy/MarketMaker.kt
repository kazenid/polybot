package app.polybot.domain.strategy

import app.polybot.domain.config.BotConfig
import app.polybot.domain.config.MarketMakerConfig
import app.polybot.domain.engine.Snapshot
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Market maker ringan (plan bagian 10.2).
 *
 * - **Skew netral**: inventori dinilai relatif terhadap posisi saat sesi dimulai ([anchor]), bukan target tetap.
 *   Bertambahnya inventori menggeser kuotasi turun (lebih mudah menjual); berkurangnya menggesernya naik.
 * - **Half spread minimum**: tidak pernah di bawah [MarketMakerConfig.MIN_HALF_SPREAD_BPS]; di bawah fee + margin
 *   ([MarketMakerConfig.safeHalfSpreadBps]) diizinkan tetapi UI memberi peringatan.
 * - **Umur order**: order lama hanya dibatalkan bila harganya sudah menyimpang dari kuotasi ideal.
 * - **Requote asimetris**: order yang menjadi lebih agresif dari kuotasi ideal (berbahaya, mudah tersambar) dibatalkan
 *   pada ambang kecil; order yang menjadi lebih jauh (aman) dibiarkan sampai ambang 3x, sehingga antrean tidak terus hilang.
 * - **Sisi yang kosong dipasang sendiri** setelah cooldown, tanpa menunggu sisi lain.
 * - **Lantai jual impas** ([MarketMakerConfig.sellAboveCost]): bila biaya pembelian terbaru yang belum terjual (lot
 *   teratas) diketahui, jual tidak pernah di bawah biaya itu + fee beli dan jual + margin; saat harga di bawah itu, order
 *   jual menunggu di lantai. Memakai lot terbaru (bukan rata-rata) agar lot lama yang mahal tidak memblokir putaran baru.
 * - **Tahan beli saat tren turun**: bila inventori sudah di atas posisi awal dan harga turun dari puncak jendela.
 */
class MarketMaker : Strategy {
    override val name = "Market Maker"

    private var lastActionMs: Long = 0
    private var anchor: BigDecimal? = null
    private val mids = ArrayDeque<Pair<Long, BigDecimal>>()

    override fun decide(snapshot: Snapshot, config: BotConfig): List<Intent> {
        val cfg = config.marketMaker
        val mid = snapshot.mid ?: return emptyList()
        // Jangan pasang order jika tidak ada book segar — mid dari lastPrice saja tidak cukup
        // karena lastPrice bisa sangat lama saat book Polyester testnet sedang tipis.
        if (snapshot.bestBid == null || snapshot.bestAsk == null) return emptyList()
        val fraction = snapshot.inventoryFraction
        val start = anchor ?: fraction.also { anchor = it }
        recordMid(snapshot.nowMs, mid, cfg.trendWindowSec)

        val ours = snapshot.openOrders
        val ideal = IdealQuotes(snapshot, config, fraction, start)

        val cancels = ours.filter { it.status == LocalStatus.OPEN }.mapNotNull { order -> staleReason(order, ideal, snapshot.nowMs, cfg)?.let { Intent.Cancel(order.clientOrderId, it) } }
        if (cancels.isNotEmpty()) {
            lastActionMs = snapshot.nowMs
            return cancels
        }
        if (snapshot.nowMs - lastActionMs < cfg.cooldownSec * 1_000L) return emptyList()

        val band = config.risk.maxExposurePct
        val holdBuy = fraction >= band.multiply(cfg.maxInventoryPct) || (fraction > start && fellFromWindowHigh(mid, cfg.trendHoldBps))
        val holdSell = fraction <= band.multiply(BigDecimal.ONE.subtract(cfg.maxInventoryPct))

        val intents = mutableListOf<Intent>()
        if (!holdBuy && ours.none { it.side == Side.BUY }) {
            for (level in 1..cfg.levels) quote(Side.BUY, ideal.price(Side.BUY, level), cfg.orderQuote, snapshot, level)?.let(intents::add)
        }
        if (!holdSell && ours.none { it.side == Side.SELL }) {
            for (level in 1..cfg.levels) {
                val note = if (ideal.atFloor(level)) " (impas)" else ""
                quote(Side.SELL, ideal.price(Side.SELL, level), cfg.orderQuote, snapshot, level, note)?.let(intents::add)
            }
        }
        if (intents.isNotEmpty()) lastActionMs = snapshot.nowMs
        return intents
    }

    /** Kuotasi ideal saat ini untuk tiap sisi dan level, sudah memperhitungkan skew dan batas pasar. */
    private class IdealQuotes(private val s: Snapshot, config: BotConfig, fraction: BigDecimal, anchor: BigDecimal) {
        private val cfg = config.marketMaker
        private val mid = s.mid!!
        private val skew: BigDecimal

        /** Harga jual terendah yang masih impas; null bila dimatikan atau biaya posisi tidak diketahui. */
        private val sellFloor: BigDecimal? = s.lotCost
            ?.takeIf { cfg.sellAboveCost && it.signum() > 0 }
            ?.let { Numeric.roundPrice(cfg.breakEvenSell(it), s.market.tickSize, Side.SELL) }

        init {
            // inv di [-1, 1]: 0 = posisi awal sesi; +1 = bertambah sebesar separuh pita eksposur atau lebih.
            val half = config.risk.maxExposurePct.divide(Snapshot.TWO)
            val inv = if (half.signum() > 0) fraction.subtract(anchor).divide(half, 8, RoundingMode.HALF_EVEN).coerceIn(-ONE, ONE) else BigDecimal.ZERO
            val skewBps = inv.multiply(cfg.skewFactor).multiply(cfg.effectiveHalfSpreadBps).negate()
            skew = BigDecimal.ONE.add(skewBps.divide(BPS, 10, RoundingMode.HALF_EVEN))
        }

        fun price(side: Side, level: Int): BigDecimal {
            val market = s.market
            val offsetBps = cfg.effectiveHalfSpreadBps.add(cfg.levelStepBps.multiply(BigDecimal(level - 1)))
            val offset = offsetBps.divide(BPS, 10, RoundingMode.HALF_EVEN)
            return if (side == Side.BUY) {
                var bid = Numeric.roundPrice(mid.multiply(BigDecimal.ONE.subtract(offset)).multiply(skew), market.tickSize, Side.BUY)
                s.bestAsk?.let { ask -> if (bid >= ask) bid = Numeric.roundPrice(ask.subtract(market.tickSize), market.tickSize, Side.BUY) }
                bid
            } else {
                var ask = Numeric.roundPrice(mid.multiply(BigDecimal.ONE.add(offset)).multiply(skew), market.tickSize, Side.SELL)
                s.bestBid?.let { bid -> if (ask <= bid) ask = Numeric.roundPrice(bid.add(market.tickSize), market.tickSize, Side.SELL) }
                sellFloor?.let { floor -> if (ask < floor) ask = floor }
                ask
            }
        }

        /** True bila harga jual level ini dinaikkan oleh lantai impas. */
        fun atFloor(level: Int): Boolean = sellFloor != null && price(Side.SELL, level) == sellFloor

        /** Harga ideal level yang paling dekat dengan harga order. */
        fun nearest(side: Side, price: BigDecimal): BigDecimal =
            (1..cfg.levels).map { price(side, it) }.minByOrNull { it.subtract(price).abs() } ?: price
    }

    private fun quote(side: Side, price: BigDecimal, orderQuote: BigDecimal, s: Snapshot, level: Int, suffix: String = ""): Intent.Place? {
        if (price.signum() <= 0) return null
        val market = s.market
        val qty = Numeric.floorQty(orderQuote.divide(price, market.baseScale + 2, RoundingMode.DOWN), market.stepSize)
        if (Numeric.validate(market, price, qty) != null) return null
        return Intent.Place(side, price, qty, note = "MM L$level ${side.name.lowercase()}$suffix")
    }

    private fun staleReason(order: LocalOrder, ideal: IdealQuotes, nowMs: Long, cfg: MarketMakerConfig): String? {
        val price = Numeric.ticksToPrice(order.priceTicks)
        val target = ideal.nearest(order.side, price)
        // Umur hanya membatalkan order yang harganya sudah tidak sama dengan kuotasi ideal. Bila pasar diam dan
        // harganya masih pas, dibiarkan agar posisi antrean tidak hilang.
        if (nowMs - order.createdAtMs > cfg.maxOrderAgeSec * 1_000L && price.compareTo(target) != 0) {
            return "requote: order berumur > ${cfg.maxOrderAgeSec}s dan harga ideal berubah"
        }
        if (target.signum() <= 0) return null
        // + berarti order lebih agresif (lebih dekat ke pasar) dari kuotasi ideal.
        val diff = if (order.side == Side.BUY) price.subtract(target) else target.subtract(price)
        val aggressiveBps = diff.divide(target, 10, RoundingMode.HALF_EVEN).multiply(BPS)
        val danger = cfg.requoteThresholdBps
        val far = cfg.requoteThresholdBps.multiply(FAR_FACTOR)
        return when {
            aggressiveBps > danger -> "requote: mid bergeser ${aggressiveBps.setScale(1, RoundingMode.HALF_UP).toPlainString()} bps ke arah order"
            aggressiveBps.negate() > far -> "requote: order terlalu jauh ${aggressiveBps.negate().setScale(1, RoundingMode.HALF_UP).toPlainString()} bps"
            else -> null
        }
    }

    private fun recordMid(nowMs: Long, mid: BigDecimal, windowSec: Int) {
        mids.addLast(nowMs to mid)
        val cutoff = nowMs - windowSec * 1_000L
        while (mids.size > 1 && mids.first().first < cutoff) mids.removeFirst()
    }

    private fun fellFromWindowHigh(mid: BigDecimal, thresholdBps: BigDecimal): Boolean {
        val high = mids.maxOfOrNull { it.second } ?: return false
        if (high.signum() <= 0) return false
        val dropBps = high.subtract(mid).divide(high, 10, RoundingMode.HALF_EVEN).multiply(BPS)
        return dropBps >= thresholdBps
    }

    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = if (this < min) min else if (this > max) max else this

    private companion object {
        val BPS: BigDecimal = BigDecimal(10_000)
        val ONE: BigDecimal = BigDecimal.ONE
        val FAR_FACTOR: BigDecimal = BigDecimal(3)
    }
}
