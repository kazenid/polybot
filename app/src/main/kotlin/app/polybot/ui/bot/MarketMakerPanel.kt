package app.polybot.ui.bot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.polybot.data.catalog.CatalogRepository
import app.polybot.data.market.MarketRepository
import app.polybot.domain.config.BotConfig
import app.polybot.domain.config.MarketMakerAdvice
import app.polybot.domain.config.MarketMakerAdvice.Field
import app.polybot.domain.config.MarketMakerConfig
import app.polybot.domain.config.MarketMakerProfiles
import app.polybot.domain.config.MmProfile
import app.polybot.domain.config.RoundTrip
import app.polybot.domain.config.Verdict
import app.polybot.ui.components.DecimalField
import app.polybot.ui.components.FieldHelp
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.IntField
import app.polybot.ui.components.NoticeBanner
import app.polybot.ui.components.Pill
import app.polybot.ui.components.SwitchRow
import app.polybot.ui.components.selectedBorder
import app.polybot.ui.market.priceText
import app.polybot.ui.theme.trade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/** Harga tengah pasar untuk contoh angka di layar Strategi; null di [mid] bila belum terbaca. */
data class PriceInfo(val symbol: String, val mid: BigDecimal?, val tickSize: BigDecimal, val quote: String)

@HiltViewModel
class StrategyPriceViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val markets: MarketRepository,
) : ViewModel() {
    private val _price = MutableStateFlow<PriceInfo?>(null)
    val price: StateFlow<PriceInfo?> = _price.asStateFlow()

    /** Gagal membaca harga tidak apa-apa: layar memakai contoh tanpa harga. */
    fun refresh(symbol: String) {
        viewModelScope.launch {
            try {
                val market = catalog.ensure().market(symbol) ?: return@launch
                val mid = MarketRepository.mid(markets.orderBook(market, "DEPTH_1"))
                _price.value = PriceInfo(symbol, mid, market.tickSize, market.quoteAsset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // pertahankan nilai terakhir
            }
        }
    }
}

private const val SLIDER_MIN = 10f
private const val SLIDER_MAX = 80f

/**
 * Konfigurasi Market Maker yang ramah: profil, pengaturan dasar dengan slider, gambaran hasil per putaran setelah
 * fee, ringkasan kalimat, lalu pengaturan lanjutan yang dikelompokkan dan punya petunjuk.
 */
@Composable
fun MarketMakerPanel(config: BotConfig, price: PriceInfo?, update: ((BotConfig) -> BotConfig) -> Unit) {
    val mm = config.marketMaker
    fun change(block: (MarketMakerConfig) -> MarketMakerConfig) = update { it.copy(marketMaker = block(it.marketMaker)) }

    val current = price?.takeIf { it.symbol == config.symbol }
    val quote = current?.quote ?: config.symbol.substringAfter('-', "quote")
    val warnings = MarketMakerAdvice.check(mm)
    val roundTrip = MarketMakerAdvice.roundTrip(mm, current?.mid)
    fun priceText(value: BigDecimal?): String? = value?.let { v -> current?.let { v.priceText(it.tickSize) } }

    ProfileCard(mm, onPick = { profile -> change { MarketMakerProfiles.apply(profile, it) } })

    InfoCard("Dasar", icon = Icons.Rounded.SwapVert) {
        DecimalField(
            "Nilai per order ($quote)", mm.orderQuote, { v -> change { it.copy(orderQuote = v) } },
            help = FieldHelp(
                "Nilai per order",
                "Besar tiap order beli dan jual yang dipasang bot, dalam $quote. Fee dan hasil dihitung sebanding dengan angka ini, jadi memperbesar order memperbesar untung sekaligus rugi.",
                example = "10 $quote berarti tiap order bernilai 10 $quote.",
                range = "Mulai kecil. Tidak boleh melebihi batas per order di Setelan > Risiko.",
            ),
        )
        SpreadSlider(mm, current, warnings[Field.HALF_SPREAD]) { bps -> change { it.copy(halfSpreadBps = bps) } }
    }

    RoundTripCard(roundTrip, mm, quote, ::priceText)

    InfoCard("Ringkasan", icon = Icons.Rounded.Insights) {
        Text(
            MarketMakerAdvice.summary(config, priceText(roundTrip.buyPrice), priceText(roundTrip.sellPrice), quote),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    AdvancedCard(mm, quote, warnings, ::change)
}

@Composable
private fun ProfileCard(mm: MarketMakerConfig, onPick: (MmProfile) -> Unit) {
    val detected = MarketMakerProfiles.detect(mm)
    InfoCard("Profil", icon = Icons.Rounded.Tune) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MmProfile.entries.forEach { profile ->
                val selected = detected == profile
                val accent = MaterialTheme.colorScheme.primary
                Surface(
                    onClick = { onPick(profile) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f).selectedBorder(selected, accent),
                ) {
                    Text(
                        profile.label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp).fillMaxWidth(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        if (detected != null) {
            Text(detected.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Kustom", MaterialTheme.colorScheme.secondary)
                Text(
                    "Ada angka yang Anda ubah sendiri. Pilih profil untuk mengembalikan angka terkait ke nilai profil; nilai order, fee, dan margin tidak ikut berubah.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            "Profil hanya menandai tingkat kehati-hatian, bukan jaminan hasil.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpreadSlider(mm: MarketMakerConfig, price: PriceInfo?, warning: String?, onChange: (BigDecimal) -> Unit) {
    val effective = mm.effectiveHalfSpreadBps
    val floor = mm.makerFeeBps.add(mm.minNetEdgeBps)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jarak kuotasi dari harga tengah", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Pill("${MarketMakerAdvice.plain(effective)} bps", MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = effective.toFloat().coerceIn(SLIDER_MIN, SLIDER_MAX),
            onValueChange = { onChange(BigDecimal(it.toInt())) },
            valueRange = SLIDER_MIN..SLIDER_MAX,
        )
        // Penanda batas aman (fee + margin) di bawah slider.
        val safeFraction = ((floor.toFloat() - SLIDER_MIN) / (SLIDER_MAX - SLIDER_MIN)).coerceIn(0.02f, 0.98f)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text("${SLIDER_MIN.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(safeFraction))
            Text("batas aman ${MarketMakerAdvice.plain(floor)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.trade.down)
            Spacer(Modifier.weight(1f - safeFraction))
            Text("${SLIDER_MAX.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val pct = MarketMakerAdvice.percent(effective)
        val distance = price?.mid?.let { mid ->
            val usd = mid.multiply(effective).divide(BigDecimal(10_000), 0, RoundingMode.HALF_UP)
            ", sekitar ${usd.toPlainString()} ${price.quote} pada harga ${mid.priceText(price.tickSize)}"
        }.orEmpty()
        Text(
            "$pct% dari harga$distance. Makin lebar, makin jarang terisi tetapi makin tebal margin per putaran.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (warning != null) Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.trade.warn)
    }
}

@Composable
private fun RoundTripCard(rt: RoundTrip, mm: MarketMakerConfig, quote: String, format: (BigDecimal?) -> String?) {
    InfoCard("Hasil satu putaran beli lalu jual", icon = Icons.Rounded.Insights) {
        PriceLadder(rt, format(rt.buyPrice), format(rt.sellPrice))
        Text(
            "Selisih beli ke jual dibagi menjadi fee dan sisa bersih",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FeeNetBar(rt.feeBps, rt.netBps)
        val (icon, tint) = verdictStyle(rt.verdict)
        val message = when (rt.verdict) {
            Verdict.GOOD -> "Margin cukup tebal secara hitungan."
            Verdict.THIN -> "Margin tipis: gerak harga kecil saja bisa menghabiskannya."
            Verdict.LOSS -> "Setelah fee, satu putaran paling banter impas. Lebarkan jarak atau naikkan margin."
        }
        NoticeBanner(
            "Teori: ${MarketMakerAdvice.plain(rt.grossBps)} − ${MarketMakerAdvice.plain(rt.feeBps)} = ${MarketMakerAdvice.plain(rt.netBps)} bps, " +
                "sekitar ${MarketMakerAdvice.plain(rt.netQuote.setScale(3, RoundingMode.HALF_UP))} $quote per order ${MarketMakerAdvice.plain(mm.orderQuote)} $quote. $message " +
                "Kenyataannya lebih kecil karena harga sering bergerak melawan order.",
            icon, tint,
        )
    }
}

/** Tangga harga: beli di kiri, harga tengah di tengah, jual di kanan; zona fee diarsir di dalam tiap sisi. */
@Composable
private fun PriceLadder(rt: RoundTrip, buyText: String?, sellText: String?) {
    val up = MaterialTheme.trade.up
    val down = MaterialTheme.trade.down
    val feeZone = MaterialTheme.trade.downSoft
    val line = MaterialTheme.colorScheme.outline
    val mid = MaterialTheme.colorScheme.onSurfaceVariant
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val value = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(84.dp)) {
        val cx = size.width / 2f
        val reach = size.width / 2f - 36.dp.toPx()
        val dist = reach * (rt.halfSpreadBps.toFloat().coerceIn(SLIDER_MIN, SLIDER_MAX) / SLIDER_MAX)
        val xb = cx - dist
        val xs = cx + dist
        val y = 40.dp.toPx()
        drawLine(line, Offset(24.dp.toPx(), y), Offset(size.width - 24.dp.toPx(), y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val feeShare = if (rt.halfSpreadBps.signum() > 0) (rt.feeBps.toFloat() / 2f / rt.halfSpreadBps.toFloat()).coerceIn(0f, 1f) else 0f
        val zone = dist * feeShare
        drawRect(feeZone, Offset(xb, y - 14.dp.toPx()), androidx.compose.ui.geometry.Size(zone, 28.dp.toPx()))
        drawRect(feeZone, Offset(xs - zone, y - 14.dp.toPx()), androidx.compose.ui.geometry.Size(zone, 28.dp.toPx()))
        drawCircle(up, 6.dp.toPx(), Offset(xb, y))
        drawCircle(down, 6.dp.toPx(), Offset(xs, y))
        drawCircle(mid, 5.dp.toPx(), Offset(cx, y))

        fun text(t: String, x: Float, top: Float, style: androidx.compose.ui.text.TextStyle) {
            val layout = measurer.measure(AnnotatedString(t), style)
            drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width), top))
        }
        text("beli", xb, 6.dp.toPx(), label)
        text("tengah", cx, 6.dp.toPx(), label)
        text("jual", xs, 6.dp.toPx(), label)
        text(buyText ?: "-${MarketMakerAdvice.plain(rt.halfSpreadBps)} bps", xb, 62.dp.toPx(), value)
        text(sellText ?: "+${MarketMakerAdvice.plain(rt.halfSpreadBps)} bps", xs, 62.dp.toPx(), value)
    }
}

@Composable
private fun AdvancedCard(mm: MarketMakerConfig, quote: String, warnings: Map<Field, String>, change: ((MarketMakerConfig) -> MarketMakerConfig) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    InfoCard("") {
        Surface(onClick = { open = !open }, color = androidx.compose.ui.graphics.Color.Transparent) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lanjutan", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (warnings.isEmpty()) "Fee, margin, pembaruan order, inventori, tren" else "${warnings.size} peringatan di pengaturan lanjutan",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (warnings.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.trade.warn,
                    )
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (open) {
            GroupTitle("Fee dan margin")
            DecimalField(
                "Fee maker (bps)", mm.makerFeeBps, { v -> change { it.copy(makerFeeBps = v) } },
                hint = "Testnet Polyester: 10 bps per fill", warning = warnings[Field.FEE],
                help = FieldHelp(
                    "Fee maker",
                    "Biaya yang dipotong tiap order Anda terisi. 1 bps = 0,01%. Bot memakai angka ini untuk menghitung batas aman jarak kuotasi dan lantai jual impas. Ini diisi manual, bot tidak membacanya dari server.",
                    example = "10 bps = 0,1%: order 10 USDT terisi kena 0,01 USDT, dan satu putaran beli-jual kena dua kali.",
                    range = "Samakan dengan tarif di akun Anda (testnet: 10).",
                ),
            )
            DecimalField(
                "Margin bersih minimum (bps)", mm.minNetEdgeBps, { v -> change { it.copy(minNetEdgeBps = v) } },
                hint = "Di atas fee, per sisi", warning = warnings[Field.MARGIN],
                help = FieldHelp(
                    "Margin bersih minimum",
                    "Sisa keuntungan minimum per sisi di atas fee. Jarak kuotasi tidak pernah di bawah fee + margin ini, dan lantai jual impas memakainya juga.",
                    example = "Fee 10 + margin 10 = batas aman 20 bps.",
                    range = "5 sampai 15 bps.",
                ),
            )
            SwitchRow(
                "Jual tidak di bawah impas", mm.sellAboveCost,
                { v -> change { it.copy(sellAboveCost = v) } },
                description = "Harga jual tidak pernah di bawah biaya pembelian terbaru yang belum terjual + fee beli dan jual + margin. Saat harga turun, order jual menunggu di lantai itu. Pembelian lama yang mahal tidak ikut memblokir.",
            )

            GroupTitle("Jarak dan level")
            DecimalField(
                "Jarak kuotasi tepat (bps)", mm.halfSpreadBps, { v -> change { it.copy(halfSpreadBps = v) } },
                hint = "Dipakai: ${MarketMakerAdvice.plain(mm.effectiveHalfSpreadBps)} bps", warning = warnings[Field.HALF_SPREAD],
                help = FieldHelp(
                    "Jarak kuotasi",
                    "Jarak order beli ke bawah dan order jual ke atas dari harga tengah, masing-masing satu sisi. Sama dengan slider di atas, tetapi bisa diisi angka pecahan atau di luar 10 sampai 80.",
                    example = "25 bps pada BTC 78.000 berarti beli sekitar 195 di bawah dan jual sekitar 195 di atas.",
                ),
            )
            IntField(
                "Level (1-3)", mm.levels, { v -> change { it.copy(levels = v) } },
                help = FieldHelp("Level", "Berapa lapis order di tiap sisi. Level 2 memasang satu pasang lagi yang lebih jauh dari harga tengah.", range = "1 untuk memulai."),
            )
            DecimalField(
                "Jarak antar level (bps)", mm.levelStepBps, { v -> change { it.copy(levelStepBps = v) } },
                help = FieldHelp("Jarak antar level", "Tambahan jarak tiap level berikutnya dari level sebelumnya. Hanya berpengaruh bila level lebih dari 1."),
            )

            GroupTitle("Pembaruan order")
            DecimalField(
                "Ambang requote (bps)", mm.requoteThresholdBps, { v -> change { it.copy(requoteThresholdBps = v) } },
                hint = "Yang terlalu dekat dibatalkan di ambang ini; yang menjauh di 3x ambang", warning = warnings[Field.REQUOTE],
                help = FieldHelp(
                    "Ambang requote",
                    "Bila harga bergerak sehingga order Anda jadi terlalu dekat ke pasar (mudah tersambar), order dibatalkan dan dipasang ulang setelah bergeser sebesar ini. Order yang menjauh dibiarkan lebih lama, sampai 3 kali ambang, supaya posisi antrean tidak terus hilang.",
                    range = "6 sampai 12 bps, lebih kecil dari jarak kuotasi.",
                ),
            )
            IntField(
                "Umur order maksimum (detik)", mm.maxOrderAgeSec, { v -> change { it.copy(maxOrderAgeSec = v) } },
                warning = warnings[Field.ORDER_AGE],
                help = FieldHelp("Umur order maksimum", "Order yang lebih tua dari ini dibatalkan lalu dipasang ulang di harga terbaru. Pembatalan tidak kena fee.", range = "45 sampai 90 detik."),
            )
            IntField(
                "Cooldown (detik)", mm.cooldownSec, { v -> change { it.copy(cooldownSec = v) } },
                warning = warnings[Field.COOLDOWN],
                help = FieldHelp("Cooldown", "Jeda minimum setelah bot memasang atau membatalkan order sebelum memasang lagi. Mencegah bot terlalu sering menghubungi server.", range = "4 sampai 10 detik."),
            )

            GroupTitle("Inventori")
            DecimalField(
                "Batas inventori (0.5-1)", mm.maxInventoryPct, { v -> change { it.copy(maxInventoryPct = v) } },
                hint = "Bagian dari batas eksposur; beli ditahan di atasnya",
                help = FieldHelp(
                    "Batas inventori",
                    "Seberapa penuh batas eksposur (Setelan > Risiko) sebelum bot berhenti membeli. Makin kecil, bot makin cepat berhenti menumpuk aset.",
                    example = "Eksposur maksimum 15% dan batas inventori 0,8: beli ditahan di 12% ekuitas.",
                    range = "0,6 sampai 0,9.",
                ),
            )
            DecimalField(
                "Faktor skew", mm.skewFactor, { v -> change { it.copy(skewFactor = v) } },
                hint = "Seberapa kuat kuotasi bergeser saat inventori berubah",
                help = FieldHelp(
                    "Faktor skew",
                    "Saat inventori bertambah dari posisi awal sesi, kedua kuotasi digeser turun supaya lebih mudah menjual; saat berkurang, digeser naik. Faktor ini mengatur kuatnya pergeseran, sebagai bagian dari jarak kuotasi.",
                    range = "0,3 sampai 0,6.",
                ),
            )

            GroupTitle("Deteksi tren")
            IntField(
                "Jendela tren (detik)", mm.trendWindowSec, { v -> change { it.copy(trendWindowSec = v) } },
                help = FieldHelp("Jendela tren", "Rentang waktu ke belakang yang dipakai untuk mencari puncak harga. Minimal 30 detik.", range = "180 sampai 600 detik."),
            )
            DecimalField(
                "Tahan beli saat turun (bps)", mm.trendHoldBps, { v -> change { it.copy(trendHoldBps = v) } },
                hint = "Beli ditahan bila inventori sudah bertambah dan harga turun sebesar ini dari puncak jendela", warning = warnings[Field.TREND_HOLD],
                help = FieldHelp(
                    "Tahan beli saat turun",
                    "Bila inventori sudah bertambah sejak sesi dimulai dan harga turun sebesar ini dari puncak jendela tren, bot berhenti memasang order beli sampai kondisi pulih. Sisi jual tetap jalan.",
                    range = "15 sampai 30 bps.",
                ),
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}
