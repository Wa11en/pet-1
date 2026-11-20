package ru.privatediary.domain.models

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CalendarMonthTest {
    @Test fun leapFebruaryHasMondayFirstPaddingAndTwentyNineDays() {
        val month = CalendarMonth.from(emptyList(), YearMonth.of(2024, 2), ZoneId.of("UTC"))
        assertEquals(42, month.cells.size)
        assertEquals(listOf(null, null, null), month.cells.take(3))
        assertEquals(LocalDate.of(2024, 2, 1), month.cells[3])
        assertEquals(LocalDate.of(2024, 2, 29), month.cells[31])
        assertTrue(month.cells.drop(32).all { it == null })
        assertEquals(29, month.cells.filterNotNull().distinct().size)
    }

    @Test fun aMonthStartingOnMondayNeedsNoLeadingPadding() {
        val month = CalendarMonth.from(emptyList(), YearMonth.of(2026, 6), ZoneId.of("UTC"))
        assertEquals(LocalDate.of(2026, 6, 1), month.cells.first())
        assertEquals(LocalDate.of(2026, 6, 7), month.cells[6])
        assertNull(month.cells[30])
    }

    @Test fun aLongMonthStartingOnSundayUsesTheSixthWeek() {
        val month = CalendarMonth.from(emptyList(), YearMonth.of(2026, 3), ZoneId.of("UTC"))
        assertTrue(month.cells.take(6).all { it == null })
        assertEquals(LocalDate.of(2026, 3, 1), month.cells[6])
        assertEquals(LocalDate.of(2026, 3, 31), month.cells[36])
    }

    @Test fun localTimezoneDeterminesTheMonthAndDay() {
        val entry = entry("2026-03-31T22:30:00Z", mood = 4)
        val aprilInMoscow = CalendarMonth.from(listOf(entry), YearMonth.of(2026, 4), ZoneId.of("Europe/Moscow"))
        val aprilInUtc = CalendarMonth.from(listOf(entry), YearMonth.of(2026, 4), ZoneId.of("UTC"))
        assertEquals(1, aprilInMoscow.days[LocalDate.of(2026, 4, 1)]?.entryCount)
        assertEquals(4.0, aprilInMoscow.averageMood!!, 0.0)
        assertTrue(aprilInUtc.days.isEmpty())
        assertNull(aprilInUtc.averageMood)
    }

    @Test fun countsIncludeUnratedEntriesAndMoodIsWeightedByEntries() {
        val entries = listOf(
            entry("2026-11-01T10:00:00Z", 1),
            entry("2026-11-01T11:00:00Z", 5),
            entry("2026-11-01T12:00:00Z", null),
            entry("2026-11-02T10:00:00Z", 5),
            entry("2026-10-01T10:00:00Z", 1),
        )
        val month = CalendarMonth.from(entries, YearMonth.of(2026, 11), ZoneId.of("UTC"))
        assertEquals(setOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)), month.days.keys)
        val first = month.days.getValue(LocalDate.of(2026, 11, 1))
        assertEquals(3, first.entryCount)
        assertEquals(3.0, first.averageMood!!, 0.0)
        assertEquals(11.0 / 3, month.averageMood!!, 0.000001)
    }

    @Test fun missingMoodRemainsMissing() {
        val month = CalendarMonth.from(listOf(entry("2026-11-20T12:00:00Z", null)),
            YearMonth.of(2026, 11), ZoneId.of("UTC"))
        assertEquals(1, month.days.getValue(LocalDate.of(2026, 11, 20)).entryCount)
        assertNull(month.days.getValue(LocalDate.of(2026, 11, 20)).averageMood)
        assertNull(month.averageMood)
    }

    private fun entry(at: String, mood: Int?) = Entry(timestamp = Instant.parse(at).toEpochMilli(),
        text = "Diary entry", mood = mood)
}
