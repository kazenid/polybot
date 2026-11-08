package app.polybot.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.core.time.ClockMonitor
import app.polybot.data.api.ApiGateway
import app.polybot.data.catalog.CatalogRepository
import app.polybot.data.crypto.SecureKeyStore
import app.polybot.data.prefs.EnvStore
import app.polybot.data.prefs.PolyEnv
import app.polybot.ui.PollingViewModel
import app.polybot.ui.bot.BotStatusViewModel
import app.polybot.ui.bot.label
import app.polybot.ui.components.ErrorBanner
import app.polybot.domain.engine.EngineState
import app.polybot.domain.engine.EngineStatus
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.NoticeBanner
import app.polybot.ui.components.Pill
import app.polybot.ui.components.ScreenHeader
import app.polybot.ui.components.StatTile
import app.polybot.ui.components.StatusDot
import app.polybot.ui.components.pnlColor
import app.polybot.ui.theme.trade
import java.math.RoundingMode
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.components.PollWhileStarted
import app.polybot.ui.components.UpdatedAt
import app.polybot.ui.market.WatchlistCard
import app.polybot.ui.report.EquityCard
import app.polybot.ui.report.KpiCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

data class DashboardData(
    val env: PolyEnv,
    val hasCredentials: Boolean,
    val clockSkewMs: Long?,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val envStore: EnvStore,
    private val keyStore: SecureKeyStore,
    private val gateway: ApiGateway,
) : PollingViewModel<DashboardData>() {
    override suspend fun load(): DashboardData {
        catalog.hydrate()
        return DashboardData(
            env = envStore.current(),
            hasCredentials = withContext(Dispatchers.IO) { keyStore.load() } != null,
            clockSkewMs = gateway.clockMonitor.skewMs(),
        )
    }
}

@Composable
fun DashboardScreen(
    onOpenMarket: (String) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
    botViewModel: BotStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bot by botViewModel.status.collectAsStateWithLifecycle()
    PollWhileStarted(intervalMs = 15_000) { viewModel.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Dashboard", null) {
            state.data?.let { Pill(it.env.label, MaterialTheme.colorScheme.secondary) }
        }
        state.error?.let { ErrorBanner(it) }

        EquityCard()
        BotStatusCard(bot)

        WatchlistCard(onOpen = onOpenMarket)
        state.data?.let { data -> ConnectionCard(data) }
        KpiCard()
        UpdatedAt(state.updatedAtMs, state.loading)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BotStatusCard(bot: EngineStatus) {
    val running = bot.state is EngineState.Running
    val dotColor = when {
        running -> MaterialTheme.trade.up
        bot.state is EngineState.Fatal -> MaterialTheme.colorScheme.error
        bot.state.inSession -> MaterialTheme.trade.warn
        else -> MaterialTheme.colorScheme.outline
    }
    InfoCard("Status bot", icon = Icons.Rounded.SmartToy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(dotColor)
            Text(bot.state.label(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (bot.state.inSession || bot.ticks > 0) {
                Pill(if (bot.dryRun) "DRY RUN" else "LIVE", if (bot.dryRun) MaterialTheme.trade.warn else MaterialTheme.trade.up)
            }
        }
        if (bot.state.inSession || bot.ticks > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Strategi", bot.strategy ?: "-", Modifier.weight(1f))
                StatTile("Order terbuka", bot.openOrders.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Ekuitas", bot.equity?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "-", Modifier.weight(1f))
                val pnl = bot.sessionPnl
                StatTile(
                    "PnL sesi",
                    pnl?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: "-",
                    Modifier.weight(1f),
                    valueColor = pnl?.let { pnlColor(it) } ?: Color.Unspecified,
                )
            }
            bot.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Text("Atur dan jalankan bot di Setelan > Kontrol Bot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionCard(data: DashboardData) {
    InfoCard("Koneksi", icon = Icons.Rounded.Wifi) {
        KeyValueRow("Environment", data.env.label)
        KeyValueRow("API key", if (data.hasCredentials) "Tersimpan" else "Belum diisi", if (data.hasCredentials) MaterialTheme.trade.up else MaterialTheme.colorScheme.error)
        val skew = data.clockSkewMs
        KeyValueRow("Selisih jam dengan server", skew?.let { "${if (it >= 0) "+" else ""}${it / 1000.0} dtk" } ?: "-")
        if (skew != null && abs(skew) > ClockMonitor.MAX_SKEW_MS) {
            NoticeBanner(
                "Jam perangkat melenceng. Aktifkan waktu otomatis agar tanda tangan tidak ditolak.",
                Icons.Rounded.WarningAmber, MaterialTheme.colorScheme.error,
            )
        }
    }
}
