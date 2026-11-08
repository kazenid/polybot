package app.polybot.domain.report

import app.polybot.domain.model.Side
import java.math.BigDecimal

data class Fill(
    val matchId: Long,
    val orderId: Long,
    val symbol: String,
    val side: Side,
    val isMaker: Boolean,
    val price: BigDecimal,
    val qty: BigDecimal,
    /** Fee dalam quote; negatif bila rebate. */
    val feeQuote: BigDecimal,
    val tsMs: Long,
) {
    val notional: BigDecimal get() = price.multiply(qty)
}
