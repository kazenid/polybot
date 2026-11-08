package app.polybot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.polybot.data.prefs.PolyEnv
import app.polybot.ui.components.ErrorBanner
import app.polybot.ui.components.InfoCard
import app.polybot.ui.components.KeyValueRow
import app.polybot.ui.components.SecureWindow

@Composable
fun AccountScreen(viewModel: AccountViewModel = hiltViewModel()) {
    SecureWindow()
    val s by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Environment", style = MaterialTheme.typography.titleSmall)
        PolyEnv.entries.forEach { env ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = s.env == env, enabled = !s.busy, role = Role.RadioButton) { viewModel.onEnv(env) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.env == env, onClick = null, enabled = !s.busy)
                Text(env.label, Modifier.padding(start = 8.dp))
            }
        }

        OutlinedTextField(
            value = s.keyId, onValueChange = viewModel::onKeyId, label = { Text("API key id") },
            singleLine = true, enabled = !s.busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        OutlinedTextField(
            value = s.privateKey, onValueChange = viewModel::onPrivateKey,
            label = { Text("Private key (64 hex)") },
            placeholder = { if (s.hasStoredKey) Text("Tersimpan. Kosongkan untuk tetap memakai yang lama.") },
            singleLine = true, enabled = !s.busy, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = s.privateKeyProblem != null,
            supportingText = {
                when {
                    s.privateKeyProblem != null -> Text(s.privateKeyProblem!!)
                    s.privateKey.isNotEmpty() -> Text("${s.privateKey.length}/64 karakter hex")
                }
            },
        )
        s.publicKey?.let { publicKey ->
            InfoCard("Public key turunan") {
                Text(
                    if (s.publicKeyFromStored) "Dari private key yang tersimpan:" else "Dari private key yang sedang diketik:",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        publicKey.chunked(32).joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    "Bukan rahasia. Cocokkan dengan public key di aplikasi Polyester (kalau ditampilkan). " +
                        "Bila berbeda, API key id dan private key ini bukan satu pasangan, atau yang ditempel bukan private key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = s.accountId, onValueChange = viewModel::onAccountId, label = { Text("Account ID (opsional)") },
            singleLine = true, enabled = !s.busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Text(
            "Gunakan API key khusus bot: hanya Spot trading, market yang dipakai, ukuran order kecil, tanpa izin withdraw.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::save, enabled = !s.busy) { Text("Simpan") }
            Button(onClick = viewModel::testConnection, enabled = !s.busy && s.hasStoredKey) { Text("Tes koneksi") }
            OutlinedButton(onClick = viewModel::deleteKey, enabled = !s.busy && s.hasStoredKey) { Text("Hapus") }
        }

        s.message?.let { msg ->
            if (s.messageIsError) ErrorBanner(msg) else Text(msg, color = MaterialTheme.colorScheme.primary)
        }

        s.report?.let { r ->
            InfoCard("Hasil tes koneksi") {
                r.steps.forEach { step ->
                    Text(
                        "${if (step.ok) "✓" else "✗"} ${step.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                r.accountId?.let { KeyValueRow("Account ID (server)", it) }
                r.apiKeyId?.let { KeyValueRow("API key id", it) }
                r.username?.let { KeyValueRow("Username", it) }
                KeyValueRow("Latensi rata-rata", "${r.latencyMs} ms")
                KeyValueRow("Selisih jam", r.clockSkewMs?.let { "${it / 1000.0} dtk" } ?: "-")
            }
        }
    }
}
