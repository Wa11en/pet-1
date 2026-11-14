package ru.privatediary.data.repository

/** Only Unicode words enter FTS syntax; punctuation is never executable. */
object FtsQuery {
    private val word = Regex("[\\p{L}\\p{N}]+")
    fun fromUserInput(query: String): String = word.findAll(query.take(4096)).take(32)
        .map { "\"${it.value}*\"" }.joinToString(" AND ")
}
