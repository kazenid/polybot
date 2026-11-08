package app.polybot.data.bot

import app.polybot.core.log.BotLog
import app.polybot.data.net.NetworkMonitor
import app.polybot.data.prefs.BotConfigStore
import app.polybot.data.prefs.SessionStore
import app.polybot.data.report.FillRepository
import app.polybot.data.report.SnapshotRepository
import app.polybot.domain.engine.BotEngine
import app.polybot.domain.engine.EngineState
import app.polybot.domain.engine.EngineStatus
import app.polybot.domain.engine.ResumeDecision
import app.polybot.domain.engine.SessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pemilik loop bot: satu coroutine per sesi yang memanggil `engine.tick()` dengan jeda adaptif.
 * Dijaga tetap hidup oleh [app.polybot.service.BotForegroundService]; kontrak ke UI tidak berubah.
 */
@Singleton
class BotRunner @Inject constructor(
    private val engine: BotEngine,
    private val configStore: BotConfigStore,
    private val log: BotLog,
    private val snapshots: SnapshotRepository,
    private val fills: FillRepository,
    private val sessions: SessionStore,
    private val network: NetworkMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastSnapshotMs = 0L
    private var lastSyncMs = 0L
    private var lastTouchMs = 0L

    val status: StateFlow<EngineStatus> = engine.status

    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            val config = configStore.current()
            if (!engine.start(config)) {
                sessions.mark(false, System.currentTimeMillis())
                return@launch
            }
            lastSnapshotMs = 0L
            lastSyncMs = System.currentTimeMillis()
            lastTouchMs = 0L
            // Penanda sesi ditulis tiap kali status berubah; bila proses mati di tengah sesi, penanda tetap true.
            val persist = launch {
                engine.status.map { wantsToRun(it.state) }.distinctUntilChanged().collect { sessions.mark(it, System.currentTimeMillis()) }
            }
            while (isActive && engine.status.value.state.inSession) {
                try {
                    val online = network.isOnline()
                    engine.networkChanged(online)
                    if (online) {
                        engine.tick()
                        recordReports()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // engine.tick sudah menangkap error API; ini hanya jaring pengaman terakhir.
                    log.error("runner", "Loop error tak terduga: ${e.javaClass.simpleName}")
                }
                delay(if (network.isOnline()) engine.pollDelaySec() * 1_000 else OFFLINE_RETRY_MS)
            }
            persist.cancel()
            sessions.mark(false, System.currentTimeMillis())
        }
    }

    /**
     * Dipanggil setelah proses dihidupkan ulang. Melanjutkan sesi hanya bila [SessionPolicy] mengizinkan;
     * engine selalu merekonsiliasi order dengan server sebelum mengirim order baru.
     */
    suspend fun resumeIfNeeded(): Boolean {
        val saved = sessions.load()
        val now = System.currentTimeMillis()
        return when (val decision = SessionPolicy.decide(saved, configStore.current(), now)) {
            ResumeDecision.Resume -> {
                log.warn("runner", "Sesi dipulihkan otomatis setelah proses dihentikan")
                start()
                true
            }
            is ResumeDecision.Skip -> {
                if (saved.wantRunning) {
                    log.info("runner", "Sesi tidak dipulihkan: ${decision.reason}")
                    sessions.mark(false, now)
                }
                false
            }
        }
    }

    private fun wantsToRun(state: EngineState) =
        state is EngineState.Running || state is EngineState.Starting || (state is EngineState.Paused && state.auto)

    /** Snapshot ekuitas tiap menit dan sinkron fill tiap ~5 menit saat live. Kegagalan di sini tidak boleh mengganggu bot. */
    private suspend fun recordReports() {
        val st = engine.status.value
        val now = System.currentTimeMillis()
        if (now - lastTouchMs >= SNAPSHOT_EVERY_MS) {
            lastTouchMs = now
            runCatching { sessions.touch(now) }
        }
        val equity = st.equity ?: return
        val symbol = st.symbol ?: return
        if (now - lastSnapshotMs >= SNAPSHOT_EVERY_MS) {
            lastSnapshotMs = now
            runCatching { snapshots.record(now, symbol, equity, st.dryRun) }
        }
        if (!st.dryRun && now - lastSyncMs >= SYNC_EVERY_MS) {
            lastSyncMs = now
            runCatching { fills.sync(setOf(symbol)) }.onFailure { log.warn("report", "Sinkron fill gagal: ${it.message}") }
        }
    }

    fun stop() {
        scope.launch {
            engine.stop()
            sessions.mark(false, System.currentTimeMillis())
        }
    }

    fun pause() {
        scope.launch { engine.pause() }
    }

    fun resume() {
        scope.launch { engine.resume() }
    }

    /** Menghentikan loop lebih dulu supaya tidak menunggu siklus yang sedang jalan, lalu membatalkan order. */
    fun killSwitch() {
        val running = loop
        scope.launch {
            running?.cancel()
            running?.join()
            engine.killSwitch()
            sessions.mark(false, System.currentTimeMillis())
        }
    }

    private companion object {
        const val SNAPSHOT_EVERY_MS = 60_000L
        const val SYNC_EVERY_MS = 300_000L
        const val OFFLINE_RETRY_MS = 3_000L
    }
}
