package app.polybot.domain.engine

import app.polybot.core.log.BotLog
import app.polybot.core.result.ApiError
import app.polybot.core.time.Clock
import app.polybot.domain.config.BotConfig
import app.polybot.domain.config.StrategyType
import app.polybot.domain.model.Catalog
import app.polybot.domain.model.Market
import app.polybot.domain.risk.Approval
import app.polybot.domain.risk.RiskManager
import app.polybot.domain.risk.RiskState
import app.polybot.domain.risk.RiskVerdict
import app.polybot.domain.strategy.Dca
import app.polybot.domain.strategy.Grid
import app.polybot.domain.strategy.Intent
import app.polybot.domain.strategy.MarketMaker
import app.polybot.domain.strategy.Strategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal

sealed interface EngineState {
    data object Idle : EngineState
    data object Starting : EngineState
    data object Running : EngineState

    /** [auto] true = lanjut sendiri begitu kondisi pulih; false = menunggu pengguna menekan Lanjut. */
    data class Paused(val reason: String, val auto: Boolean) : EngineState
    data object Stopping : EngineState
    data class Stopped(val reason: String) : EngineState
    data class Fatal(val reason: String) : EngineState

    /** Engine sedang memegang sesi (tick boleh jalan atau menunggu). */
    val inSession: Boolean get() = this is Starting || this is Running || this is Paused || this is Stopping
}

data class EngineStatus(
    val state: EngineState = EngineState.Idle,
    val dryRun: Boolean = true,
    val strategy: String? = null,
    val symbol: String? = null,
    val openOrders: Int = 0,
    val ticks: Long = 0,
    val lastTickMs: Long? = null,
    val startEquity: BigDecimal? = null,
    val equity: BigDecimal? = null,
    val consecutiveErrors: Int = 0,
    val lastMessage: String? = null,
    /** Bertambah tiap percobaan Start; dipakai Service untuk membedakan sesi baru dari status sesi lama. */
    val sessionId: Long = 0,
    val startedAtMs: Long? = null,
) {
    val sessionPnl: BigDecimal? get() = if (startEquity != null && equity != null) equity.subtract(startEquity) else null
}

fun interface CatalogProvider {
    suspend fun load(refresh: Boolean): Catalog
}

/** Satu set komponen pengiriman order: live memakai API sungguhan, dry run memakai versi di memori. */
class EngineComponents(
    val exchange: ExchangeGateway,
    val store: OrderStore,
    val manager: OrderManager,
    val reconciler: Reconciler,
    val costBasis: CostBasisSource = CostBasisSource { null },
)

/**
 * Loop utama bot (plan bagian 10.1). Satu panggilan [tick] = satu siklus: rekonsiliasi -> snapshot -> risiko ->
 * strategi -> eksekusi. Pemanggil (BotRunner) yang mengatur jeda antar siklus.
 */
class BotEngine(
    private val live: EngineComponents,
    private val dryRun: () -> EngineComponents,
    private val data: MarketDataSource,
    private val catalogProvider: CatalogProvider,
    private val log: BotLog,
    private val clock: Clock = Clock.System,
    private val strategyFor: (StrategyType) -> Strategy = ::defaultStrategy,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private lateinit var config: BotConfig
    private lateinit var components: EngineComponents
    private lateinit var market: Market
    private lateinit var strategy: Strategy
    private lateinit var risk: RiskManager
    private var riskState = RiskState()
    private var serverMaxOpenOrders = 0
    private var tickHadError = false
    private val tag get() = if (config.dryRun) "DRY" else "LIVE"

    /** Detik sampai siklus berikutnya: rapat saat ada order aktif, longgar saat idle. */
    fun pollDelaySec(): Long = if (_status.value.openOrders > 0) config.pollActiveSec.toLong() else config.pollIdleSec.toLong()

    suspend fun start(newConfig: BotConfig): Boolean = mutex.withLock {
        if (_status.value.state.inSession) return false
        _status.update { it.copy(sessionId = it.sessionId + 1) }
        val errors = newConfig.validate()
        if (errors.isNotEmpty()) {
            errors.forEach { log.error("config", it) }
            setState(EngineState.Stopped("Konfigurasi tidak valid: ${errors.first()}"), message = errors.first())
            return false
        }
        setState(EngineState.Starting, strategyName = null)
        try {
            config = newConfig
            val catalog = catalogProvider.load(refresh = true)
            market = catalog.market(config.symbol) ?: throw IllegalStateException("Simbol ${config.symbol} tidak ada di katalog")
            serverMaxOpenOrders = catalog.maxOpenOrdersTotal
            components = if (config.dryRun) dryRun() else live
            strategy = strategyFor(config.strategy)
            risk = RiskManager(config.risk)
            riskState = RiskState()
            _status.update {
                EngineStatus(
                    dryRun = config.dryRun, strategy = strategy.name, symbol = market.symbol, state = EngineState.Starting,
                    sessionId = it.sessionId, startedAtMs = clock.nowMs(),
                )
            }
            log.info("engine", "Mulai [$tag] ${strategy.name} di ${market.symbol}")
            if (config.dryRun) {
                log.info("engine", "Dry run: keputusan hanya dicatat; tidak ada order dikirim dan fill tidak disimulasikan")
            } else {
                val report = components.reconciler.run(OrphanPolicy.ADOPT)
                log.info(
                    "reconcile",
                    "Awal: diperbarui=${report.updated}, diadopsi=${report.adopted.size}, belum pasti=${report.unresolved.size}",
                )
            }
            setState(EngineState.Running)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.Auth) {
            log.error("engine", "Autentikasi gagal saat start: ${e.message}")
            setState(EngineState.Fatal("Autentikasi ditolak (${e.code})"))
            false
        } catch (e: Exception) {
            log.error("engine", "Gagal start: ${e.message ?: e.javaClass.simpleName}")
            setState(EngineState.Stopped("Gagal start: ${e.message ?: e.javaClass.simpleName}"))
            false
        }
    }

    suspend fun tick() = mutex.withLock { runTick() }

    private suspend fun runTick() {
        val before = _status.value.state
        if (before !is EngineState.Running && !(before is EngineState.Paused && before.auto)) return
        tickHadError = false
        try {
            components.reconciler.run(OrphanPolicy.ADOPT).let { report ->
                if (report.unresolved.isNotEmpty()) log.warn("reconcile", "Belum pasti: ${report.unresolved.joinToString()}")
            }
            val inputs = data.load(market)
            val open = components.store.active().filter { it.symbolId == market.symbolId }
            val snapshot = Snapshot(clock.nowMs(), market, inputs, open, serverMaxOpenOrders, lotCost = costBasis())
            noteTick(snapshot)

            when (val verdict = risk.preTrade(snapshot, riskState)) {
                RiskVerdict.Ok -> if (before is EngineState.Paused) {
                    log.info("risk", "Kondisi pulih, bot lanjut")
                    setState(EngineState.Running)
                }
                is RiskVerdict.Pause -> {
                    if (before is EngineState.Running) {
                        log.warn("risk", "Jeda: ${verdict.reason}")
                        cancelOurOrders("jeda")
                    }
                    if (before !is EngineState.Paused || before.reason != verdict.reason) {
                        setState(EngineState.Paused(verdict.reason, verdict.auto), message = verdict.reason)
                    }
                    return
                }
                is RiskVerdict.Stop -> {
                    log.error("risk", "Berhenti: ${verdict.reason}")
                    cancelOurOrders("stop")
                    setState(EngineState.Stopped(verdict.reason), message = verdict.reason)
                    return
                }
            }

            val keepGoing = execute(strategy.decide(snapshot, config), snapshot)
            if (keepGoing) {
                if (tickHadError) {
                    afterError()
                } else {
                    riskState.recordSuccess()
                    _status.update { it.copy(consecutiveErrors = 0) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError.Auth) {
            log.error("engine", "Autentikasi/izin ditolak (${e.code}): ${e.message}")
            setState(EngineState.Fatal("Autentikasi atau izin ditolak (${e.code})"))
        } catch (e: Exception) {
            riskState.recordError()
            log.warn("engine", "Siklus gagal (${riskState.consecutiveErrors} beruntun): ${e.message ?: e.javaClass.simpleName}")
            _status.update { it.copy(lastMessage = e.message) }
            afterError()
        }
    }

    /** Gagal membaca biaya tidak boleh menjatuhkan siklus: tanpa biaya, strategi memakai harga ideal biasa. */
    private suspend fun costBasis(): BigDecimal? = try {
        components.costBasis.lotCost(market)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun noteError() {
        riskState.recordError()
        tickHadError = true
    }

    /** Memperbarui hitungan error dan menjeda bot (manual) bila batas error beruntun tercapai. */
    private suspend fun afterError() {
        val errors = riskState.consecutiveErrors
        _status.update { it.copy(consecutiveErrors = errors) }
        if (errors >= config.risk.maxConsecutiveErrors && _status.value.state is EngineState.Running) {
            log.error("risk", "$errors error beruntun, bot dijeda")
            cancelOurOrders("error beruntun")
            setState(EngineState.Paused("$errors error beruntun", auto = false), message = "$errors error beruntun")
        }
    }

    /** Mengembalikan false bila siklus berakhir dengan Halt. */
    private suspend fun execute(intents: List<Intent>, snapshot: Snapshot): Boolean {
        val placed = mutableListOf<Intent.Place>()
        for (intent in intents) {
            when (intent) {
                is Intent.Halt -> {
                    log.info("strategy", "Berhenti: ${intent.reason}")
                    cancelOurOrders("halt")
                    setState(EngineState.Stopped("Strategi: ${intent.reason}"), message = intent.reason)
                    return false
                }
                is Intent.Cancel -> when (val out = components.manager.cancel(intent.clientOrderId)) {
                    is CancelOutcome.Closed -> log.info("order", "[$tag] Batal ${intent.clientOrderId} (${intent.reason})")
                    is CancelOutcome.Requested -> log.info("order", "[$tag] Batal diminta ${intent.clientOrderId}, menunggu konfirmasi")
                    CancelOutcome.NotTracked -> log.warn("order", "Batal: ${intent.clientOrderId} tidak tercatat")
                    is CancelOutcome.Failed -> {
                        if (out.error is ApiError.Auth) throw out.error
                        log.warn("order", "Batal gagal ${intent.clientOrderId}: ${out.error.message}")
                    }
                }
                is Intent.Place -> {
                    val approval = risk.approve(intent, snapshot, riskState, placed)
                    if (approval is Approval.Rejected) {
                        log.warn("risk", "Ditolak risiko: ${approval.reason} (${intent.note})")
                        continue
                    }
                    when (val out = components.manager.submit(
                        market, intent.side, intent.price, intent.qty, intent.postOnly, intent.tif, intent.expireAtMs,
                    )) {
                        is SubmitOutcome.Accepted -> {
                            placed += intent
                            riskState.recordOrder(clock.nowMs())
                            val midStr = snapshot.mid?.toPlainString() ?: "?"
                            log.info("order", "[$tag] ${intent.side} ${market.symbol} ${intent.qty.toPlainString()} @ ${intent.price.toPlainString()} mid=$midStr (${intent.note}) -> ${out.order.clientOrderId}")
                        }
                        is SubmitOutcome.Rejected -> {
                            if (out.error is ApiError.Auth) throw out.error
                            log.warn("order", "Order ditolak: ${out.reason} (${intent.note})")
                            if (out.error != null) noteError()
                        }
                        is SubmitOutcome.Unresolved -> {
                            placed += intent
                            noteError()
                            log.warn("order", "Hasil order belum pasti: ${out.order.clientOrderId}; menunggu rekonsiliasi")
                        }
                    }
                }
            }
        }
        return true
    }

    /**
     * Dipanggil pemantau jaringan. Saat offline bot dijeda otomatis (order yang sudah terpasang tidak bisa
     * dibatalkan tanpa jaringan); saat kembali online bot lanjut dan siklus berikutnya merekonsiliasi lebih dulu.
     */
    suspend fun networkChanged(online: Boolean) = mutex.withLock {
        val state = _status.value.state
        if (!online && state is EngineState.Running) {
            log.warn("net", "Jaringan hilang, bot dijeda")
            setState(EngineState.Paused(NETWORK_REASON, auto = true), message = NETWORK_REASON)
        } else if (online && state is EngineState.Paused && state.reason == NETWORK_REASON) {
            log.info("net", "Jaringan kembali, bot lanjut")
            setState(EngineState.Running)
        }
    }

    suspend fun pause(reason: String = "Dijeda pengguna") = mutex.withLock {
        if (_status.value.state !is EngineState.Running) return@withLock
        log.info("engine", reason)
        cancelOurOrders("jeda")
        setState(EngineState.Paused(reason, auto = false), message = reason)
    }

    suspend fun resume() = mutex.withLock {
        val state = _status.value.state
        if (state !is EngineState.Paused) return@withLock
        riskState.resetErrors()
        _status.update { it.copy(consecutiveErrors = 0) }
        log.info("engine", "Dilanjutkan pengguna")
        setState(EngineState.Running)
    }

    suspend fun stop(reason: String = "Dihentikan pengguna") = mutex.withLock {
        if (!_status.value.state.inSession) return@withLock
        setState(EngineState.Stopping)
        log.info("engine", "Berhenti: $reason")
        cancelOurOrders("stop")
        setState(EngineState.Stopped(reason), message = reason)
    }

    /** Berhenti darurat: batalkan order bot (dan semua order di pair itu bila diizinkan), lalu STOPPED. */
    suspend fun killSwitch() = mutex.withLock {
        if (!::config.isInitialized || !_status.value.state.inSession) return@withLock
        setState(EngineState.Stopping)
        log.warn("engine", "KILL SWITCH")
        cancelOurOrders("kill switch")
        if (config.killSwitchCancelsAllOnSymbol) {
            try {
                components.exchange.cancelAll(listOf(market.symbolId))
                log.warn("engine", "cancel_all ${market.symbol} dikirim")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("engine", "cancel_all gagal: ${e.message}")
            }
        }
        try {
            val report = components.reconciler.run(OrphanPolicy.CANCEL)
            if (!report.clean) log.warn("reconcile", "Setelah kill switch belum pasti: ${report.unresolved.joinToString()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("reconcile", "Rekonsiliasi setelah kill switch gagal: ${e.message}")
        }
        setState(EngineState.Stopped("Kill switch"), message = "Kill switch")
    }

    private suspend fun cancelOurOrders(why: String) {
        if (!config.cancelOnStop) return
        val ours = components.store.active().filter { it.symbolId == market.symbolId }
        if (ours.isEmpty()) return
        log.info("order", "[$tag] Membatalkan ${ours.size} order ($why)")
        for (order in ours) {
            try {
                components.manager.cancel(order.clientOrderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("order", "Gagal membatalkan ${order.clientOrderId}: ${e.message}")
            }
        }
    }

    private fun noteTick(snapshot: Snapshot) {
        _status.update {
            it.copy(
                ticks = it.ticks + 1,
                lastTickMs = snapshot.nowMs,
                openOrders = snapshot.openOrders.size,
                equity = snapshot.equityQuote,
                startEquity = it.startEquity ?: snapshot.equityQuote,
            )
        }
    }

    private fun setState(state: EngineState, message: String? = null, strategyName: String? = _status.value.strategy) {
        _status.update { it.copy(state = state, lastMessage = message ?: it.lastMessage, strategy = strategyName) }
    }

    companion object {
        const val NETWORK_REASON = "Tidak ada jaringan"

        fun defaultStrategy(type: StrategyType): Strategy = when (type) {
            StrategyType.MARKET_MAKER -> MarketMaker()
            StrategyType.GRID -> Grid()
            StrategyType.DCA -> Dca()
        }
    }
}
