package app.polybot.data.market

import app.polybot.data.api.dto.CandlesResponse
import app.polybot.data.api.dto.OrderBookResponse
import app.polybot.data.api.services.MarketDataApi
import app.polybot.data.catalog.CatalogRepository
import app.polybot.domain.market.Timeframe
import app.polybot.domain.model.BookLevel
import app.polybot.domain.model.Candle
import app.polybot.domain.model.Market
import app.polybot.domain.model.OrderBook
import app.polybot.domain.numeric.Numeric
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton

/** Data pasar publik (tanpa kredensial) untuk watchlist dan layar detail pair. */
@Singleton
class MarketRepository @Inject constructor(
    private val api: MarketDataApi,
    private val catalog: CatalogRepository,
) {
    /** Candle urut lama ke baru. Candle terakhir bisa belum selesai (`isClosed = false`). */
    suspend fun candles(market: Market, timeframe: Timeframe, limit: Int): List<Candle> =
        toCandles(api.candles(market.symbolId, timeframe.wire, limit), volumeScale(market))

    suspend fun orderBook(market: Market, depth: String = "DEPTH_10"): OrderBook =
        toOrderBook(api.orderBook(market.symbolId, depth), volumeScale(market))

    /** Skala data pasar (volume dan kuantitas order book) mengikuti aset dasar. */
    private suspend fun volumeScale(market: Market): Int =
        catalog.ensure().asset(market.baseAsset)?.volumeScale ?: market.baseScale

    companion object {
        fun toCandles(response: CandlesResponse, volumeScale: Int): List<Candle> = response.candles
            .map {
                Candle(
                    it.tsSec, Numeric.ticksToPrice(it.open), Numeric.ticksToPrice(it.high), Numeric.ticksToPrice(it.low),
                    Numeric.ticksToPrice(it.close), Numeric.scaledToQty(it.volume, volumeScale), it.isClosed,
                )
            }
            .sortedBy { it.tsSec }

        fun toOrderBook(response: OrderBookResponse, qtyScale: Int): OrderBook = OrderBook(
            response.symbolId,
            response.bids.map { BookLevel(Numeric.ticksToPrice(it.priceTicks), Numeric.scaledToQty(it.qtyScaled, qtyScale)) },
            response.asks.map { BookLevel(Numeric.ticksToPrice(it.priceTicks), Numeric.scaledToQty(it.qtyScaled, qtyScale)) },
        )

        /** Harga tengah bid/ask terbaik; null bila salah satu sisi kosong. */
        fun mid(book: OrderBook): BigDecimal? {
            val bid = book.bids.firstOrNull()?.price ?: return null
            val ask = book.asks.firstOrNull()?.price ?: return null
            return bid.add(ask).divide(BigDecimal(2), Numeric.PRICE_SCALE, RoundingMode.HALF_UP)
        }
    }
}
