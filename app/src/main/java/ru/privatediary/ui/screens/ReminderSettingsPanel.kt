@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.privatediary.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.privatediary.R
import java.util.Calendar

/** Permission requests belong to the activity; this panel only presents preference callbacks. */
@Composable
internal fun ReminderSettingsPanel(
    dailyEnabled: Boolean,
    dailyMinutes: Int,
    backupEnabled: Boolean,
    enabled: Boolean = true,
    onDailyEnabledChanged: (Boolean) -> Unit,
    onDailyTimeChanged: (Int) -> Unit,
    onBackupEnabledChanged: (Boolean) -> Unit,
) {
    var showTime by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val formattedTime = DateFormat.getTimeFormat(context).format(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, dailyMinutes / 60)
        set(Calendar.MINUTE, dailyMinutes % 60)
    }.time)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.reminder_settings_section), Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ReminderToggle(Icons.Outlined.NotificationsNone,
                    stringResource(R.string.reminder_daily_title), stringResource(R.string.reminder_daily_description),
                    dailyEnabled, enabled, onDailyEnabledChanged)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                ReminderToggle(Icons.Outlined.Archive,
                    stringResource(R.string.reminder_backup_title), stringResource(R.string.reminder_backup_description),
                    backupEnabled, enabled, onBackupEnabledChanged)
                if (dailyEnabled || backupEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    Surface(onClick = { showTime = true }, enabled = enabled,
                        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Schedule, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.reminder_time_title), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(formattedTime, style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text(stringResource(R.string.reminder_privacy_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showTime) {
        val time = rememberTimePickerState(initialHour = dailyMinutes / 60,
            initialMinute = dailyMinutes % 60, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.reminder_time_title)) },
            text = { TimePicker(state = time) },
            confirmButton = {
                TextButton(enabled = enabled, onClick = {
                    onDailyTimeChanged(time.hour * 60 + time.minute)
                    showTime = false
                }) { Text(stringResource(R.string.reminder_time_save)) }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ReminderToggle(icon: ImageVector, title: String, description: String,
    checked: Boolean, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChanged),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
