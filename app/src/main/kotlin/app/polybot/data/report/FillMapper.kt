package app.polybot.data.report

import app.polybot.data.api.dto.UserTradeDto
import app.polybot.domain.model.Market
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import app.polybot.domain.report.Fill

/** Mengubah fill dari server ke [Fill] dalam satuan desimal; null bila datanya tidak bisa dipakai. */
object FillMapper {
    fun map(dto: UserTradeDto, market: Market): Fill? {
        val side = when (dto.side.removePrefix("SIDE_")) {
            "BUY" -> Side.BUY
            "SELL" -> Side.SELL
            else -> return null
        }
        if (dto.matchId == 0L || dto.qtyScaled <= 0 || dto.priceTicks <= 0) return null
        val price = Numeric.ticksToPrice(dto.priceTicks)
        val qty = Numeric.scaledToQty(dto.qtyScaled, market.baseScale)
        val fee = Numeric.u128ToDecimal(dto.feeAmountE18.hi, dto.feeAmountE18.lo)
        // Fee bisa dalam aset dasar: dikonversi ke quote pada harga fill supaya semua laporan satu satuan.
        val feeInQuote = if (dto.feeAsset.removePrefix("FEE_ASSET_") == "BASE") fee.multiply(price) else fee
        return Fill(
            matchId = dto.matchId, orderId = dto.orderId, symbol = market.symbol, side = side, isMaker = dto.isMaker,
            price = price, qty = qty, feeQuote = if (dto.feeIsRebate) feeInQuote.negate() else feeInQuote,
            tsMs = dto.tsNs / 1_000_000,
        )
    }
}
