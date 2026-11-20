package ru.privatediary.util

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/** Literal, Unicode-aware fragments; user input is never interpreted as SQL or FTS syntax. */
object WordSearch {
    // Android rejects UNICODE_CHARACTER_CLASS. Spell out Unicode whitespace instead
    // of passing a desktop-only flag during this object's first initialization.
    private val whitespace = Pattern.compile("[\\p{Z}\\u0009-\\u000D\\u0085]+")

    data class Match(val start: Int, val endExclusive: Int)

    class PreparedQuery internal constructor(val terms: List<String>) {
        val isEmpty: Boolean get() = terms.isEmpty()

        /** All query fragments must occur, possibly across the text and different tags. */
        fun matches(text: String, tags: List<String> = emptyList()): Boolean {
            if (isEmpty) return false
            val fields = listOf(normalize(text)) + tags.map(::normalize)
            return terms.all { term -> fields.any { term in it } }
        }

        /** Strong lexical evidence for meaning search: fragments inside a different word do
         * not count. In particular, еда inside победа must not imply a food-related entry. */
        fun matchesWholeWords(text: String, tags: List<String> = emptyList()): Boolean {
            if (isEmpty) return false
            val fields = listOf(normalize(text)) + tags.map(::normalize)
            return terms.all { term -> fields.any { containsWholeWord(it, term) } }
        }

        /** Source UTF-16 ranges, including matches within words or compatibility characters. */
        fun rangesIn(text: String): List<Match> {
            if (isEmpty || text.isEmpty()) return emptyList()
            val normalized = normalize(text)
            val ranges = mutableListOf<Match>()
            for (term in terms) {
                var first = normalized.indexOf(term)
                while (first >= 0) {
                    ranges += Match(sourceOffset(text, first, end = false),
                        sourceOffset(text, first + term.length, end = true))
                    first = normalized.indexOf(term, first + term.length)
                }
            }
            val merged = mutableListOf<Match>()
            for (range in ranges.sortedWith(compareBy<Match> { it.start }.thenBy { it.endExclusive })) {
                val previous = merged.lastOrNull()
                if (previous != null && range.start <= previous.endExclusive) {
                    merged[merged.lastIndex] = Match(previous.start, maxOf(previous.endExclusive, range.endExclusive))
                } else merged += range
            }
            return merged
        }

        /** Keep a real matching fragment visible even when it occurs far into a long entry. */
        fun fragment(text: String, maximumLength: Int = 260): String {
            require(maximumLength >= 2)
            if (text.isEmpty()) return ""
            val normalized = normalize(text)
            val first = terms.map { normalized.indexOf(it) }.filter { it >= 0 }.minOrNull()
            val anchor = if (first == null) 0 else sourceOffset(text, first, end = false)
            var start = maxOf(0, anchor - minOf(80, maximumLength / 3))
            var end = minOf(text.length, start + maximumLength)
            start = maxOf(0, end - maximumLength)
            if (start > 0 && Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) start--
            if (end < text.length && Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) end++
            return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
        }
    }

    fun prepare(input: String): PreparedQuery = PreparedQuery(whitespace.split(normalize(input.take(4096)))
        .asSequence().filter(String::isNotEmpty).distinct().take(32).toList())

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun containsWholeWord(text: String, term: String): Boolean {
        var first = text.indexOf(term)
        while (first >= 0) {
            val end = first + term.length
            val before = first > 0 && wordPart(text.codePointBefore(first))
            val after = end < text.length && wordPart(text.codePointAt(end))
            if (!before && !after) return true
            first = text.indexOf(term, first + 1)
        }
        return false
    }

    private fun wordPart(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) ||
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }

    /** Normalization can expand ligatures or compose accents, so normalized offsets cannot be
     * used directly to slice the original diary text. Grapheme boundaries also protect UTF-16. */
    private fun sourceOffset(text: String, normalizedOffset: Int, end: Boolean): Int {
        if (normalizedOffset <= 0) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var first = iterator.first()
        var count = 0
        var last = iterator.next()
        while (last != BreakIterator.DONE) {
            val next = count + normalize(text.substring(first, last)).length
            if (normalizedOffset < next || (end && normalizedOffset == next)) return if (end) last else first
            count = next
            first = last
            last = iterator.next()
        }
        return text.length
    }
}
