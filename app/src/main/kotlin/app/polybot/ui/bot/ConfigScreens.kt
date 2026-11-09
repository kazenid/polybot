package app.polybot.ui.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import app.polybot.ui.components.SymbolMultiPickerField
import app.polybot.ui.components.SymbolPickerField
import app.polybot.ui.components.selectedBorder
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.domain.config.MarketMakerAdvice
import app.polybot.domain.config.RiskAdvice
import app.polybot.domain.config.RiskConfig
import app.polybot.domain.config.StrategyType
import app.polybot.domain.report.quoteOf
import app.polybot.ui.components.FieldHelp
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.components.PercentField
import androidx.compose.material.icons.rounded.Security
import java.math.BigDecimal
import java.math.RoundingMode
import app.polybot.ui.components.DecimalField
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.IntField
import app.polybot.ui.components.PollWhileStarted
import app.polybot.ui.components.SwitchRow

@Composable
fun StrategyScreen(viewModel: ConfigViewModel = hiltViewModel(), priceViewModel: StrategyPriceViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val price by priceViewModel.price.collectAsStateWithLifecycle()
    val errors = config.validate()
    PollWhileStarted(intervalMs = 15_000) { priceViewModel.refresh(config.symbol) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Perubahan berlaku pada Start berikutnya. Hentikan bot lebih dulu bila sedang berjalan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (errors.isNotEmpty()) ErrorBanner(errors.joinToString("\n") { "• $it" })

        InfoCard("Strategi") {
            StrategyType.entries.forEach { type ->
                StrategyOption(type, selected = config.strategy == type) { viewModel.update { it.copy(strategy = type) } }
            }
        }
        InfoCard("Pasar") {
            SymbolPickerField(
                label = "Simbol yang diperdagangkan",
                selected = config.symbol,
                onSelect = { v -> viewModel.update { it.copy(symbol = v, risk = it.risk.copy(allowedSymbols = it.risk.allowedSymbols + v)) } },
            )
            Text(
                "Simbol yang dipilih otomatis masuk ke daftar yang diizinkan di Setelan > Risiko.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (config.strategy) {
            StrategyType.MARKET_MAKER -> MarketMakerPanel(config, price, viewModel::update)
            StrategyType.GRID -> GridPanel(config, price, viewModel::update)
            StrategyType.DCA -> DcaPanel(config, price, viewModel::update)
        }

        InfoCard("Umum") {
            IntField("Polling saat ada order (detik)", config.pollActiveSec, { v -> viewModel.update { it.copy(pollActiveSec = v) } },
                hint = "3 detik direkomendasikan — makin cepat, makin cepat requote saat harga bergerak")
            IntField("Polling saat idle (detik)", config.pollIdleSec, { v -> viewModel.update { it.copy(pollIdleSec = v) } },
                hint = "15–30 detik cukup saat tidak ada order aktif")
            SwitchRow(
                "Batalkan order saat berhenti, jeda, atau error", config.cancelOnStop,
                { v -> viewModel.update { it.copy(cancelOnStop = v) } },
                description = "Hanya order milik bot (prefix client_order_id).",
            )
            SwitchRow(
                "Pulihkan sesi LIVE otomatis setelah HP restart atau proses dimatikan", config.autoResumeLive,
                { v -> viewModel.update { it.copy(autoResumeLive = v) } },
                description = "Dry run selalu dipulihkan. Live hanya bila ini aktif, sesi terakhir kurang dari 6 jam, dan Anda tidak menekan Stop.",
            )
            SwitchRow(
                "Kill switch membatalkan SEMUA order di pair ini", config.killSwitchCancelsAllOnSymbol,
                { v -> viewModel.update { it.copy(killSwitchCancelsAllOnSymbol = v) } },
                description = "Termasuk order manual Anda. Matikan bila akun dipakai bersama.",
            )
        }
    }
}

@Composable
fun RiskScreen(viewModel: ConfigViewModel = hiltViewModel(), equityViewModel: RiskEquityViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val points by equityViewModel.points.collectAsStateWithLifecycle()
    val r = config.risk
    val errors = config.validate()
    val quote = quoteOf(config.symbol).ifEmpty { "quote" }
    val equity = points.lastOrNull { quoteOf(it.symbol) == quote }?.equity
    val warnings = RiskAdvice.check(r, equity)
    fun money(v: BigDecimal?): String? = v?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
    fun change(block: (RiskConfig) -> RiskConfig) = viewModel.update { it.copy(risk = block(it.risk)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Semua aturan ini diperiksa sebelum setiap order. Persen ditulis sebagai persen. Berlaku pada Start berikutnya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (errors.isNotEmpty()) ErrorBanner(errors.joinToString("\n") { "• $it" })

        InfoCard("Ringkasan batas", icon = Icons.Rounded.Security) {
            val cap = RiskAdvice.orderCap(r, equity)
            val exposure = RiskAdvice.exposureCap(r, equity)
            val lossPct = RiskAdvice.dailyLossPercent(r, equity)
            KeyValueRow("Ekuitas terakhir", equity?.let { "${money(it)} $quote" } ?: "belum terekam")
            KeyValueRow("Order maksimum", "${money(cap)} $quote")
            KeyValueRow(
                "Aset dasar maksimum ditahan",
                exposure?.let { "${money(it)} $quote (${MarketMakerAdvice.plain(r.maxExposurePct.multiply(BigDecimal(100)))}%)" }
                    ?: "${MarketMakerAdvice.plain(r.maxExposurePct.multiply(BigDecimal(100)))}% ekuitas",
            )
            KeyValueRow(
                "Rugi harian maksimum",
                "${MarketMakerAdvice.plain(r.maxDailyLossQuote)} $quote" + (lossPct?.let { " (${MarketMakerAdvice.plain(it)}% ekuitas)" } ?: ""),
            )
            KeyValueRow("Order terbuka / baru per menit", "${r.maxOpenOrders} / ${r.maxNewOrdersPerMinute}")
            if (equity == null) {
                Text(
                    "Ekuitas belum terekam. Jalankan bot sebentar (dry run juga bisa) supaya angka dalam $quote muncul di sini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        InfoCard("Ukuran dan eksposur") {
            DecimalField(
                "Nilai maksimum per order ($quote)", r.maxOrderQuote, { v -> change { it.copy(maxOrderQuote = v) } },
                help = FieldHelp("Nilai maksimum per order", "Batas mutlak nilai satu order. Order yang lebih besar ditolak sebelum dikirim.", example = "10 berarti tidak ada order di atas 10 $quote.", range = "Mulai kecil di testnet."),
            )
            PercentField(
                "Maksimum per order (% ekuitas)", r.maxOrderPctOfEquity, { v -> change { it.copy(maxOrderPctOfEquity = v) } },
                hint = equity?.let { "Sekitar ${money(it.multiply(r.maxOrderPctOfEquity))} $quote dari ekuitas Anda" }, warning = warnings[RiskAdvice.Field.ORDER_PCT],
                help = FieldHelp("Maksimum per order (% ekuitas)", "Batas nilai satu order sebagai persen ekuitas. Yang berlaku adalah yang lebih kecil antara batas ini dan nilai maksimum per order.", example = "2% dari ekuitas 5.000 = 100.", range = "1% sampai 3%."),
            )
            PercentField(
                "Eksposur aset dasar maksimum (% ekuitas)", r.maxExposurePct, { v -> change { it.copy(maxExposurePct = v) } },
                hint = equity?.let { "Sekitar ${money(it.multiply(r.maxExposurePct))} $quote, termasuk order beli yang masih terbuka" } ?: "Termasuk order beli yang masih terbuka",
                warning = warnings[RiskAdvice.Field.EXPOSURE],
                help = FieldHelp("Eksposur aset dasar maksimum", "Seberapa besar nilai aset dasar (mis. BTC) yang boleh ditahan dibanding ekuitas. Order beli yang akan melampauinya ditolak.", example = "15% dari ekuitas 5.000 = 750.", range = "10% sampai 20% untuk memulai."),
            )
            PercentField(
                "Cadangan saldo quote (%)", r.reserveQuotePct, { v -> change { it.copy(reserveQuotePct = v) } },
                hint = "Bagian saldo $quote yang tidak pernah dipakai untuk order",
                help = FieldHelp("Cadangan saldo quote", "Persen saldo $quote yang selalu disisakan dan tidak dipakai bot untuk order beli.", range = "5% sampai 10%."),
            )
        }
        InfoCard("Laju dan harga") {
            IntField(
                "Order terbuka maksimum", r.maxOpenOrders, { v -> change { it.copy(maxOpenOrders = v) } },
                hint = "Juga dibatasi oleh batas server", warning = warnings[RiskAdvice.Field.OPEN_ORDERS],
                help = FieldHelp("Order terbuka maksimum", "Jumlah order bot yang boleh aktif bersamaan. Market Maker memakai 2 per level; Grid memakai satu per level.", example = "Market Maker 1 level butuh 2.", range = "6 sampai 10."),
            )
            IntField(
                "Order baru per menit maksimum", r.maxNewOrdersPerMinute, { v -> change { it.copy(maxNewOrdersPerMinute = v) } },
                help = FieldHelp("Order baru per menit", "Batas pengiriman order baru dalam satu menit, supaya bot yang salah atur tidak membanjiri server.", range = "15 sampai 30."),
            )
            PercentField(
                "Penyimpangan harga maksimum dari harga tengah (%)", r.maxPriceDeviationPct, { v -> change { it.copy(maxPriceDeviationPct = v) } },
                warning = warnings[RiskAdvice.Field.DEVIATION],
                help = FieldHelp("Penyimpangan harga maksimum", "Order yang harganya menyimpang lebih dari ini dari harga tengah ditolak. Menjaga dari harga yang salah ketik atau buku order yang janggal.", example = "2% pada BTC 78.000 = order ditolak jika lebih dari 1.560 dari harga tengah.", range = "1% sampai 3%."),
            )
        }
        InfoCard("Penghentian otomatis") {
            DecimalField(
                "Rugi harian maksimum ($quote)", r.maxDailyLossQuote, { v -> change { it.copy(maxDailyLossQuote = v) } },
                hint = "Bot berhenti dan membatalkan order; hari dihitung UTC",
                warning = warnings[RiskAdvice.Field.DAILY_LOSS],
                help = FieldHelp("Rugi harian maksimum", "Bila ekuitas turun sebesar ini dari awal hari (UTC), bot berhenti total dan membatalkan ordernya. Penurunan harga aset yang dipegang ikut dihitung.", example = "20 $quote: berhenti saat ekuitas turun 20 dari awal hari.", range = "Sekitar 0,2% sampai 1% ekuitas."),
            )
            IntField(
                "Error beruntun sebelum jeda", r.maxConsecutiveErrors, { v -> change { it.copy(maxConsecutiveErrors = v) } },
                warning = warnings[RiskAdvice.Field.ERRORS],
                help = FieldHelp("Error beruntun", "Jumlah siklus gagal berturut-turut (mis. HTTP 503) sebelum bot dijeda dan menunggu Anda menekan Lanjut.", range = "5 sampai 8."),
            )
            IntField(
                "Selisih jam maksimum (ms)", r.maxClockSkewMs.toInt(), { v -> change { it.copy(maxClockSkewMs = v.toLong()) } },
                warning = warnings[RiskAdvice.Field.CLOCK],
                help = FieldHelp("Selisih jam maksimum", "Bila jam HP berbeda dari jam server lebih dari ini, bot dijeda karena tanda tangan permintaan bisa ditolak. Lanjut sendiri setelah jam benar.", example = "3000 ms = 3 detik.", range = "2000 sampai 5000 ms."),
            )
        }
        InfoCard("Simbol yang diizinkan") {
            SymbolMultiPickerField(
                label = "Bot hanya boleh berdagang di simbol ini",
                selected = r.allowedSymbols,
                onChange = { v -> change { it.copy(allowedSymbols = v) } },
            )
        }
    }
}

@Composable
private fun StrategyOption(type: StrategyType, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().selectedBorder(selected, accent),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(type.icon(), null, tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(type.label, style = MaterialTheme.typography.titleSmall)
                Text(type.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = accent)
        }
    }
}

private fun StrategyType.icon(): ImageVector = when (this) {
    StrategyType.MARKET_MAKER -> Icons.Rounded.SwapVert
    StrategyType.GRID -> Icons.Rounded.GridOn
    StrategyType.DCA -> Icons.Rounded.Schedule
}

private fun StrategyType.summary(): String = when (this) {
    StrategyType.MARKET_MAKER -> "Pasang beli dan jual di sekitar harga tengah, ambil selisihnya"
    StrategyType.GRID -> "Beli rendah jual tinggi bertahap dalam satu rentang harga"
    StrategyType.DCA -> "Beli dengan nilai tetap pada interval teratur"
}
