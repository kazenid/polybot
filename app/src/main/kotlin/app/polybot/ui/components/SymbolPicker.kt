package app.polybot.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.polybot.core.result.userMessage
import app.polybot.data.catalog.CatalogRepository
import app.polybot.ui.theme.trade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PickableMarket(val symbol: String, val base: String, val quote: String, val active: Boolean)

data class MarketListState(val markets: List<PickableMarket> = emptyList(), val loading: Boolean = false, val error: String? = null)

/** Daftar pair dari katalog server untuk pemilih simbol; dimuat sekali lalu dipakai ulang. */
@HiltViewModel
class MarketListViewModel @Inject constructor(private val catalog: CatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow(MarketListState())
    val state: StateFlow<MarketListState> = _state.asStateFlow()

    init { load() }

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val cat = catalog.ensure()
                val list = cat.markets
                    .map { PickableMarket(it.symbol, it.baseAsset, it.quoteAsset, it.status.acceptsNewQuotes) }
                    .sortedWith(compareByDescending<PickableMarket> { it.active }.thenBy { it.symbol })
                _state.value = MarketListState(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }
}

/** Kolom bentuk dropdown: menampilkan pilihan saat ini dan membuka lembar pemilih. */
@Composable
private fun PickerBox(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) { content() }
                Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SymbolPickerField(
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onlyActive: Boolean = true,
    viewModel: MarketListViewModel = hiltViewModel(),
) {
    var open by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier) {
        PickerBox(label, { open = true }) {
            if (selected.isBlank()) Text("Pilih simbol", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else PairLabel(selected)
        }
    }
    if (open) {
        SymbolSheet(
            title = "Pilih simbol",
            state = state,
            selected = setOf(selected),
            multi = false,
            onlyActive = onlyActive,
            onToggle = { onSelect(it); open = false },
            onRetry = viewModel::load,
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SymbolMultiPickerField(
    label: String,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MarketListViewModel = hiltViewModel(),
) {
    var open by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            selected.sorted().forEach { symbol ->
                InputChip(
                    selected = true,
                    onClick = { onChange(selected - symbol) },
                    label = { Text(symbol) },
                    avatar = { CoinIcon(symbol.substringBefore('-'), 22.dp) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Hapus $symbol", Modifier.size(16.dp)) },
                )
            }
            AssistChip(
                onClick = { open = true },
                label = { Text("Tambah") },
                leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
            )
        }
        if (selected.isEmpty()) {
            Text("Belum ada simbol. Bot tidak akan bisa dijalankan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (open) {
        SymbolSheet(
            title = "Simbol yang diizinkan",
            state = state,
            selected = selected,
            multi = true,
            onlyActive = false,
            onToggle = { symbol -> onChange(if (symbol in selected) selected - symbol else selected + symbol) },
            onRetry = viewModel::load,
            onDismiss = { open = false },
        )
    }
}

/** Lembar pemilih banyak simbol (hanya pair aktif) tanpa kolom chip, untuk daftar yang ditampilkan di tempat lain. */
@Composable
fun SymbolMultiPickerSheet(
    title: String,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    viewModel: MarketListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SymbolSheet(
        title = title,
        state = state,
        selected = selected,
        multi = true,
        onlyActive = true,
        onToggle = { symbol -> onChange(if (symbol in selected) selected - symbol else selected + symbol) },
        onRetry = viewModel::load,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SymbolSheet(
    title: String,
    state: MarketListState,
    selected: Set<String>,
    multi: Boolean,
    onlyActive: Boolean,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shown = state.markets.filter { query.isBlank() || it.symbol.contains(query.trim(), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (multi) TextButton(onClick = onDismiss) { Text("Selesai") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Cari, mis. BTC") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                ErrorBanner(it)
                TextButton(onClick = onRetry) { Text("Coba lagi") }
            }
            if (state.loading && state.markets.isEmpty()) Text("Memuat daftar simbol...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(shown, key = { it.symbol }) { m ->
                    val enabled = m.active || !onlyActive
                    val isSelected = m.symbol in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onToggle(m.symbol) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PairIcon(m.symbol, 34.dp)
                        Column(Modifier.weight(1f)) {
                            Text(m.symbol, style = MaterialTheme.typography.titleSmall)
                            Text("${m.base} / ${m.quote}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!m.active) Pill("Nonaktif", MaterialTheme.trade.warn)
                        if (multi) Checkbox(checked = isSelected, onCheckedChange = null)
                        else if (isSelected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (state.markets.isEmpty() && !state.loading && state.error == null) onRetry() }
}
