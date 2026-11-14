package ru.privatediary.domain.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.testing.SearchFakeRepository

class SearchEntriesTest {
    private val wordHit = SearchResult(Entry(1, 0, "озеро"), "озеро")

    @Test fun missingModelFallsBackToFts() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { null }
        assertEquals(listOf(wordHit), search("озеро", byMeaning = true))
        assertEquals(listOf("озеро"), repository.wordQueries)
    }

    @Test fun validEmptySemanticResultDoesNotBecomeAKeywordSearch() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { emptyList() }
        assertTrue(search("озеро", byMeaning = true).isEmpty())
        assertTrue(repository.wordQueries.isEmpty())
    }

    @Test fun wordsModeAndBlankQueriesDoNotInvokeTheModel() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { error("Model must not run") }
        assertEquals(listOf(wordHit), search("озеро", byMeaning = false))
        assertTrue(search(" \n ", byMeaning = true).isEmpty())
        assertEquals(listOf("озеро"), repository.wordQueries)
    }

    @Test fun aFailedOptionalEngineFallsBackToFts() = runBlocking {
        val repository = SearchFakeRepository().apply { wordResults = listOf(wordHit) }
        val search = SearchEntries(repository) { throw IllegalStateException("Unsupported model") }
        assertEquals(listOf(wordHit), search("озеро", byMeaning = true))
        assertEquals(listOf("озеро"), repository.wordQueries)
    }

    @Test fun cancellationIsPropagatedWithoutStartingFts() = runBlocking {
        val repository = SearchFakeRepository()
        val cancellation = CancellationException("locked")
        val search = SearchEntries(repository) { throw cancellation }
        try {
            search("озеро", byMeaning = true)
            fail("Cancellation was swallowed")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertTrue(repository.wordQueries.isEmpty())
    }
}
