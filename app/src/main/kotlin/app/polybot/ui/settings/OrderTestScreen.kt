package app.polybot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polybot.core.result.userMessage
import app.polybot.data.api.services.MarketDataApi
import app.polybot.data.catalog.CatalogRepository
import app.polybot.domain.engine.CancelOutcome
import app.polybot.domain.engine.OrderManager
import app.polybot.domain.engine.OrderStore
import app.polybot.domain.engine.OrphanPolicy
import app.polybot.domain.engine.Reconciler
import app.polybot.domain.engine.SubmitOutcome
import app.polybot.domain.model.LocalOrder
import app.polybot.domain.model.Market
import app.polybot.domain.model.Side
import app.polybot.domain.numeric.Numeric
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.plain
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class TestPlan(val market: Market, val price: BigDecimal, val qty: BigDecimal) {
    val notional: BigDecimal get() = price.multiply(qty)
    val summary: String get() = "BUY ${market.symbol} ${qty.plain()} @ ${price.plain()} (±${notional.setScale(2, java.math.RoundingMode.HALF_UP).plain()} ${market.quoteAsset}), post-only GTC"
}

data class OrderTestState(
    val symbol: String = "BTC-USDT",
    val plan: TestPlan? = null,
    val lastClientOrderId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val log: List<String> = emptyList(),
    val recent: List<LocalOrder> = emptyList(),
)

/**
 * Uji manual Fase 3: satu order BUY kecil, post-only, 1% di bawah best bid (tidak akan tereksekusi),
 * lalu batalkan dan rekonsiliasi. Order hanya dikirim setelah pengguna menyetujui dialog konfirmasi.
 */
@HiltViewModel
class OrderTestViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val marketData: MarketDataApi,
    private val manager: OrderManager,
    private val reconciler: Reconciler,
    private val store: OrderStore,
) : ViewModel() {
    private val _state = MutableStateFlow(OrderTestState())
    val state: StateFlow<OrderTestState> = _state.asStateFlow()

    init { refreshRecent() }

    fun onSymbol(value: String) = _state.update { it.copy(symbol = value.trim().uppercase(), plan = null) }

    fun prepare() = run("Menyiapkan order uji") {
        val cat = catalog.ensure()
        val market = cat.market(_state.value.symbol) ?: error("Simbol ${_state.value.symbol} tidak ada di katalog")
        if (!market.status.acceptsNewQuotes) error("${market.symbol} berstatus ${market.status}")
        val bid = marketData.orderBook(market.symbolId, "DEPTH_1").bids.firstOrNull()?.priceTicks
            ?: error("Order book kosong")
        val price = Numeric.roundPrice(Numeric.ticksToPrice(bid).multiply(BigDecimal("0.99")), market.tickSize, Side.BUY)
        var qty = Numeric.floorQty(market.minNotional.multiply(BigDecimal("1.5")).divide(price, market.baseScale + 2, java.math.RoundingMode.UP), market.stepSize)
        while (qty < market.minQty || price.multiply(qty) < market.minNotional.multiply(BigDecimal("1.2"))) qty += market.stepSize
        val plan = TestPlan(market, price, qty)
        Numeric.validate(market, price, qty)?.let { error("Validasi lokal gagal: $it") }
        _state.update { it.copy(plan = plan) }
    }

    fun place() {
        val plan = _state.value.plan ?: return
        run("Mengirim order") {
            when (val outcome = manager.submit(plan.market, Side.BUY, plan.price, plan.qty, postOnly = true)) {
                is SubmitOutcome.Accepted -> {
                    log("Diterima: ${outcome.order.clientOrderId} orderId=${outcome.order.orderId} status=${outcome.order.status}")
                    _state.update { it.copy(lastClientOrderId = outcome.order.clientOrderId, plan = null) }
                }
                is SubmitOutcome.Rejected -> {
                    log("Ditolak: ${outcome.reason}")
                    _state.update { it.copy(plan = null, lastClientOrderId = outcome.order?.clientOrderId ?: it.lastClientOrderId) }
                }
                is SubmitOutcome.Unresolved -> {
                    log("HASIL BELUM PASTI: ${outcome.order.clientOrderId}. Jalankan rekonsiliasi; jangan kirim ulang.")
                    _state.update { it.copy(lastClientOrderId = outcome.order.clientOrderId, plan = null) }
                }
            }
        }
    }

    fun cancelLast() {
        val id = _state.value.lastClientOrderId ?: return
        run("Membatalkan $id") {
            when (val outcome = manager.cancel(id)) {
                is CancelOutcome.Closed -> log("Tertutup: $id status=${outcome.order.status}")
                is CancelOutcome.Requested -> log("Pembatalan diterima, belum terkonfirmasi: $id (status ${outcome.order.status})")
                CancelOutcome.NotTracked -> log("Tidak ada catatan lokal untuk $id")
                is CancelOutcome.Failed -> log("Pembatalan gagal: ${outcome.error.userMessage()}")
            }
        }
    }

    fun reconcile() = run("Rekonsiliasi") {
        val report = reconciler.run(OrphanPolicy.ADOPT)
        log(
            "Rekonsiliasi: diperbarui=${report.updated}, diadopsi=${report.adopted.size}, ditolak=${report.rejected.size}, " +
                "belum pasti=${report.unresolved.size}, order lain diabaikan=${report.foreignIgnored}",
        )
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "$label gagal: ${e.userMessage().takeIf { m -> !m.startsWith("Terjadi kesalahan") } ?: e.message}") }
            } finally {
                _state.update { it.copy(busy = false) }
                refreshRecent()
            }
        }
    }

    private fun refreshRecent() {
        viewModelScope.launch { _state.update { it.copy(recent = store.recent(8)) } }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _state.update { it.copy(log = (listOf("$time  $message") + it.log).take(30)) }
    }
}

@Composable
fun OrderTestScreen(viewModel: OrderTestViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    s.plan?.let { plan ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Kirim order uji ke testnet?") },
            text = { Text("${plan.summary}\n\nOrder ini memakai Paper Assets dan diletakkan di bawah harga pasar, jadi seharusnya tidak tereksekusi.") },
            confirmButton = { TextButton(onClick = viewModel::place, enabled = !s.busy) { Text("Kirim") } },
            dismissButton = { TextButton(onClick = { viewModel.onSymbol(s.symbol) }) { Text("Batal") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Memasang satu order BUY post-only kecil, 1% di bawah best bid, lalu membatalkannya dan memeriksa rekonsiliasi. " +
                "Butuh saldo quote (mis. USDT) di akun trading dan API key dengan izin Spot trading.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = s.symbol, onValueChange = viewModel::onSymbol, label = { Text("Simbol") },
            singleLine = true, enabled = !s.busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::prepare, enabled = !s.busy) { Text("1. Pasang") }
            OutlinedButton(onClick = viewModel::cancelLast, enabled = !s.busy && s.lastClientOrderId != null) { Text("2. Batalkan") }
            OutlinedButton(onClick = viewModel::reconcile, enabled = !s.busy) { Text("3. Rekonsiliasi") }
        }
        s.error?.let { ErrorBanner(it) }

        InfoCard("Log") {
            if (s.log.isEmpty()) Text("Belum ada aktivitas.", style = MaterialTheme.typography.bodySmall)
            s.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        InfoCard("Order tercatat lokal") {
            if (s.recent.isEmpty()) Text("Kosong.", style = MaterialTheme.typography.bodySmall)
            s.recent.forEach { o ->
                Text(
                    "${o.clientOrderId}  ${o.status}${if (o.adopted) " (yatim)" else ""}${o.reason?.let { r -> " - $r" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
