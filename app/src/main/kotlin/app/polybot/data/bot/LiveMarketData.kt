package app.polybot.data.bot

import app.polybot.data.api.ApiGateway
import app.polybot.data.api.dto.AssetBalanceDto
import app.polybot.data.api.services.LedgerApi
import app.polybot.data.api.services.MarketDataApi
import app.polybot.domain.engine.MarketDataSource
import app.polybot.domain.engine.MarketInputs
import app.polybot.domain.model.Market
import app.polybot.domain.numeric.Numeric
import app.polybot.data.catalog.CatalogRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** Order book (publik) dan saldo trading (terautentikasi) untuk satu siklus, diambil paralel. */
@Singleton
class LiveMarketData @Inject constructor(
    private val marketData: MarketDataApi,
    private val ledger: LedgerApi,
    private val catalog: CatalogRepository,
    private val gateway: ApiGateway,
) : MarketDataSource {
    override suspend fun load(market: Market): MarketInputs = coroutineScope {
        val book = async { marketData.orderBook(market.symbolId, "DEPTH_1") }
        val balances = async { ledger.balances().balances }
        val cat = catalog.ensure()
        val baseId = cat.asset(market.baseAsset)?.ledgerId ?: error("Aset ${market.baseAsset} tidak ada di katalog")
        val quoteId = cat.asset(market.quoteAsset)?.ledgerId ?: error("Aset ${market.quoteAsset} tidak ada di katalog")

        val b = book.await()
        val all = balances.await()
        val base = all.firstOrNull { it.assetId == baseId }
        val quote = all.firstOrNull { it.assetId == quoteId }
        MarketInputs(
            bestBid = b.bids.firstOrNull()?.let { Numeric.ticksToPrice(it.priceTicks) },
            bestAsk = b.asks.firstOrNull()?.let { Numeric.ticksToPrice(it.priceTicks) },
            lastPrice = null,
            baseAvailable = base.availableAmount(),
            quoteAvailable = quote.availableAmount(),
            baseTotal = base.tradingAmount(),
            quoteTotal = quote.tradingAmount(),
            clockSkewMs = gateway.clockMonitor.skewMs(),
        )
    }

    private fun AssetBalanceDto?.tradingAmount(): BigDecimal =
        this?.let { Numeric.u128ToDecimal(it.trading.hi, it.trading.lo) } ?: BigDecimal.ZERO

    private fun AssetBalanceDto?.availableAmount(): BigDecimal =
        this?.let { Numeric.u128ToDecimal(it.available.hi, it.available.lo) } ?: BigDecimal.ZERO
}
