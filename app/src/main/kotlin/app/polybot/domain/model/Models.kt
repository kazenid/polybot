package app.polybot.domain.model

import java.math.BigDecimal

enum class Side { BUY, SELL }

enum class MarketStatus {
    ENABLED, POST_ONLY, DISABLED, CANCEL_ONLY, REDUCE_ONLY, HALTED, UNKNOWN;

    /** Bot hanya boleh memasang quote baru pada pair yang menerima order baru. */
    val acceptsNewQuotes: Boolean get() = this == ENABLED || this == POST_ONLY

    companion object {
        fun fromWire(name: String): MarketStatus =
            entries.firstOrNull { "PAIR_STATUS_${it.name}" == name } ?: UNKNOWN
    }
}

data class Asset(val symbol: String, val ledgerId: Long, val name: String, val quantityScale: Int, val volumeScale: Int)

data class Market(
    val symbolId: Long,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val tickSize: BigDecimal,
    val stepSize: BigDecimal,
    val minNotional: BigDecimal,
    val minQty: BigDecimal,
    val baseScale: Int,
    val quoteScale: Int,
    val status: MarketStatus,
    val maxOrderNotional: BigDecimal?,
    val maxMarketNotional: BigDecimal?,
    val maxOpenOrders: Int,
)

data class Catalog(
    val markets: List<Market>,
    val assets: List<Asset>,
    val maxOpenOrdersTotal: Int,
    val serverTimeSec: Long,
) {
    private val bySymbol = markets.associateBy { it.symbol }
    private val byId = markets.associateBy { it.symbolId }
    private val assetByLedgerId = assets.associateBy { it.ledgerId }
    private val assetBySymbol = assets.associateBy { it.symbol }

    fun market(symbol: String): Market? = bySymbol[symbol]
    fun market(symbolId: Long): Market? = byId[symbolId]
    fun assetByLedgerId(id: Long): Asset? = assetByLedgerId[id]
    fun asset(symbol: String): Asset? = assetBySymbol[symbol]
}

data class Balance(
    val asset: String,
    val trading: BigDecimal,
    val funding: BigDecimal,
    val reserved: BigDecimal,
    val available: BigDecimal,
)

data class OpenOrder(
    val orderId: Long,
    val clientOrderId: String,
    val symbol: String,
    val side: Side?,
    val orderType: String,
    val timeInForce: String,
    val status: String,
    val postOnly: Boolean,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val filled: BigDecimal,
    val remaining: BigDecimal,
    val createdAtMs: Long,
    /** Alasan order tertutup (riwayat); kosong untuk order terbuka. */
    val terminalReason: String = "",
)

data class BookLevel(val price: BigDecimal, val quantity: BigDecimal)

data class OrderBook(val symbolId: Long, val bids: List<BookLevel>, val asks: List<BookLevel>)

data class Candle(
    val tsSec: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val isClosed: Boolean,
)
