package app.polybot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class CoinStyle(val color: Color, val glyph: String)

private val known = mapOf(
    "BTC" to CoinStyle(Color(0xFFF7931A), "B"),
    "ETH" to CoinStyle(Color(0xFF627EEA), "Ξ"),
    "USDT" to CoinStyle(Color(0xFF26A17B), "₮"),
    "USDC" to CoinStyle(Color(0xFF2775CA), "$"),
    "SOL" to CoinStyle(Color(0xFF9945FF), "S"),
    "BNB" to CoinStyle(Color(0xFFF3BA2F), "B"),
    "XRP" to CoinStyle(Color(0xFF23292F), "X"),
    "DOGE" to CoinStyle(Color(0xFFC2A633), "D"),
    "ADA" to CoinStyle(Color(0xFF0033AD), "A"),
    "AVAX" to CoinStyle(Color(0xFFE84142), "A"),
    "DOT" to CoinStyle(Color(0xFFE6007A), "D"),
    "LINK" to CoinStyle(Color(0xFF2A5ADA), "L"),
    "MATIC" to CoinStyle(Color(0xFF8247E5), "M"),
    "LTC" to CoinStyle(Color(0xFF345D9D), "Ł"),
    "TRX" to CoinStyle(Color(0xFFEB0029), "T"),
    "USD" to CoinStyle(Color(0xFF3FA66B), "$"),
    "IDR" to CoinStyle(Color(0xFFD7263D), "R"),
)

private val fallbackPalette = listOf(
    Color(0xFF5B8CFF), Color(0xFF0ECB81), Color(0xFFF0B90B), Color(0xFFB36BFF), Color(0xFFFF7A59), Color(0xFF2BB5D8),
)

private fun styleFor(asset: String): CoinStyle {
    val key = asset.uppercase()
    known[key]?.let { return it }
    val color = fallbackPalette[(key.hashCode() and Int.MAX_VALUE) % fallbackPalette.size]
    return CoinStyle(color, key.take(1).ifEmpty { "?" })
}

/** Ikon koin bulat berwarna merek, digambar lokal (tanpa jaringan). */
@Composable
fun CoinIcon(asset: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val style = styleFor(asset)
    Box(
        modifier.size(size).clip(CircleShape).background(style.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            style.glyph,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.46f).sp,
        )
    }
}

/** Dua koin bertumpuk: aset dasar di depan, aset quote di belakang kanan bawah. */
@Composable
fun PairIcon(symbol: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val parts = symbol.split('-', '/', '_')
    val base = parts.getOrNull(0).orEmpty()
    val quote = parts.getOrNull(1)
    val small = size * 0.62f
    Box(modifier.size(width = size + small * 0.7f, height = size)) {
        if (quote != null) {
            CoinIcon(quote, small, Modifier.align(Alignment.BottomEnd))
        }
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) { CoinIcon(base, size - 3.dp) }
    }
}

/** Simbol pasangan dengan ikon di sampingnya, mis. untuk baris daftar. */
@Composable
fun PairLabel(symbol: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PairIcon(symbol, 28.dp)
        Text(symbol, Modifier.offset(x = 6.dp), style = MaterialTheme.typography.titleSmall)
    }
}
