package app.polybot.ui.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.polybot.domain.config.MarketMakerAdvice
import app.polybot.domain.config.Verdict
import app.polybot.ui.theme.trade
import java.math.BigDecimal

/** Ikon dan warna untuk penilaian hasil per putaran: hijau cukup, kuning tipis, merah rugi. */
@Composable
internal fun verdictStyle(verdict: Verdict): Pair<ImageVector, Color> = when (verdict) {
    Verdict.GOOD -> Icons.Rounded.CheckCircle to MaterialTheme.trade.up
    Verdict.THIN -> Icons.Rounded.WarningAmber to MaterialTheme.trade.warn
    Verdict.LOSS -> Icons.Rounded.ErrorOutline to MaterialTheme.trade.down
}

/** Bar selisih beli ke jual yang dibagi menjadi bagian fee (merah) dan sisa bersih (hijau, kuning bila nol). */
@Composable
internal fun FeeNetBar(feeBps: BigDecimal, netBps: BigDecimal) {
    val feeShare = feeBps.toFloat().coerceAtLeast(0.01f)
    val netShare = netBps.toFloat().coerceAtLeast(0.01f)
    Row(Modifier.fillMaxWidth().height(22.dp).clip(MaterialTheme.shapes.small)) {
        Box(Modifier.weight(feeShare).height(22.dp).background(MaterialTheme.trade.down.copy(alpha = 0.75f)))
        Box(Modifier.weight(netShare).height(22.dp).background(if (netBps.signum() > 0) MaterialTheme.trade.up else MaterialTheme.trade.warn))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("fee ${MarketMakerAdvice.plain(feeBps)} bps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("bersih ${MarketMakerAdvice.plain(netBps)} bps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
