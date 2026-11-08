package app.polybot.data.api.services

import app.polybot.data.api.ApiGateway
import app.polybot.data.api.RateClass
import app.polybot.data.api.dto.BalancesResponse
import app.polybot.data.api.dto.CancelAllOrdersRequest
import app.polybot.data.api.dto.CancelAllOrdersResponse
import app.polybot.data.api.dto.CancelOrderRequest
import app.polybot.data.api.dto.CancelOrderResponse
import app.polybot.data.api.dto.CreateOrderRequest
import app.polybot.data.api.dto.CreateOrderResponse
import app.polybot.data.api.dto.GetOrderRequest
import app.polybot.data.api.dto.GetOrderResponse
import app.polybot.data.api.dto.CandlesRequest
import app.polybot.data.api.dto.CandlesResponse
import app.polybot.data.api.dto.EmptyRequest
import app.polybot.data.api.dto.MeResponse
import app.polybot.data.api.dto.OpenOrdersRequest
import app.polybot.data.api.dto.OpenOrdersResponse
import app.polybot.data.api.dto.OrderBookRequest
import app.polybot.data.api.dto.OrderHistoryRequest
import app.polybot.data.api.dto.UserTradesRequest
import app.polybot.data.api.dto.UserTradesResponse
import app.polybot.data.api.dto.OrderBookResponse
import app.polybot.data.api.dto.SpotConfigResponse
import app.polybot.data.api.dto.TradesRequest
import app.polybot.data.api.dto.TradesResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthApi @Inject constructor(private val gateway: ApiGateway) {
    suspend fun me(): MeResponse = gateway.client().call(
        "auth.v1.AuthService/Me", EmptyRequest.serializer(), EmptyRequest(), MeResponse.serializer(), auth = true,
    )
}

@Singleton
class MarketDataApi @Inject constructor(private val gateway: ApiGateway) {
    suspend fun spotConfig(): SpotConfigResponse = gateway.client().call(
        "marketdata.v1.MarketDataService/GetSpotConfig",
        EmptyRequest.serializer(), EmptyRequest(), SpotConfigResponse.serializer(),
    )

    suspend fun orderBook(symbolId: Long, depth: String = "DEPTH_10"): OrderBookResponse = gateway.client().call(
        "orderbook.v1.OrderbookService/GetOrderBook",
        OrderBookRequest.serializer(), OrderBookRequest(symbolId, depth), OrderBookResponse.serializer(),
    )

    /** Newest-first dari server; pemanggil yang mengurutkan sebelum menghitung indikator. */
    suspend fun candles(symbolId: Long, timeframe: String, limit: Int): CandlesResponse = gateway.client().call(
        "marketdata.v1.MarketDataService/GetCandles",
        CandlesRequest.serializer(), CandlesRequest(symbolId, timeframe, limit), CandlesResponse.serializer(),
    )

    suspend fun trades(symbolId: Long, limit: Int): TradesResponse = gateway.client().call(
        "marketdata.v1.MarketDataService/GetTrades",
        TradesRequest.serializer(), TradesRequest(symbolId, limit), TradesResponse.serializer(),
    )
}

@Singleton
class LedgerApi @Inject constructor(private val gateway: ApiGateway) {
    suspend fun balances(): BalancesResponse = gateway.client().call(
        "ledger.read.v1.LedgerReadService/GetBalances",
        EmptyRequest.serializer(), EmptyRequest(), BalancesResponse.serializer(), auth = true,
    )
}

@Singleton
class OrdersApi @Inject constructor(private val gateway: ApiGateway) {
    suspend fun openOrders(pageToken: String = "", limit: Int = 100): OpenOrdersResponse = gateway.client().call(
        "orders.v1.OrdersReadService/GetOpenOrders",
        OpenOrdersRequest.serializer(), OpenOrdersRequest(limit = limit, pageToken = pageToken),
        OpenOrdersResponse.serializer(), auth = true,
    )

    /** Mutasi: tidak pernah diulang otomatis oleh klien. */
    suspend fun create(request: CreateOrderRequest): CreateOrderResponse = gateway.client().call(
        "orders.v1.OrdersService/CreateOrder",
        CreateOrderRequest.serializer(), request, CreateOrderResponse.serializer(),
        auth = true, rateClass = RateClass.PLACE, retryable = false,
    )

    suspend fun cancel(request: CancelOrderRequest): CancelOrderResponse = gateway.client().call(
        "orders.v1.OrdersService/CancelOrder",
        CancelOrderRequest.serializer(), request, CancelOrderResponse.serializer(),
        auth = true, rateClass = RateClass.CANCEL, retryable = false,
    )

    suspend fun cancelAll(request: CancelAllOrdersRequest): CancelAllOrdersResponse = gateway.client().call(
        "orders.v1.OrdersService/CancelAllOrders",
        CancelAllOrdersRequest.serializer(), request, CancelAllOrdersResponse.serializer(),
        auth = true, rateClass = RateClass.CANCEL, retryable = false,
    )

    suspend fun userTrades(symbolId: Long, limit: Int = 100, pageToken: String = "", afterMatchId: Long = 0): UserTradesResponse =
        gateway.client().call(
            "orders.v1.OrdersReadService/GetUserTrades",
            UserTradesRequest.serializer(), UserTradesRequest(symbolId, limit, pageToken, afterMatchId),
            UserTradesResponse.serializer(), auth = true,
        )

    suspend fun orderHistory(pageToken: String = "", limit: Int = 100): OpenOrdersResponse = gateway.client().call(
        "orders.v1.OrdersReadService/GetOrderHistory",
        OrderHistoryRequest.serializer(), OrderHistoryRequest(limit = limit, pageToken = pageToken),
        OpenOrdersResponse.serializer(), auth = true,
    )

    suspend fun get(clientOrderId: String): GetOrderResponse = gateway.client().call(
        "orders.v1.OrdersReadService/GetOrder",
        GetOrderRequest.serializer(), GetOrderRequest(clientOrderId), GetOrderResponse.serializer(), auth = true,
    )
}
