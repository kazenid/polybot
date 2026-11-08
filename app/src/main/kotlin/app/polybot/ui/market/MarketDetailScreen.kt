package app.polybot.ui.market

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.data.catalog.CatalogRepository
import app.polybot.data.catalog.UnknownSymbolException
import app.polybot.data.market.MarketRepository
import app.polybot.domain.market.MarketStats
import app.polybot.domain.market.PriceChange
import app.polybot.domain.market.Timeframe
import app.polybot.domain.model.Candle
import app.polybot.domain.model.Market
import app.polybot.domain.model.OrderBook
import app.polybot.ui.PollingViewModel
import app.polybot.ui.components.CandleChart
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.LineChart
import app.polybot.ui.components.PairIcon
import app.polybot.ui.components.Pill
import app.polybot.ui.components.PollWhileStarted
import app.polybot.ui.components.StatTile
import app.polybot.ui.components.UpdatedAt
import app.polybot.ui.theme.BigNumberStyle
import app.polybot.ui.theme.trade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class MarketDetail(
    val market: Market,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    val change24h: PriceChange?,
    val high24h: BigDecimal?,
    val low24h: BigDecimal?,
    val volume24h: BigDecimal?,
    val book: OrderBook?,
    val price: BigDecimal?,
)

@HiltViewModel
class MarketDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val markets: MarketRepository,
) : PollingViewModel<MarketDetail>() {
    private val symbol: String = savedState.get<String>("symbol").orEmpty()
    private val _timeframe = MutableStateFlow(Timeframe.MIN_15)
    val timeframe: StateFlow<Timeframe> = _timeframe.asStateFlow()

    fun select(timeframe: Timeframe) {
        if (_timeframe.value == timeframe) return
        _timeframe.value = timeframe
        refresh(force = true)
    }

    override suspend fun load(): MarketDetail {
        val market = catalog.ensure().market(symbol) ?: throw UnknownSymbolException(symbol)
        val tf = _timeframe.value
        return coroutineScope {
            val chart = async { markets.candles(market, tf, CANDLE_COUNT) }
            // Statistik 24 jam dari 25 candle 1 jam; tidak wajib, kegagalannya tidak menjatuhkan layar.
            val day = async { optional { markets.candles(market, Timeframe.HOUR_1, 25) }.orEmpty() }
            val book = async { optional { markets.orderBook(market) } }
            val candles = chart.await()
            val dayCandles = day.await()
            val orderBook = book.await()
            val mid = orderBook?.let(MarketRepository::mid)
            MarketDetail(
                market = market,
                timeframe = tf,
                candles = candles,
                change24h = MarketStats.change(dayCandles, mid),
                high24h = MarketStats.high(dayCandles),
                low24h = MarketStats.low(dayCandles),
                volume24h = dayCandles.takeIf { it.isNotEmpty() }?.let(MarketStats::volume),
                book = orderBook,
                price = mid ?: candles.lastOrNull()?.close,
            )
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val CANDLE_COUNT = 80
    }
}

@Composable
fun MarketDetailScreen(viewModel: MarketDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val timeframe by viewModel.timeframe.collectAsStateWithLifecycle()
    var candleMode by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    PollWhileStarted(intervalMs = 10_000) { viewModel.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { ErrorBanner(it) }
        val d = state.data
        if (d == null) {
            if (state.error == null) Text("Memuat...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val tick = d.market.tickSize
            Header(d)
            val candle = selected?.let { d.candles.getOrNull(it) }
            if (candle != null) CandleReadout(candle, tick) else StatsRow(d)

            InfoCard("") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Timeframe.entries.forEach { tf ->
                        FilterChip(selected = timeframe == tf, onClick = { selected = null; viewModel.select(tf) }, label = { Text(tf.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = candleMode, onClick = { candleMode = true }, label = { Text("Candle") })
                    FilterChip(selected = !candleMode, onClick = { candleMode = false; selected = null }, label = { Text("Garis") })
                }
                if (d.candles.size < 2) {
                    Text("Belum ada data candle untuk timeframe ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (candleMode) {
                    CandleChart(d.candles, selected = selected, onSelect = { selected = it }, priceLabel = { it.priceText(tick) })
                } else {
                    val up = (d.candles.last().close >= d.candles.first().open)
                    LineChart(
                        d.candles.map { it.close.toFloat() },
                        lineColor = if (up) MaterialTheme.trade.up else MaterialTheme.trade.down,
                        height = 240.dp,
                    )
                }
                if (d.candles.size >= 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timeText(d.candles.first().tsSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(timeText(d.candles.last().tsSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "Ketuk atau geser pada grafik untuk melihat detail candle.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            d.book?.let { OrderBookCard(it, tick) }
        }
        UpdatedAt(state.updatedAtMs, state.loading)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Header(d: MarketDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PairIcon(d.market.symbol, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(d.market.symbol, style = MaterialTheme.typography.titleLarge)
                Text("${d.market.baseAsset} / ${d.market.quoteAsset}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!d.market.status.acceptsNewQuotes) Pill("Nonaktif", MaterialTheme.trade.warn)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(d.price?.priceText(d.market.tickSize) ?: "-", style = BigNumberStyle, color = d.change24h?.let { directionColor(it.direction) } ?: MaterialTheme.colorScheme.onSurface)
            ChangeBadge(d.change24h)
            d.change24h?.let { Text("${it.spanHours} jam", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun StatsRow(d: MarketDetail) {
    val tick = d.market.tickSize
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Tertinggi", d.high24h?.priceText(tick) ?: "-", Modifier.weight(1f))
        StatTile("Terendah", d.low24h?.priceText(tick) ?: "-", Modifier.weight(1f))
        StatTile("Volume ${d.market.baseAsset}", d.volume24h?.qtyText(2) ?: "-", Modifier.weight(1f))
    }
}

@Composable
private fun CandleReadout(c: Candle, tick: BigDecimal) {
    val color = if (c.close >= c.open) MaterialTheme.trade.up else MaterialTheme.trade.down
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(timeText(c.tsSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Buka", c.open.priceText(tick), Modifier.weight(1f))
            StatTile("Tinggi", c.high.priceText(tick), Modifier.weight(1f))
            StatTile("Rendah", c.low.priceText(tick), Modifier.weight(1f))
            StatTile("Tutup", c.close.priceText(tick), Modifier.weight(1f), valueColor = color)
        }
    }
}

@Composable
private fun OrderBookCard(book: OrderBook, tick: BigDecimal) {
    InfoCard("Order book") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BookColumn("Bid", book.bids.take(ROWS), MaterialTheme.trade.up, tick, Modifier.weight(1f))
            BookColumn("Ask", book.asks.take(ROWS), MaterialTheme.trade.down, tick, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BookColumn(title: String, levels: List<app.polybot.domain.model.BookLevel>, color: androidx.compose.ui.graphics.Color, tick: BigDecimal, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Jumlah", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (levels.isEmpty()) Text("-", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        levels.forEach { l ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(l.price.priceText(tick), style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
                Text(l.quantity.qtyText(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

private const val ROWS = 6

private fun timeText(tsSec: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(tsSec * 1000))
