package ru.privatediary.domain.usecases

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.TagInsight

class ComputeDiaryInsightsTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC)
    private val compute = ComputeDiaryInsights(clock)

    @Test fun emptyDiaryHasCompleteDateWindowsAndNoInventedMood() {
        val result = compute(emptyList())
        assertEquals(LocalDate.of(2026, 11, 22), result.today)
        assertEquals(YearMonth.of(2026, 11), result.month)
        assertEquals(0, result.totalEntryCount)
        assertEquals(0, result.currentMonthEntryCount)
        assertEquals(0, result.currentMonthWritingDays)
        assertEquals(0, result.currentStreakDays)
        assertEquals(0, result.longestStreakDays)
        assertNull(result.currentMonthAverageMood)
        assertTrue(result.topTags.isEmpty())
        assertEquals(30, result.dailyMoods.size)
        assertEquals(LocalDate.of(2026, 10, 24), result.dailyMoods.first().date)
        assertEquals(result.today, result.dailyMoods.last().date)
        assertTrue(result.dailyMoods.all { it.entryCount == 0 && it.ratedEntryCount == 0 && it.averageMood == null })
        assertEquals(4, result.weeklyMoods.size)
        assertEquals(LocalDate.of(2026, 10, 26), result.weeklyMoods.first().from)
        assertEquals(result.today, result.weeklyMoods.last().to)
        result.weeklyMoods.zipWithNext().forEach { (left, right) -> assertEquals(left.to.plusDays(1), right.from) }
        assertTrue(result.weeklyMoods.all { it.entryCount == 0 && it.averageMood == null })
    }

    @Test fun multipleEntriesOnOneDayCountSeparatelyButWritingDaysAreUnique() {
        val result = compute(listOf(
            entry("2026-11-01T09:00:00Z", 1),
            entry("2026-11-01T10:00:00Z", 5),
            entry("2026-11-01T11:00:00Z"),
            entry("2026-11-02T10:00:00Z", 5),
            entry("2026-10-20T10:00:00Z", 1),
        ))
        assertEquals(5, result.totalEntryCount)
        assertEquals(4, result.currentMonthEntryCount)
        assertEquals(2, result.currentMonthWritingDays)
        assertEquals(11.0 / 3, result.currentMonthAverageMood!!, 0.000001)
        val first = result.dailyMoods.single { it.date == LocalDate.of(2026, 11, 1) }
        assertEquals(3, first.entryCount)
        assertEquals(2, first.ratedEntryCount)
        assertEquals(3.0, first.averageMood!!, 0.0)
    }

    @Test fun missingAndInvalidMoodsAreExcludedRatherThanTreatedAsZero() {
        val result = compute(listOf(
            entry("2026-11-22T09:00:00Z"),
            entry("2026-11-22T09:01:00Z", 0),
            entry("2026-11-22T09:02:00Z", 6),
            entry("2026-11-22T09:03:00Z", -1),
        ))
        assertEquals(4, result.currentMonthEntryCount)
        assertNull(result.currentMonthAverageMood)
        assertEquals(0, result.dailyMoods.last().ratedEntryCount)
        assertNull(result.dailyMoods.last().averageMood)
        assertNull(result.weeklyMoods.last().averageMood)
    }

    @Test fun aStreakEndingYesterdayStillContinuesAndLongestStreakIsIndependent() {
        val result = compute(listOf(
            entry("2026-11-02T10:00:00Z"),
            entry("2026-11-03T10:00:00Z"),
            entry("2026-11-04T10:00:00Z"),
            entry("2026-11-20T10:00:00Z"),
            entry("2026-11-21T10:00:00Z"),
            entry("2026-11-21T11:00:00Z"),
        ).reversed())
        assertEquals(2, result.currentStreakDays)
        assertEquals(3, result.longestStreakDays)
    }

    @Test fun todayExtendsTheStreakOnceRegardlessOfEntryCount() {
        val result = compute(listOf(
            entry("2026-11-20T10:00:00Z"),
            entry("2026-11-21T10:00:00Z"),
            entry("2026-11-22T10:00:00Z"),
            entry("2026-11-22T11:00:00Z"),
        ))
        assertEquals(3, result.currentStreakDays)
        assertEquals(3, result.longestStreakDays)
        assertEquals(3, result.currentMonthWritingDays)
    }

    @Test fun aMissedCalendarDayEndsTheCurrentStreak() {
        val result = compute(listOf(entry("2026-11-19T10:00:00Z"), entry("2026-11-20T10:00:00Z")))
        assertEquals(0, result.currentStreakDays)
        assertEquals(2, result.longestStreakDays)
    }

    @Test fun fourWeeklyWindowsUseEntryWeightedMoodsAndIncludeTheirBoundaries() {
        val result = compute(listOf(
            entry("2026-10-25T10:00:00Z", 5), // outside four weeks, inside thirty days
            entry("2026-10-26T10:00:00Z", 1),
            entry("2026-11-01T10:00:00Z", 3),
            entry("2026-11-01T11:00:00Z", 5),
            entry("2026-11-01T12:00:00Z"),
            entry("2026-11-02T10:00:00Z", 2),
            entry("2026-11-22T10:00:00Z", 4),
        ))
        val first = result.weeklyMoods.first()
        assertEquals(4, first.entryCount)
        assertEquals(3, first.ratedEntryCount)
        assertEquals(3.0, first.averageMood!!, 0.0)
        assertEquals(2.0, result.weeklyMoods[1].averageMood!!, 0.0)
        assertNull(result.weeklyMoods[2].averageMood)
        assertEquals(4.0, result.weeklyMoods[3].averageMood!!, 0.0)
        assertEquals(7, result.dailyMoods.sumOf { it.entryCount })
    }

    @Test fun tagsAreNormalizedAndCountedOncePerEntryWithinThirtyLocalDates() {
        val result = compute(listOf(
            entry("2026-10-23T10:00:00Z", tags = listOf("too-old")),
            entry("2026-10-24T10:00:00Z", tags = listOf(" #Отдых ", "отдых", "ＯＦＦ", "off", "", " # ")),
            entry("2026-11-22T10:00:00Z", tags = listOf("ОТДЫХ", "OFF", "работа")),
        ))
        assertEquals(listOf(TagInsight("off", 2), TagInsight("отдых", 2), TagInsight("работа", 1)), result.topTags)
        assertEquals(3, result.totalEntryCount)
        assertEquals(2, result.dailyMoods.sumOf { it.entryCount })
    }

    @Test fun tagRankingIsLimitedToFiveAndBreaksTiesDeterministically() {
        val result = compute(listOf(
            entry("2026-11-21T10:00:00Z", tags = listOf("z", "d", "c", "b", "a", "e")),
            entry("2026-11-22T10:00:00Z", tags = listOf("z")),
        ))
        assertEquals(listOf("z", "a", "b", "c", "d"), result.topTags.map { it.tag })
        assertEquals(listOf(2, 1, 1, 1, 1), result.topTags.map { it.entryCount })
    }

    @Test fun futureLocalDatesDoNotAffectCountsMoodStreaksOrTags() {
        val result = compute(listOf(
            entry("2026-11-22T23:00:00Z", 2, listOf("today")),
            entry("2026-11-23T00:00:00Z", 5, listOf("future")),
            entry("2027-01-01T00:00:00Z", 5, listOf("future")),
        ))
        assertEquals(1, result.totalEntryCount)
        assertEquals(1, result.currentMonthEntryCount)
        assertEquals(1, result.currentStreakDays)
        assertEquals(2.0, result.currentMonthAverageMood!!, 0.0)
        assertEquals(listOf(TagInsight("today", 1)), result.topTags)
    }

    @Test fun explicitZoneAppliesToTodayAndEntriesAtYearBoundary() {
        val yearEndClock = Clock.fixed(Instant.parse("2026-12-31T22:30:00Z"), ZoneOffset.UTC)
        val moscow = ZoneId.of("Europe/Moscow")
        val entries = listOf(entry("2026-12-31T21:30:00Z", 4))
        val local = ComputeDiaryInsights(yearEndClock, moscow)(entries)
        assertEquals(LocalDate.of(2027, 1, 1), local.today)
        assertEquals(YearMonth.of(2027, 1), local.month)
        assertEquals(1, local.currentMonthEntryCount)
        assertEquals(1, local.currentMonthWritingDays)
        assertEquals(1, local.currentStreakDays)
        assertEquals(1, local.dailyMoods.last().entryCount)
    }

    @Test fun daylightSavingSpringForwardUsesCalendarDaysForStreakAndWindows() {
        val newYork = ZoneId.of("America/New_York")
        val dstClock = Clock.fixed(Instant.parse("2026-03-09T14:00:00Z"), newYork)
        val result = ComputeDiaryInsights(dstClock)(listOf(
            entry("2026-03-08T04:30:00Z", 1), // March 7, 23:30 EST
            entry("2026-03-09T03:30:00Z", 3), // March 8, 23:30 EDT: a 23-hour day
            entry("2026-03-09T13:00:00Z", 5),
        ))
        assertEquals(3, result.currentStreakDays)
        assertEquals(3, result.currentMonthWritingDays)
        assertEquals(3.0, result.currentMonthAverageMood!!, 0.0)
        assertEquals(listOf(1, 1, 1), result.dailyMoods.takeLast(3).map { it.entryCount })
        result.dailyMoods.zipWithNext().forEach { (left, right) -> assertEquals(left.date.plusDays(1), right.date) }
    }

    @Test fun daylightSavingRepeatedHourStillBelongsToOneWritingDay() {
        val newYork = ZoneId.of("America/New_York")
        val dstClock = Clock.fixed(Instant.parse("2026-11-02T14:00:00Z"), newYork)
        val result = ComputeDiaryInsights(dstClock)(listOf(
            entry("2026-11-01T05:30:00Z", 1), // 01:30 EDT
            entry("2026-11-01T06:30:00Z", 5), // 01:30 EST
        ))
        assertEquals(2, result.currentMonthEntryCount)
        assertEquals(1, result.currentMonthWritingDays)
        assertEquals(1, result.currentStreakDays)
        val repeatedDay = result.dailyMoods.single { it.date == LocalDate.of(2026, 11, 1) }
        assertEquals(2, repeatedDay.entryCount)
        assertEquals(3.0, repeatedDay.averageMood!!, 0.0)
    }

    @Test fun tagCaseNormalizationDoesNotDependOnDeviceLocale() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val result = compute(listOf(entry("2026-11-22T10:00:00Z", tags = listOf("INBOX", "inbox"))))
            assertEquals(listOf(TagInsight("inbox", 1)), result.topTags)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test fun longEntryTextDoesNotChangeStatisticsAndInputsRemainUntouched() {
        val tags = listOf(" Работа ", "работа")
        val short = entry("2026-11-22T10:00:00Z", 4, tags)
        val long = short.copy(text = "Личный текст diary ".repeat(100_000))
        assertEquals(compute(listOf(short)), compute(listOf(long)))
        assertEquals(listOf(" Работа ", "работа"), long.tags)
    }

    private fun entry(at: String, mood: Int? = null, tags: List<String> = emptyList()) =
        Entry(timestamp = Instant.parse(at).toEpochMilli(), text = "Private diary entry", mood = mood, tags = tags)
}
