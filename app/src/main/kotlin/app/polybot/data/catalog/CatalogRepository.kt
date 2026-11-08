package app.polybot.data.catalog

import app.polybot.core.result.ApiError
import app.polybot.data.api.dto.AssetConfigDto
import app.polybot.data.api.dto.PairConfigDto
import app.polybot.data.api.dto.SpotConfigResponse
import app.polybot.data.api.services.MarketDataApi
import app.polybot.domain.model.Asset
import app.polybot.domain.model.Catalog
import app.polybot.domain.model.Market
import app.polybot.domain.model.MarketStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

class CatalogNotReadyException : IllegalStateException("Katalog belum dimuat; order tidak boleh dikirim")
class UnknownSymbolException(symbol: String) : IllegalArgumentException("Simbol tidak dikenal: $symbol")

/** Sumber tunggal skala, tick, step, dan batas. Gagal tertutup bila katalog belum siap atau simbol asing. */
@Singleton
class CatalogRepository @Inject constructor(private val api: MarketDataApi) {
    private val state = MutableStateFlow<Catalog?>(null)
    val catalog: StateFlow<Catalog?> = state.asStateFlow()

    suspend fun hydrate(): Catalog {
        val loaded = toCatalog(api.spotConfig())
        state.value = loaded
        return loaded
    }

    fun requireCatalog(): Catalog = state.value ?: throw CatalogNotReadyException()

    fun market(symbol: String): Market = requireCatalog().market(symbol) ?: throw UnknownSymbolException(symbol)

    /** Katalog yang sudah ada, atau memuatnya bila belum. */
    suspend fun ensure(): Catalog = state.value ?: hydrate()

    companion object {
        fun toCatalog(dto: SpotConfigResponse): Catalog {
            if (dto.pairs.isEmpty()) throw ApiError.BadResponse("Katalog kosong")
            return Catalog(
                markets = dto.pairs.map(::toMarket),
                assets = dto.assets.map(::toAsset),
                maxOpenOrdersTotal = dto.maxOpenOrdersTotal,
                serverTimeSec = dto.tsSec,
            )
        }

        private fun toAsset(a: AssetConfigDto) = Asset(a.asset, a.ledgerId, a.name, a.quantityScale, a.marketDataVolumeScale)

        private fun toMarket(p: PairConfigDto) = Market(
            symbolId = p.symbolId,
            symbol = p.symbol,
            baseAsset = p.baseAsset,
            quoteAsset = p.quoteAsset,
            tickSize = BigDecimal(p.tickSize),
            stepSize = BigDecimal(p.stepSize),
            minNotional = BigDecimal(p.minNotionalQuote),
            minQty = BigDecimal(p.minQtyBase),
            baseScale = p.baseQuantityScale,
            quoteScale = p.quoteQuantityScale,
            status = MarketStatus.fromWire(p.status),
            maxOrderNotional = p.maxOrderNotionalQuote.toDecimalOrNull(),
            maxMarketNotional = p.maxMarketNotionalQuote.toDecimalOrNull(),
            maxOpenOrders = p.maxOpenOrdersPerPair,
        )

        private fun String.toDecimalOrNull(): BigDecimal? = takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
    }
}
