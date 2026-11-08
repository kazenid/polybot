package app.polybot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.polybot.domain.model.Candle
import app.polybot.ui.theme.trade
import java.math.BigDecimal

/**
 * Grafik area sederhana (tanpa library). Nilai Float hanya untuk menggambar; angka uang tetap BigDecimal
 * di lapisan atas. Garis datar digambar di tengah.
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    gridColor: Color = MaterialTheme.colorScheme.outlineVariant,
    height: Dp = 140.dp,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 0..2) {
            val y = size.height * i / 2f
            drawLine(gridColor.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = max - min
        val stepX = size.width / (values.size - 1)
        fun yOf(v: Float) = if (span < 1e-9f) size.height / 2 else size.height - (v - min) / span * size.height * 0.84f - size.height * 0.08f

        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            if (i == 0) line.moveTo(x, yOf(v)) else line.lineTo(x, yOf(v))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f))))
        drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(size.width, yOf(values.last())))
    }
}

/** Garis mini tanpa sumbu untuk baris daftar. Garis datar digambar di tengah. */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val span = values.max() - min
        val stepX = size.width / (values.size - 1)
        val pad = 2.dp.toPx()
        fun yOf(v: Float) = if (span < 1e-9f) size.height / 2 else size.height - pad - (v - min) / span * (size.height - 2 * pad)
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, yOf(v)) else path.lineTo(i * stepX, yOf(v)) }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Grafik candlestick (urut lama ke baru) dengan volume di bawah, label harga di kanan, garis harga terakhir,
 * dan kursor: ketuk atau geser untuk memilih candle ([onSelect] menerima indeksnya, null bila tidak ada).
 * Float hanya untuk menggambar; label dibuat lewat [priceLabel] dari BigDecimal.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {},
    height: Dp = 280.dp,
    priceLabel: (BigDecimal) -> String = { it.toPlainString() },
) {
    val up = MaterialTheme.trade.up
    val down = MaterialTheme.trade.down
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cursor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tagStyle = MaterialTheme.typography.labelSmall.copy(color = Color.White)
    val measurer = rememberTextMeasurer()
    val axisWidthDp = 64.dp

    fun indexAt(x: Float, plotWidth: Float): Int? {
        if (candles.isEmpty() || plotWidth <= 0f) return null
        return (x / (plotWidth / candles.size)).toInt().coerceIn(0, candles.size - 1)
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(candles.size) {
                val plot = size.width - axisWidthDp.toPx()
                detectTapGestures(onTap = { o -> onSelect(if (o.x <= plot) indexAt(o.x, plot) else null) })
            }
            .pointerInput(candles.size) {
                val plot = size.width - axisWidthDp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { o -> onSelect(indexAt(o.x.coerceIn(0f, plot), plot)) },
                    onDragEnd = { onSelect(null) },
                    onDragCancel = { onSelect(null) },
                    onHorizontalDrag = { change, _ -> onSelect(indexAt(change.position.x.coerceIn(0f, plot), plot)) },
                )
            },
    ) {
        if (candles.isEmpty()) return@Canvas
        val plotW = size.width - axisWidthDp.toPx()
        val volH = size.height * 0.16f
        val priceH = size.height - volH - 8.dp.toPx()
        val hiRaw = candles.maxOf { it.high }.toFloat()
        val loRaw = candles.minOf { it.low }.toFloat()
        val pad = ((hiRaw - loRaw) * 0.06f).takeIf { it > 0f } ?: (hiRaw * 0.001f).coerceAtLeast(1e-6f)
        val hi = hiRaw + pad
        val lo = loRaw - pad
        fun yOf(v: Float) = (hi - v) / (hi - lo) * priceH

        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (k in 0..3) {
            val value = hi - (hi - lo) * k / 3f
            val y = priceH * k / 3f
            drawLine(grid.copy(alpha = 0.5f), Offset(0f, y), Offset(plotW, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val text = priceLabel(BigDecimal(value.toDouble()))
            val layout = measurer.measure(AnnotatedString(text), labelStyle)
            val ty = (y - layout.size.height / 2f).coerceIn(0f, priceH - layout.size.height)
            drawText(layout, topLeft = Offset(plotW + 6.dp.toPx(), ty))
        }

        val slot = plotW / candles.size
        val body = (slot * 0.7f).coerceAtLeast(1f)
        val maxVol = candles.maxOf { it.volume }.toFloat().takeIf { it > 0f } ?: 1f
        candles.forEachIndexed { i, c ->
            val color = if (c.close >= c.open) up else down
            val cx = (i + 0.5f) * slot
            val o = yOf(c.open.toFloat())
            val cl = yOf(c.close.toFloat())
            drawLine(color, Offset(cx, yOf(c.high.toFloat())), Offset(cx, yOf(c.low.toFloat())), strokeWidth = 1.dp.toPx())
            val top = minOf(o, cl)
            drawRect(color, Offset(cx - body / 2, top), Size(body, maxOf(1.dp.toPx(), kotlin.math.abs(o - cl))))
            val vh = c.volume.toFloat() / maxVol * volH
            drawRect(color.copy(alpha = 0.35f), Offset(cx - body / 2, size.height - vh), Size(body, vh))
        }

        // Garis dan tag harga terakhir.
        val last = candles.last()
        val lastColor = if (last.close >= last.open) up else down
        val ly = yOf(last.close.toFloat())
        drawLine(lastColor, Offset(0f, ly), Offset(plotW, ly), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        val tag = measurer.measure(AnnotatedString(priceLabel(last.close)), tagStyle)
        val tagTop = (ly - tag.size.height / 2f - 2.dp.toPx()).coerceIn(0f, priceH - tag.size.height)
        drawRoundRect(
            lastColor,
            Offset(plotW + 2.dp.toPx(), tagTop),
            Size(size.width - plotW - 2.dp.toPx(), tag.size.height + 4.dp.toPx()),
            CornerRadius(4.dp.toPx()),
        )
        drawText(tag, topLeft = Offset(plotW + 6.dp.toPx(), tagTop + 2.dp.toPx()))

        selected?.takeIf { it in candles.indices }?.let { i ->
            val cx = (i + 0.5f) * slot
            val cy = yOf(candles[i].close.toFloat())
            drawLine(cursor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawLine(cursor, Offset(0f, cy), Offset(plotW, cy), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawCircle(cursor, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
        }
    }
}
