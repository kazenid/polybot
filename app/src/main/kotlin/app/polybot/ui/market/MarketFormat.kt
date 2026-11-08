package app.polybot.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.polybot.domain.market.Direction
import app.polybot.domain.market.PriceChange
import app.polybot.ui.theme.trade
import java.math.BigDecimal
import java.math.RoundingMode

/** Harga dengan jumlah desimal sebesar tick pair, mis. tick 0.01 -> 2 desimal. */
fun BigDecimal.priceText(tickSize: BigDecimal): String =
    setScale(tickSize.stripTrailingZeros().scale().coerceAtLeast(0), RoundingMode.HALF_UP).toPlainString()

/** Kuantitas ringkas: maksimal [maxDecimals] desimal, nol di belakang dibuang. */
fun BigDecimal.qtyText(maxDecimals: Int = 4): String {
    val rounded = setScale(minOf(scale().coerceAtLeast(0), maxDecimals), RoundingMode.HALF_UP).stripTrailingZeros()
    return if (rounded.signum() == 0) "0" else rounded.toPlainString()
}

/** Persen perubahan tanpa tanda (arah ditunjukkan ikon), mis. "1.23%". */
fun PriceChange.percentText(): String = percent?.abs()?.toPlainString()?.plus("%") ?: "-"

@Composable
fun directionColor(direction: Direction): Color = when (direction) {
    Direction.UP -> MaterialTheme.trade.up
    Direction.DOWN -> MaterialTheme.trade.down
    Direction.FLAT -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Kapsul perubahan harga: panah hijau ke atas saat naik, panah merah ke bawah saat turun. */
@Composable
fun ChangeBadge(change: PriceChange?, modifier: Modifier = Modifier) {
    if (change?.percent == null) {
        Text("-", modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val color = directionColor(change.direction)
    val icon = when (change.direction) {
        Direction.UP -> Icons.Rounded.ArrowUpward
        Direction.DOWN -> Icons.Rounded.ArrowDownward
        Direction.FLAT -> Icons.Rounded.Remove
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(change.percentText(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}
