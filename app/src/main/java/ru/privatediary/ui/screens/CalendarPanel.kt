package ru.privatediary.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.privatediary.R
import ru.privatediary.domain.models.CalendarDaySummary
import ru.privatediary.domain.models.CalendarMonth as DiaryCalendarMonth
import ru.privatediary.domain.models.Entry
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun CalendarPanel(entries: List<Entry>, selectedDate: LocalDate?, onSelectDate: (LocalDate?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.from(selectedDate ?: LocalDate.now())) }
    LaunchedEffect(selectedDate) { selectedDate?.let { month = YearMonth.from(it) } }
    val zone = ZoneId.systemDefault()
    val calendar = remember(entries, month, zone) { DiaryCalendarMonth.from(entries, month, zone) }
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now(zone)
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f))) {
        Column {
            val expandedDescription = stringResource(if (expanded) R.string.calendar_expanded else R.string.calendar_collapsed)
            Surface(onClick = { expanded = !expanded }, color = colors.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = expandedDescription }) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(21.dp), tint = colors.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.calendar_title), style = MaterialTheme.typography.titleSmall)
                        Text(selectedDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
                            ?: stringResource(R.string.calendar_all_dates), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                        Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                }
            }
            if (selectedDate != null) {
                TextButton(onClick = { onSelectDate(null) }, modifier = Modifier.padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.calendar_show_all))
                }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { month = month.minusMonths(1) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.calendar_previous_month))
                        }
                        Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
                            .replaceFirstChar { it.titlecase(locale) }, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                        IconButton(onClick = { month = month.plusMonths(1) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.calendar_next_month))
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        DayOfWeek.values().forEach { weekday ->
                            Text(weekday.getDisplayName(TextStyle.SHORT_STANDALONE, locale),
                                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                    calendar.cells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                    if (day != null) CalendarDay(day, calendar.days[day], day == selectedDate, day == today) {
                                        onSelectDate(day)
                                    }
                                }
                            }
                        }
                    }
                    val mean = calendar.averageMood
                    Text(if (mean == null) stringResource(R.string.calendar_mood_unspecified)
                        else stringResource(R.string.calendar_month_mood, formatMood(mean, locale)),
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(5.dp), shape = CircleShape, color = colors.primary) {}
                        Spacer(Modifier.width(7.dp))
                        Text(stringResource(R.string.calendar_dot_legend), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(day: LocalDate, summary: CalendarDaySummary?, isSelected: Boolean, isToday: Boolean,
    onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val count = summary?.entryCount ?: 0
    val countDescription = pluralStringResource(R.plurals.calendar_entry_count, count, count)
    val moodDescription = summary?.averageMood?.let { stringResource(R.string.calendar_day_mood, formatMood(it, locale)) }
    val description = listOfNotNull(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
        countDescription, moodDescription, if (isToday) stringResource(R.string.calendar_today) else null).joinToString(". ")
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { contentDescription = description; selected = isSelected },
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
        border = if (isToday) BorderStroke(1.dp, colors.primary.copy(alpha = 0.7f)) else null) {
        Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium)
            Surface(Modifier.size(5.dp), shape = CircleShape,
                color = if (count > 0) colors.primary else androidx.compose.ui.graphics.Color.Transparent) {}
        }
    }
}

private fun formatMood(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 1
}.format(value)
