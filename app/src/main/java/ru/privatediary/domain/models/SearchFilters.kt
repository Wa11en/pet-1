package ru.privatediary.domain.models

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Inclusive local calendar dates; filtering is shared by keyword and semantic search. */
data class SearchFilters(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val mood: Int? = null,
    val tag: String? = null,
) {
    val activeCount: Int get() = (if (from != null) 1 else 0) +
        (if (to != null) 1 else 0) + (if (mood != null) 1 else 0) +
        (if (!tag.isNullOrBlank()) 1 else 0)
    val isEmpty: Boolean get() = activeCount == 0

    fun matches(entry: Entry, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (mood != null && entry.mood != mood) return false
        if (!tag.isNullOrBlank()) {
            val expected = normalizeTag(tag)
            if (entry.tags.none { normalizeTag(it) == expected }) return false
        }
        if (from != null || to != null) {
            val day = Instant.ofEpochMilli(entry.timestamp).atZone(zone).toLocalDate()
            if (from != null && day < from) return false
            if (to != null && day > to) return false
        }
        return true
    }

    private fun normalizeTag(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
