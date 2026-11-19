@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.privatediary.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.privatediary.R
import ru.privatediary.ui.DiaryActions
import ru.privatediary.ui.DiaryUiState

@Composable
internal fun SettingsScreen(state: DiaryUiState, actions: DiaryActions) {
    var passwordAction by remember { mutableStateOf<String?>(null) }
    var exportWarning by remember { mutableStateOf(false) }
    val enabled = !state.busy
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp).padding(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        PrivacyCard()

        SettingsGroup(R.string.settings_security_section) {
            SettingsHeading(Icons.Outlined.Lock, R.string.lock_timeout, R.string.settings_lock_hint)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                maxItemsInEachRow = 2
            ) {
                listOf(
                    0 to R.string.timeout_immediate,
                    30 to R.string.timeout_30,
                    60 to R.string.timeout_60,
                    300 to R.string.timeout_300
                ).forEach { (seconds, label) ->
                    val selected = state.timeoutSeconds == seconds
                    FilterChip(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        selected = selected,
                        onClick = { actions.onTimeoutChanged(seconds) },
                        label = { Text(stringResource(label)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                        } else null,
                        enabled = enabled,
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
            if (state.biometricAvailable) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(
                            value = state.biometricsEnabled,
                            onValueChange = actions.onBiometricsChanged,
                            role = Role.Switch,
                            enabled = enabled
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingsIcon(Icons.Outlined.Fingerprint)
                    Text(
                        stringResource(R.string.enable_biometrics),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = state.biometricsEnabled,
                        onCheckedChange = null,
                        enabled = enabled
                    )
                }
            }
        }

        SettingsGroup(R.string.settings_preferences_section) {
            SettingsHeading(Icons.Outlined.Language, R.string.language)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ru" to R.string.language_ru, "en" to R.string.language_en).forEach { (tag, label) ->
                    FilterChip(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        selected = state.selectedLanguage == tag,
                        onClick = { actions.onLanguageChanged(tag) },
                        label = { Text(stringResource(label)) },
                        enabled = enabled,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = if (state.selectedLanguage == tag) {
                            { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }

        SettingsGroup(R.string.settings_files_section, innerPadding = 0.dp) {
            Column {
                SettingsActionRow(Icons.AutoMirrored.Outlined.NoteAdd, R.string.import_files,
                    R.string.settings_import_support, enabled, actions.onImport)
                ActionDivider()
                SettingsActionRow(Icons.Outlined.FileUpload, R.string.export_markdown,
                    R.string.settings_markdown_support, enabled, { exportWarning = true })
                ActionDivider()
                SettingsActionRow(Icons.Outlined.Archive, R.string.export_backup,
                    R.string.settings_backup_support, enabled, { passwordAction = "backup" })
                ActionDivider()
                SettingsActionRow(Icons.Outlined.Restore, R.string.restore_backup,
                    R.string.settings_restore_support, enabled, { passwordAction = "restore" })
            }
        }

        SettingsGroup(R.string.settings_search_section) {
            SettingsHeading(Icons.Outlined.AutoAwesome, R.string.semantic_model)
            Surface(
                color = if (state.semanticAvailable) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (state.semanticAvailable) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(50)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.semanticAvailable) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp))
                    Text(
                        stringResource(when {
                            state.indexing && !state.semanticAvailable -> R.string.settings_model_preparing
                            state.semanticAvailable -> R.string.settings_model_available
                            else -> R.string.settings_model_fallback
                        }),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Text(
                stringResource(when {
                    state.indexing && !state.semanticAvailable -> R.string.semantic_preparing
                    state.semanticAvailable -> R.string.semantic_ready
                    else -> R.string.semantic_missing
                }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.indexing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                Text(stringResource(R.string.indexing), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text(
            stringResource(R.string.settings_footer),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (exportWarning) AlertDialog(
        onDismissRequest = { exportWarning = false },
        icon = { Icon(Icons.Outlined.FileUpload, null) },
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.markdown_export_title)) },
        text = { Text(stringResource(R.string.markdown_export_explanation)) },
        confirmButton = {
            TextButton(
                enabled = enabled,
                onClick = { exportWarning = false; actions.onExportMarkdown() }
            ) { Text(stringResource(R.string.export_action)) }
        },
        dismissButton = {
            TextButton(onClick = { exportWarning = false }) { Text(stringResource(R.string.cancel)) }
        }
    )
    passwordAction?.let { kind ->
        PasswordDialog(
            restore = kind == "restore",
            busy = state.busy,
            onDismiss = { passwordAction = null },
            onConfirm = { password ->
                passwordAction = null
                if (kind == "restore") actions.onRestore(password) else actions.onBackup(password)
            }
        )
    }
}

@Composable
private fun PrivacyCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .08f), shape = CircleShape) {
                    Icon(Icons.Outlined.Shield, null, Modifier.padding(11.dp).size(22.dp))
                }
                Text(stringResource(R.string.privacy_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(R.string.settings_privacy_summary), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.settings_privacy_caption), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
        }
    }
}

@Composable
private fun SettingsGroup(
    title: Int,
    innerPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(title), Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
        ) {
            Column(Modifier.padding(innerPadding), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .07f), shape = RoundedCornerShape(12.dp)) {
        Icon(icon, null, Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingsHeading(icon: ImageVector, title: Int, subtitle: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: Int,
    subtitle: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionDivider() {
    HorizontalDivider(Modifier.padding(start = 70.dp, end = 18.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
}

@Composable
private fun PasswordDialog(restore: Boolean, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val mismatch = repeated.isNotEmpty() && password != repeated
    val valid = !busy && if (restore) password.isNotEmpty() else password.length >= 10 && password == repeated
    val dismiss = { password = ""; repeated = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(if (restore) Icons.Outlined.Restore else Icons.Outlined.Lock, null) },
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(if (restore) R.string.restore_backup else R.string.export_backup)) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(if (restore) R.string.restore_explanation else R.string.password_explanation),
                    color = if (restore) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(16.dp),
                    supportingText = if (!restore) { { Text(stringResource(R.string.settings_password_minimum)) } } else null
                )
                if (!restore) OutlinedTextField(
                    value = repeated,
                    onValueChange = { repeated = it },
                    label = { Text(stringResource(R.string.repeat_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    isError = mismatch,
                    supportingText = if (mismatch) { { Text(stringResource(R.string.settings_password_mismatch)) } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { val confirmed = password; password = ""; repeated = ""; onConfirm(confirmed) }
            ) {
                Text(stringResource(if (restore) R.string.restore else R.string.continue_action))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
