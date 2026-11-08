package app.polybot.ui.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polybot.data.catalog.CatalogRepository
import app.polybot.data.market.MarketRepository
import app.polybot.data.prefs.WatchlistStore
import app.polybot.domain.market.MarketStats
import app.polybot.domain.market.PriceChange
import app.polybot.domain.market.Timeframe
import app.polybot.domain.model.Market
import app.polybot.ui.PollingViewModel
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.PairIcon
import app.polybot.ui.components.PollWhileStarted
import app.polybot.ui.components.Sparkline
import app.polybot.ui.components.SymbolMultiPickerSheet
import app.polybot.ui.components.UpdatedAt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class WatchRow(val market: Market, val price: BigDecimal?, val change: PriceChange?, val closes: List<Float>)

data class WatchlistData(val rows: List<WatchRow>, val symbols: List<String>, val enabled: Int, val total: Int)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val markets: MarketRepository,
    private val store: WatchlistStore,
) : PollingViewModel<WatchlistData>() {

    /** Mengganti daftar dan memuat ulang segera. */
    fun setSymbols(symbols: List<String>) {
        viewModelScope.launch {
            store.save(symbols)
            refresh(force = true)
        }
    }

    override suspend fun load(): WatchlistData {
        val cat = catalog.ensure()
        val active = cat.markets.filter { it.status.acceptsNewQuotes }.map { it.symbol }.sorted()
        val symbols = (store.current() ?: WatchlistStore.defaults(active)).filter { cat.market(it) != null }
        val results = coroutineScope { symbols.map { symbol -> async { row(cat.market(symbol)!!) } }.awaitAll() }
        // Satu pair gagal tidak menjatuhkan daftar; hanya bila semuanya gagal galatnya ditampilkan.
        if (results.isNotEmpty() && results.all { it.second != null }) throw results.first().second!!
        return WatchlistData(results.map { it.first }, symbols, active.size, cat.markets.size)
    }

    private suspend fun row(market: Market): Pair<WatchRow, Exception?> = coroutineScope {
        val book = async { attempt { markets.orderBook(market, "DEPTH_1") } }
        val candles = async { attempt { markets.candles(market, Timeframe.HOUR_1, 25) } }
        val b = book.await()
        val c = candles.await()
        val mid = b.getOrNull()?.let(MarketRepository::mid)
        val list = c.getOrNull().orEmpty()
        val row = WatchRow(market, mid ?: list.lastOrNull()?.close, MarketStats.change(list, mid), list.map { it.close.toFloat() })
        val failure = if (b.isFailure && c.isFailure) b.exceptionOrNull() else null
        row to (failure as? Exception)
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

@Composable
fun WatchlistCard(onOpen: (String) -> Unit, viewModel: WatchlistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    PollWhileStarted(intervalMs = 15_000) { viewModel.refresh() }

    InfoCard("Watchlist", icon = Icons.Rounded.CandlestickChart) {
        state.error?.let { ErrorBanner(it) }
        val data = state.data
        if (data != null) {
            if (data.rows.isEmpty()) {
                Text(
                    "Watchlist kosong. Tambahkan pair untuk memantau harga dan perubahannya.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            data.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                WatchRowItem(row, onClick = { onOpen(row.market.symbol) })
            }
            Text(
                "${data.enabled} dari ${data.total} pair aktif. Ketuk pair untuk melihat grafik.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { editing = true }) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                Text("Ubah daftar", Modifier.padding(start = 6.dp))
            }
            if (editing) {
                SymbolMultiPickerSheet(
                    title = "Watchlist",
                    selected = data.symbols.toSet(),
                    onChange = { chosen ->
                        viewModel.setSymbols(data.symbols.filter { it in chosen } + chosen.filter { it !in data.symbols }.sorted())
                    },
                    onDismiss = { editing = false },
                )
            }
        } else if (state.error == null) {
            Text("Memuat...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UpdatedAt(state.updatedAtMs, state.loading)
    }
}

@Composable
private fun WatchRowItem(row: WatchRow, onClick: () -> Unit) {
    val color = row.change?.let { directionColor(it.direction) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PairIcon(row.market.symbol, 32.dp)
        Column(Modifier.weight(1f)) {
            Text(row.market.baseAsset, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("/ ${row.market.quoteAsset}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Sparkline(row.closes, color, Modifier.width(56.dp).height(28.dp))
        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                row.price?.priceText(row.market.tickSize) ?: "-",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            ChangeBadge(row.change)
        }
    }
}
