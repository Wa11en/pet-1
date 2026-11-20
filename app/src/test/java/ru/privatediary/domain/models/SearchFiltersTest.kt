package ru.privatediary.domain.models

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SearchFiltersTest {
    private fun entry(at: String, mood: Int? = 4, tags: List<String> = listOf("Прогулка")) =
        Entry(1, Instant.parse(at).toEpochMilli(), "Запись", mood, tags)

    @Test fun emptyFiltersHaveNoConditionsAndBlankTagDoesNotActivateThem() {
        assertTrue(SearchFilters().isEmpty)
        assertTrue(SearchFilters(tag = "  ").isEmpty)
        assertEquals(0, SearchFilters().activeCount)
        assertTrue(SearchFilters().matches(entry("2026-11-20T12:00:00Z")))
        assertEquals(4, SearchFilters(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 4, "лес").activeCount)
    }

    @Test fun dateBoundsIncludeBothWholeLocalDays() {
        val day = LocalDate.of(2026, 11, 20)
        val filters = SearchFilters(from = day, to = day)
        val zone = ZoneId.of("Europe/Moscow")
        assertFalse(filters.matches(entry("2026-11-19T20:59:59.999Z"), zone))
        assertTrue(filters.matches(entry("2026-11-19T21:00:00Z"), zone))
        assertTrue(filters.matches(entry("2026-11-20T20:59:59.999Z"), zone))
        assertFalse(filters.matches(entry("2026-11-20T21:00:00Z"), zone))
        assertFalse(filters.matches(entry("2026-11-19T21:00:00Z"), ZoneOffset.UTC))
    }

    @Test fun daylightSavingDaysUseTheirActualCalendarBoundaries() {
        val zone = ZoneId.of("Europe/Berlin")
        val spring = SearchFilters(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29))
        assertTrue(spring.matches(entry("2026-03-28T23:00:00Z"), zone))
        assertTrue(spring.matches(entry("2026-03-29T21:59:59.999Z"), zone))
        assertFalse(spring.matches(entry("2026-03-29T22:00:00Z"), zone))
        val autumn = SearchFilters(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25))
        assertTrue(autumn.matches(entry("2026-10-24T22:00:00Z"), zone))
        assertTrue(autumn.matches(entry("2026-10-25T22:59:59.999Z"), zone))
        assertFalse(autumn.matches(entry("2026-10-25T23:00:00Z"), zone))
    }

    @Test fun allConditionsMustMatchAndTagsAreWholeNormalizedNames() {
        val filters = SearchFilters(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 4, "  ПРОГУЛКА ")
        val original = entry("2026-11-20T12:00:00Z")
        assertTrue(filters.matches(original, ZoneOffset.UTC))
        assertFalse(filters.matches(original.copy(mood = null), ZoneOffset.UTC))
        assertFalse(filters.matches(original.copy(mood = 3), ZoneOffset.UTC))
        assertFalse(filters.matches(original.copy(tags = listOf("Прогулка-домой")), ZoneOffset.UTC))
        assertFalse(filters.matches(entry("2026-12-01T12:00:00Z"), ZoneOffset.UTC))
        assertTrue(SearchFilters(tag = "CAFÉ").matches(original.copy(tags = listOf("cafe\u0301"))))
        assertTrue(SearchFilters(tag = "work").matches(original.copy(tags = listOf("ＷＯＲＫ"))))
    }

    @Test fun openEndedRangesWorkAndAnInvertedRangeReturnsNoEntries() {
        val row = entry("2026-11-20T12:00:00Z")
        assertTrue(SearchFilters(from = LocalDate.of(2026, 11, 1)).matches(row, ZoneOffset.UTC))
        assertTrue(SearchFilters(to = LocalDate.of(2026, 12, 1)).matches(row, ZoneOffset.UTC))
        assertFalse(SearchFilters(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 1)).matches(row, ZoneOffset.UTC))
    }
}
