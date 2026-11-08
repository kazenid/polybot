package app.polybot.domain.risk

import app.polybot.domain.config.RiskConfig
import app.polybot.domain.engine.Snapshot
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import app.polybot.domain.strategy.Intent
import java.math.BigDecimal
import kotlin.math.abs

sealed interface RiskVerdict {
    data object Ok : RiskVerdict

    /** Berhenti sementara. [auto] true = lanjut sendiri begitu kondisinya pulih. */
    data class Pause(val reason: String, val auto: Boolean) : RiskVerdict

    /** Berhenti total dan batalkan order (mis. rugi harian). */
    data class Stop(val reason: String) : RiskVerdict
}

sealed interface Approval {
    data object Approved : Approval
    data class Rejected(val reason: String) : Approval
}

/** Status risiko yang berubah antar siklus. Satu instance per proses bot. */
class RiskState {
    var consecutiveErrors: Int = 0
        private set
    private val orderTimes = ArrayDeque<Long>()
    private var dayIndex: Long = -1
    var dayStartEquity: BigDecimal = BigDecimal.ZERO
        private set

    fun recordError() { consecutiveErrors++ }
    fun recordSuccess() { consecutiveErrors = 0 }
    fun resetErrors() { consecutiveErrors = 0 }

    fun recordOrder(nowMs: Long) { orderTimes.addLast(nowMs) }

    fun ordersInLastMinute(nowMs: Long): Int {
        while (orderTimes.isNotEmpty() && nowMs - orderTimes.first() >= MINUTE_MS) orderTimes.removeFirst()
        return orderTimes.size
    }

    /** Ekuitas awal hari (UTC). Dipasang pada panggilan pertama dan tiap pergantian hari. */
    fun rollDay(nowMs: Long, equity: BigDecimal) {
        val day = nowMs / DAY_MS
        if (day != dayIndex) {
            dayIndex = day
            dayStartEquity = equity
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}

/**
 * Semua aturan wajib lulus sebelum order apa pun (plan bagian 10.6).
 * [preTrade] menilai kondisi umum tiap siklus; [approve] menilai satu order.
 */
class RiskManager(private val config: RiskConfig) {

    fun preTrade(s: Snapshot, state: RiskState): RiskVerdict {
        if (s.market.symbol !in config.allowedSymbols) {
            return RiskVerdict.Stop("Simbol ${s.market.symbol} tidak ada di daftar yang diizinkan")
        }
        val equity = s.equityQuote
        state.rollDay(s.nowMs, equity)
        val loss = state.dayStartEquity.subtract(equity)
        if (s.mid != null && loss >= config.maxDailyLossQuote) {
            return RiskVerdict.Stop("Rugi harian ${loss.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()} " +
                "mencapai batas ${config.maxDailyLossQuote.toPlainString()} ${s.market.quoteAsset}")
        }
        if (state.consecutiveErrors >= config.maxConsecutiveErrors) {
            return RiskVerdict.Pause("${state.consecutiveErrors} error beruntun", auto = false)
        }
        val skew = s.inputs.clockSkewMs
        if (skew != null && abs(skew) > config.maxClockSkewMs) {
            return RiskVerdict.Pause("Jam perangkat melenceng ${skew / 1000.0} detik dari server", auto = true)
        }
        s.bookAnomaly?.let { return RiskVerdict.Pause(it, auto = true) }
        if (!s.market.status.acceptsNewQuotes) {
            return RiskVerdict.Pause("Pair ${s.market.symbol} berstatus ${s.market.status}", auto = true)
        }
        return RiskVerdict.Ok
    }

    /**
     * @param placedThisTick order yang sudah disetujui pada siklus yang sama tapi belum tampak di [Snapshot.openOrders];
     * dihitung agar batas jumlah, saldo, dan eksposur tidak terlampaui oleh beberapa order sekaligus.
     */
    fun approve(intent: Intent.Place, s: Snapshot, state: RiskState, placedThisTick: List<Intent.Place> = emptyList()): Approval {
        val mid = s.mid ?: return reject("Harga acuan tidak tersedia")
        if (intent.price.signum() <= 0 || intent.qty.signum() <= 0) return reject("Harga atau jumlah tidak positif")
        val notional = intent.notional
        val equity = s.equityQuote

        val cap = config.maxOrderQuote.min(config.maxOrderPctOfEquity.multiply(equity))
        if (notional > cap) return reject("Nilai order ${fmt(notional)} melebihi batas ${fmt(cap)}")

        val openLimit = openOrderLimit(s)
        val openCount = s.openOrders.size + placedThisTick.size
        if (openCount >= openLimit) return reject("Order terbuka sudah $openCount dari batas $openLimit")

        val recent = state.ordersInLastMinute(s.nowMs) + placedThisTick.size
        if (recent >= config.maxNewOrdersPerMinute) return reject("Sudah $recent order baru dalam semenit")

        val deviation = intent.price.subtract(mid).abs().divide(mid, 8, java.math.RoundingMode.HALF_EVEN)
        if (deviation > config.maxPriceDeviationPct) return reject("Harga menyimpang ${fmt(deviation.multiply(HUNDRED))}% dari mid")

        when (intent.side) {
            Side.BUY -> {
                val committed = placedThisTick.filter { it.side == Side.BUY }.sumOf { it.notional }
                val reserve = config.reserveQuotePct.multiply(s.inputs.quoteTotal)
                val usable = s.inputs.quoteAvailable.subtract(committed).subtract(reserve)
                if (notional > usable) return reject("Saldo quote tidak cukup (tersedia ${fmt(usable)} setelah cadangan)")
                if (equity.signum() > 0) {
                    // Order beli yang masih terbuka bisa terisi kapan saja, jadi ikut dihitung sebagai eksposur.
                    val openBuys = s.openOrders.filter { it.side == Side.BUY && !it.status.terminal }.sumOf {
                        Numeric.ticksToPrice(it.priceTicks).multiply(Numeric.scaledToQty(it.qtyScaled - it.filledScaled, s.market.baseScale))
                    }
                    val exposure = s.baseValueQuote.add(committed).add(openBuys).add(notional)
                        .divide(equity, 8, java.math.RoundingMode.HALF_EVEN)
                    if (exposure > config.maxExposurePct) {
                        return reject("Eksposur ${fmt(exposure.multiply(HUNDRED))}% melebihi batas ${fmt(config.maxExposurePct.multiply(HUNDRED))}%")
                    }
                }
                if (intent.postOnly && s.bestAsk != null && intent.price >= s.bestAsk!!) return reject("Beli post-only akan menyilang book")
            }
            Side.SELL -> {
                val committed = placedThisTick.filter { it.side == Side.SELL }.sumOf { it.qty }
                val reservedInOpen = BigDecimal.ZERO // saldo available dari server sudah memotong order terbuka
                if (intent.qty > s.inputs.baseAvailable.subtract(committed).subtract(reservedInOpen)) {
                    return reject("Saldo aset dasar tidak cukup untuk jual")
                }
                if (intent.postOnly && s.bestBid != null && intent.price <= s.bestBid!!) return reject("Jual post-only akan menyilang book")
            }
        }

        // Jangan sampai order milik sendiri saling bertemu (self-trade).
        val ownOpposite = (s.openOrders.filter { it.side != intent.side }.map { it.priceTicks.toBigPrice() } +
            placedThisTick.filter { it.side != intent.side }.map { it.price })
        if (intent.side == Side.BUY && ownOpposite.any { intent.price >= it }) return reject("Akan bertemu order jual milik sendiri")
        if (intent.side == Side.SELL && ownOpposite.any { intent.price <= it }) return reject("Akan bertemu order beli milik sendiri")

        return Approval.Approved
    }

    fun openOrderLimit(s: Snapshot): Int {
        var limit = config.maxOpenOrders
        if (s.market.maxOpenOrders > 0) limit = minOf(limit, s.market.maxOpenOrders)
        if (s.serverMaxOpenOrders > 0) limit = minOf(limit, s.serverMaxOpenOrders)
        return limit
    }

    private fun reject(reason: String) = Approval.Rejected(reason)

    private fun fmt(v: BigDecimal) = v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    private fun Long.toBigPrice(): BigDecimal = Numeric.ticksToPrice(this)

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal(100)
    }
}
