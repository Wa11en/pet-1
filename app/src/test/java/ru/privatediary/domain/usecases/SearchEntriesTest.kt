package ru.privatediary.domain.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.domain.models.SearchFilters
import ru.privatediary.testing.SearchFakeRepository

class SearchEntriesTest {
    private val wordHit = SearchResult(Entry(1, 0, "озеро"), "озеро")

    @Test fun missingModelFallsBackToFts() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { _, _ -> null }
        assertEquals(listOf(wordHit), search("озеро", byMeaning = true))
        assertEquals(listOf("озеро"), repository.wordQueries)
    }

    @Test fun validEmptySemanticResultDoesNotBecomeAKeywordSearch() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { _, _ -> emptyList() }
        assertTrue(search("озеро", byMeaning = true).isEmpty())
        assertTrue(repository.wordQueries.isEmpty())
    }

    @Test fun wordsModeAndBlankQueriesDoNotInvokeTheModel() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { _, _ -> error("Model must not run") }
        assertEquals(listOf(wordHit), search("озеро", byMeaning = false))
        assertTrue(search(" \n ", byMeaning = true).isEmpty())
        assertEquals(listOf("озеро"), repository.wordQueries)
    }

    @Test fun aFailedOptionalEngineFallsBackToFts() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { _, _ -> throw IllegalStateException("Unsupported model") }
        assertEquals(listOf(wordHit), search("озеро", byMeaning = true))
        assertEquals(listOf("озеро"), repository.wordQueries)
    }

    @Test fun cancellationIsPropagatedWithoutStartingFts() = runBlocking {
        val repository = SearchFakeRepository()
        val cancellation = CancellationException("locked")
        val search = SearchEntries(repository) { _, _ -> throw cancellation }
        try {
            search("озеро", byMeaning = true)
            fail("Cancellation was swallowed")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertTrue(repository.wordQueries.isEmpty())
    }

    @Test fun activeFiltersBrowseMatchingEntriesWithoutCallingAnySearchEngine() = runBlocking {
        val excluded = (1L..120L).map { Entry(it, it + 1000, "newer excluded", mood = 1) }
        val older = Entry(121, 1, "older eligible", mood = 4, tags = listOf("Дом"))
        val newer = Entry(122, 2, "newer eligible", mood = 4, tags = listOf("дом"))
        val repository = SearchFakeRepository(excluded + older + newer)
        val search = SearchEntries(repository) { _, _ -> error("Browsing does not need the model") }
        val results = search(" \n ", byMeaning = true, SearchFilters(mood = 4, tag = "ДОМ"))
        assertEquals(listOf(newer, older), results.map { it.entry })
        assertTrue(repository.wordQueries.isEmpty())
        assertEquals(1, repository.allEntriesCalls)
        assertTrue(results.all { it.score == null })
    }

    @Test fun metadataFiltersReachBothSemanticAndKeywordFallbackPaths() = runBlocking {
        val selected = wordHit.copy(entry = wordHit.entry.copy(mood = 4))
        val excluded = wordHit.copy(entry = wordHit.entry.copy(id = 2, mood = 1))
        val repository = SearchFakeRepository().apply { wordResults = listOf(excluded, selected) }
        val filters = SearchFilters(mood = 4)
        var modelCalls = 0
        val search = SearchEntries(repository) { query, actualFilters ->
            assertEquals("озеро", query)
            assertSame(filters, actualFilters)
            modelCalls++
            null
        }
        assertEquals(listOf(selected), search("озеро", byMeaning = true, filters))
        assertEquals(listOf(selected), search("озеро", byMeaning = false, filters))
        assertEquals(1, modelCalls)
        assertEquals(listOf(filters, filters), repository.wordFilters)
    }

    @Test fun aValidFilteredRejectionStillDoesNotTriggerKeywordFallback() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { _, _ -> emptyList() }
        assertTrue(search("озеро", byMeaning = true, SearchFilters(mood = 4)).isEmpty())
        assertTrue(repository.wordQueries.isEmpty())
    }
}
