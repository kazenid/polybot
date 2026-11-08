package app.polybot.ui.orders

import androidx.compose.foundation.layout.Arrangement
import java.math.RoundingMode
import app.polybot.ui.theme.trade
import app.polybot.ui.components.StatTile
import app.polybot.ui.components.SideBadge
import app.polybot.ui.components.ScreenHeader
import app.polybot.ui.components.Pill
import app.polybot.ui.components.PairIcon
import app.polybot.ui.components.EmptyState
import app.polybot.domain.model.Side
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.data.account.OpenOrderRepository
import app.polybot.domain.model.OpenOrder
import app.polybot.ui.PollingViewModel
import app.polybot.ui.ScreenState
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.components.PollWhileStarted
import app.polybot.ui.components.UpdatedAt
import app.polybot.ui.components.plain
import app.polybot.ui.report.OrderHistoryViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val repository: OpenOrderRepository,
) : PollingViewModel<List<OpenOrder>>() {
    override suspend fun load(): List<OpenOrder> = repository.openOrders()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(open: OrdersViewModel = hiltViewModel(), history: OrderHistoryViewModel = hiltViewModel()) {
    var showHistory by remember { mutableStateOf(false) }
    val openState by open.state.collectAsStateWithLifecycle()
    val historyState by history.state.collectAsStateWithLifecycle()

    if (showHistory) {
        LaunchedEffect(Unit) { history.refresh() }
    } else {
        PollWhileStarted(intervalMs = 10_000) { open.refresh() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("Order", "Order terbuka diperbarui tiap 10 detik") {
            if (showHistory) {
                IconButton(onClick = history::refresh) { Icon(Icons.Rounded.Refresh, "Muat ulang") }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !showHistory, onClick = { showHistory = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Terbuka") }
            SegmentedButton(selected = showHistory, onClick = { showHistory = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Riwayat") }
        }
        OrderList(if (showHistory) historyState else openState, history = showHistory)
    }
}

@Composable
private fun OrderList(state: ScreenState<List<OpenOrder>>, history: Boolean) {
    UpdatedAt(state.updatedAtMs, state.loading)
    state.error?.let { ErrorBanner(it) }

    val orders = state.data.orEmpty()
    if (state.data != null && orders.isEmpty()) {
        EmptyState(
            Icons.AutoMirrored.Rounded.ListAlt,
            if (history) "Belum ada riwayat" else "Tidak ada order terbuka",
            if (history) "Order yang selesai atau dibatalkan akan muncul di sini." else "Order yang dipasang bot akan tampil di sini.",
        )
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(orders, key = { it.orderId }) { o -> OrderCard(o, history) }
    }
}

@Composable
private fun statusColor(status: String): Color {
    val s = status.uppercase()
    return when {
        "FILLED" in s && "PARTIAL" !in s -> MaterialTheme.trade.up
        "PARTIAL" in s -> MaterialTheme.trade.warn
        "REJECT" in s || "EXPIRE" in s -> MaterialTheme.trade.down
        "CANCEL" in s -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondary
    }
}

private fun prettyStatus(status: String): String =
    status.removePrefix("ORDER_STATUS_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun OrderCard(o: OpenOrder, history: Boolean) {
    InfoCard("") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PairIcon(o.symbol, 36.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(o.symbol, style = MaterialTheme.typography.titleSmall)
                    SideBadge(o.side)
                }
                Text(
                    "${o.orderType.removePrefix("ORDER_TYPE_").lowercase()} · ${o.timeInForce.removePrefix("TIME_IN_FORCE_")}${if (o.postOnly) " · post-only" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(prettyStatus(o.status), statusColor(o.status))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Harga", o.price.plain(), Modifier.weight(1f))
            StatTile("Jumlah", o.quantity.plain(), Modifier.weight(1f))
        }
        if (o.quantity.signum() > 0) {
            val fraction = o.filled.divide(o.quantity, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (o.side == Side.SELL) MaterialTheme.trade.down else MaterialTheme.trade.up,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                KeyValueRow("Terisi", "${o.filled.plain()} (${(fraction * 100).toInt()}%)")
            }
        }
        if (!history) KeyValueRow("Sisa", o.remaining.plain())
        if (o.terminalReason.isNotEmpty()) KeyValueRow("Alasan", o.terminalReason.removePrefix("TERMINAL_REASON_").replace('_', ' ').lowercase())
        if (o.clientOrderId.isNotEmpty()) {
            Text(o.clientOrderId, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
        }
    }
}
