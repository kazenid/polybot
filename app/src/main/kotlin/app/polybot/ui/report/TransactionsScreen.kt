package app.polybot.ui.report

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import app.polybot.ui.theme.BigNumberStyle
import app.polybot.ui.components.pnlColor
import app.polybot.ui.components.StatTile
import app.polybot.ui.components.SideBadge
import app.polybot.ui.components.ScreenHeader
import app.polybot.ui.components.Pill
import app.polybot.ui.components.PairIcon
import app.polybot.ui.components.EmptyState
import app.polybot.ui.components.CoinIcon
import androidx.compose.ui.Alignment
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.domain.model.Side
import app.polybot.domain.report.money
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.components.plain
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TransactionsScreen(viewModel: TransactionsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val equityChange by viewModel.equityChange.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(Unit) { viewModel.sync() }
    LaunchedEffect(Unit) {
        viewModel.exports.collect { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Ekspor transaksi"))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("Transaksi", "Fill dari akun Anda") {
            IconButton(onClick = viewModel::sync, enabled = !status.syncing) { Icon(Icons.Rounded.Sync, "Sinkron") }
            IconButton(onClick = viewModel::export) { Icon(Icons.Rounded.IosShare, "Ekspor CSV") }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeFilter.entries.forEach { r ->
                FilterChip(selected = ui.filter.range == r, onClick = { viewModel.setRange(r) }, label = { Text(r.label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ui.filter.side == null, onClick = { viewModel.setSide(null) }, label = { Text("Semua") })
            FilterChip(selected = ui.filter.side == Side.BUY, onClick = { viewModel.setSide(Side.BUY) }, label = { Text("Beli") })
            FilterChip(selected = ui.filter.side == Side.SELL, onClick = { viewModel.setSide(Side.SELL) }, label = { Text("Jual") })
            FilterChip(selected = ui.filter.symbol == null, onClick = { viewModel.setSymbol(null) }, label = { Text("Semua simbol") })
            ui.symbols.forEach { sym ->
                FilterChip(
                    selected = ui.filter.symbol == sym,
                    onClick = { viewModel.setSymbol(sym) },
                    label = { Text(sym) },
                    leadingIcon = { CoinIcon(sym.substringBefore('-'), 18.dp) },
                )
            }
        }
        if (status.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        status.error?.let { ErrorBanner(it) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                InfoCard("Ringkasan (${ui.filter.range.label})") {
                    Text(ui.summary.net.money(), style = BigNumberStyle, color = pnlColor(ui.summary.net))
                    Text("PnL bersih", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Transaksi", ui.summary.trades.toString(), Modifier.weight(1f))
                        StatTile("Volume", ui.summary.volumeQuote.money(), Modifier.weight(1f))
                    }
                    PnlBreakdown(ui.summary)
                    equityChange?.let { change ->
                        KeyValueRow("Perubahan ekuitas saat bot jalan", "${if (change.signum() > 0) "+" else ""}${change.money()}", pnlColor(change))
                        Text(
                            "Laba kotor dihitung per lot, pembelian terbaru dulu, jadi lot lama yang mahal tidak menyeret putaran baru. " +
                                "Perubahan ekuitas termasuk naik turunnya harga aset yang dipegang; saat bot mati tidak dihitung.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ui.positions.filter { it.qty.signum() > 0 }.forEach { p ->
                        KeyValueRow("Posisi ${p.symbol}", "${p.qty.plain()} @ ${p.avgCost.money(2)}")
                        p.newestLotCost?.let { KeyValueRow("Lot terbaru ${p.symbol}", "@ ${it.money(2)} (${p.lots.size} lot)") }
                    }
                    status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (ui.rows.isEmpty()) {
                item {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.ReceiptLong,
                        "Belum ada transaksi",
                        "Fill muncul setelah order bot terisi. Tekan ikon sinkron untuk menarik yang terbaru.",
                    )
                }
            } else if (ui.matching > ui.rows.size) {
                item {
                    Text(
                        "Menampilkan ${ui.rows.size} terbaru dari ${ui.matching}. Ekspor CSV memuat semuanya.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.rows, key = { "${it.fill.matchId}-${it.fill.orderId}" }) { r ->
                val f = r.fill
                InfoCard("") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PairIcon(f.symbol, 34.dp)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(f.symbol, style = MaterialTheme.typography.titleSmall)
                                SideBadge(f.side)
                                Pill(if (f.isMaker) "maker" else "taker", MaterialTheme.colorScheme.secondary)
                            }
                            Text(timeFormat.format(Date(f.tsMs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (f.side == Side.SELL) {
                            Text(r.realized.money(4), style = MaterialTheme.typography.titleSmall, color = pnlColor(r.realized))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Harga", f.price.plain(), Modifier.weight(1f))
                        StatTile("Jumlah", f.qty.plain(), Modifier.weight(1f))
                        StatTile("Nilai", f.notional.money(2), Modifier.weight(1f))
                    }
                    KeyValueRow("Fee", f.feeQuote.money(6))
                }
            }
        }
    }
}
