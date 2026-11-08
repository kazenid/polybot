package app.polybot.ui.bot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import app.polybot.ui.components.Pill
import app.polybot.ui.components.StatTile
import app.polybot.ui.components.StatusDot
import app.polybot.ui.components.pnlColor
import app.polybot.ui.theme.trade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.domain.config.BotConfig
import app.polybot.domain.engine.EngineState
import app.polybot.domain.engine.EngineStatus
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.components.SwitchRow
import app.polybot.ui.components.plain

@Composable
fun ControlScreen(viewModel: BotControlViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val hasCredentials by viewModel.hasCredentials.collectAsStateWithLifecycle()
    var confirmLive by remember { mutableStateOf(false) }
    var confirmKill by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val notificationsOn = remember(status.state) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    // Android 13+: minta izin notifikasi dulu; bot tetap dimulai walau ditolak (notifikasi saja yang tidak tampil).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.start() }
    val launchStart = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start()
        }
    }
    val errors = config.validate()
    val state = status.state
    val inSession = state.inSession

    if (confirmLive) {
        AlertDialog(
            onDismissRequest = { confirmLive = false },
            title = { Text("Mulai mode LIVE?") },
            text = {
                Text(
                    "Bot akan mengirim order sungguhan (memakai Paper Assets) ke testnet.\n\n" +
                        "Strategi: ${config.strategy.label}\nSimbol: ${config.symbol}\n" +
                        "Batas per order: ${config.risk.maxOrderQuote.plain()}\n" +
                        "Rugi harian maksimum: ${config.risk.maxDailyLossQuote.plain()}\n\n" +
                        "Anda bisa menghentikannya kapan saja dengan Stop atau Kill Switch.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmLive = false; launchStart() }) { Text("Mulai LIVE") } },
            dismissButton = { TextButton(onClick = { confirmLive = false }) { Text("Batal") } },
        )
    }
    if (confirmKill) {
        AlertDialog(
            onDismissRequest = { confirmKill = false },
            title = { Text("Kill Switch") },
            text = {
                Text(
                    "Bot dihentikan sekarang dan semua order milik bot dibatalkan." +
                        if (config.killSwitchCancelsAllOnSymbol) "\n\nPERHATIAN: semua order di ${config.symbol}, termasuk order manual Anda, juga dibatalkan." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmKill = false; viewModel.killSwitch() }) {
                    Text("Batalkan semua", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmKill = false }) { Text("Batal") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(status)

        if (!hasCredentials) {
            ErrorBanner("API key belum diisi. Bot butuh saldo dari akun Anda, bahkan untuk dry run. Buka Setelan > Akun & API Key.")
        }
        if (state is EngineState.Fatal) ErrorBanner(state.reason)
        if (inSession && !notificationsOn) {
            ErrorBanner("Notifikasi dimatikan: bot tetap berjalan tetapi status dan peringatan tidak tampil. Aktifkan di Setelan > Panduan baterai.")
        }

        InfoCard("Mode") {
            SwitchRow(
                label = if (config.dryRun) "Dry run (aman)" else "LIVE: kirim order sungguhan",
                checked = config.dryRun,
                onChecked = viewModel::setDryRun,
                enabled = !inSession,
                description = if (config.dryRun) {
                    "Hanya mencatat keputusan di Log. Tidak ada order dikirim dan fill tidak disimulasikan."
                } else {
                    "Order post-only dikirim ke testnet memakai Paper Assets."
                },
            )
            if (inSession) Text("Hentikan bot untuk mengubah mode atau pengaturan.", style = MaterialTheme.typography.bodySmall)
        }

        if (!inSession && errors.isNotEmpty()) {
            ErrorBanner("Konfigurasi belum valid:\n" + errors.joinToString("\n") { "• $it" })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !inSession -> Button(
                    onClick = { if (config.dryRun) launchStart() else confirmLive = true },
                    enabled = errors.isEmpty(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.trade.up, contentColor = Color.White),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (config.dryRun) "Start (dry run)" else "Start LIVE")
                }

                state is EngineState.Paused -> {
                    Button(
                        onClick = viewModel::resume,
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.trade.up, contentColor = Color.White),
                    ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Lanjut") }
                    OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
                    }
                }
                state is EngineState.Running -> {
                    OutlinedButton(onClick = viewModel::pause, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Rounded.Pause, null); Spacer(Modifier.width(6.dp)); Text("Jeda")
                    }
                    OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
                    }
                }
                else -> OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
                }
            }
        }
        if (inSession) {
            Button(
                onClick = { confirmKill = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, null)
                Spacer(Modifier.width(6.dp))
                Text("KILL SWITCH")
            }
        }
    }
}

@Composable
private fun StatusCard(status: EngineStatus) {
    val state = status.state
    val dot = when {
        state is EngineState.Running -> MaterialTheme.trade.up
        state is EngineState.Fatal -> MaterialTheme.colorScheme.error
        state.inSession -> MaterialTheme.trade.warn
        else -> MaterialTheme.colorScheme.outline
    }
    InfoCard("Status bot", icon = Icons.Rounded.SmartToy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(dot, 12.dp)
            Text(state.label(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(if (status.dryRun) "DRY RUN" else "LIVE", if (status.dryRun) MaterialTheme.trade.warn else MaterialTheme.trade.up)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Strategi", status.strategy ?: "-", Modifier.weight(1f))
            StatTile("Simbol", status.symbol ?: "-", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Siklus", status.ticks.toString(), Modifier.weight(1f))
            StatTile("Order terbuka", status.openOrders.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Ekuitas", status.equity?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "-", Modifier.weight(1f))
            val pnl = status.sessionPnl
            StatTile("PnL sesi", pnl?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "-", Modifier.weight(1f), pnl?.let { pnlColor(it) } ?: Color.Unspecified)
        }
        if (status.consecutiveErrors > 0) KeyValueRow("Error beruntun", status.consecutiveErrors.toString(), MaterialTheme.colorScheme.error)
        status.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
