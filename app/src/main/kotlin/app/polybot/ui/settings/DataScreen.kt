package app.polybot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import app.polybot.data.report.RetentionManager
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.report.DataViewModel

@Composable
fun DataScreen(viewModel: DataViewModel = hiltViewModel()) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    var confirmReports by remember { mutableStateOf(false) }

    if (confirmReports) {
        AlertDialog(
            onDismissRequest = { confirmReports = false },
            title = { Text("Hapus riwayat transaksi lokal?") },
            text = {
                Text(
                    "Fill dan snapshot ekuitas di perangkat ini dihapus. Data di server tidak terpengaruh dan bisa ditarik lagi dengan Sinkron. " +
                        "PnL dihitung ulang dari data yang tersisa.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmReports = false; viewModel.clearReports() }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { confirmReports = false }) { Text("Batal") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard("Data di perangkat") {
            KeyValueRow("Fill tersimpan", counts?.fills?.toString() ?: "...")
            KeyValueRow("Snapshot ekuitas", counts?.snapshots?.toString() ?: "...")
            KeyValueRow("Catatan log", counts?.logs?.toString() ?: "...")
        }
        InfoCard("Retensi otomatis") {
            Text("Log: ${RetentionManager.LOG_DAYS} hari", style = MaterialTheme.typography.bodyMedium)
            Text("Snapshot ekuitas: ${RetentionManager.SNAPSHOT_DAYS} hari", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Fill: ${RetentionManager.FILL_DAYS} hari. Lebih panjang dari yang lain karena PnL memakai biaya rata-rata dari seluruh riwayat.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Pemangkasan berjalan setiap aplikasi dibuka.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = viewModel::clearLogs) { Text("Hapus log") }
        OutlinedButton(onClick = { confirmReports = true }) { Text("Hapus riwayat transaksi lokal") }
        Text(
            "Ekspor CSV ada di tab Transaksi. Data order dan kunci API tidak termasuk yang dihapus di sini.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
