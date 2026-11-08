package app.polybot.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.service.StatusNotifier
import app.polybot.ui.bot.ConfigViewModel
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.NoticeBanner
import app.polybot.ui.components.Pill
import app.polybot.ui.theme.trade

@Composable
fun PollingScreen(viewModel: ConfigViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Bot memeriksa order dan harga lewat polling berkala. Perubahan berlaku pada Start berikutnya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        InfoCard("Interval polling", icon = Icons.Rounded.Speed) {
            IntervalSlider(
                title = "Saat ada order terbuka",
                seconds = config.pollActiveSec,
                range = 1..30,
                onChange = { v -> viewModel.update { it.copy(pollActiveSec = v, pollIdleSec = maxOf(it.pollIdleSec, v)) } },
            )
            IntervalSlider(
                title = "Saat idle (tanpa order)",
                seconds = config.pollIdleSec,
                range = 5..120,
                onChange = { v -> viewModel.update { it.copy(pollIdleSec = maxOf(v, it.pollActiveSec)) } },
            )
            Text(
                "Interval pendek membuat bot lebih cepat bereaksi tetapi memakai lebih banyak baterai dan kuota rate limit. " +
                    "Nilai idle tidak bisa lebih kecil dari nilai aktif.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoCard("Notifikasi", icon = if (notificationsOn) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Izin notifikasi", Modifier.weight(1f))
                if (notificationsOn) Pill("Aktif", MaterialTheme.trade.up) else Pill("Mati", MaterialTheme.colorScheme.error)
            }
            if (!notificationsOn) {
                NoticeBanner(
                    "Tanpa notifikasi, status dan peringatan bot tidak tampil. Bot tetap berjalan, tetapi Anda tidak tahu bila dijeda.",
                    Icons.Rounded.NotificationsOff, MaterialTheme.trade.warn,
                )
            }
            Button(onClick = { openAppNotificationSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Pengaturan notifikasi aplikasi")
            }
            OutlinedButton(onClick = { openChannelSettings(context, StatusNotifier.CHANNEL_ALERTS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Atur nada peringatan")
            }
            Text(
                "Status bot memakai channel senyap. Peringatan (jeda, berhenti otomatis, galat fatal) punya channel sendiri sehingga bisa diberi nada.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IntervalSlider(title: String, seconds: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Pill("$seconds dtk", MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = seconds.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun openChannelSettings(context: Context, channelId: String) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
