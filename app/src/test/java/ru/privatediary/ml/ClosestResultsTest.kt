package ru.privatediary.ml

import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.ml.search.ClosestResults

class ClosestResultsTest {
    private fun hit(id: Long, text: String, score: Float?) = SearchResult(Entry(id, 0L, text), text, score)

    @Test fun weakerHitsStayAccessibleAndRawScoresArePreserved() {
        val pizza = hit(1, "поел пиццу", 0.833931f)
        val soup = hit(2, "съел суп", 0.829f)
        val game = hit(3, "выиграл в доту", 0.815975f)
        val selected = ClosestResults.select(listOf(pizza, soup, game))
        assertEquals(listOf(pizza, soup), selected.primary)
        assertEquals(listOf(game), selected.additional)
        assertEquals(listOf(pizza, soup, game), selected.primary + selected.additional)
        assertEquals(0.833931f, selected.primary.first().score!!, 0f)
    }

    @Test fun tiesAndBoundaryHitsAreNotLost() {
        val hits = listOf(hit(1, "a", 0.85f), hit(2, "b", 0.85f), hit(3, "c", 0.84f))
        assertEquals(hits, ClosestResults.select(hits).primary)
    }

    @Test fun keywordFallbackIsNeverFiltered() {
        val words = listOf(hit(1, "еда", null), hit(2, "еще еда", null))
        assertEquals(words, ClosestResults.select(words).primary)
        assertTrue(ClosestResults.select(words).additional.isEmpty())
    }

    @Test fun singletonKeepsItsActualScoreAndIsNotPresentedAsCertain() {
        val only = hit(1, "unrelated", 0.815975f)
        assertEquals(listOf(only), ClosestResults.select(listOf(only)).primary)
        assertTrue(ClosestResults.select(emptyList()).primary.isEmpty())
        assertTrue(ClosestResults.select(listOf(hit(1, "invalid", Float.NaN))).primary.isEmpty())
    }
}
