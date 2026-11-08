package app.polybot.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.polybot.ui.theme.trade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import java.math.BigDecimal

/**
 * Field angka desimal. Teks lokal dipertahankan selama pengguna mengetik ("1." tidak dipaksa jadi "1");
 * nilai hanya diteruskan ke atas bila valid, dan nilai dari luar yang berbeda menimpa teks.
 */
@Composable
fun DecimalField(
    label: String,
    value: BigDecimal,
    onValue: (BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String? = null,
    help: FieldHelp? = null,
    warning: String? = null,
) {
    var text by remember { mutableStateOf(value.toPlainString()) }
    var showHelp by remember { mutableStateOf(false) }
    val parsed = text.trim().toBigDecimalOrNull()
    LaunchedEffect(value) {
        if (parsed == null || parsed.compareTo(value) != 0) text = value.toPlainString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.trim().toBigDecimalOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = parsed == null,
        supportingText = { FieldSupport(if (parsed == null) "Angka tidak valid" else hint.orEmpty(), warning.takeIf { parsed != null }) },
        trailingIcon = help?.let { { HelpButton(it) { showHelp = true } } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
    if (showHelp && help != null) FieldHelpDialog(help) { showHelp = false }
}

/**
 * Field persen untuk nilai yang disimpan sebagai pecahan (0.02 = 2%). Pengguna mengetik persen; pecahan diteruskan ke atas.
 */
@Composable
fun PercentField(
    label: String,
    fraction: BigDecimal,
    onFraction: (BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    help: FieldHelp? = null,
    warning: String? = null,
) {
    val hundred = BigDecimal(100)
    val percent = fraction.multiply(hundred).let { if (it.signum() == 0) BigDecimal.ZERO else it.stripTrailingZeros() }
    DecimalField(
        label = label,
        value = percent,
        onValue = { v -> onFraction(v.divide(hundred, 8, java.math.RoundingMode.HALF_UP).let { if (it.signum() == 0) BigDecimal.ZERO else it.stripTrailingZeros() }) },
        modifier = modifier,
        hint = hint,
        help = help,
        warning = warning,
    )
}

@Composable
fun IntField(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String? = null,
    help: FieldHelp? = null,
    warning: String? = null,
) {
    var text by remember { mutableStateOf(value.toString()) }
    var showHelp by remember { mutableStateOf(false) }
    val parsed = text.trim().toIntOrNull()
    LaunchedEffect(value) {
        if (parsed == null || parsed != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.trim().toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = parsed == null,
        supportingText = { FieldSupport(if (parsed == null) "Bilangan bulat tidak valid" else hint.orEmpty(), warning.takeIf { parsed != null }) },
        trailingIcon = help?.let { { HelpButton(it) { showHelp = true } } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
    if (showHelp && help != null) FieldHelpDialog(help) { showHelp = false }
}

/** Penjelasan satu kolom dalam bahasa biasa: arti, contoh, dan kisaran yang disarankan. */
data class FieldHelp(val title: String, val what: String, val example: String? = null, val range: String? = null)

/** Teks bantu di bawah kolom; peringatan (bila ada) tampil berwarna di bawah petunjuk. */
@Composable
private fun FieldSupport(hint: String, warning: String?) {
    Column {
        if (hint.isNotEmpty()) Text(hint)
        if (warning != null) Text(warning, color = MaterialTheme.trade.warn)
    }
}

@Composable
private fun HelpButton(help: FieldHelp, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Rounded.Info, "Petunjuk ${help.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FieldHelpDialog(help: FieldHelp, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(help.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(help.what, style = MaterialTheme.typography.bodyMedium)
                help.example?.let { Text("Contoh: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                help.range?.let { Text("Disarankan: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Mengerti") } },
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true, description: String? = null) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(label)
            if (description != null) {
                Text(
                    description,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

/** Field teks yang menyimpan teks lokal supaya kursor tidak melompat saat nilai disimpan ke DataStore. */
@Composable
fun TextConfigField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (text.trim() != value) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        supportingText = { if (hint != null) Text(hint) },
        modifier = modifier.fillMaxWidth(),
    )
}
