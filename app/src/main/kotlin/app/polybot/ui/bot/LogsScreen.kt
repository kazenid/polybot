package app.polybot.ui.bot

import androidx.compose.foundation.layout.Arrangement
import app.polybot.ui.theme.trade
import app.polybot.ui.components.ScreenHeader
import app.polybot.ui.components.Pill
import app.polybot.ui.components.EmptyState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.core.log.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(viewModel: LogsViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var minLevel by remember { mutableStateOf(LogLevel.INFO) }
    val shown = entries.filter { it.level >= minLevel }.asReversed()
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("Log", "${shown.size} catatan") {
            IconButton(onClick = viewModel::clear) { Icon(Icons.Rounded.DeleteSweep, "Hapus log") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = minLevel == LogLevel.INFO, onClick = { minLevel = LogLevel.INFO }, label = { Text("Semua") })
            FilterChip(selected = minLevel == LogLevel.WARN, onClick = { minLevel = LogLevel.WARN }, label = { Text("Peringatan+") })
            FilterChip(selected = minLevel == LogLevel.ERROR, onClick = { minLevel = LogLevel.ERROR }, label = { Text("Error") })
        }
        if (shown.isEmpty()) {
            EmptyState(Icons.AutoMirrored.Rounded.Notes, "Belum ada catatan", "Jalankan bot dari Setelan > Kontrol Bot.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown) { e ->
                val color = when (e.level) {
                    LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    LogLevel.WARN -> MaterialTheme.trade.warn
                    LogLevel.INFO -> MaterialTheme.colorScheme.outline
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(format.format(Date(e.timeMs)), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Pill(e.category, color)
                        }
                        Text(e.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
