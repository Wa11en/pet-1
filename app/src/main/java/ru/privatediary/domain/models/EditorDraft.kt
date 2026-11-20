package ru.privatediary.domain.models

/** Stored only inside SQLCipher. Zero identifies the unfinished new entry. */
data class EditorDraft(
    val entryId: Long = 0,
    val timestamp: Long,
    val text: String = "",
    val mood: Int? = null,
    val rawTags: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toEntry(): Entry = Entry(entryId, timestamp, text, mood,
        rawTags.split(',').map { it.trim().removePrefix("#") }.filter(String::isNotEmpty).distinct())

    fun validated(): EditorDraft {
        require(entryId >= 0 && text.length <= 1_000_000 && rawTags.length <= 6_000)
        require(mood == null || mood in 1..5)
        return copy(selectionStart = selectionStart.coerceIn(0, text.length),
            selectionEnd = selectionEnd.coerceIn(0, text.length))
    }

    companion object {
        fun from(entry: Entry) = EditorDraft(entry.id, entry.timestamp, entry.text, entry.mood,
            entry.tags.joinToString(", "), entry.text.length, entry.text.length)
    }
}
