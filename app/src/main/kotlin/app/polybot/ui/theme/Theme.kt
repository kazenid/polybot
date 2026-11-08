package app.polybot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** Warna khusus perdagangan yang tidak ada di skema Material: naik/turun, peringatan, dan aksen pink. */
@Immutable
data class TradeColors(
    val up: Color,
    val down: Color,
    val warn: Color,
    val accent: Color,
    val upSoft: Color,
    val downSoft: Color,
    val warnSoft: Color,
)

private val DarkTrade = TradeColors(
    up = Color(0xFF0ECB81), down = Color(0xFFF6465D), warn = Color(0xFFF0B90B), accent = Color(0xFFFF5FA2),
    upSoft = Color(0x260ECB81), downSoft = Color(0x26F6465D), warnSoft = Color(0x26F0B90B),
)
private val LightTrade = TradeColors(
    up = Color(0xFF02A56B), down = Color(0xFFD9304A), warn = Color(0xFFB58100), accent = Color(0xFFC2185B),
    upSoft = Color(0x1F02A56B), downSoft = Color(0x1FD9304A), warnSoft = Color(0x1FB58100),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF5FA2),
    onPrimary = Color(0xFF2E0017),
    primaryContainer = Color(0xFF4F1233),
    onPrimaryContainer = Color(0xFFFFD6E7),
    secondary = Color(0xFF5B8CFF),
    onSecondary = Color(0xFF00133A),
    secondaryContainer = Color(0xFF1B2B54),
    onSecondaryContainer = Color(0xFFD8E2FF),
    tertiary = Color(0xFF0ECB81),
    onTertiary = Color(0xFF00210F),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFEAECEF),
    surface = Color(0xFF0B0E11),
    onSurface = Color(0xFFEAECEF),
    surfaceVariant = Color(0xFF1E2329),
    onSurfaceVariant = Color(0xFF9AA3AE),
    surfaceContainerLowest = Color(0xFF080A0D),
    surfaceContainerLow = Color(0xFF12161A),
    surfaceContainer = Color(0xFF181C22),
    surfaceContainerHigh = Color(0xFF1E2329),
    surfaceContainerHighest = Color(0xFF262C34),
    outline = Color(0xFF47505B),
    outlineVariant = Color(0xFF2B3139),
    error = Color(0xFFF6465D),
    onError = Color(0xFF3A0008),
    errorContainer = Color(0xFF4A1620),
    onErrorContainer = Color(0xFFFFD9DE),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFC2185B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E6),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF2F5FD8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE5FF),
    onSecondaryContainer = Color(0xFF00164D),
    tertiary = Color(0xFF02A56B),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF14181D),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF14181D),
    surfaceVariant = Color(0xFFE6E9ED),
    onSurfaceVariant = Color(0xFF5A6572),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF0F3),
    surfaceContainerHighest = Color(0xFFE6E9ED),
    outline = Color(0xFF8A94A0),
    outlineVariant = Color(0xFFDCE0E5),
    error = Color(0xFFD9304A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDADF),
    onErrorContainer = Color(0xFF410008),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val Base = Typography()
private val AppTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(letterSpacing = 0.3.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 20.sp),
    bodySmall = Base.bodySmall.copy(lineHeight = 16.sp),
)

/** Gaya angka besar untuk nilai ekuitas dan saldo. */
val BigNumberStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)

private val LocalTradeColors = staticCompositionLocalOf { DarkTrade }

val MaterialTheme.trade: TradeColors
    @Composable @ReadOnlyComposable get() = LocalTradeColors.current

/** Tema tetap bergaya aplikasi trading (tanpa warna dinamis sistem) supaya warna naik/turun konsisten. */
@Composable
fun PolyBotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalTradeColors provides if (dark) DarkTrade else LightTrade) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
