package ru.privatediary.domain.models

/** Epoch milliseconds; tags preserve their display spelling. */
data class Entry(val id: Long = 0, val timestamp: Long, val text: String, val mood: Int? = null,
    val tags: List<String> = emptyList(), val isFavorite: Boolean = false)
data class SearchResult(val entry: Entry, val fragment: String, val score: Float? = null)
data class EmbeddingChunk(val entryId: Long, val ordinal: Int, val fragment: String, val vector: FloatArray, val contentHash: String)
