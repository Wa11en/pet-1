package ru.privatediary.ml.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import ru.privatediary.ml.embedder.EngineAvailability
import ru.privatediary.ml.embedder.EmbeddingEngine
import ru.privatediary.ml.tokenizer.TextChunk
import ru.privatediary.testing.SearchFakeEngine
import ru.privatediary.testing.SearchFakeRepository
import ru.privatediary.testing.SearchFakeVectors
import ru.privatediary.testing.SearchFakeRelevance
import ru.privatediary.domain.models.EmbeddingChunk
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.domain.models.SearchFilters
import ru.privatediary.domain.usecases.SearchEntries
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async

class SemanticSearchServiceTest {
    private val entry = Entry(1L, 0L, "old entry")

    @Test fun modelPreparationIsBusyAndCancellationResetsBusy() = runBlocking {
        lateinit var service: SemanticSearchService
        val engine = object : EmbeddingEngine by SearchFakeEngine() {
            override suspend fun prepare(): EngineAvailability {
                assertTrue(service.indexing.value)
                throw CancellationException("Model preparation cancelled")
            }
        }
        service = SemanticSearchService(engine, SearchFakeRepository(), SearchFakeVectors(), SearchFakeRelevance())
        try {
            service.indexPending()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(service.indexing.value)
        }
    }

    @Test fun missingModelDoesNotReadVectorsOrStartIndexing() = runBlocking {
        val engine = SearchFakeEngine().apply { availability = EngineAvailability.MODEL_MISSING }
        val entries = SearchFakeRepository(listOf(entry))
        val vectors = SearchFakeVectors().apply { pending = listOf(entry) }
        val service = SemanticSearchService(engine, entries, vectors, SearchFakeRelevance())
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
        val service = SemanticSearchService(engine, entries, vectors, SearchFakeRelevance())
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
        val service = SemanticSearchService(engine, entries, vectors, SearchFakeRelevance())
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
        val service = SemanticSearchService(engine, SearchFakeRepository(listOf(entry)), vectors, SearchFakeRelevance())
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
        SemanticSearchService(engine, entries, vectors, SearchFakeRelevance()).indexPending()
        val attempt = vectors.writes.single()
        assertEquals(entry.id, attempt.entryId)
        assertEquals("042e2ae5ae5f1cf6696691203fd9af88298f8df29dc022050cfad3a9ebc304d3", attempt.expectedHash)
        assertEquals("old entry", attempt.chunks.single().fragment)
        assertEquals(attempt.expectedHash, attempt.chunks.single().contentHash)
        assertTrue(vectors.stored.isEmpty())
    }

    @Test fun rejectedGameIsNotReturnedEvenWhenItIsTheOnlyEntryAndHasPerfectCosine() = runBlocking {
        val game = Entry(1L, 0L, "выиграл в доту")
        val repository = SearchFakeRepository(listOf(game)).apply {
            wordResults = listOf(SearchResult(game, game.text))
        }
        val vectors = SearchFakeVectors().apply { stored = listOf(embedding(game)) }
        val gate = SearchFakeRelevance().apply { result = { _, _ -> false } }
        val service = SemanticSearchService(SearchFakeEngine(), repository, vectors, gate)
        val search = SearchEntries(repository, service::search)

        assertTrue(search("еда", byMeaning = true).isEmpty())
        assertTrue(repository.wordQueries.isEmpty())
        assertEquals(listOf("еда" to game.text), gate.pairs)
        assertTrue(service.available.value) // Valid rejection, not a missing-model error.
    }

    @Test fun storedPassageVectorCanBeReusedWithoutEmbeddingTheFragmentAgain() = runBlocking {
        val vector = floatArrayOf(4f, 3f)
        val vectors = SearchFakeVectors().apply { stored = listOf(embedding(entry, vector = vector)) }
        var checked = false
        val gate = object : RelevanceEngine by SearchFakeRelevance() {
            override suspend fun isRelevant(query: String, fragment: String): Boolean? =
                error("The stored passage vector should be reused")

            override suspend fun isRelevant(query: String, fragment: String, embedding: FloatArray): Boolean {
                assertEquals("еда", query)
                assertEquals(entry.text, fragment)
                assertArrayEquals(vector, embedding, 0f)
                checked = true
                return true
            }
        }
        val result = SemanticSearchService(SearchFakeEngine(), SearchFakeRepository(listOf(entry)), vectors, gate)
            .search("еда")!!
        assertTrue(checked)
        assertEquals(entry.id, result.single().entry.id)
        assertEquals(0.8f, result.single().score!!, 0.00001f)
    }

    @Test fun pizzaPassesAndHigherCosineGameIsCompletelyRejected() = runBlocking {
        val game = Entry(1L, 0L, "выиграл в доту")
        val pizza = Entry(2L, 0L, "поел пиццу")
        val repository = SearchFakeRepository(listOf(game, pizza))
        val vectors = SearchFakeVectors().apply {
            stored = listOf(embedding(game), embedding(pizza, vector = floatArrayOf(4f, 3f)))
        }
        val gate = SearchFakeRelevance().apply { result = { _, fragment -> fragment == pizza.text } }
        val service = SemanticSearchService(SearchFakeEngine(), repository, vectors, gate)

        val result = service.search("еда")!!
        assertEquals(listOf(pizza.id), result.map { it.entry.id })
        assertEquals(0.8f, result.single().score!!, 0.00001f)
        assertEquals(listOf("еда" to game.text, "еда" to pizza.text), gate.pairs)
        assertTrue(service.available.value)
    }

    @Test fun anotherChunkCanPassWhenTheBestCosineChunkOfALongEntryIsRejected() = runBlocking {
        val longEntry = Entry(1L, 0L, "Сначала играл. Потом поел пиццу.")
        val vectors = SearchFakeVectors().apply {
            stored = listOf(
                embedding(longEntry, fragment = "Сначала играл.", ordinal = 0),
                embedding(longEntry, fragment = "Потом поел пиццу.", ordinal = 1, vector = floatArrayOf(4f, 3f)),
            )
        }
        val gate = SearchFakeRelevance().apply { result = { _, fragment -> fragment.contains("пиццу") } }
        val service = SemanticSearchService(SearchFakeEngine(), SearchFakeRepository(listOf(longEntry)), vectors, gate)

        val result = service.search("еда")!!.single()
        assertEquals("Потом поел пиццу.", result.fragment)
        assertEquals(0.8f, result.score!!, 0.00001f)
        assertEquals(2, gate.pairs.size)
    }

    @Test fun failedGateNeverLeaksAnAlreadyAcceptedPartialListAndUsesKeywordFallback() = runBlocking {
        val first = Entry(1L, 0L, "first")
        val second = Entry(2L, 0L, "second")
        val keywordHit = SearchResult(first, "keyword fragment")
        val repository = SearchFakeRepository(listOf(first, second)).apply { wordResults = listOf(keywordHit) }
        val vectors = SearchFakeVectors().apply { stored = listOf(embedding(first), embedding(second)) }
        val gate = SearchFakeRelevance().apply { result = { _, fragment -> if (fragment == first.text) true else null } }
        val service = SemanticSearchService(SearchFakeEngine(), repository, vectors, gate)

        assertEquals(listOf(keywordHit), SearchEntries(repository, service::search)("еда", byMeaning = true))
        assertEquals(listOf("еда"), repository.wordQueries)
        assertFalse(service.available.value)
    }

    @Test fun unexpectedGateExceptionFallsBackInsteadOfReturningRawEmbeddingMatches() = runBlocking {
        val vectors = SearchFakeVectors().apply { stored = listOf(embedding(entry)) }
        val gate = SearchFakeRelevance().apply { result = { _, _ -> throw IllegalStateException("Native failure") } }
        val service = SemanticSearchService(SearchFakeEngine(), SearchFakeRepository(listOf(entry)), vectors, gate)

        assertNull(service.search("еда"))
        assertFalse(service.available.value)
    }

    @Test fun unavailableGateKeepsSearchUnavailableButDoesNotPreventEmbeddingIndexing() = runBlocking {
        val gate = SearchFakeRelevance().apply { availability = EngineAvailability.MODEL_MISSING }
        val engine = SearchFakeEngine()
        val vectors = SearchFakeVectors().apply { pending = listOf(entry) }
        val service = SemanticSearchService(engine, SearchFakeRepository(listOf(entry)), vectors, gate)

        assertNull(service.search("еда"))
        assertEquals(0, engine.queryCalls)
        service.indexPending()
        assertEquals(1, vectors.writes.size)
        assertFalse(service.available.value)
        assertFalse(service.indexing.value)
        assertTrue(gate.pairs.isEmpty())
    }

    @Test fun indexingDoesNotOverwriteAFailedGateWithEmbeddingOnlyReadiness() = runBlocking {
        val gate = SearchFakeRelevance().apply { result = { _, _ -> null } }
        val vectors = SearchFakeVectors().apply { stored = listOf(embedding(entry)); pending = listOf(entry) }
        val service = SemanticSearchService(SearchFakeEngine(), SearchFakeRepository(listOf(entry)), vectors, gate)
        assertNull(service.search("еда"))
        service.indexPending()
        assertFalse(service.available.value)
        assertEquals(1, vectors.writes.size)
    }

    @Test fun cancellationAfterNativePairCheckDoesNotPublishResultsOrStartFts() = runBlocking {
        val repository = SearchFakeRepository(listOf(entry))
        val vectors = SearchFakeVectors().apply { stored = listOf(embedding(entry)) }
        val gate = SearchFakeRelevance().apply {
            result = { _, _ -> currentCoroutineContext().cancel(CancellationException("locked")); true }
        }
        val service = SemanticSearchService(SearchFakeEngine(), repository, vectors, gate)
        val task = async { SearchEntries(repository, service::search)("еда", byMeaning = true) }
        try {
            task.await()
            fail("Cancellation after native inference was swallowed")
        } catch (_: CancellationException) { /* A lock cannot turn into an FTS request. */ }
        assertTrue(repository.wordQueries.isEmpty())
        assertEquals(1, gate.pairs.size)
        assertEquals(1, repository.allEntriesCalls) // No post-inference publication snapshot.
    }

    @Test fun anEntryEditedOrDeletedDuringInferenceCannotReturnAnOldAcceptedFragment() = runBlocking {
        for (newRows in listOf(emptyList(), listOf(entry.copy(text = "edited")))) {
            val repository = SearchFakeRepository(listOf(entry))
            val vectors = SearchFakeVectors().apply { stored = listOf(embedding(entry)) }
            val gate = SearchFakeRelevance().apply { result = { _, _ -> repository.rows = newRows; true } }
            val service = SemanticSearchService(SearchFakeEngine(), repository, vectors, gate)
            assertTrue(service.search("еда")!!.isEmpty())
        }
    }

    @Test fun retainedEntryUsesFreshMetadataAndSkipsLowerChunksAfterBestOnePasses() = runBlocking {
        val updated = entry.copy(timestamp = 123L, tags = listOf("new"))
        val repository = SearchFakeRepository(listOf(entry))
        val vectors = SearchFakeVectors().apply {
            stored = listOf(embedding(entry, fragment = "best"),
                embedding(entry, fragment = "other", ordinal = 1, vector = floatArrayOf(4f, 3f)))
        }
        val gate = SearchFakeRelevance().apply { result = { _, _ -> repository.rows = listOf(updated); true } }
        val service = SemanticSearchService(SearchFakeEngine(), repository, vectors, gate)
        val result = service.search("еда")!!.single()
        assertEquals(updated, result.entry)
        assertEquals("best", result.fragment)
        assertEquals(listOf("еда" to "best"), gate.pairs)
    }

    @Test fun pairBudgetAndPerEntryLimitBoundMobileInferenceWork() = runBlocking {
        val rows = (1L..40L).map { Entry(it, 0L, "entry $it") }
        val vectors = SearchFakeVectors().apply {
            stored = rows.flatMap { row -> (0..4).map { ordinal -> embedding(row, "${row.text}/$ordinal", ordinal) } }
        }
        // Every first/second/third chunk needs a decision when none passes.
        val gate = SearchFakeRelevance().apply { result = { _, _ -> false } }
        val service = SemanticSearchService(SearchFakeEngine(), SearchFakeRepository(rows), vectors, gate)
        val result = service.search("еда")!!
        assertEquals(60, gate.pairs.size)
        assertTrue(gate.pairs.groupingBy { it.second.substringBefore('/') }.eachCount().values.all { it <= 3 })
        assertEquals(result.map { it.entry.id }.distinct(), result.map { it.entry.id })
        assertTrue(result.size <= 30)
        assertTrue(result.isEmpty())
    }

    @Test fun metadataFiltersApplyBeforeTheSixtyPairMobileBudget() = runBlocking {
        val excluded = (1L..70L).map { Entry(it, 0, "excluded $it", mood = 1, tags = listOf("работа")) }
        val eligible = Entry(71, 0, "eligible quiet evening", mood = 4, tags = listOf("Дом"))
        val repository = SearchFakeRepository(excluded + eligible)
        val vectors = SearchFakeVectors().apply {
            stored = excluded.map { embedding(it) } + embedding(eligible, vector = floatArrayOf(4f, 3f))
        }
        val gate = SearchFakeRelevance()
        val service = SemanticSearchService(SearchFakeEngine(), repository, vectors, gate)
        val result = service.search("спокойствие", SearchFilters(mood = 4, tag = "дом"))!!
        assertEquals(listOf(eligible), result.map { it.entry })
        assertEquals(listOf("спокойствие" to eligible.text), gate.pairs)
        assertEquals(0.8f, result.single().score!!, 0.00001f)
    }

    @Test fun metadataChangedDuringInferenceCannotPublishAResultOutsideTheFilter() = runBlocking {
        val original = entry.copy(mood = 4, tags = listOf("Дом"))
        for (changed in listOf(original.copy(mood = 1), original.copy(tags = listOf("Работа")))) {
            val repository = SearchFakeRepository(listOf(original))
            val vectors = SearchFakeVectors().apply { stored = listOf(embedding(original)) }
            val gate = SearchFakeRelevance().apply { result = { _, _ -> repository.rows = listOf(changed); true } }
            val service = SemanticSearchService(SearchFakeEngine(), repository, vectors, gate)
            assertTrue(service.search("спокойствие", SearchFilters(mood = 4, tag = "Дом"))!!.isEmpty())
            assertTrue(service.available.value)
        }
    }

    private fun embedding(
        row: Entry,
        fragment: String = row.text,
        ordinal: Int = 0,
        vector: FloatArray = floatArrayOf(1f, 0f),
    ): EmbeddingChunk {
        val hash = MessageDigest.getInstance("SHA-256").digest(row.text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return EmbeddingChunk(row.id, ordinal, fragment, vector, hash)
    }
}
