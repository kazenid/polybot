package app.polybot.ui.settings

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.polybot.R
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.components.NoticeBanner
import app.polybot.ui.theme.trade

private const val DEVELOPER = "Azzurahai"

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "-"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_polybot),
            contentDescription = "Logo PolyBot",
            modifier = Modifier.size(104.dp).clip(RoundedCornerShape(28.dp)),
        )
        Text("PolyBot", style = MaterialTheme.typography.headlineMedium)
        Text("Bot trading spot untuk Polyester testnet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text("oleh $DEVELOPER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

        NoticeBanner(
            "Aplikasi ini hanya untuk testnet (Paper Assets). Tidak ada uang sungguhan yang dipakai, dan hasilnya bukan saran keuangan.",
            Icons.Rounded.WarningAmber, MaterialTheme.trade.warn,
        )

        InfoCard("Pengembang", icon = Icons.Rounded.Code) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Text(DEVELOPER.take(1), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                Column {
                    Text(DEVELOPER, style = MaterialTheme.typography.titleMedium)
                    Text("Pengembang PolyBot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        InfoCard("Versi") {
            KeyValueRow("Aplikasi", version)
            KeyValueRow("Package", context.packageName)
            KeyValueRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            KeyValueRow("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        InfoCard("Teknis") {
            KeyValueRow("Protokol", "Connect RPC (JSON)")
            KeyValueRow("Tanda tangan", "Ed25519")
            KeyValueRow("SDK acuan", "polyester-sdk 0.3.0")
            KeyValueRow("Strategi", "Market Maker, Grid, DCA")
        }
        InfoCard("Keamanan") {
            Text(
                "Private key disimpan terenkripsi di Android Keystore dan tidak pernah ditampilkan ulang atau dikirim ke pihak lain. " +
                    "Order dikirim dengan client order ID unik dan selalu direkonsiliasi sebelum dikirim ulang.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
