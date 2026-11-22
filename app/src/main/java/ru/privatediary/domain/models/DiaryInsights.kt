package ru.privatediary.domain.models

import java.time.LocalDate
import java.time.YearMonth

/** Derived, in-memory summaries. No diary text or persistent statistics cache is needed. */
data class DiaryInsights(
    val today: LocalDate,
    val month: YearMonth,
    val totalEntryCount: Int,
    val currentMonthEntryCount: Int,
    val currentMonthWritingDays: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    /** Weighted by rated entries, not by daily averages. Null means no recorded mood. */
    val currentMonthAverageMood: Double?,
    /** Thirty consecutive local dates, including today and dates without entries. */
    val dailyMoods: List<DailyMoodInsight>,
    /** Four consecutive seven-day ranges, oldest first, with the last ending today. */
    val weeklyMoods: List<WeeklyMoodInsight>,
    /** Up to five normalized tags, counted once per entry over the last thirty days. */
    val topTags: List<TagInsight>,
)

data class DailyMoodInsight(
    val date: LocalDate,
    val entryCount: Int,
    val ratedEntryCount: Int,
    val averageMood: Double?,
)

data class WeeklyMoodInsight(
    val from: LocalDate,
    val to: LocalDate,
    val entryCount: Int,
    val ratedEntryCount: Int,
    val averageMood: Double?,
)

data class TagInsight(val tag: String, val entryCount: Int)
