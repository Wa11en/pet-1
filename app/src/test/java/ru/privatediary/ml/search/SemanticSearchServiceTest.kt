package ru.privatediary.ml.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import ru.privatediary.ml.embedder.EngineAvailability
import ru.privatediary.ml.tokenizer.TextChunk
import ru.privatediary.testing.SearchFakeEngine
import ru.privatediary.testing.SearchFakeRepository
import ru.privatediary.testing.SearchFakeVectors

class SemanticSearchServiceTest {
    private val entry = Entry(1L, 0L, "old entry")

    @Test fun missingModelDoesNotReadVectorsOrStartIndexing() = runBlocking {
        val engine = SearchFakeEngine().apply { availability = EngineAvailability.MODEL_MISSING }
        val entries = SearchFakeRepository(listOf(entry))
        val vectors = SearchFakeVectors().apply { pending = listOf(entry) }
        val service = SemanticSearchService(engine, entries, vectors)
        assertNull(service.search("спокойствие"))
        service.indexPending()
        assertEquals(0, engine.queryCalls)
        assertEquals(0, entries.allEntriesCalls)
        assertEquals(0, vectors.allEmbeddingsCalls)
        assertTrue(vectors.writes.isEmpty())
        assertFalse(service.available.value)
        assertFalse(service.indexing.value)
    }

    @Test fun nullQueryEmbeddingRequestsKeywordFallback() = runBlocking {
        val engine = SearchFakeEngine().apply { queryResult = { null } }
        val entries = SearchFakeRepository(listOf(entry))
        val vectors = SearchFakeVectors()
        val service = SemanticSearchService(engine, entries, vectors)
        assertNull(service.search("спокойствие"))
        assertEquals(0, vectors.allEmbeddingsCalls)
        assertFalse(service.available.value)
    }

    @Test fun failedChunkDoesNotCommitPartialVectors() = runBlocking {
        val engine = SearchFakeEngine().apply {
            chunks = listOf(TextChunk("old", 0, 3, 1), TextChunk("entry", 4, 9, 1))
            passageResult = { text -> if (text == "entry") null else floatArrayOf(1f, 0f) }
        }
        val entries = SearchFakeRepository(listOf(entry))
        val vectors = SearchFakeVectors().apply { pending = listOf(entry) }
        val service = SemanticSearchService(engine, entries, vectors)
        service.indexPending()
        assertEquals(listOf("old", "entry"), engine.passages)
        assertTrue(vectors.writes.isEmpty())
        assertFalse(service.available.value)
        assertFalse(service.indexing.value)
    }

    @Test fun cancellationBetweenChunksDoesNotCommitPartialVectorsAndResetsBusy() = runBlocking {
        val engine = SearchFakeEngine().apply {
            chunks = listOf(TextChunk("old", 0, 3, 1), TextChunk("entry", 4, 9, 1))
            passageResult = { text ->
                if (text == "entry") throw CancellationException("locked")
                floatArrayOf(1f, 0f)
            }
        }
        val vectors = SearchFakeVectors().apply { pending = listOf(entry) }
        val service = SemanticSearchService(engine, SearchFakeRepository(listOf(entry)), vectors)
        try { service.indexPending(); fail("Cancellation was swallowed") }
        catch (_: CancellationException) { /* Lock cancels work instead of pretending it succeeded. */ }
        assertTrue(vectors.writes.isEmpty())
        assertFalse(service.indexing.value)
        assertEquals(0L, service.revision.value)
    }

    @Test fun aConcurrentEditDoesNotChangeTheHashOfAnInFlightIndexWrite() = runBlocking {
        val entries = SearchFakeRepository(listOf(entry))
        val vectors = SearchFakeVectors().apply { pending = listOf(entry); acceptWrites = false }
        val engine = SearchFakeEngine().apply {
            passageResult = {
                entries.rows = listOf(entry.copy(text = "new entry"))
                floatArrayOf(1f, 0f)
            }
        }
        SemanticSearchService(engine, entries, vectors).indexPending()
        val attempt = vectors.writes.single()
        assertEquals(entry.id, attempt.entryId)
        assertEquals("042e2ae5ae5f1cf6696691203fd9af88298f8df29dc022050cfad3a9ebc304d3", attempt.expectedHash)
        assertEquals("old entry", attempt.chunks.single().fragment)
        assertEquals(attempt.expectedHash, attempt.chunks.single().contentHash)
        assertTrue(vectors.stored.isEmpty())
    }
}
