package ru.privatediary.domain.usecases

import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import ru.privatediary.domain.models.DailyMoodInsight
import ru.privatediary.domain.models.DiaryInsights
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.TagInsight
import ru.privatediary.domain.models.WeeklyMoodInsight

/**
 * Local calendar and arithmetic only: does not inspect entry text, access Android or retain entries.
 * Future local dates are excluded. Dates, streaks and windows follow the injected time zone, so
 * daylight-saving days are treated as calendar days rather than as fixed 24-hour periods.
 */
class ComputeDiaryInsights(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = clock.zone,
) {
    operator fun invoke(entries: List<Entry>): DiaryInsights {
        val today = clock.instant().atZone(zone).toLocalDate()
        val month = YearMonth.from(today)
        val firstDay = today.minusDays(29)
        val days = mutableMapOf<LocalDate, MoodTotals>()
        val tagCounts = mutableMapOf<String, Int>()
        val monthly = MoodTotals()
        var totalEntries = 0
        var monthWritingDays = 0

        for (entry in entries) {
            val date = Instant.ofEpochMilli(entry.timestamp).atZone(zone).toLocalDate()
            if (date > today) continue
            totalEntries++
            val daily = days.getOrPut(date) { MoodTotals() }
            if (YearMonth.from(date) == month) {
                if (daily.entryCount == 0) monthWritingDays++
                monthly.add(entry.mood)
            }
            daily.add(entry.mood)
            if (date >= firstDay) {
                entry.tags.asSequence().map(::normalizeTag).filter { it.isNotEmpty() }.distinct()
                    .forEach { tag -> tagCounts[tag] = (tagCounts[tag] ?: 0) + 1 }
            }
        }

        val dailyMoods = List(30) { offset ->
            val date = firstDay.plusDays(offset.toLong())
            val totals = days[date]
            DailyMoodInsight(date, totals?.entryCount ?: 0, totals?.ratedEntryCount ?: 0, totals?.average)
        }
        val weeklyMoods = List(4) { offset ->
            val from = today.minusDays((27 - offset * 7).toLong())
            val to = from.plusDays(6)
            val totals = MoodTotals()
            repeat(7) { day -> days[from.plusDays(day.toLong())]?.let(totals::merge) }
            WeeklyMoodInsight(from, to, totals.entryCount, totals.ratedEntryCount, totals.average)
        }

        val writingDates = days.keys.sorted()
        var longestStreak = 0
        var run = 0
        var previous: LocalDate? = null
        for (date in writingDates) {
            run = if (previous?.plusDays(1) == date) run + 1 else 1
            longestStreak = maxOf(longestStreak, run)
            previous = date
        }
        // Allow the user to write later today without losing yesterday's continuing streak.
        val currentStreak = if (previous == today || previous == today.minusDays(1)) run else 0
        val topTags = tagCounts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
        ).take(5).map { TagInsight(it.key, it.value) }

        return DiaryInsights(
            today = today,
            month = month,
            totalEntryCount = totalEntries,
            currentMonthEntryCount = monthly.entryCount,
            currentMonthWritingDays = monthWritingDays,
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            currentMonthAverageMood = monthly.average,
            dailyMoods = dailyMoods,
            weeklyMoods = weeklyMoods,
            topTags = topTags,
        )
    }

    private fun normalizeTag(tag: String): String =
        Normalizer.normalize(tag, Normalizer.Form.NFKC).trim().removePrefix("#").trim()
            .lowercase(Locale.ROOT)

    private class MoodTotals {
        var entryCount = 0
        var ratedEntryCount = 0
        private var moodSum = 0L
        val average: Double? get() = if (ratedEntryCount == 0) null else moodSum.toDouble() / ratedEntryCount

        fun add(mood: Int?) {
            entryCount++
            if (mood != null && mood in 1..5) {
                ratedEntryCount++
                moodSum += mood
            }
        }

        fun merge(other: MoodTotals) {
            entryCount += other.entryCount
            ratedEntryCount += other.ratedEntryCount
            moodSum += other.moodSum
        }
    }
}
