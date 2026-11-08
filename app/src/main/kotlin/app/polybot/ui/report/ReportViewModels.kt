package app.polybot.ui.report

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polybot.core.result.userMessage
import app.polybot.data.account.OpenOrderRepository
import app.polybot.data.prefs.BotConfigStore
import app.polybot.data.report.FillRepository
import app.polybot.data.report.RetentionManager
import app.polybot.data.report.SnapshotRepository
import app.polybot.data.report.StorageCounts
import app.polybot.domain.model.OpenOrder
import app.polybot.domain.model.Side
import app.polybot.domain.report.CsvExporter
import app.polybot.domain.report.EquityPoint
import app.polybot.domain.report.EquityStats
import app.polybot.domain.report.Fill
import app.polybot.domain.report.FillResult
import app.polybot.domain.report.PnlCalculator
import app.polybot.domain.report.PnlSummary
import app.polybot.domain.report.SymbolPosition
import app.polybot.domain.report.downsample
import app.polybot.ui.PollingViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class RangeFilter(val label: String) {
    TODAY("Hari ini"), WEEK("7 hari"), MONTH("30 hari"), ALL("Semua");

    /** Awal rentang dalam ms. "Hari ini" mengikuti tengah malam waktu lokal perangkat. */
    fun startMs(nowMs: Long): Long = when (this) {
        TODAY -> Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        WEEK -> nowMs - 7 * DAY_MS
        MONTH -> nowMs - 30 * DAY_MS
        ALL -> 0L
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

data class TxFilter(val range: RangeFilter = RangeFilter.WEEK, val symbol: String? = null, val side: Side? = null)

data class TxUi(
    val filter: TxFilter = TxFilter(),
    /** Terbaru dulu, dibatasi [SHOWN] baris. */
    val rows: List<FillResult> = emptyList(),
    val matching: Int = 0,
    val summary: PnlSummary = PnlCalculator.summarize(emptyList()),
    val symbols: List<String> = emptyList(),
    val positions: List<SymbolPosition> = emptyList(),
) {
    companion object {
        const val SHOWN = 300
    }
}

data class TxStatus(val syncing: Boolean = false, val message: String? = null, val error: String? = null)

/** PnL dihitung dari seluruh riwayat (biaya rata-rata), lalu disaring ke rentang yang dipilih. */
internal fun filterResults(all: List<FillResult>, filter: TxFilter, nowMs: Long): List<FillResult> {
    val since = filter.range.startMs(nowMs)
    return all.filter { r ->
        r.fill.tsMs >= since && (filter.symbol == null || r.fill.symbol == filter.symbol) && (filter.side == null || r.fill.side == filter.side)
    }
}

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val fillRepository: FillRepository,
    snapshots: SnapshotRepository,
    private val configStore: BotConfigStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val filter = MutableStateFlow(TxFilter())

    val ui: StateFlow<TxUi> = combine(fillRepository.fills, filter) { fills, f -> build(fills, f) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxUi())

    /** Perubahan ekuitas selama bot berjalan pada rentang terpilih (termasuk gerak harga aset yang dipegang). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val equityChange: StateFlow<BigDecimal?> = filter
        .map { it.range }
        .distinctUntilChanged()
        .flatMapLatest { range -> snapshots.observeSince(range.startMs(System.currentTimeMillis())) }
        .map { points -> if (points.size < 2) null else EquityStats.sessionChange(points) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _status = MutableStateFlow(TxStatus())
    val status: StateFlow<TxStatus> = _status.asStateFlow()

    private val _exports = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val exports: SharedFlow<File> = _exports.asSharedFlow()

    fun setRange(range: RangeFilter) = filter.update { it.copy(range = range) }
    fun setSymbol(symbol: String?) = filter.update { it.copy(symbol = symbol) }
    fun setSide(side: Side?) = filter.update { it.copy(side = side) }

    fun sync() {
        if (_status.value.syncing) return
        viewModelScope.launch {
            _status.value = TxStatus(syncing = true)
            try {
                val report = fillRepository.sync(configStore.current().risk.allowedSymbols)
                _status.value = TxStatus(message = "${report.added} fill baru (${report.pages} halaman${if (report.skipped > 0) ", ${report.skipped} dilewati" else ""})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = TxStatus(error = e.userMessage())
            }
        }
    }

    fun export() {
        viewModelScope.launch {
            try {
                val fills = fillRepository.fills.first()
                val rows = filterResults(PnlCalculator.compute(fills).fills, filter.value, System.currentTimeMillis())
                if (rows.isEmpty()) {
                    _status.value = TxStatus(error = "Tidak ada transaksi pada filter ini untuk diekspor.")
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, "polybot-fills-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv")
                        .also { it.writeText(CsvExporter.fills(rows)) }
                }
                _exports.emit(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = TxStatus(error = "Ekspor gagal: ${e.message}")
            }
        }
    }

    private fun build(fills: List<Fill>, f: TxFilter): TxUi {
        val computed = PnlCalculator.compute(fills)
        val matching = filterResults(computed.fills, f, System.currentTimeMillis())
        return TxUi(
            filter = f,
            rows = matching.asReversed().take(TxUi.SHOWN),
            matching = matching.size,
            summary = PnlCalculator.summarize(matching),
            symbols = fills.map { it.symbol }.distinct().sorted(),
            positions = computed.positions.filter { f.symbol == null || it.symbol == f.symbol },
        )
    }
}

data class Kpi(val today: PnlSummary, val week: PnlSummary)

@HiltViewModel
class KpiViewModel @Inject constructor(fillRepository: FillRepository) : ViewModel() {
    val kpi: StateFlow<Kpi?> = fillRepository.fills.map { fills ->
        val results = PnlCalculator.compute(fills).fills
        val now = System.currentTimeMillis()
        Kpi(
            today = PnlCalculator.summarize(filterResults(results, TxFilter(RangeFilter.TODAY), now)),
            week = PnlCalculator.summarize(filterResults(results, TxFilter(RangeFilter.WEEK), now)),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

enum class EquityRange(val label: String, val ms: Long) {
    HOUR("1 jam", 3_600_000L), DAY("24 jam", 86_400_000L), WEEK("7 hari", 7 * 86_400_000L)
}

@HiltViewModel
class EquityViewModel @Inject constructor(snapshots: SnapshotRepository) : ViewModel() {
    private val range = MutableStateFlow(EquityRange.DAY)
    val selected: StateFlow<EquityRange> = range.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val points: StateFlow<List<EquityPoint>> = range
        .flatMapLatest { r -> snapshots.observeSince(System.currentTimeMillis() - r.ms) }
        .map { downsample(EquityStats.sameQuoteAsLatest(it), MAX_POINTS.coerceAtLeast(2)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(r: EquityRange) { range.value = r }

    private companion object {
        const val MAX_POINTS = 120
    }
}

@HiltViewModel
class OrderHistoryViewModel @Inject constructor(private val repository: OpenOrderRepository) : PollingViewModel<List<OpenOrder>>() {
    override suspend fun load(): List<OpenOrder> = repository.history()
}

@HiltViewModel
class DataViewModel @Inject constructor(private val retention: RetentionManager) : ViewModel() {
    private val _counts = MutableStateFlow<StorageCounts?>(null)
    val counts: StateFlow<StorageCounts?> = _counts.asStateFlow()

    init { refresh() }

    fun refresh() { viewModelScope.launch { _counts.value = retention.counts() } }

    fun clearLogs() { viewModelScope.launch { retention.clearLogs(); refresh() } }

    fun clearReports() { viewModelScope.launch { retention.clearReports(); refresh() } }
}

internal fun BigDecimal.toChartFloat(): Float = toDouble().toFloat()
