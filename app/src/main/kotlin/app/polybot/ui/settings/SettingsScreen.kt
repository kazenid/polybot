package app.polybot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Dataset
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.ui.bot.BotControlViewModel
import app.polybot.ui.bot.label
import app.polybot.ui.components.Pill
import app.polybot.ui.components.ScreenHeader
import app.polybot.ui.theme.trade

private data class SettingsEntry(val title: String, val subtitle: String, val route: String, val icon: ImageVector, val tint: Color)

private val bot = Color(0xFF0ECB81)
private val account = Color(0xFF5B8CFF)
private val tools = Color(0xFFFF5FA2)
private val system = Color(0xFFB36BFF)

private val groups: List<Pair<String, List<SettingsEntry>>> = listOf(
    "Bot" to listOf(
        SettingsEntry("Kontrol Bot", "Start, jeda, stop, kill switch, mode dry run", "settings/control", Icons.Rounded.PlayCircle, bot),
        SettingsEntry("Strategi", "Market Maker, Grid, DCA dan simbol", "settings/strategy", Icons.Rounded.Tune, bot),
        SettingsEntry("Risiko", "Batas order, eksposur, rugi harian", "settings/risk", Icons.Rounded.Security, bot),
    ),
    "Akun" to listOf(
        SettingsEntry("Akun & API Key", "Environment, key, tes koneksi", "settings/account", Icons.Rounded.AccountCircle, account),
        SettingsEntry("Uji order manual", "Pasang dan batalkan satu order kecil di testnet", "settings/order-test", Icons.Rounded.Science, account),
    ),
    "Bantuan" to listOf(
        SettingsEntry("Panduan aplikasi", "Cara memakai PolyBot, strategi, risiko, dan membaca PnL", "settings/guide", Icons.AutoMirrored.Rounded.MenuBook, tools),
        SettingsEntry("Panduan baterai", "Bebas optimasi baterai dan langkah per merek HP", "settings/battery", Icons.Rounded.BatteryChargingFull, tools),
        SettingsEntry("Tentang", "Versi aplikasi, pengembang, dan catatan penting", "settings/about", Icons.Rounded.Info, tools),
    ),
    "Sistem" to listOf(
        SettingsEntry("Polling & Notifikasi", "Interval polling, izin dan channel notifikasi", "settings/polling", Icons.Rounded.Notifications, system),
        SettingsEntry("Data", "Penyimpanan lokal, retensi, hapus riwayat", "settings/data", Icons.Rounded.Dataset, system),
    ),
)

@Composable
fun SettingsScreen(onOpen: (String) -> Unit, viewModel: BotControlViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val hasCredentials by viewModel.hasCredentials.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Pengaturan", "Semua kontrol dan konfigurasi bot ada di sini")

        Surface(
            onClick = { onOpen("settings/control") },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status.state.label(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Pill(if (config.dryRun) "DRY RUN" else "LIVE", if (config.dryRun) MaterialTheme.trade.warn else MaterialTheme.trade.up)
                }
                Text(
                    "${config.strategy.label} · ${config.symbol}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hasCredentials) {
                    Text("API key belum diisi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        groups.forEach { (title, entries) ->
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    entries.forEachIndexed { index, entry ->
                        SettingsRow(entry, onClick = { onOpen(entry.route) })
                        if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Box(Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(entry.tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(entry.icon, null, tint = entry.tint, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
