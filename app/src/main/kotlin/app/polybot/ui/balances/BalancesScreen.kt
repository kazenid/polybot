package app.polybot.ui.balances

import androidx.compose.foundation.layout.Arrangement
import java.math.RoundingMode
import app.polybot.ui.theme.trade
import app.polybot.ui.components.StatTile
import app.polybot.ui.components.ScreenHeader
import app.polybot.ui.components.EmptyState
import app.polybot.ui.components.CoinIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.data.account.BalanceRepository
import app.polybot.domain.model.Balance
import app.polybot.ui.PollingViewModel
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.components.PollWhileStarted
import app.polybot.ui.components.UpdatedAt
import app.polybot.ui.components.plain
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BalancesViewModel @Inject constructor(
    private val repository: BalanceRepository,
) : PollingViewModel<List<Balance>>() {
    override suspend fun load(): List<Balance> = repository.trading()
}

@Composable
fun BalancesScreen(viewModel: BalancesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PollWhileStarted(intervalMs = 15_000) { viewModel.refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("Saldo", "Akun Unified Trading") { UpdatedAt(state.updatedAtMs, state.loading) }
        state.error?.let { ErrorBanner(it) }

        val balances = state.data.orEmpty().filter { it.hasValue() }
        if (state.data != null && balances.isEmpty()) {
            EmptyState(
                Icons.Rounded.AccountBalanceWallet,
                "Tidak ada saldo",
                "Pindahkan Paper Assets dari Funding ke Unified Trading di aplikasi Polyester.",
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(balances, key = { it.asset }) { b -> BalanceCard(b) }
        }
    }
}

private fun Balance.hasValue() = trading.signum() != 0 || funding.signum() != 0 || reserved.signum() != 0 || available.signum() != 0

@Composable
private fun BalanceCard(b: Balance) {
    InfoCard("") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoinIcon(b.asset, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(b.asset, style = MaterialTheme.typography.titleMedium)
                Text("Total trading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(b.trading.plain(), style = MaterialTheme.typography.titleLarge)
        }
        if (b.trading.signum() > 0) {
            val locked = b.reserved.divide(b.trading, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { locked },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.trade.warn,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Tersedia", b.available.plain(), Modifier.weight(1f), MaterialTheme.trade.up)
            StatTile("Terkunci", b.reserved.plain(), Modifier.weight(1f), if (b.reserved.signum() > 0) MaterialTheme.trade.warn else Color.Unspecified)
            StatTile("Funding", b.funding.plain(), Modifier.weight(1f))
        }
    }
}
