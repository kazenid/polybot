package app.polybot.domain.engine

import app.polybot.core.result.ApiError
import app.polybot.domain.model.Market
import app.polybot.domain.model.OrderRequest
import app.polybot.domain.model.RemoteOrder
import app.polybot.domain.model.RemoteStatus
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Bursa simulasi dalam memori: harga berjalan acak (dalam kelipatan tick), order maker terisi bila harga
 * menembusnya, saldo dan cadangan dihitung seperti di server. Menolak persis hal yang akan ditolak server
 * (post-only menyilang, saldo kurang, ID ganda), dan mencatat pelanggaran agar test bisa menegaskan nol.
 */
class SimMarket(
    private val market: Market,
    startMidTicks: Long,
    quote: BigDecimal,
    base: BigDecimal,
    seed: Long,
    private val maxStepTicks: Int = 3,
) : ExchangeGateway, MarketDataSource {
    private val random = Random(seed)
    private var midTicks = startMidTicks // satuan = tickSize
    private val orders = linkedMapOf<String, RemoteOrder>()
    private var nextOrderId = 1L

    var quoteTotal: BigDecimal = quote
        private set
    var baseTotal: BigDecimal = base
        private set

    // ---- catatan untuk assertion ----
    var fills = 0
        private set
    var maxOpen = 0
        private set
    var maxNotional: BigDecimal = BigDecimal.ZERO
        private set
    var worstExposureAtPlacement: BigDecimal = BigDecimal.ZERO
        private set
    val serverRejections = mutableListOf<String>()
    val placedIds = mutableListOf<String>()

    val mid: BigDecimal get() = BigDecimal(midTicks).multiply(market.tickSize)
    val bestBid: BigDecimal get() = mid.subtract(market.tickSize)
    val bestAsk: BigDecimal get() = mid.add(market.tickSize)

    val openOrders: List<RemoteOrder> get() = orders.values.filter { it.status == RemoteStatus.WORKING }
    val equity: BigDecimal get() = quoteTotal.add(baseTotal.multiply(mid))

    private fun price(o: RemoteOrder) = Numeric.ticksToPrice(o.priceTicks)
    private fun qty(o: RemoteOrder) = Numeric.scaledToQty(o.qtyScaled, market.baseScale)

    private val quoteReserved get() = openOrders.filter { it.side == Side.BUY }.sumOf { price(it).multiply(qty(it)) }
    private val baseReserved get() = openOrders.filter { it.side == Side.SELL }.sumOf { qty(it) }

    /** Menggerakkan harga satu langkah lalu mengisi order yang tertembus. */
    fun step() {
        val move = random.nextInt(-maxStepTicks, maxStepTicks + 1)
        midTicks = maxOf(10, midTicks + move)
        for (o in openOrders) {
            val crossed = if (o.side == Side.BUY) price(o) >= bestAsk else price(o) <= bestBid
            if (!crossed) continue
            val notional = price(o).multiply(qty(o))
            if (o.side == Side.BUY) {
                quoteTotal = quoteTotal.subtract(notional)
                baseTotal = baseTotal.add(qty(o))
            } else {
                quoteTotal = quoteTotal.add(notional)
                baseTotal = baseTotal.subtract(qty(o))
            }
            orders[o.clientOrderId] = o.copy(status = RemoteStatus.FILLED, filledScaled = o.qtyScaled)
            fills++
        }
    }

    override suspend fun load(market: Market) = MarketInputs(
        bestBid = bestBid, bestAsk = bestAsk, lastPrice = mid,
        baseAvailable = baseTotal.subtract(baseReserved), quoteAvailable = quoteTotal.subtract(quoteReserved),
        baseTotal = baseTotal, quoteTotal = quoteTotal, clockSkewMs = null,
    )

    override suspend fun create(request: OrderRequest): Long {
        orders[request.clientOrderId]?.let { throw ApiError.DuplicateClientOrderId(it.orderId) }
        val p = Numeric.ticksToPrice(request.priceTicks)
        val q = Numeric.scaledToQty(request.qtyScaled, market.baseScale)
        val notional = p.multiply(q)
        fun reject(code: String): Nothing {
            serverRejections += code
            throw ApiError.Validation(code, code)
        }
        if (request.postOnly && ((request.side == Side.BUY && p >= bestAsk) || (request.side == Side.SELL && p <= bestBid))) {
            reject("POST_ONLY_WOULD_CROSS")
        }
        if (request.side == Side.BUY && notional > quoteTotal.subtract(quoteReserved)) reject("INSUFFICIENT_QUOTE")
        if (request.side == Side.SELL && q > baseTotal.subtract(baseReserved)) reject("INSUFFICIENT_BASE")
        if (openOrders.any { it.side != request.side && ((request.side == Side.BUY && p >= price(it)) || (request.side == Side.SELL && p <= price(it))) }) {
            reject("SELF_TRADE")
        }

        if (request.side == Side.BUY) {
            val openBuys = openOrders.filter { it.side == Side.BUY }.sumOf { price(it).multiply(qty(it)) }
            val exposure = baseTotal.multiply(mid).add(openBuys).add(notional).divide(equity, 8, java.math.RoundingMode.HALF_EVEN)
            if (exposure > worstExposureAtPlacement) worstExposureAtPlacement = exposure
        }
        if (notional > maxNotional) maxNotional = notional

        val id = nextOrderId++
        orders[request.clientOrderId] = RemoteOrder(
            orderId = id, clientOrderId = request.clientOrderId, symbolId = request.symbolId, side = request.side,
            status = RemoteStatus.WORKING, priceTicks = request.priceTicks, qtyScaled = request.qtyScaled,
            filledScaled = 0, postOnly = request.postOnly, tif = request.tif,
        )
        placedIds += request.clientOrderId
        maxOpen = maxOf(maxOpen, openOrders.size)
        return id
    }

    override suspend fun cancel(clientOrderId: String, symbolId: Long) {
        val o = orders[clientOrderId]
        if (o == null || o.status != RemoteStatus.WORKING) throw ApiError.NotFound("not_found", "order")
        orders[clientOrderId] = o.copy(status = RemoteStatus.CANCELED)
    }

    override suspend fun cancelAll(symbolIds: List<Long>) {
        for (o in openOrders) if (o.symbolId in symbolIds) orders[o.clientOrderId] = o.copy(status = RemoteStatus.CANCELED)
    }

    override suspend fun get(clientOrderId: String): RemoteOrder? = orders[clientOrderId]

    override suspend fun listOpen(): List<RemoteOrder> = openOrders
}
