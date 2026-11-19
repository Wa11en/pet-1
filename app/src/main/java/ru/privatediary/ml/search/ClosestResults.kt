package ru.privatediary.ml.search

import ru.privatediary.domain.models.SearchResult

/**
 * A conservative first page for E5's tightly clustered cosine scores, not a relevance classifier.
 * The 0.01 window is an explicit retrieval heuristic; all other ranked hits remain accessible.
 * Never rescale the scores against the current diary: even a singleton is not a 100% match.
 */
object ClosestResults {
    private const val MAX_GAP = 0.01f

    data class Selection(val primary: List<SearchResult>, val additional: List<SearchResult>)

    fun select(results: List<SearchResult>): Selection {
        // A null score means keyword search, including the missing-model fallback.
        if (results.isEmpty() || results.any { it.score == null }) return Selection(results, emptyList())
        val valid = results.filter { it.score!!.isFinite() }
        val best = valid.maxOfOrNull { it.score!! } ?: return Selection(emptyList(), emptyList())
        val (primary, additional) = valid.partition { best - it.score!! <= MAX_GAP + 0.000001f }
        return Selection(primary, additional)
    }
}
