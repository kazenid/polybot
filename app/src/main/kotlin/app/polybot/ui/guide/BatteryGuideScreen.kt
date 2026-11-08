package app.polybot.ui.guide

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.KeyValueRow

private fun Context.ignoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

private fun Context.notificationsAllowed(): Boolean =
    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(this).areNotificationsEnabled()

private fun Context.openSafely(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // coba alternatif berikutnya
        }
    }
}

@Composable
fun BatteryGuideScreen() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(context.ignoringBatteryOptimizations()) }
    var notifications by remember { mutableStateOf(context.notificationsAllowed()) }
    // Pengguna kembali dari layar pengaturan sistem: baca ulang statusnya.
    LifecycleResumeEffect(Unit) {
        exempt = context.ignoringBatteryOptimizations()
        notifications = context.notificationsAllowed()
        onPauseOrDispose { }
    }
    val guide = remember { BatteryGuide.forManufacturer(Build.MANUFACTURER.orEmpty()) }
    val packageUri = Uri.parse("package:${context.packageName}")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Android sering mematikan aplikasi di latar belakang. Tanpa pengaturan di bawah, bot bisa berhenti diam-diam saat layar mati.",
            style = MaterialTheme.typography.bodyMedium,
        )

        InfoCard("Status") {
            KeyValueRow("Bebas optimasi baterai", if (exempt) "Ya" else "Belum")
            KeyValueRow("Notifikasi", if (notifications) "Aktif" else "Mati")
            KeyValueRow("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL}")
        }

        if (!exempt) {
            Button(onClick = {
                context.openSafely(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }) { Text("Minta bebas optimasi baterai") }
        }
        OutlinedButton(onClick = {
            context.openSafely(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )
        }) { Text("Buka info aplikasi") }
        if (!notifications) {
            OutlinedButton(onClick = {
                context.openSafely(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
                )
            }) { Text("Buka pengaturan notifikasi") }
        }

        InfoCard(guide.brand) {
            guide.steps.forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Bot tidak berjalan sendiri setelah dihentikan sistem kecuali sesi dry run, atau sesi live bila Anda mengaktifkannya di Setelan > Strategi. " +
                "Lihat docs/runbook.md untuk uji 24 jam.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
