package app.polybot.data.report

import app.polybot.core.log.LogEntry
import app.polybot.core.log.LogLevel
import app.polybot.core.log.RingLogger
import app.polybot.core.time.Clock
import app.polybot.data.api.services.OrdersApi
import app.polybot.data.catalog.CatalogRepository
import app.polybot.domain.engine.CostBasisSource
import app.polybot.domain.model.Market
import app.polybot.domain.model.Side
import app.polybot.domain.report.EquityPoint
import app.polybot.domain.report.Fill
import app.polybot.domain.report.PnlCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(val added: Int, val pages: Int, val skipped: Int)

data class StorageCounts(val fills: Int, val snapshots: Int, val logs: Int)

@Singleton
class FillRepository @Inject constructor(
    private val api: OrdersApi,
    private val catalog: CatalogRepository,
    private val db: ReportDatabase,
) {
    private val mutex = Mutex()

    val fills: Flow<List<Fill>> = db.fills().observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * Menarik fill baru per simbol. Fill yang sudah ada dilewati (kunci matchId+orderId), jadi sinkronisasi
     * berulang aman. Mengembalikan ringkasan; error API dilempar ke pemanggil.
     */
    suspend fun sync(symbols: Collection<String>): SyncReport = mutex.withLock {
        val cat = catalog.ensure()
        var added = 0
        var pages = 0
        var skipped = 0
        for (symbol in symbols) {
            val market = cat.market(symbol) ?: continue
            val after = db.fills().maxMatchId(symbol) ?: 0L
            var token = ""
            do {
                val page = api.userTrades(market.symbolId, limit = PAGE_SIZE, pageToken = token, afterMatchId = after)
                pages++
                val mapped = page.trades.mapNotNull { dto -> FillMapper.map(dto, market).also { if (it == null) skipped++ } }
                if (mapped.isNotEmpty()) added += db.fills().insertAll(mapped.map { it.toEntity(symbol) }).count { it != -1L }
                token = page.nextPageToken
            } while (token.isNotEmpty() && page.trades.isNotEmpty() && pages < MAX_PAGES)
        }
        SyncReport(added, pages, skipped)
    }

    private fun Fill.toEntity(symbol: String) = FillEntity(
        matchId, orderId, symbol, side.name, isMaker, price.toPlainString(), qty.toPlainString(),
        feeQuote.toPlainString(), "QUOTE", tsMs,
    )

    private fun FillEntity.toDomain() = Fill(
        matchId, orderId, symbol, Side.valueOf(side), isMaker, BigDecimal(price), BigDecimal(qty), BigDecimal(feeQuote), tsMs,
    )

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 30
    }
}

/**
 * Biaya lot teratas (pembelian terbaru yang belum terjual) dari riwayat fill di Room (dihitung [PnlCalculator]). Fill ditarik dari server paling sering
 * tiap [SYNC_INTERVAL_MS] supaya fill terbaru ikut terhitung tanpa membebani API; sinkron gagal memakai data terakhir.
 */
@Singleton
class FillCostBasis @Inject constructor(
    private val fills: FillRepository,
    private val clock: Clock,
) : CostBasisSource {
    private var lastSyncMs = 0L

    override suspend fun lotCost(market: Market): BigDecimal? {
        val now = clock.nowMs()
        if (now - lastSyncMs >= SYNC_INTERVAL_MS) {
            lastSyncMs = now
            try {
                fills.sync(listOf(market.symbol))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pakai data lokal yang sudah ada; sinkron berikutnya mencoba lagi.
            }
        }
        val position = PnlCalculator.compute(fills.fills.first()).positions.firstOrNull { it.symbol == market.symbol }
        return position?.newestLotCost?.takeIf { it.signum() > 0 }
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 20_000L
    }
}

@Singleton
class SnapshotRepository @Inject constructor(private val db: ReportDatabase) {
    suspend fun record(tsMs: Long, symbol: String, equity: BigDecimal, dryRun: Boolean) {
        db.snapshots().insert(EquitySnapshotEntity(tsMs = tsMs, symbol = symbol, equityQuote = equity.toPlainString(), dryRun = dryRun))
    }

    fun observeSince(sinceMs: Long): Flow<List<EquityPoint>> =
        db.snapshots().observeSince(sinceMs).map { rows -> rows.map { EquityPoint(it.tsMs, BigDecimal(it.equityQuote), it.dryRun, it.symbol) } }
}

/**
 * Menyimpan log ke Room dan memulihkannya setelah proses dimulai ulang. Penulisan lewat antrean tunggal
 * supaya urutan terjaga dan pemanggil log tidak pernah menunggu disk.
 */
@Singleton
class LogRepository @Inject constructor(private val db: ReportDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<LogEntry>(Channel.UNLIMITED)
    private var attached = false

    @Synchronized
    fun attach(logger: RingLogger, restore: Int = RESTORE_COUNT) {
        if (attached) return
        attached = true
        scope.launch {
            for (entry in queue) {
                runCatching { db.logs().insert(LogEntity(tsMs = entry.timeMs, level = entry.level.name, category = entry.category, message = entry.message)) }
            }
        }
        scope.launch {
            val recent = runCatching { db.logs().recent(restore) }.getOrDefault(emptyList()).reversed()
            logger.restore(recent.map { LogEntry(it.tsMs, runCatching { LogLevel.valueOf(it.level) }.getOrDefault(LogLevel.INFO), it.category, it.message) })
            logger.sink = { entry -> queue.trySend(entry) }
        }
    }

    private companion object {
        const val RESTORE_COUNT = 200
    }
}

@Singleton
class RetentionManager @Inject constructor(private val db: ReportDatabase) {
    /** Log 7 hari, snapshot 90 hari. Fill 365 hari: dibutuhkan untuk biaya rata-rata PnL, jadi tidak dipangkas cepat. */
    suspend fun prune(nowMs: Long) {
        db.logs().prune(nowMs - LOG_DAYS * DAY_MS)
        db.snapshots().prune(nowMs - SNAPSHOT_DAYS * DAY_MS)
        db.fills().prune(nowMs - FILL_DAYS * DAY_MS)
    }

    suspend fun counts() = StorageCounts(db.fills().count(), db.snapshots().count(), db.logs().count())

    suspend fun clearLogs() = db.logs().deleteAll()

    suspend fun clearReports() {
        db.fills().deleteAll()
        db.snapshots().deleteAll()
    }

    companion object {
        const val LOG_DAYS = 7L
        const val SNAPSHOT_DAYS = 90L
        const val FILL_DAYS = 365L
        private const val DAY_MS = 86_400_000L
    }
}
