package ru.privatediary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.privatediary.R
import ru.privatediary.domain.models.DailyMoodInsight
import ru.privatediary.domain.models.DiaryInsights
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.usecases.ComputeDiaryInsights

/** The caller owns the unlock/Pro gate; derived values live only as long as this screen. */
@Composable
internal fun InsightsScreen(entries: List<Entry>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val insights by produceState<DiaryInsights?>(null, entries) {
        value = null
        value = withContext(Dispatchers.Default) { ComputeDiaryInsights()(entries) }
    }
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val numberFormat = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    Scaffold(
        containerColor = colors.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedIconButton(onClick = onBack, modifier = Modifier.size(48.dp),
                    shape = CircleShape, border = BorderStroke(1.dp, colors.outlineVariant)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), Modifier.size(21.dp))
                }
                Text(stringResource(R.string.insights_title), style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "privacy") {
                Text(stringResource(R.string.insights_privacy), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            val data = insights
            when {
                data == null -> item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.insights_loading))
                    }
                }
                data.totalEntryCount == 0 -> item(key = "empty") {
                    InsightsCard {
                        Text(stringResource(R.string.insights_empty_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.insights_empty_description), color = colors.onSurfaceVariant)
                    }
                }
                else -> {
                    item(key = "month") {
                        InsightsCard {
                            Text(stringResource(R.string.insights_month, data.month.format(monthFormat)),
                                style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                InsightsMetric(data.currentMonthEntryCount.toString(),
                                    stringResource(R.string.insights_entries_month), Modifier.weight(1f))
                                InsightsMetric(stringResource(R.string.insights_writing_days_value,
                                    data.currentMonthWritingDays, data.today.dayOfMonth),
                                    stringResource(R.string.insights_writing_days), Modifier.weight(1f))
                            }
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
                            Text(stringResource(R.string.insights_mood_average),
                                style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                            Text(insightMoodLabel(data.currentMonthAverageMood, numberFormat),
                                style = MaterialTheme.typography.headlineSmall, color = colors.primary)
                            Text(stringResource(R.string.insights_mood_explanation),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Text(stringResource(R.string.insights_all_entries, data.totalEntryCount),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item(key = "rhythm") {
                        InsightsCard {
                            Text(stringResource(R.string.insights_regularity), style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                InsightsMetric(pluralStringResource(R.plurals.insights_streak_days,
                                    data.currentStreakDays, data.currentStreakDays),
                                    stringResource(R.string.insights_current_streak), Modifier.weight(1f))
                                InsightsMetric(pluralStringResource(R.plurals.insights_streak_days,
                                    data.longestStreakDays, data.longestStreakDays),
                                    stringResource(R.string.insights_longest_streak), Modifier.weight(1f))
                            }
                            Text(stringResource(R.string.insights_streak_explanation),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    item(key = "daily_moods") {
                        InsightsCard {
                            Text(stringResource(R.string.insights_last_thirty_days),
                                style = MaterialTheme.typography.titleMedium)
                            MoodChart(data.dailyMoods, numberFormat, dateFormat)
                            Text(stringResource(R.string.insights_chart_scale),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    item(key = "weeks") {
                        InsightsCard {
                            Text(stringResource(R.string.insights_four_weeks), style = MaterialTheme.typography.titleMedium)
                            data.weeklyMoods.forEach { week ->
                                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.insights_week_range,
                                            week.from.format(dateFormat), week.to.format(dateFormat)),
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(pluralStringResource(R.plurals.insights_week_entries,
                                            week.entryCount, week.entryCount),
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                    Text(insightMoodLabel(week.averageMood, numberFormat),
                                        style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End,
                                        modifier = Modifier.widthIn(max = 160.dp))
                                }
                            }
                        }
                    }
                    item(key = "tags") {
                        InsightsCard {
                            Text(stringResource(R.string.insights_tags), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.insights_tags_period),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            if (data.topTags.isEmpty()) {
                                Text(stringResource(R.string.insights_tags_empty), style = MaterialTheme.typography.bodyMedium)
                            }
                            data.topTags.forEach { tag ->
                                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("#${tag.tag}", modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(pluralStringResource(R.plurals.insights_tag_count,
                                        tag.entryCount, tag.entryCount), style = MaterialTheme.typography.labelLarge,
                                        color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item(key = "dates_note") {
                        Text(stringResource(R.string.insights_dates_note), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun InsightsMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun insightMoodLabel(average: Double?, numberFormat: NumberFormat): String =
    if (average == null) stringResource(R.string.insights_mood_missing)
    else stringResource(R.string.insights_mood_value, numberFormat.format(average))

@Composable
private fun MoodChart(days: List<DailyMoodInsight>, numberFormat: NumberFormat, dateFormat: DateTimeFormatter) {
    val colors = MaterialTheme.colorScheme
    val dailyDescriptions = days.map { day ->
        stringResource(R.string.insights_chart_day, day.date.format(dateFormat),
            insightMoodLabel(day.averageMood, numberFormat))
    }.joinToString("; ")
    val description = stringResource(R.string.insights_chart_description, dailyDescriptions)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.height(148.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("5", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text("1", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Canvas(Modifier.weight(1f).height(148.dp).semantics { contentDescription = description }) {
            val slot = size.width / days.size
            val gap = minOf(3.dp.toPx(), slot * 0.35f)
            val chartHeight = size.height - 4.dp.toPx()
            val baseline = chartHeight
            for (mood in 1..5) {
                val y = baseline - chartHeight * mood / 5f
                drawLine(colors.outlineVariant.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            days.forEachIndexed { index, day ->
                val left = index * slot + gap / 2f
                val width = slot - gap
                val mood = day.averageMood
                if (mood == null) {
                    drawLine(colors.onSurfaceVariant.copy(alpha = 0.5f),
                        Offset(left, baseline), Offset(left + width, baseline), 2.dp.toPx())
                } else {
                    val height = chartHeight * mood.toFloat() / 5f
                    drawRoundRect(colors.primary, Offset(left, baseline - height), Size(width, height), CornerRadius(2.dp.toPx()))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(days.first().date.format(dateFormat), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(days.last().date.format(dateFormat), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}
