package app.polybot.domain.strategy

import app.polybot.domain.config.BotConfig
import app.polybot.domain.engine.Snapshot
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import app.polybot.domain.numeric.Numeric.ticksToPrice
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Grid statis: pada tiap level, beli di bawah mid dan jual di atas mid. Tanpa status internal: bila satu level
 * terisi, mid sudah berada di sisi lain level itu, sehingga order lawan otomatis menjadi yang diinginkan.
 * Jual hanya dipasang sejauh ada saldo aset dasar.
 */
class Grid : Strategy {
    override val name = "Grid"

    override fun decide(snapshot: Snapshot, config: BotConfig): List<Intent> {
        val cfg = config.grid
        val mid = snapshot.mid ?: return emptyList()
        val market = snapshot.market

        val breakLow = cfg.lower.multiply(BigDecimal.ONE.subtract(cfg.rangeBreakBps.divide(BPS, 10, RoundingMode.HALF_EVEN)))
        val breakHigh = cfg.upper.multiply(BigDecimal.ONE.add(cfg.rangeBreakBps.divide(BPS, 10, RoundingMode.HALF_EVEN)))
        if (mid < breakLow || mid > breakHigh) {
            return listOf(Intent.Halt("Harga ${mid.toPlainString()} keluar dari rentang grid ${cfg.lower.toPlainString()}-${cfg.upper.toPlainString()}"))
        }

        val step = cfg.upper.subtract(cfg.lower).divide(BigDecimal(cfg.gridCount), 10, RoundingMode.HALF_EVEN)
        val levels = (0..cfg.gridCount).map { i ->
            // Level dibulatkan ke tick; beli turun, jual naik agar tidak pernah tumpang tindih dengan mid.
            cfg.lower.add(step.multiply(BigDecimal(i)))
        }

        val live = snapshot.openOrders.filter { it.status == LocalStatus.OPEN || it.status == LocalStatus.PENDING }
        val haveBuy = live.filter { it.side == Side.BUY }.map { ticksToPrice(it.priceTicks) }
        val haveSell = live.filter { it.side == Side.SELL }.map { ticksToPrice(it.priceTicks) }

        var baseLeft = snapshot.inputs.baseAvailable
        val intents = mutableListOf<Intent.Place>()
        for (level in levels) {
            val gap = market.tickSize.multiply(BigDecimal(2))
            when {
                level < mid.subtract(gap) -> {
                    val price = Numeric.roundPrice(level, market.tickSize, Side.BUY)
                    if (haveBuy.none { it.compareTo(price) == 0 }) place(Side.BUY, price, cfg.orderQuote, snapshot)?.let(intents::add)
                }
                level > mid.add(gap) -> {
                    val price = Numeric.roundPrice(level, market.tickSize, Side.SELL)
                    if (haveSell.none { it.compareTo(price) == 0 }) {
                        val intent = place(Side.SELL, price, cfg.orderQuote, snapshot)
                        if (intent != null && intent.qty <= baseLeft) {
                            baseLeft = baseLeft.subtract(intent.qty)
                            intents += intent
                        }
                    }
                }
            }
        }
        // Level terdekat mid dipasang lebih dulu (paling mungkin terisi); sisanya di siklus berikutnya.
        return intents.sortedBy { it.price.subtract(mid).abs() }.take(MAX_NEW_PER_TICK)
    }

    private fun place(side: Side, price: BigDecimal, orderQuote: BigDecimal, s: Snapshot): Intent.Place? {
        if (price.signum() <= 0) return null
        val market = s.market
        val qty = Numeric.floorQty(orderQuote.divide(price, market.baseScale + 2, RoundingMode.DOWN), market.stepSize)
        if (Numeric.validate(market, price, qty) != null) return null
        return Intent.Place(side, price, qty, note = "Grid ${side.name.lowercase()} @ ${price.toPlainString()}")
    }

    private companion object {
        val BPS: BigDecimal = BigDecimal(10_000)
        const val MAX_NEW_PER_TICK = 4
    }
}
