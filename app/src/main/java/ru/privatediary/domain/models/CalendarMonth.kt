package ru.privatediary.domain.models

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Calendar calculations are independent of Android and use the diary's local dates. */
data class CalendarDaySummary(val entryCount: Int, val averageMood: Double?)

data class CalendarMonth(
    val month: YearMonth,
    /** Six Monday-first weeks. Padding cells do not refer to a day in this month. */
    val cells: List<LocalDate?>,
    val days: Map<LocalDate, CalendarDaySummary>,
    /** Average over entries with a specified mood, rather than over daily averages. */
    val averageMood: Double?,
) {
    companion object {
        fun from(entries: List<Entry>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): CalendarMonth {
            val grouped = entries.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
                .filterKeys { YearMonth.from(it) == month }
            val first = month.atDay(1)
            val leading = first.dayOfWeek.value - 1
            val cells = List(42) { position ->
                val day = position - leading + 1
                if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
            }
            val days = grouped.mapValues { (_, daily) ->
                CalendarDaySummary(daily.size, meanMood(daily))
            }
            return CalendarMonth(month, cells, days, meanMood(grouped.values.flatten()))
        }

        private fun meanMood(entries: List<Entry>): Double? {
            val moods = entries.mapNotNull { it.mood?.takeIf { value -> value in 1..5 } }
            return if (moods.isEmpty()) null else moods.average()
        }
    }
}
