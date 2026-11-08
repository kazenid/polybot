package app.polybot.ui.bot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.polybot.domain.config.BotConfig
import app.polybot.domain.config.DcaAdvice
import app.polybot.domain.config.GridAdvice
import app.polybot.domain.config.GridConfig
import app.polybot.domain.config.GridPlan
import app.polybot.domain.config.MarketMakerAdvice
import app.polybot.domain.config.RangePosition
import app.polybot.ui.components.DecimalField
import app.polybot.ui.components.FieldHelp
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.IntField
import app.polybot.ui.components.NoticeBanner
import app.polybot.ui.components.Pill
import app.polybot.ui.market.priceText
import app.polybot.ui.theme.trade
import java.math.BigDecimal
import java.math.RoundingMode

private const val GRID_SLIDER_MIN = 2f
private const val GRID_SLIDER_MAX = 40f

/**
 * Konfigurasi Grid yang ramah: rentang bisa diisi dari harga sekarang, gambar rentang dengan level dan zona berhenti,
 * hasil bersih satu siklus setelah fee, kebutuhan modal, dan ringkasan kalimat.
 */
@Composable
fun GridPanel(config: BotConfig, price: PriceInfo?, update: ((BotConfig) -> BotConfig) -> Unit) {
    val g = config.grid
    fun change(block: (GridConfig) -> GridConfig) = update { it.copy(grid = block(it.grid)) }

    val current = price?.takeIf { it.symbol == config.symbol }
    val mid = current?.mid
    val quote = current?.quote ?: config.symbol.substringAfter('-', "quote")
    val fee = config.marketMaker.makerFeeBps
    val plan = GridAdvice.plan(g, fee, mid)
    val warnings = GridAdvice.check(g, config.risk, plan)
    fun fmt(v: BigDecimal): String = current?.let { v.priceText(it.tickSize) } ?: MarketMakerAdvice.plain(v.setScale(2, RoundingMode.HALF_UP))

    InfoCard("Rentang harga", icon = Icons.Rounded.GridOn) {
        Text(
            "Grid beli dan jual bertahap di dalam satu rentang harga. Pilih rentang dari harga sekarang, atau isi batasnya sendiri.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("0.02" to "±2%", "0.05" to "±5%", "0.10" to "±10%").forEach { (pct, label) ->
                FilterChip(
                    selected = false,
                    enabled = mid != null && current != null,
                    onClick = {
                        if (mid != null && current != null) {
                            val (lo, up) = GridAdvice.rangeAround(mid, BigDecimal(pct), current.tickSize)
                            change { it.copy(lower = lo, upper = up) }
                        }
                    },
                    label = { Text(label) },
                )
            }
            if (mid == null) Text("menunggu harga pasar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DecimalField(
            "Batas bawah ($quote)", g.lower, { v -> change { it.copy(lower = v) } }, warning = warnings[GridAdvice.Field.RANGE],
            help = FieldHelp("Batas bawah", "Harga terendah grid. Level beli tersebar dari sini naik ke harga sekarang.", example = "76000 untuk BTC di sekitar 78000.", range = "Sedikit di bawah area yang menurut Anda masih wajar dicapai harga."),
        )
        DecimalField(
            "Batas atas ($quote)", g.upper, { v -> change { it.copy(upper = v) } },
            help = FieldHelp("Batas atas", "Harga tertinggi grid. Level jual tersebar dari harga sekarang sampai sini.", range = "Sedikit di atas area yang menurut Anda masih wajar dicapai harga."),
        )
        if (plan != null) {
            GridRangeBar(g, plan, mid, mid?.let(::fmt))
            RangeStatus(GridAdvice.position(g, mid))
        } else {
            Text(
                "Isi batas bawah dan atas untuk melihat gambaran grid.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    InfoCard("Level dan ukuran", icon = Icons.Rounded.GridOn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jumlah bagian grid", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Pill("${g.gridCount} bagian", MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = g.gridCount.toFloat().coerceIn(GRID_SLIDER_MIN, GRID_SLIDER_MAX),
            onValueChange = { v -> change { it.copy(gridCount = v.toInt()) } },
            valueRange = GRID_SLIDER_MIN..GRID_SLIDER_MAX,
        )
        Text(
            warnings[GridAdvice.Field.GRID_COUNT] ?: "Makin banyak bagian, makin rapat jaraknya dan makin tipis laba per siklus setelah fee.",
            style = MaterialTheme.typography.bodySmall,
            color = if (warnings.containsKey(GridAdvice.Field.GRID_COUNT)) MaterialTheme.trade.warn else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DecimalField(
            "Nilai per order ($quote)", g.orderQuote, { v -> change { it.copy(orderQuote = v) } },
            help = FieldHelp("Nilai per order", "Besar tiap order di tiap level, dalam $quote. Tidak boleh melebihi batas per order di Setelan > Risiko.", range = "Mulai kecil, mis. 10."),
        )
        DecimalField(
            "Fee maker (bps)", fee, { v -> update { it.copy(marketMaker = it.marketMaker.copy(makerFeeBps = v)) } },
            hint = "Dipakai bersama Market Maker. Testnet Polyester: 10 bps",
            help = FieldHelp("Fee maker", "Biaya tiap order terisi, 1 bps = 0,01%. Dipakai untuk menghitung laba bersih satu siklus. Diisi manual; bot tidak membacanya dari server.", range = "Samakan dengan tarif di akun Anda."),
        )
        DecimalField(
            "Jarak keluar rentang (bps)", g.rangeBreakBps, { v -> change { it.copy(rangeBreakBps = v) } },
            hint = "Di luar jarak ini bot berhenti dan membatalkan order", warning = warnings[GridAdvice.Field.BREAK],
            help = FieldHelp(
                "Jarak keluar rentang",
                "Bila harga keluar dari rentang lebih dari jarak ini, bot berhenti dan membatalkan ordernya, supaya tidak terus menjual atau membeli saat tren sudah pergi. Di dalam jarak ini bot tetap jalan tetapi hanya satu sisi yang punya level.",
                example = "100 bps = 1%: batas bawah 76000 berarti berhenti di bawah 75240.",
                range = "50 sampai 200 bps.",
            ),
        )
    }

    if (plan != null) {
        InfoCard("Hasil satu siklus", icon = Icons.Rounded.Insights) {
            Text(
                "Beli di satu level lalu jual di level berikutnya: jarak ${MarketMakerAdvice.plain(plan.stepBps)} bps dikurangi fee dua kali ${MarketMakerAdvice.plain(plan.feeBps)} bps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FeeNetBar(plan.feeBps, plan.netBps)
            val (icon, tint) = verdictStyle(plan.verdict)
            val message = when (plan.verdict) {
                app.polybot.domain.config.Verdict.GOOD -> "Jarak antar level cukup tebal secara hitungan."
                app.polybot.domain.config.Verdict.THIN -> "Margin tipis: gerak harga kecil saja bisa menghabiskannya."
                app.polybot.domain.config.Verdict.LOSS -> "Jarak antar level tidak menutup fee. Kurangi jumlah bagian atau lebarkan rentang."
            }
            val netQuote = g.orderQuote.multiply(plan.netBps).divide(BigDecimal(10_000), 3, RoundingMode.HALF_UP)
            NoticeBanner(
                "Teori: ${MarketMakerAdvice.plain(plan.netBps)} bps bersih, sekitar ${MarketMakerAdvice.plain(netQuote)} $quote per siklus order ${MarketMakerAdvice.plain(g.orderQuote)} $quote. $message " +
                    "Kenyataannya lebih kecil bila harga bergerak searah dan menembus beberapa level.",
                icon, tint,
            )
            if (mid != null) {
                Text(
                    "Saat ini ${plan.buyLevels} level beli di bawah harga butuh sekitar ${MarketMakerAdvice.plain(plan.quoteNeeded)} $quote. " +
                        "${plan.sellLevels} level jual di atas harga hanya dipasang bila Anda punya aset dasar senilai sekitar ${MarketMakerAdvice.plain(plan.baseValueNeeded)} $quote.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        InfoCard("Ringkasan", icon = Icons.Rounded.Insights) {
            Text(GridAdvice.summary(g, plan, quote, ::fmt), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RangeStatus(position: RangePosition) {
    val (text, tint) = when (position) {
        RangePosition.INSIDE -> "Harga sekarang di dalam rentang." to MaterialTheme.trade.up
        RangePosition.OUTSIDE_RUNNING -> "Harga sedikit di luar rentang: bot tetap jalan, tetapi hanya satu sisi yang punya level." to MaterialTheme.trade.warn
        RangePosition.HALT -> "Harga jauh di luar rentang: bot akan langsung berhenti saat dijalankan. Geser rentang ke harga sekarang." to MaterialTheme.trade.down
        RangePosition.UNKNOWN -> return
    }
    NoticeBanner(text, if (position == RangePosition.INSIDE) Icons.Rounded.Insights else Icons.Rounded.WarningAmber, tint)
}

/** Sumbu harga: rentang grid dengan level, zona luar rentang (kuning) dan zona berhenti (merah), serta penanda harga. */
@Composable
private fun GridRangeBar(grid: GridConfig, plan: GridPlan, price: BigDecimal?, priceLabel: String?) {
    val up = MaterialTheme.trade.up
    val down = MaterialTheme.trade.down
    val haltZone = MaterialTheme.trade.downSoft
    val runZone = MaterialTheme.trade.warnSoft
    val rangeZone = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val neutral = MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.onSurface
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val labelStrong = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        val lo = grid.lower.toFloat()
        val hi = grid.upper.toFloat()
        val brk = grid.rangeBreakBps.toFloat() / 10_000f
        val breakLow = lo * (1f - brk)
        val breakHigh = hi * (1f + brk)
        val p = price?.toFloat()
        val span = hi - lo
        val axisMin = minOf(breakLow, p ?: breakLow) - span * 0.05f
        val axisMax = maxOf(breakHigh, p ?: breakHigh) + span * 0.05f
        val left = 12.dp.toPx()
        val width = size.width - 24.dp.toPx()
        fun x(v: Float) = left + (v - axisMin) / (axisMax - axisMin) * width
        val top = 32.dp.toPx()
        val h = 28.dp.toPx()

        fun zone(a: Float, b: Float, color: androidx.compose.ui.graphics.Color) {
            val xa = x(a)
            val xb = x(b)
            if (xb > xa) drawRect(color, Offset(xa, top), Size(xb - xa, h))
        }
        zone(axisMin, breakLow, haltZone)
        zone(breakLow, lo, runZone)
        zone(lo, hi, rangeZone)
        zone(hi, breakHigh, runZone)
        zone(breakHigh, axisMax, haltZone)

        plan.levels.forEach { level ->
            val color = when {
                price == null -> neutral
                level < price -> up
                level > price -> down
                else -> neutral
            }
            drawLine(color, Offset(x(level.toFloat()), top - 4.dp.toPx()), Offset(x(level.toFloat()), top + h + 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }

        fun text(t: String, cx: Float, y: Float, style: TextStyle) {
            val layout = measurer.measure(AnnotatedString(t), style)
            drawText(layout, topLeft = Offset((cx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width), y))
        }
        text(MarketMakerAdvice.plain(grid.lower.setScale(0, RoundingMode.HALF_UP)), x(lo), top + h + 8.dp.toPx(), label)
        text(MarketMakerAdvice.plain(grid.upper.setScale(0, RoundingMode.HALF_UP)), x(hi), top + h + 8.dp.toPx(), label)
        if (p != null) {
            val px = x(p).coerceIn(left, left + width)
            drawLine(marker, Offset(px, top - 12.dp.toPx()), Offset(px, top + h + 12.dp.toPx()), strokeWidth = 2.dp.toPx())
            text(priceLabel?.let { "harga $it" } ?: "harga", px, 2.dp.toPx(), labelStrong)
        }
    }
    Text(
        "Garis hijau level beli, merah level jual. Biru muda rentang grid, kuning di luar rentang (bot jalan, satu sisi), merah muda zona bot berhenti.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val DCA_PRESETS = listOf(60 to "1 mnt", 900 to "15 mnt", 3_600 to "1 jam", 14_400 to "4 jam", 86_400 to "1 hari")
private const val DCA_DOTS = 24

/** Konfigurasi DCA yang ramah: pilihan interval, deretan pembelian, estimasi fee, dan ringkasan kalimat. */
@Composable
fun DcaPanel(config: BotConfig, price: PriceInfo?, update: ((BotConfig) -> BotConfig) -> Unit) {
    val d = config.dca
    fun change(block: (app.polybot.domain.config.DcaConfig) -> app.polybot.domain.config.DcaConfig) = update { it.copy(dca = block(it.dca)) }
    val current = price?.takeIf { it.symbol == config.symbol }
    val quote = current?.quote ?: config.symbol.substringAfter('-', "quote")
    val fee = config.marketMaker.makerFeeBps
    val plan = DcaAdvice.plan(d, fee)
    val warnings = DcaAdvice.check(d, plan)

    InfoCard("Jadwal pembelian", icon = Icons.Rounded.Schedule) {
        Text(
            "DCA membeli dengan nilai tetap pada interval teratur dan tidak pernah menjual.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DCA_PRESETS.forEach { (seconds, label) ->
                FilterChip(selected = d.intervalSec == seconds, onClick = { change { it.copy(intervalSec = seconds) } }, label = { Text(label) })
            }
        }
        IntField(
            "Interval (detik)", d.intervalSec, { v -> change { it.copy(intervalSec = v) } },
            hint = "Setara ${DcaAdvice.duration(d.intervalSec.toLong())}", warning = warnings[DcaAdvice.Field.INTERVAL],
            help = FieldHelp("Interval", "Jeda antar pembelian. Pembelian pertama dilakukan segera setelah bot dimulai. Minimal 10 detik.", example = "3600 detik = 1 jam.", range = "Dari 15 menit sampai 1 hari untuk DCA sungguhan."),
        )
        DecimalField(
            "Nilai per pembelian ($quote)", d.orderQuote, { v -> change { it.copy(orderQuote = v) } },
            help = FieldHelp("Nilai per pembelian", "Besar tiap pembelian dalam $quote. Tidak boleh melebihi batas per order di Setelan > Risiko.", range = "Mulai kecil, mis. 10."),
        )
        DecimalField(
            "Total anggaran ($quote)", d.maxTotalQuote, { v -> change { it.copy(maxTotalQuote = v) } }, warning = warnings[DcaAdvice.Field.TOTAL],
            help = FieldHelp(
                "Total anggaran",
                "Batas belanja seluruh DCA. Setelah anggaran habis bot berhenti sendiri. Jumlah pembelian = total dibagi nilai per pembelian, dibulatkan ke bawah.",
                example = "Total 100 dan 10 per pembelian = 10 pembelian.",
            ),
        )
    }

    if (plan != null) {
        InfoCard("Gambaran", icon = Icons.Rounded.Insights) {
            Text("${plan.purchases} pembelian, selesai sekitar ${DcaAdvice.duration(plan.durationSec)}", style = MaterialTheme.typography.titleSmall)
            PurchaseDots(plan.purchases)
            Text(
                "Total ${MarketMakerAdvice.plain(plan.totalSpend)} $quote. Estimasi fee sekitar ${MarketMakerAdvice.plain(plan.estimatedFee.setScale(3, RoundingMode.HALF_UP))} $quote " +
                    "(${MarketMakerAdvice.plain(fee)} bps per pembelian).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NoticeBanner(
                "Pembelian ditolak bila eksposur aset dasar sudah melewati batas di Setelan > Risiko. DCA tidak menjual, jadi hasilnya mengikuti harga aset sampai Anda menjualnya sendiri.",
                Icons.Rounded.WarningAmber, MaterialTheme.trade.warn,
            )
        }
        InfoCard("Ringkasan", icon = Icons.Rounded.Insights) {
            Text(DcaAdvice.summary(d, plan, quote), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PurchaseDots(count: Int) {
    val shown = count.coerceAtMost(DCA_DOTS)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(shown) {
            Box(Modifier.size(width = 8.dp, height = 22.dp).clip(MaterialTheme.shapes.extraSmall).background(MaterialTheme.colorScheme.primary))
        }
        if (count > shown) {
            Text("+${count - shown}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
    }
}
