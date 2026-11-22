@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.privatediary.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.privatediary.R
import ru.privatediary.monetization.ProScreenState

@Composable
internal fun ProScreen(
    state: ProScreenState,
    onPay: () -> Unit,
    onCheckPayment: () -> Unit,
    onRestore: (String) -> Unit,
    onRevealRecoveryCode: () -> Unit,
    onBack: () -> Unit,
) {
    var restoreExpanded by remember { mutableStateOf(false) }
    var recoveryInput by remember { mutableStateOf("") }
    var showRecoveryCode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val enabled = state.paymentsConfigured && !state.busy
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.pro_title)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.pro_back))
            }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(34.dp))
                    Text(stringResource(R.string.pro_headline), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.pro_price), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.pro_manual_terms), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.activeUntilMillis != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.pro_active_until, formatDateTime(state.activeUntilMillis)),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }

            ProFeature(Icons.Outlined.Shield, stringResource(R.string.pro_no_ads), stringResource(R.string.pro_no_ads_description))
            ProFeature(Icons.Outlined.BarChart, stringResource(R.string.pro_insights), stringResource(R.string.pro_insights_description))
            HorizontalDivider()
            Text(stringResource(R.string.pro_core_free), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.pro_network_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (!state.paymentsConfigured) {
                Text(stringResource(R.string.pro_payments_unavailable), style = MaterialTheme.typography.bodyMedium)
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))

            Button(onClick = onPay, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.activeUntilMillis == null) R.string.pro_pay else R.string.pro_renew))
            }
            if (state.hasPendingCheckout) {
                OutlinedButton(onClick = onCheckPayment, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pro_check_payment))
                }
                Text(stringResource(R.string.pro_check_payment_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.hasAccount) {
                TextButton(onClick = { showRecoveryCode = true; onRevealRecoveryCode() }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pro_show_recovery))
                }
            }

            TextButton(onClick = { restoreExpanded = !restoreExpanded }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pro_restore))
            }
            if (restoreExpanded) {
                Text(stringResource(R.string.pro_restore_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = recoveryInput,
                    onValueChange = { input -> recoveryInput = input.filterNot(Char::isWhitespace).take(43) },
                    label = { Text(stringResource(R.string.pro_recovery_code)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedButton(
                    onClick = { onRestore(recoveryInput); recoveryInput = "" },
                    enabled = enabled && recoveryInput.matches(Regex("[A-Za-z0-9_-]{43}")),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.pro_restore_submit)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (showRecoveryCode && state.recoveryCode != null) {
        AlertDialog(
            onDismissRequest = { showRecoveryCode = false },
            title = { Text(stringResource(R.string.pro_recovery_code)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.pro_recovery_warning))
                    SelectionContainer { Text(state.recoveryCode, style = MaterialTheme.typography.bodyLarge) }
                }
            },
            confirmButton = { TextButton(onClick = {
                val clip = ClipData.newPlainText(context.getString(R.string.pro_recovery_code), state.recoveryCode)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                showRecoveryCode = false
            }) { Text(stringResource(R.string.pro_copy_code)) } },
            dismissButton = { TextButton(onClick = { showRecoveryCode = false }) { Text(stringResource(R.string.pro_close)) } },
        )
    }
}

@Composable
private fun ProFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
