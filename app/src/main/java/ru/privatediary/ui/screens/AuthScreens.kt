package ru.privatediary.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import ru.privatediary.R
import ru.privatediary.ui.DiaryActions
import ru.privatediary.ui.DiaryUiState
import ru.privatediary.ui.theme.DiaryStyle

@Composable
internal fun SetupScreen(state: DiaryUiState, actions: DiaryActions) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var biometrics by remember { mutableStateOf(false) }
    val ready = pin.length in 6..12 && pin == confirmation && !state.busy
    val create = {
        if (ready) {
            actions.onCreatePin(pin, biometrics)
            pin = ""
            confirmation = ""
        }
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AuthBrand()
        Spacer(Modifier.height(26.dp))
        DiaryEmblem(Modifier.size(88.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.auth_setup_title), style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.auth_setup_subtitle), style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 340.dp))
        Spacer(Modifier.height(28.dp))
        AuthCard {
            Text(stringResource(R.string.auth_pin_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.auth_pin_subtitle), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            PinField(pin, { pin = it }, R.string.create_pin, !state.busy, ImeAction.Next)
            Spacer(Modifier.height(8.dp))
            PinField(confirmation, { confirmation = it }, R.string.repeat_pin, !state.busy,
                ImeAction.Done, create)
            if (confirmation.isNotEmpty() && pin != confirmation) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.pin_mismatch), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.biometricAvailable) {
                Spacer(Modifier.height(12.dp))
                BiometricChoice(biometrics, !state.busy) { biometrics = it }
            }
            Spacer(Modifier.height(20.dp))
            AuthButton(stringResource(R.string.start_diary), ready, state.busy, create)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.auth_pin_reminder), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
        PrivacyFootnote()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun LockScreen(state: DiaryUiState, actions: DiaryActions) {
    var pin by remember { mutableStateOf("") }
    val permitted = !state.busy && state.lockRemainingSeconds <= 0
    val ready = permitted && pin.length in 6..12
    val unlock = {
        if (ready) {
            actions.onUnlock(pin)
            pin = ""
        }
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AuthBrand()
        Spacer(Modifier.height(44.dp))
        DiaryEmblem(Modifier.size(112.dp))
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.auth_lock_title), style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.unlock_hint), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        AuthCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.auth_your_space), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            PinField(pin, { pin = it }, R.string.enter_pin, permitted, ImeAction.Done, unlock)
            if (state.lockRemainingSeconds > 0) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.retry_after, state.lockRemainingSeconds),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            AuthButton(stringResource(R.string.unlock), ready, state.busy, unlock)
            if (state.biometricsEnabled && state.biometricAvailable) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = actions.onBiometric, enabled = permitted,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Icon(Icons.Outlined.Fingerprint, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.unlock_biometric))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        PrivacyFootnote()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AuthBrand() {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary, letterSpacing = 1.6.sp)
}

@Composable
private fun AuthCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
        shadowElevation = 0.dp) {
        Column(Modifier.padding(22.dp), content = content)
    }
}

@Composable
private fun AuthButton(label: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DiaryStyle.colors.cover,
            contentColor = DiaryStyle.colors.onCover)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center)
        if (!busy) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
    }
}

@Composable
private fun BiometricChoice(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch,
        onValueChange = onChange).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Fingerprint, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.enable_biometrics), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(3.dp))
            Text(stringResource(R.string.auth_biometrics_subtitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun PrivacyFootnote() {
    Row(Modifier.widthIn(max = 340.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Shield, null, Modifier.padding(top = 2.dp).size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.auth_privacy_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean,
    imeAction: ImeAction, onDone: () -> Unit = {}) {
    OutlinedTextField(value = value, onValueChange = { text ->
        if (text.length <= 12 && text.all { it in '0'..'9' }) onChange(text)
    }, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp, fontWeight = FontWeight.Medium),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        enabled = enabled, supportingText = { Text(stringResource(R.string.pin_length)) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
        ))
}
