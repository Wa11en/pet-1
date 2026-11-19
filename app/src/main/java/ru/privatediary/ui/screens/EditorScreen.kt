@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.privatediary.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.privatediary.R
import ru.privatediary.domain.models.Entry
import ru.privatediary.ui.theme.DiaryStyle
import java.time.Instant
import java.time.ZoneId

/** The draft stays only in memory; explicit save remains the persistence boundary. */
@Composable
internal fun EditorScreen(
    entry: Entry,
    busy: Boolean,
    onClose: () -> Unit,
    onSave: (Entry) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(entry.id) { mutableStateOf(entry.text) }
    var tags by remember(entry.id) { mutableStateOf(entry.tags.joinToString(", ")) }
    var mood by remember(entry.id) { mutableStateOf(entry.mood) }
    var timestamp by remember(entry.id) { mutableLongStateOf(entry.timestamp) }
    var discard by remember { mutableStateOf(false) }
    var detailsExpanded by remember(entry.id) { mutableStateOf(entry.mood != null || entry.tags.isNotEmpty()) }
    var dateDialog by remember { mutableStateOf<DatePickerDialog?>(null) }
    var timeDialog by remember { mutableStateOf<TimePickerDialog?>(null) }
    val latestBusy by rememberUpdatedState(busy)
    val context = LocalContext.current
    val palette = DiaryStyle.colors
    val dirty = text != entry.text || tags != entry.tags.joinToString(", ") || mood != entry.mood || timestamp != entry.timestamp
    val close = { if (!busy) { if (dirty) discard = true else onClose() } }
    BackHandler { close() }
    DisposableEffect(context) {
        onDispose { dateDialog?.dismiss(); timeDialog?.dismiss() }
    }

    Scaffold(
        containerColor = palette.paper,
        topBar = {
            Column(Modifier.background(palette.paper).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedIconButton(
                        onClick = close,
                        enabled = !busy,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), Modifier.size(21.dp)) }
                    Spacer(Modifier.weight(1f))
                    if (entry.id != 0L) IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            onSave(entry.copy(timestamp = timestamp, text = text, mood = mood,
                                tags = tags.split(',').map { it.trim().removePrefix("#") }.filter(String::isNotEmpty).distinct()))
                        },
                        enabled = text.isNotBlank() && !busy,
                        shape = CircleShape,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.save))
                    }
                }
                AnimatedVisibility(busy) { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val dateDescription = stringResource(R.string.editor_change_date, formatDate(timestamp))
                EditorDatePill(formatDate(timestamp), Icons.Outlined.CalendarToday, !busy, dateDescription) {
                    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                    dateDialog?.dismiss()
                    dateDialog = DatePickerDialog(context, { _, year, month, day ->
                        if (!latestBusy) {
                            timestamp = date.withDayOfMonth(1).withYear(year).withMonth(month + 1).withDayOfMonth(day)
                                .toInstant().toEpochMilli()
                        }
                    }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        show()
                    }
                }
                val timeDescription = stringResource(R.string.editor_change_time, formatTime(timestamp))
                EditorDatePill(formatTime(timestamp), Icons.Outlined.Schedule, !busy, timeDescription) {
                    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                    timeDialog?.dismiss()
                    timeDialog = TimePickerDialog(context, { _, hour, minute ->
                        if (!latestBusy) timestamp = date.withHour(hour).withMinute(minute).toInstant().toEpochMilli()
                    }, date.hour, date.minute, true).apply {
                        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        show()
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.editor_page_title), style = MaterialTheme.typography.headlineLarge, color = palette.ink)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.width(36.dp).height(2.dp).background(palette.accent, CircleShape))
            Spacer(Modifier.height(22.dp))
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= 1_000_000) text = it },
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 33.sp, color = palette.ink),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                decorationBox = { input ->
                    Box {
                        if (text.isEmpty()) Text(
                            stringResource(R.string.editor_writing_hint),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 33.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        input()
                    }
                },
            )
            if (text.length >= 1_000_000) Text(stringResource(R.string.editor_text_limit),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(28.dp))

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            ) {
                Column {
                    val detailState = stringResource(if (detailsExpanded) R.string.editor_expanded else R.string.editor_collapsed)
                    Surface(
                        onClick = { detailsExpanded = !detailsExpanded },
                        enabled = !busy,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().semantics { stateDescription = detailState },
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.editor_details_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Icon(if (detailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    AnimatedVisibility(detailsExpanded) {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.mood_optional), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                mood?.let { Text(stringResource(moodLabel(it)), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (value in 1..5) EditorMoodChoice(value, mood == value, !busy) { mood = if (mood == value) null else value }
                            }
                            OutlinedTextField(
                                value = tags, onValueChange = { tags = it.take(500) },
                                label = { Text(stringResource(R.string.tags_label)) },
                                leadingIcon = { Icon(Icons.Outlined.Tag, null, Modifier.size(19.dp)) },
                                supportingText = { Text(stringResource(R.string.editor_tags_hint)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = palette.paper, unfocusedContainerColor = palette.paper,
                                ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.editor_private_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_explanation)) },
        confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }, enabled = !busy) { Text(stringResource(R.string.continue_writing)) } },
    )
}

@Composable
private fun EditorDatePill(label: String, icon: ImageVector, enabled: Boolean, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EditorMoodChoice(value: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.editor_mood_choice, value, stringResource(moodLabel(value)))
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.width(48.dp).heightIn(min = 56.dp).semantics { contentDescription = description; this.selected = selected },
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(when (value) {
                1 -> Icons.Outlined.SentimentVeryDissatisfied
                2 -> Icons.Outlined.SentimentDissatisfied
                3 -> Icons.Outlined.SentimentNeutral
                4 -> Icons.Outlined.SentimentSatisfied
                else -> Icons.Outlined.SentimentVerySatisfied
            }, null, Modifier.size(23.dp))
            Text(value.toString(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp))
        }
    }
}

private fun moodLabel(value: Int): Int = when (value) {
    1 -> R.string.editor_mood_1
    2 -> R.string.editor_mood_2
    3 -> R.string.editor_mood_3
    4 -> R.string.editor_mood_4
    else -> R.string.editor_mood_5
}
