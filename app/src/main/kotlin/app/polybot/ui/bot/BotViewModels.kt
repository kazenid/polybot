package app.polybot.ui.bot

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polybot.core.log.LogEntry
import app.polybot.core.log.RingLogger
import app.polybot.data.bot.BotRunner
import app.polybot.data.crypto.SecureKeyStore
import app.polybot.data.prefs.BotConfigStore
import app.polybot.domain.config.BotConfig
import app.polybot.domain.engine.EngineState
import app.polybot.domain.engine.EngineStatus
import app.polybot.service.BotForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

fun EngineState.label(): String = when (this) {
    EngineState.Idle -> "Belum berjalan"
    EngineState.Starting -> "Memulai..."
    EngineState.Running -> "Berjalan"
    is EngineState.Paused -> if (auto) "Dijeda otomatis: $reason" else "Dijeda: $reason"
    EngineState.Stopping -> "Menghentikan..."
    is EngineState.Stopped -> "Berhenti: $reason"
    is EngineState.Fatal -> "GALAT FATAL: $reason"
}

/** Status mesin bot untuk Dashboard dan layar kontrol. */
@HiltViewModel
class BotStatusViewModel @Inject constructor(private val runner: BotRunner) : ViewModel() {
    val status: StateFlow<EngineStatus> = runner.status
}

@HiltViewModel
class BotControlViewModel @Inject constructor(
    private val runner: BotRunner,
    private val configStore: BotConfigStore,
    private val keyStore: SecureKeyStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val status: StateFlow<EngineStatus> = runner.status
    val config: StateFlow<BotConfig> = configStore.config.stateIn(viewModelScope, SharingStarted.Eagerly, BotConfig())

    private val _hasCredentials = MutableStateFlow(true)
    val hasCredentials: StateFlow<Boolean> = _hasCredentials.asStateFlow()

    init { refreshCredentials() }

    fun refreshCredentials() {
        viewModelScope.launch { _hasCredentials.value = withContext(Dispatchers.IO) { keyStore.load() } != null }
    }

    fun setDryRun(dryRun: Boolean) {
        viewModelScope.launch { configStore.update { it.copy(dryRun = dryRun) } }
    }

    /** Start selalu lewat Foreground Service supaya proses bot tidak dimatikan sistem saat layar mati. */
    fun start() = BotForegroundService.start(context)
    fun stop() = runner.stop()
    fun pause() = runner.pause()
    fun resume() = runner.resume()
    fun killSwitch() = runner.killSwitch()
}

/** Dipakai bersama oleh layar Strategi dan Risiko. Setiap perubahan disimpan atomik. */
@HiltViewModel
class ConfigViewModel @Inject constructor(private val configStore: BotConfigStore) : ViewModel() {
    val config: StateFlow<BotConfig> = configStore.config.stateIn(viewModelScope, SharingStarted.Eagerly, BotConfig())

    fun update(transform: (BotConfig) -> BotConfig) {
        viewModelScope.launch { configStore.update(transform) }
    }
}

@HiltViewModel
class LogsViewModel @Inject constructor(private val logger: RingLogger) : ViewModel() {
    val entries: StateFlow<List<LogEntry>> = logger.entries

    fun clear() = logger.clear()
}

/** Ekuitas 24 jam terakhir untuk menerjemahkan persen batas risiko ke nilai quote di layar Risiko. */
@HiltViewModel
class RiskEquityViewModel @Inject constructor(snapshots: app.polybot.data.report.SnapshotRepository) : ViewModel() {
    val points: StateFlow<List<app.polybot.domain.report.EquityPoint>> = snapshots
        .observeSince(System.currentTimeMillis() - 86_400_000L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
