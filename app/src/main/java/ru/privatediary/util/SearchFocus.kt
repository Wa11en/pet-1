package ru.privatediary.util

/** Locate a search result in the editor's current text, including a recovered edited draft. */
object SearchFocus {
    data class Selection(val start: Int, val endExclusive: Int)

    fun selection(text: String, fragment: String, query: String, semantic: Boolean): Selection? {
        if (text.isEmpty()) return null
        val context = locateFragment(text, fragment)
        val prepared = WordSearch.prepare(query)
        if (semantic && context != null) return safeSelection(text, context.start, context.endExclusive)

        // Prefer the occurrence inside the actual result snippet when the query repeats.
        if (context != null) {
            val match = prepared.rangesIn(text.substring(context.start, context.endExclusive)).firstOrNull()
            if (match != null) return safeSelection(text,
                context.start + match.start, context.start + match.endExclusive)
        }
        val match = prepared.rangesIn(text).firstOrNull()
        if (match != null) return safeSelection(text, match.start, match.endExclusive)
        // A tag-only hit has no word to select in the body. Keep the cursor near its preview.
        return context?.let { safeSelection(text, it.start, it.start) }
    }

    private fun locateFragment(text: String, fragment: String): Selection? {
        if (fragment.isBlank()) return null
        val exact = text.indexOf(fragment)
        if (exact >= 0) return Selection(exact, exact + fragment.length)
        // FTS inserts separators between omitted passages; keyword preview also adds ellipses.
        // Always try the original snippet first so literal diary ellipses remain meaningful.
        for (piece in fragment.split('…').map(String::trim).filter(String::isNotEmpty).sortedByDescending(String::length)) {
            val start = text.indexOf(piece)
            if (start >= 0) return Selection(start, start + piece.length)
        }
        return null
    }

    private fun safeSelection(text: String, start: Int, end: Int): Selection {
        var first = start.coerceIn(0, text.length)
        var last = end.coerceIn(first, text.length)
        if (first > 0 && first < text.length && Character.isLowSurrogate(text[first]) && Character.isHighSurrogate(text[first - 1])) first--
        if (last > 0 && last < text.length && Character.isLowSurrogate(text[last]) && Character.isHighSurrogate(text[last - 1])) last++
        return Selection(first, last)
    }
}
