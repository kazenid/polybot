package app.polybot.data.api.dto

import kotlinx.serialization.Serializable

/**
 * DTO kawat (Connect JSON). Semua field punya default karena server menghilangkan nilai nol/false.
 * `int64` memakai [LenientLong]; enum dibaca sebagai String agar nilai baru tidak merusak parsing.
 */

@Serializable
class EmptyRequest

// --- auth.v1.AuthService ---

@Serializable
data class MeResponse(
    /** Di stub bertipe angka, tapi Account ID di aplikasi berupa string (mis. `RLxqJGUDg92`): terima keduanya. */
    @Serializable(with = NumberText::class) val accountId: String = "",
    val apiKeyId: String = "",
    val username: String = "",
    val rootSmartAccountAddress: String = "",
)

// --- marketdata.v1.MarketDataService ---

@Serializable
data class SpotConfigResponse(
    val assets: List<AssetConfigDto> = emptyList(),
    val pairs: List<PairConfigDto> = emptyList(),
    @Serializable(with = LenientLong::class) val tsSec: Long = 0,
    val maxOpenOrdersTotal: Int = 0,
)

@Serializable
data class AssetConfigDto(
    val asset: String = "",
    @Serializable(with = LenientLong::class) val ledgerId: Long = 0,
    val name: String = "",
    val quantityDisplayDecimals: Int = 0,
    val quantityScale: Int = 0,
    val marketDataVolumeScale: Int = 0,
)

@Serializable
data class PairConfigDto(
    @Serializable(with = LenientLong::class) val symbolId: Long = 0,
    val symbol: String = "",
    val baseAsset: String = "",
    val quoteAsset: String = "",
    val tickSize: String = "0",
    val stepSize: String = "0",
    val minNotionalQuote: String = "0",
    val minQtyBase: String = "0",
    val baseQuantityScale: Int = 0,
    val quoteQuantityScale: Int = 0,
    val referencePriceScale: Int = 0,
    val status: String = "",
    val maxLimitQtyBase: String = "",
    val maxMarketQtyBase: String = "",
    val maxOrderNotionalQuote: String = "",
    val maxMarketNotionalQuote: String = "",
    val maxOpenOrdersPerPair: Int = 0,
)

@Serializable
data class CandlesRequest(@Serializable(with = LenientLong::class) val symbolId: Long, val timeframe: String, val limit: Int)

@Serializable
data class CandlePointDto(
    @Serializable(with = LenientLong::class) val tsSec: Long = 0,
    @Serializable(with = LenientLong::class) val open: Long = 0,
    @Serializable(with = LenientLong::class) val high: Long = 0,
    @Serializable(with = LenientLong::class) val low: Long = 0,
    @Serializable(with = LenientLong::class) val close: Long = 0,
    @Serializable(with = LenientLong::class) val volume: Long = 0,
    val isClosed: Boolean = false,
    val quoteVolume: String = "0",
)

@Serializable
data class CandlesResponse(val candles: List<CandlePointDto> = emptyList(), val nextPageToken: String = "")

@Serializable
data class TradesRequest(@Serializable(with = LenientLong::class) val symbolId: Long, val limit: Int)

@Serializable
data class MarketTradeDto(
    @Serializable(with = LenientLong::class) val symbolId: Long = 0,
    @Serializable(with = LenientLong::class) val matchId: Long = 0,
    val isBuy: Boolean = false,
    @Serializable(with = LenientLong::class) val priceTicks: Long = 0,
    @Serializable(with = LenientLong::class) val qtyScaled: Long = 0,
    @Serializable(with = LenientLong::class) val tsNs: Long = 0,
)

@Serializable
data class TradesResponse(val trades: List<MarketTradeDto> = emptyList(), val nextPageToken: String = "")

// --- orderbook.v1.OrderbookService ---

@Serializable
data class OrderBookRequest(@Serializable(with = LenientLong::class) val symbolId: Long, val depth: String)

@Serializable
data class PriceLevelDto(@Serializable(with = LenientLong::class) val priceTicks: Long = 0, @Serializable(with = LenientLong::class) val qtyScaled: Long = 0)

@Serializable
data class OrderBookResponse(
    @Serializable(with = LenientLong::class) val symbolId: Long = 0,
    @Serializable(with = LenientLong::class) val bookSeq: Long = 0,
    val bids: List<PriceLevelDto> = emptyList(),
    val asks: List<PriceLevelDto> = emptyList(),
    val ts: String = "",
)

// --- ledger.read.v1.LedgerReadService ---

/** 128-bit tanpa tanda sebagai dua uint64 (teks desimal). */
@Serializable
data class U128Dto(
    @Serializable(with = NumberText::class) val hi: String = "0",
    @Serializable(with = NumberText::class) val lo: String = "0",
)

@Serializable
data class AssetBalanceDto(
    @Serializable(with = LenientLong::class) val assetId: Long = 0,
    val trading: U128Dto = U128Dto(),
    val funding: U128Dto = U128Dto(),
    val reserved: U128Dto = U128Dto(),
    val available: U128Dto = U128Dto(),
)

@Serializable
data class BalancesResponse(val balances: List<AssetBalanceDto> = emptyList())

// --- orders.v1.OrdersReadService ---

@Serializable
data class OpenOrdersRequest(
    val symbolId: List<@Serializable(with = LenientLong::class) Long> = emptyList(),
    val limit: Int = 0,
    val pageToken: String = "",
)

@Serializable
data class OrderDto(
    @Serializable(with = LenientId::class) val orderId: Long = 0,
    @Serializable(with = LenientLong::class) val symbolId: Long = 0,
    val clientOrderId: String = "",
    val side: String = "",
    val status: String = "",
    val orderType: String = "",
    val timeInForce: String = "",
    val postOnly: Boolean = false,
    @Serializable(with = LenientLong::class) val origQtyScaled: Long = 0,
    @Serializable(with = LenientLong::class) val cumQtyScaled: Long = 0,
    @Serializable(with = LenientLong::class) val leavesQtyScaled: Long = 0,
    @Serializable(with = LenientLong::class) val avgPriceTicks: Long = 0,
    @Serializable(with = LenientLong::class) val priceTicks: Long = 0,
    @Serializable(with = LenientTimeNs::class) val createdTsNs: Long = 0,
    @Serializable(with = LenientTimeNs::class) val terminalTsNs: Long = 0,
    val terminalReason: String = "",
)

@Serializable
data class OpenOrdersResponse(val orders: List<OrderDto> = emptyList(), val nextPageToken: String = "")

// --- orders.v1.OrdersService (mutasi) ---
// Bentuk request diturunkan dari stub protobuf; belum teruji terhadap server (butuh API key).

@Serializable
data class LimitPriceDto(@Serializable(with = LenientLong::class) val priceTicks: Long)

@Serializable
data class LimitGtcDto(
    @Serializable(with = LenientLong::class) val priceTicks: Long,
    val postOnly: Boolean = false,
)

@Serializable
data class LimitGtdDto(
    @Serializable(with = LenientLong::class) val priceTicks: Long,
    val postOnly: Boolean = false,
    val expireAt: String,
)

@Serializable
data class OrderIntentDto(
    @Serializable(with = LenientLong::class) val symbolId: Long,
    val side: String,
    @Serializable(with = LenientLong::class) val baseQtyScaled: Long,
    val limitGtc: LimitGtcDto? = null,
    val limitIoc: LimitPriceDto? = null,
    val limitFok: LimitPriceDto? = null,
    val limitGtd: LimitGtdDto? = null,
    val clientOrderId: String,
    val selfTradePreventionMode: String,
)

@Serializable
data class CreateOrderRequest(val order: OrderIntentDto)

@Serializable
data class CreateOrderResponse(
    @Serializable(with = LenientId::class) val orderId: Long = 0,
    val clientOrderId: String = "",
)

@Serializable
data class CancelOrderRequest(
    val clientOrderId: String,
    @Serializable(with = LenientLong::class) val symbolId: Long,
)

@Serializable
data class CancelOrderResponse(
    val status: String = "",
    @Serializable(with = LenientId::class) val orderId: Long = 0,
)

@Serializable
data class CancelAllOrdersRequest(
    val symbolIds: List<@Serializable(with = LenientLong::class) Long>,
    val requestId: String,
)

@Serializable
data class CancelAllOrdersResponse(val status: String = "")

@Serializable
data class GetOrderRequest(val clientOrderId: String)

@Serializable
data class GetOrderResponse(val order: OrderDto? = null)

// --- orders.v1.OrdersReadService (laporan) ---
// Bentuk diturunkan dari stub protobuf; belum teruji terhadap server.

@Serializable
data class UserTradesRequest(
    @Serializable(with = LenientLong::class) val symbolId: Long,
    val limit: Int = 0,
    val pageToken: String = "",
    @Serializable(with = LenientLong::class) val afterMatchId: Long = 0,
)

@Serializable
data class UserTradeDto(
    @Serializable(with = LenientLong::class) val symbolId: Long = 0,
    @Serializable(with = LenientId::class) val matchId: Long = 0,
    @Serializable(with = LenientId::class) val orderId: Long = 0,
    val side: String = "",
    val isMaker: Boolean = false,
    @Serializable(with = LenientLong::class) val priceTicks: Long = 0,
    @Serializable(with = LenientLong::class) val qtyScaled: Long = 0,
    val feeAmountE18: U128Dto = U128Dto(),
    val feeAsset: String = "",
    val feeIsRebate: Boolean = false,
    @Serializable(with = LenientTimeNs::class) val tsNs: Long = 0,
)

@Serializable
data class UserTradesResponse(val trades: List<UserTradeDto> = emptyList(), val nextPageToken: String = "")

@Serializable
data class OrderHistoryRequest(
    val symbolId: List<@Serializable(with = LenientLong::class) Long> = emptyList(),
    val limit: Int = 0,
    val pageToken: String = "",
)
