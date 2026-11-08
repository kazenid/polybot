package app.polybot.ui.report

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.domain.report.PnlSummary
import app.polybot.domain.report.money
import app.polybot.domain.report.quoteOf
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.LineChart
import app.polybot.ui.components.StatTile
import app.polybot.ui.components.pnlColor
import app.polybot.ui.theme.BigNumberStyle
import app.polybot.ui.theme.trade
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun EquityCard(viewModel: EquityViewModel = hiltViewModel()) {
    val points by viewModel.points.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    val quote = quoteOf(points.lastOrNull()?.symbol.orEmpty()).ifEmpty { "quote" }
    InfoCard("Ekuitas ($quote)", icon = Icons.Rounded.ShowChart) {
        if (points.size < 2) {
            Text("--", style = BigNumberStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Belum cukup data. Ekuitas direkam tiap menit selama bot berjalan (dry run maupun live).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val first = points.first().equity
            val last = points.last().equity
            val change = last.subtract(first)
            val pct = if (first.signum() != 0) change.multiply(BigDecimal(100)).divide(first, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
            val color = pnlColor(change)
            Text(last.money(), style = BigNumberStyle)
            Text(
                "${if (change.signum() > 0) "+" else ""}${change.money()}  (${if (pct.signum() > 0) "+" else ""}${pct.toPlainString()}%)",
                style = MaterialTheme.typography.titleSmall,
                color = color,
            )
            LineChart(points.map { it.equity.toChartFloat() }, lineColor = color, height = 150.dp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Terendah", points.minOf { it.equity }.money(), Modifier.weight(1f))
                StatTile("Tertinggi", points.maxOf { it.equity }.money(), Modifier.weight(1f))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            EquityRange.entries.forEach { r ->
                FilterChip(selected = selected == r, onClick = { viewModel.select(r) }, label = { Text(r.label) })
            }
        }
    }
}

@Composable
fun KpiCard(viewModel: KpiViewModel = hiltViewModel()) {
    val kpi by viewModel.kpi.collectAsStateWithLifecycle()
    InfoCard("Laporan transaksi", icon = Icons.Rounded.Assessment) {
        val k = kpi
        if (k == null || (k.week.trades == 0)) {
            Text(
                "Belum ada transaksi 7 hari terakhir. Sinkronkan di tab Transaksi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Block("Hari ini", k.today)
            Block("7 hari", k.week)
        }
    }
}

@Composable
private fun Block(title: String, s: PnlSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Transaksi", s.trades.toString(), Modifier.weight(1f))
            StatTile("Volume", s.volumeQuote.money(), Modifier.weight(1f))
            StatTile("PnL bersih", s.net.money(), Modifier.weight(1f), pnlColor(s.net))
        }
        PnlBreakdown(s)
    }
}

/**
 * Rincian laba kotor, fee, dan bersih, plus seberapa besar fee memakan laba kotor. Laba kotor yang lebih kecil dari fee
 * berarti strategi belum punya keunggulan setelah biaya.
 */
@Composable
fun PnlBreakdown(s: PnlSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Laba kotor", s.realized.money(4), Modifier.weight(1f), pnlColor(s.realized))
            StatTile("Fee", s.fees.money(4), Modifier.weight(1f))
            StatTile("Fee / volume", s.feeBps?.let { "${it.toPlainString()} bps" } ?: "-", Modifier.weight(1f))
        }
        if (s.trades > 0) {
            val share = s.feeShareOfGross
            val (text, color) = when {
                s.coversFees -> "Laba kotor menutup fee (fee ${share?.toPlainString() ?: "0"}% dari laba kotor)." to MaterialTheme.trade.up
                share != null -> "Fee memakan ${share.toPlainString()}% laba kotor: selisih harga yang tertangkap belum menutup fee." to MaterialTheme.trade.down
                else -> "Belum ada laba kotor terealisasi; fee berjalan sendiri." to MaterialTheme.trade.down
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}
