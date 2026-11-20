@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.privatediary.ui.screens

import android.app.DatePickerDialog
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import ru.privatediary.R
import ru.privatediary.domain.models.SearchFilters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun SearchFiltersPanel(
    filters: SearchFilters,
    tags: List<String>,
    enabled: Boolean,
    onChange: (SearchFilters) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var tagPicker by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf<DatePickerDialog?>(null) }
    val latestFilters by rememberUpdatedState(filters)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestChange by rememberUpdatedState(onChange)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale) }
    val palette = MaterialTheme.colorScheme
    val availableTags = remember(tags, filters.tag) {
        (tags + listOfNotNull(filters.tag)).distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    DisposableEffect(context) { onDispose { dateDialog?.dismiss() } }
    LaunchedEffect(enabled) {
        if (!enabled) { dateDialog?.dismiss(); tagPicker = false }
    }

    fun chooseDate(start: Boolean) {
        if (!latestEnabled) return
        val current = latestFilters
        val initial = (if (start) current.from ?: current.to else current.to ?: current.from) ?: LocalDate.now()
        dateDialog?.dismiss()
        dateDialog = DatePickerDialog(context, { _, year, month, day ->
            if (latestEnabled) {
                val date = LocalDate.of(year, month + 1, day)
                val latest = latestFilters
                latestChange(if (start) latest.copy(from = date,
                    to = latest.to?.let { if (it < date) date else it })
                else latest.copy(to = date,
                    from = latest.from?.let { if (it > date) date else it }))
            }
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            setTitle(context.getString(if (start) R.string.search_filter_from else R.string.search_filter_to))
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            show()
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val summary = buildList {
        filters.from?.let { add(stringResource(R.string.search_filter_summary_from, it.format(dateFormat))) }
        filters.to?.let { add(stringResource(R.string.search_filter_summary_to, it.format(dateFormat))) }
        filters.mood?.let { add(stringResource(R.string.search_filter_summary_mood, it)) }
        filters.tag?.let { add("#$it") }
    }.joinToString(" · ").ifEmpty { stringResource(R.string.search_filter_hint) }

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = palette.surfaceContainerLow,
        border = BorderStroke(1.dp, palette.outlineVariant.copy(alpha = 0.7f))) {
        Column {
            Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val expandedDescription = stringResource(if (expanded) R.string.search_filter_expanded else R.string.search_filter_collapsed)
                Surface(onClick = { expanded = !expanded }, enabled = enabled, color = Color.Transparent,
                    modifier = Modifier.weight(1f).semantics { stateDescription = expandedDescription }) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(20.dp), tint = palette.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (filters.isEmpty) stringResource(R.string.search_filter_title)
                                else stringResource(R.string.search_filter_title_count, filters.activeCount),
                                style = MaterialTheme.typography.titleSmall)
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                            Modifier.size(20.dp), tint = palette.onSurfaceVariant)
                    }
                }
                if (!filters.isEmpty) IconButton(onClick = { onChange(SearchFilters()) }, enabled = enabled) {
                    Icon(Icons.Outlined.RestartAlt, stringResource(R.string.search_filter_clear_all), Modifier.size(21.dp))
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HorizontalDivider(color = palette.outlineVariant.copy(alpha = 0.6f))
                    FilterDateRow(stringResource(R.string.search_filter_from), filters.from?.format(dateFormat), enabled,
                        stringResource(R.string.search_filter_clear_from), onChoose = { chooseDate(true) },
                        onClear = { onChange(filters.copy(from = null)) })
                    FilterDateRow(stringResource(R.string.search_filter_to), filters.to?.format(dateFormat), enabled,
                        stringResource(R.string.search_filter_clear_to), onChoose = { chooseDate(false) },
                        onClear = { onChange(filters.copy(to = null)) })
                    Text(stringResource(R.string.search_filter_mood), style = MaterialTheme.typography.labelMedium,
                        color = palette.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (mood in 1..5) {
                            val label = stringResource(R.string.search_filter_mood_choice, mood)
                            FilterChip(selected = filters.mood == mood,
                                onClick = { onChange(filters.copy(mood = mood.takeIf { filters.mood != mood })) }, enabled = enabled,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                label = { Text(mood.toString()) },
                                leadingIcon = { Icon(when (mood) {
                                    1 -> Icons.Outlined.SentimentVeryDissatisfied
                                    2 -> Icons.Outlined.SentimentDissatisfied
                                    3 -> Icons.Outlined.SentimentNeutral
                                    4 -> Icons.Outlined.SentimentSatisfied
                                    else -> Icons.Outlined.SentimentVerySatisfied
                                }, label, Modifier.size(18.dp)) })
                        }
                    }
                    Text(stringResource(R.string.search_filter_tag), style = MaterialTheme.typography.labelMedium,
                        color = palette.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { tagPicker = true }, enabled = enabled && availableTags.isNotEmpty(),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.Tag, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(filters.tag?.let { "#$it" } ?: stringResource(R.string.search_filter_all_tags),
                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(20.dp))
                        }
                        if (filters.tag != null) IconButton(onClick = { onChange(filters.copy(tag = null)) }, enabled = enabled) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.search_filter_clear_tag), Modifier.size(20.dp))
                        }
                    }
                    if (availableTags.isEmpty()) Text(stringResource(R.string.search_filter_no_tags),
                        style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                }
            }
        }
    }
    if (tagPicker && enabled) SearchTagDialog(availableTags, filters.tag,
        onSelect = { tag -> if (latestEnabled) { latestChange(latestFilters.copy(tag = tag)); tagPicker = false } },
        onDismiss = { tagPicker = false })
}

@Composable
private fun FilterDateRow(label: String, date: String?, enabled: Boolean, clearDescription: String,
    onChoose: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onChoose, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(date ?: stringResource(R.string.search_filter_any_date), maxLines = 2,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (date != null) IconButton(onClick = onClear, enabled = enabled) {
            Icon(Icons.Outlined.Close, clearDescription, Modifier.size(20.dp))
        } else Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun SearchTagDialog(tags: List<String>, selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filteredTags = remember(tags, query) { tags.filter { it.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.search_filter_choose_tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    label = { Text(stringResource(R.string.search_filter_tag_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) })
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    item {
                        TagFilterChoice(stringResource(R.string.search_filter_all_tags), selected == null) { onSelect(null) }
                    }
                    items(filteredTags, key = { it }) { tag ->
                        TagFilterChoice("#$tag", selected?.equals(tag, ignoreCase = true) == true) { onSelect(tag) }
                    }
                    if (filteredTags.isEmpty()) item {
                        Text(stringResource(R.string.search_filter_no_matching_tags), Modifier.padding(vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.search_filter_close)) } })
}

@Composable
private fun TagFilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
