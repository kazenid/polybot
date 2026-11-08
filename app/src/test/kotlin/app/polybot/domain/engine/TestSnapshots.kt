package app.polybot.domain.engine

import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.LocalStatus
import app.polybot.domain.model.Market
import app.polybot.domain.model.Side
import java.math.BigDecimal

fun testSnapshot(
    now: Long = 1_800_000_000_000L,
    bid: String? = "77000.00",
    ask: String? = "77000.02",
    quoteTotal: String = "10000",
    baseTotal: String = "0",
    quoteAvailable: String = quoteTotal,
    baseAvailable: String = baseTotal,
    skew: Long? = null,
    open: List<LocalOrder> = emptyList(),
    market: Market = btcUsdt,
    lotCost: String? = null,
) = Snapshot(
    nowMs = now,
    market = market,
    inputs = MarketInputs(
        bestBid = bid?.let(::BigDecimal), bestAsk = ask?.let(::BigDecimal), lastPrice = null,
        baseAvailable = BigDecimal(baseAvailable), quoteAvailable = BigDecimal(quoteAvailable),
        baseTotal = BigDecimal(baseTotal), quoteTotal = BigDecimal(quoteTotal), clockSkewMs = skew,
    ),
    openOrders = open,
    lotCost = lotCost?.let(::BigDecimal),
)

fun ourOrder(
    side: Side,
    price: String,
    id: String = "pbab12-${side.name.first()}${price.replace(".", "")}",
    createdAtMs: Long = 1_800_000_000_000L,
    status: LocalStatus = LocalStatus.OPEN,
) = LocalOrder(
    clientOrderId = id, orderId = 1, symbolId = 1, side = side,
    priceTicks = BigDecimal(price).movePointRight(9).longValueExact(), qtyScaled = 10_000, filledScaled = 0,
    postOnly = true, tif = "GTC", status = status, reason = null, createdAtMs = createdAtMs, updatedAtMs = createdAtMs,
)
