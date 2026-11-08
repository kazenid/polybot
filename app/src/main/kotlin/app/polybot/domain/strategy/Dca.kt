package app.polybot.domain.strategy

import app.polybot.domain.config.BotConfig
import app.polybot.domain.engine.Snapshot
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * DCA terjadwal: beli kecil berkala dengan limit post-only satu tick di bawah best bid.
 * Total yang dibelanjakan dihitung konservatif sejak order dipasang (bukan saat terisi).
 */
class Dca : Strategy {
    override val name = "DCA"

    private var spent: BigDecimal = BigDecimal.ZERO
    private var lastBuyMs: Long? = null

    override fun decide(snapshot: Snapshot, config: BotConfig): List<Intent> {
        val cfg = config.dca
        if (spent.add(cfg.orderQuote) > cfg.maxTotalQuote) {
            return listOf(Intent.Halt("DCA selesai: total ${spent.toPlainString()} dari ${cfg.maxTotalQuote.toPlainString()}"))
        }
        val last = lastBuyMs
        if (last != null && snapshot.nowMs - last < cfg.intervalSec * 1_000L) return emptyList()

        val bid = snapshot.bestBid ?: return emptyList()
        val market = snapshot.market
        val price = Numeric.roundPrice(bid.subtract(market.tickSize), market.tickSize, Side.BUY)
        if (price.signum() <= 0) return emptyList()
        val qty = Numeric.floorQty(cfg.orderQuote.divide(price, market.baseScale + 2, RoundingMode.DOWN), market.stepSize)
        if (Numeric.validate(market, price, qty) != null) return emptyList()

        spent = spent.add(price.multiply(qty))
        lastBuyMs = snapshot.nowMs
        return listOf(Intent.Place(Side.BUY, price, qty, note = "DCA beli (total ${spent.setScale(2, RoundingMode.HALF_UP).toPlainString()})"))
    }
}
